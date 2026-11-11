package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.pathfinding.Navigator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compact, observation-only movement audit for duel benchmarks.
 *
 * Purpose: flag "deviant" behaviour without storing multi-gigabyte per-tick AI traces.
 * A unit is sampled roughly once per second while compact trace mode is active.
 * The audit never changes movement, pathfinding, targeting or combat.
 */
final class CFMotionAudit {
    private static volatile Session active;

    private CFMotionAudit() { }

    private static final class Session {
        final String mode;
        final String startedUtc = Instant.now().toString();
        final Map<Integer, UnitStats> units = new HashMap<>();
        Session(String mode) { this.mode = mode; }
    }

    private static final class UnitStats {
        final String scenario;
        final String team;
        final String kind;
        final int entityId;
        final double originX;
        final double originZ;

        int samples;
        double totalMoved;
        double maxMoved;
        double totalTargetDistance;
        int targetDistanceSamples;
        double maxTargetDistance;
        double maxLateralDrift;
        double maxForwardTravel;
        int targetlessSamples;
        int navInvalidSamples;
        int stallSamples;
        int jammedSamples;
        int farStillSamples;
        int pushSamples;
        int totalPushCalls;
        int maxPushOverlaps;
        double totalPushImpulse;
        String lastGoal = "";
        String lastNavState = "";

        UnitStats(CFUnit unit, String scenario) {
            this.scenario = scenario;
            this.team = unit.team();
            this.kind = unit.kind();
            this.entityId = unit.getEntityId();
            Pos pos = unit.motionSpawnPosition();
            this.originX = pos.x();
            this.originZ = pos.z();
        }
    }

    record Report(String perUnitCsv, String perScenarioCsv,
                  String anomaliesCsv, String metadataTxt) { }

    static synchronized void begin(String mode) {
        if (active != null) throw new IllegalStateException("Motion audit already active");
        active = new Session(mode);
    }

    static synchronized void discard() { active = null; }

    static void tagUnit(CFUnit unit, String scenario) {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            s.units.putIfAbsent(unit.getEntityId(), new UnitStats(unit, scenario));
        }
    }

    static void sample(CFUnit unit, MarchGoal march, double moved,
                       int pushes, double impulseX, double impulseZ,
                       int maxOverlaps) {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            UnitStats st = s.units.computeIfAbsent(unit.getEntityId(), id -> new UnitStats(unit, "unassigned"));
            sampleInto(st, unit, march, moved, pushes, impulseX, impulseZ, maxOverlaps);
        }
    }

    private static void sampleInto(UnitStats st, CFUnit unit, MarchGoal march, double moved,
                                   int pushes, double impulseX, double impulseZ,
                                   int maxOverlaps) {
        st.samples++;
        st.totalMoved += moved;
        st.maxMoved = Math.max(st.maxMoved, moved);
        st.totalPushCalls += pushes;
        st.maxPushOverlaps = Math.max(st.maxPushOverlaps, maxOverlaps);
        if (pushes > 0 || maxOverlaps > 0) st.pushSamples++;
        double pushImpulse = Math.hypot(impulseX, impulseZ);
        st.totalPushImpulse += pushImpulse;

        Pos pos = unit.getPosition();
        st.maxLateralDrift = Math.max(st.maxLateralDrift, Math.abs(pos.z() - st.originZ));
        st.maxForwardTravel = Math.max(st.maxForwardTravel, Math.abs(pos.x() - st.originX));

        Navigator nav = unit.getNavigator();
        String navState = nav.getState().name();
        String goal = CFTrace.goal(unit);
        st.lastNavState = navState;
        st.lastGoal = goal;
        if ("INVALID".equals(navState)) st.navInvalidSamples++;

        Entity target = unit.traceTarget();
        double targetDistance = target == null ? -1 : unit.getDistance(target);
        if (target == null) st.targetlessSamples++;
        else if (Double.isFinite(targetDistance) && targetDistance >= 0) {
            st.totalTargetDistance += targetDistance;
            st.targetDistanceSamples++;
            st.maxTargetDistance = Math.max(st.maxTargetDistance, targetDistance);
        }

        boolean routeFinished = march.routeFinished();
        boolean stalled = moved < 0.35 && !routeFinished;
        if (stalled) st.stallSamples++;

        boolean jammed = stalled && (pushes > 0 || maxOverlaps >= 2 || pushImpulse >= 0.15);
        if (jammed) st.jammedSamples++;

        double neededRange = unit.kind().equals("skeleton") ? 16.0 : 2.4;
        boolean farStill = stalled && targetDistance > neededRange + 1.0;
        if (farStill) st.farStillSamples++;
    }

    static synchronized Report finish() {
        Session s = active;
        active = null;
        if (s == null) return new Report("", "", "", "No motion audit was active.\n");
        synchronized (s) {
            return createReport(s);
        }
    }

    private static final class ScenarioTotals {
        int units;
        int samples;
        int targetlessSamples;
        int navInvalidSamples;
        int stallSamples;
        int jammedSamples;
        int farStillSamples;
        int stalledUnits;
        int jammedUnits;
        int targetlessUnits;
        int navInvalidUnits;
        int farStillUnits;
        double totalMoved;
        double maxLateralDrift;
        double maxForwardTravel;
        double totalPushImpulse;
    }

    private static Report createReport(Session s) {
        Map<String, ScenarioTotals> scenarios = new TreeMap<>();
        StringBuilder perUnit = new StringBuilder(
                "scenario,unit,team,kind,samples,seconds_observed,total_moved,avg_moved,max_moved," +
                "stall_samples,jammed_samples,targetless_samples,nav_invalid_samples,far_still_samples," +
                "avg_target_distance,max_target_distance,max_lateral_drift,max_forward_travel," +
                "push_samples,total_push_calls,max_push_overlaps,total_push_impulse,last_goal,last_nav_state,flags\n");
        List<String> anomalies = new ArrayList<>();

        for (UnitStats st : s.units.values()) {
            ScenarioTotals totals = scenarios.computeIfAbsent(st.scenario, key -> new ScenarioTotals());
            totals.units++;
            totals.samples += st.samples;
            totals.targetlessSamples += st.targetlessSamples;
            totals.navInvalidSamples += st.navInvalidSamples;
            totals.stallSamples += st.stallSamples;
            totals.jammedSamples += st.jammedSamples;
            totals.farStillSamples += st.farStillSamples;
            totals.totalMoved += st.totalMoved;
            totals.maxLateralDrift = Math.max(totals.maxLateralDrift, st.maxLateralDrift);
            totals.maxForwardTravel = Math.max(totals.maxForwardTravel, st.maxForwardTravel);
            totals.totalPushImpulse += st.totalPushImpulse;

            List<String> flags = new ArrayList<>();
            if (st.stallSamples >= 6) { totals.stalledUnits++; flags.add("STALLED_LONG"); }
            if (st.jammedSamples >= 4) { totals.jammedUnits++; flags.add("JAMMED_CROWD"); }
            if (st.targetlessSamples >= 6) { totals.targetlessUnits++; flags.add("TARGETLESS_IDLE"); }
            if (st.navInvalidSamples >= 4) { totals.navInvalidUnits++; flags.add("NAV_INVALID_OFTEN"); }
            if (st.farStillSamples >= 4) { totals.farStillUnits++; flags.add("OUT_OF_RANGE_IDLE"); }

            if (!flags.isEmpty()) {
                for (String flag : flags) {
                    anomalies.add(st.scenario + ',' + st.entityId + ',' + st.team + ',' + st.kind + ',' + flag + ',' +
                            fmt(st.maxLateralDrift) + ',' + st.stallSamples + ',' + st.jammedSamples + ',' +
                            st.targetlessSamples + ',' + st.navInvalidSamples + ',' + fmt(st.totalMoved) + ',' +
                            fmt(st.totalPushImpulse) + '\n');
                }
            }

            double avgMoved = st.samples == 0 ? 0 : st.totalMoved / st.samples;
            double avgTargetDistance = st.targetDistanceSamples == 0 ? Double.NaN : st.totalTargetDistance / st.targetDistanceSamples;
            perUnit.append(st.scenario).append(',').append(st.entityId).append(',')
                    .append(st.team).append(',').append(st.kind).append(',')
                    .append(st.samples).append(',').append(st.samples).append(',')
                    .append(fmt(st.totalMoved)).append(',').append(fmt(avgMoved)).append(',').append(fmt(st.maxMoved)).append(',')
                    .append(st.stallSamples).append(',').append(st.jammedSamples).append(',')
                    .append(st.targetlessSamples).append(',').append(st.navInvalidSamples).append(',')
                    .append(st.farStillSamples).append(',').append(fmt(avgTargetDistance)).append(',')
                    .append(fmt(st.maxTargetDistance)).append(',').append(fmt(st.maxLateralDrift)).append(',')
                    .append(fmt(st.maxForwardTravel)).append(',').append(st.pushSamples).append(',')
                    .append(st.totalPushCalls).append(',').append(st.maxPushOverlaps).append(',')
                    .append(fmt(st.totalPushImpulse)).append(',').append(csvCell(st.lastGoal)).append(',')
                    .append(csvCell(st.lastNavState)).append(',').append(csvCell(String.join("|", flags))).append('\n');
        }

        StringBuilder perScenario = new StringBuilder(
                "scenario,units,samples,seconds_observed,total_moved,avg_moved_per_sample," +
                "stall_samples,jammed_samples,targetless_samples,nav_invalid_samples,far_still_samples," +
                "stalled_units,jammed_units,targetless_units,nav_invalid_units,far_still_units," +
                "max_lateral_drift,max_forward_travel,total_push_impulse\n");
        for (var e : scenarios.entrySet()) {
            ScenarioTotals t = e.getValue();
            double avgMoved = t.samples == 0 ? 0 : t.totalMoved / t.samples;
            perScenario.append(e.getKey()).append(',').append(t.units).append(',').append(t.samples).append(',')
                    .append(t.samples).append(',').append(fmt(t.totalMoved)).append(',').append(fmt(avgMoved)).append(',')
                    .append(t.stallSamples).append(',').append(t.jammedSamples).append(',')
                    .append(t.targetlessSamples).append(',').append(t.navInvalidSamples).append(',')
                    .append(t.farStillSamples).append(',').append(t.stalledUnits).append(',')
                    .append(t.jammedUnits).append(',')
                    .append(t.targetlessUnits).append(',').append(t.navInvalidUnits).append(',')
                    .append(t.farStillUnits).append(',').append(fmt(t.maxLateralDrift)).append(',')
                    .append(fmt(t.maxForwardTravel)).append(',').append(fmt(t.totalPushImpulse)).append('\n');
        }

        StringBuilder anomaliesCsv = new StringBuilder(
                "scenario,unit,team,kind,issue,max_lateral_drift,stall_samples,jammed_samples,targetless_samples,nav_invalid_samples,total_moved,total_push_impulse\n");
        anomalies.stream().sorted().forEach(anomaliesCsv::append);

        String meta = "CF MOTION AUDIT (observation only)\n" +
                "mode=" + s.mode + "\nstartedUtc=" + s.startedUtc + "\n" +
                "sampling=~1 sample per second per living unit in compact duel mode\n" +
                "stalled = moved < 0.35 blocks in a sample while the march route is not finished\n" +
                "jammed = stalled and also receiving crowd push / overlapping neighbours\n" +
                "out_of_range_idle = stalled while target exists beyond practical attack range\n" +
                "lateral_drift = abs(currentZ - spawnZ); useful for lane escape / crowd ejection\n" +
                "v1.5.3: cancel balanced crowd push instead of fabricating a sideways impulse.\n" +
                "LATERAL_DRIFT / EJECTED_BY_PUSH were misleading labels and have been removed.\n";
        return new Report(perUnit.toString(), perScenario.toString(), anomaliesCsv.toString(), meta);
    }

    private static String fmt(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) return "";
        return String.format(Locale.ROOT, "%.3f", n);
    }

    private static String csvCell(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
