package dev.cf;

import net.minestom.server.entity.Entity;
import net.minestom.server.event.entity.EntityDamageEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lossless per-shot audit for the duration of a duel, independent of high-volume AI tracing.
 * Tracks AI shoot events, rejected launches, projectiles, damage attempts and useful HP loss.
 * Rows are kept in memory for a single bounded benchmark (70s per wave, <=500 units).
 * Neither aims nor damage mechanics are modified.
 */
final class CFHitAudit {
    private static volatile Session active;
    private static final ThreadLocal<Shot> DAMAGE_CALL = new ThreadLocal<>();
    private CFHitAudit() { }

    static final class Shot {
        final long seq;
        final String scenario;
        final int shooterId, targetId;
        final String shooterTeam, targetTeam, targetKind;
        final float targetHpAtFire;
        final float configuredDamage;
        final long launchedAtNs;
        int arrowId = -1, delayTicks, viewers, pendingAtLaunch;
        double distance = -1;
        float hpBefore = Float.NaN, hpAfter = Float.NaN;
        double usefulHpLost;
        boolean damageReturned, eventSeen, eventCancelled, targetInvulnerable;
        float damageEventAmount = Float.NaN;
        EntityDamageEvent pendingEvent;
        String result = "AWAITING_SHOOT";
        boolean finished;

        Shot(long seq, String scenario, CFUnit shooter, Entity selected) {
            this.seq = seq;
            this.scenario = scenario;
            this.shooterId = shooter.getEntityId();
            this.configuredDamage = shooter.attackDamage();
            this.shooterTeam = shooter.team();
            this.targetId = CFTrace.id(selected);
            this.targetTeam = selected instanceof CFUnit u ? u.team() : "non_cf_unit";
            this.targetKind = selected instanceof CFUnit u ? u.kind() : "non_cf_unit";
            this.targetHpAtFire = selected instanceof CFUnit u ? u.getHealth() : Float.NaN;
            this.launchedAtNs = System.nanoTime();
        }
    }

    private static final class UnitHp {
        final String scenario, team, kind;
        final int entityId;
        final double startingHp;
        double finalHp = Double.NaN;
        double arrowHpLost, meleeHpLost;
        int arrowHits, meleeHits;

        UnitHp(CFUnit unit, String scenario) {
            this.scenario = scenario;
            team = unit.team();
            kind = unit.kind();
            entityId = unit.getEntityId();
            startingHp = Math.max(0, unit.getHealth());
        }
    }

    private static final class Session {
        final String mode;
        final String startedUtc = Instant.now().toString();
        final List<Shot> shots = new ArrayList<>();
        final Map<Integer, UnitHp> units = new HashMap<>();
        final Map<Integer, Integer> pendingToTarget = new HashMap<>();
        final Map<String, Integer> aiShootEvents = new HashMap<>();
        long nextSeq;
        Session(String mode) { this.mode = mode; }
    }

    record Report(String shotsCsv, String casesCsv, String teamsCsv,
                  String reasonsCsv, String metadataTxt) { }

    static synchronized void begin(String mode) {
        if (active != null) throw new IllegalStateException("Hit audit already active");
        active = new Session(mode);
    }

    static synchronized void discard() { active = null; }

    static void tagUnit(CFUnit unit, String scenario) {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            s.units.putIfAbsent(unit.getEntityId(), new UnitHp(unit, scenario));
        }
    }

    static void aiShoot(CFUnit shooter) {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            UnitHp unit = s.units.get(shooter.getEntityId());
            String scenario = unit == null ? "unassigned" : unit.scenario;
            s.aiShootEvents.merge(scenario, 1, Integer::sum);
        }
    }

    static Shot attempt(CFUnit shooter, Entity target) {
        Session s = active;
        if (s == null) return null;
        synchronized (s) {
            UnitHp shooterHp = s.units.get(shooter.getEntityId());
            String scenario = shooterHp == null ? "unassigned" : shooterHp.scenario;
            Shot shot = new Shot(++s.nextSeq, scenario, shooter, target);
            s.shots.add(shot);
            return shot;
        }
    }

    static long id(Shot shot) { return shot == null ? -1 : shot.seq; }

    static void notFired(Shot shot, String why) {
        if (shot == null) return;
        synchronized (shot) {
            shot.result = "NOT_FIRED_" + why;
            shot.finished = true;
        }
        CFTrace.event("ARROW_NOT_FIRED", "shot", shot.seq,
                "shooter", shot.shooterId, "target", shot.targetId, "reason", why);
    }

    static void launched(Shot shot, int arrowId, double distance, int delay, int viewers) {
        if (shot == null) return;
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            shot.arrowId = arrowId;
            shot.distance = distance;
            shot.delayTicks = delay;
            shot.viewers = viewers;
            // Count other scheduled arrows at the same target at the moment this one flies.
            shot.pendingAtLaunch = s.pendingToTarget.getOrDefault(shot.targetId, 0);
            s.pendingToTarget.merge(shot.targetId, 1, Integer::sum);
            shot.result = "IN_FLIGHT";
        }
    }

    private static void releasePending(Session s, Shot shot) {
        if (shot.arrowId == -1) return;
        s.pendingToTarget.computeIfPresent(shot.targetId, (id, count) -> count <= 1 ? null : count - 1);
    }

    /** Correlates the synchronous EntityDamageEvent with the scheduled arrow callback. */
    static void beforeDamage(Shot shot) {
        if (shot != null) DAMAGE_CALL.set(shot);
    }

    static void damageEvent(EntityDamageEvent event) {
        if (active == null) return;
        Shot shot = DAMAGE_CALL.get();
        if (shot != null && event.getEntity().getEntityId() == shot.targetId) {
            shot.eventSeen = true;
            shot.pendingEvent = event;
        } else if (event.getEntity() instanceof CFUnit unit) {
            // A different source damaged a unit: useful when final HP does not reconcile.
            // This observes the event, NOT its final (possibly cancelled) outcome.
            CFTrace.event("NON_ARROW_DAMAGE_EVENT", "unit", unit.getEntityId(),
                    "kind", unit.kind(), "team", unit.team(),
                    "amount", event.getDamage().getAmount(),
                    "type", String.valueOf(event.getDamage().getType()),
                    "source", CFTrace.id(event.getDamage().getSource()),
                    "hpBefore", unit.getHealth());
        }
    }

    static void afterDamage() {
        Shot shot = DAMAGE_CALL.get();
        if (shot != null && shot.pendingEvent != null) {
            // Observe the FINAL state, including cancellation/modifications by later listeners.
            shot.eventCancelled = shot.pendingEvent.isCancelled();
            shot.damageEventAmount = shot.pendingEvent.getDamage().getAmount();
            shot.pendingEvent = null;
        }
        DAMAGE_CALL.remove();
    }

    static void resolved(Shot shot, CFUnit target, String result, boolean applied,
                         float before, float after, boolean invulnerable) {
        if (shot == null) return;
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            synchronized (shot) {
                // If a wave has ended, this shot was already classified as outside the test.
                if (shot.finished) return;
                releasePending(s, shot);
                shot.result = result;
                shot.damageReturned = applied;
                shot.hpBefore = before;
                shot.hpAfter = after;
                shot.targetInvulnerable = invulnerable;
                shot.usefulHpLost = hpLost(before, after);
                shot.finished = true;
            }
            UnitHp hp = s.units.get(target.getEntityId());
            if (hp != null) {
                hp.arrowHpLost += shot.usefulHpLost;
                if (shot.usefulHpLost > 0) hp.arrowHits++;
            }
        }
    }

    static void meleeDamage(CFUnit target, float hpBefore, float hpAfter) {
        Session s = active;
        if (s == null) return;
        double lost = hpLost(hpBefore, hpAfter);
        if (lost <= 0) return;
        synchronized (s) {
            UnitHp hp = s.units.get(target.getEntityId());
            if (hp != null) {
                hp.meleeHpLost += lost;
                hp.meleeHits++;
            }
        }
    }

    static double hpLost(float before, float after) {
        return Math.max(0, Math.max(0, before) - Math.max(0, after));
    }

    /** Call before cleaning a case's entities, or pending shots could be misreported as hits. */
    static void closeCase(String scenario, List<CFUnit> roster) {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            for (CFUnit mob : roster) {
                UnitHp unit = s.units.get(mob.getEntityId());
                if (unit != null) unit.finalHp = Math.max(0, mob.getHealth());
            }
            for (Shot shot : s.shots) {
                if (!shot.scenario.equals(scenario)) continue;
                synchronized (shot) {
                    if (!shot.finished) {
                        releasePending(s, shot);
                        shot.result = shot.arrowId == -1 ? "TEST_ENDED_BEFORE_LAUNCH" : "TEST_ENDED_IN_FLIGHT";
                        shot.finished = true;
                    }
                }
            }
        }
    }

    static synchronized Report finish() {
        Session s = active;
        active = null;
        DAMAGE_CALL.remove();
        if (s == null) return new Report("", "", "", "", "No hit audit was active.\n");
        synchronized (s) {
            return createReport(s);
        }
    }

    private static final class Totals {
        int aiShootEvents, attempts, fired, resolved, fullHits, partialHits, reducedHits, noHp, inFlight, notFired;
        double launchedDamage, usefulArrowHp, usefulMeleeHp, startHp, endHp;
    }

    private static Report createReport(Session s) {
        Map<String, Totals> cases = new TreeMap<>();
        Map<String, Totals> teams = new TreeMap<>();
        Map<String, Map<String, Integer>> reasons = new TreeMap<>();
        for (var e : s.aiShootEvents.entrySet()) cases.computeIfAbsent(e.getKey(), k -> new Totals()).aiShootEvents = e.getValue();
        for (UnitHp u : s.units.values()) {
            Totals c = cases.computeIfAbsent(u.scenario, k -> new Totals());
            Totals team = teams.computeIfAbsent(u.scenario + "," + u.team + "," + u.kind, k -> new Totals());
            double after = Double.isNaN(u.finalHp) ? u.startingHp : u.finalHp;
            c.startHp += u.startingHp;
            c.endHp += after;
            c.usefulArrowHp += u.arrowHpLost;
            c.usefulMeleeHp += u.meleeHpLost;
            team.startHp += u.startingHp;
            team.endHp += after;
            team.usefulArrowHp += u.arrowHpLost;
            team.usefulMeleeHp += u.meleeHpLost;
        }
        StringBuilder shots = new StringBuilder(
                "shot,scenario,shooter_id,shooter_team,target_id,target_team,target_kind,arrow_id,launched," +
                "distance,delay_ticks,viewers,other_arrows_to_target_at_launch," +
                "target_hp_at_fire,hp_before_impact,hp_after_impact_raw," +
                "useful_hp_lost,damage_returned,damage_event_seen,damage_event_cancelled," +
                "damage_event_amount,invulnerable_before,result,configured_damage\n");
        for (Shot shot : s.shots) {
            Totals c = cases.computeIfAbsent(shot.scenario, k -> new Totals());
            c.attempts++;
            if (shot.arrowId != -1) { c.fired++; c.launchedDamage += shot.configuredDamage; }
            if (shot.result.startsWith("TEST_ENDED_") || "IN_FLIGHT".equals(shot.result)) c.inFlight++;
            else if (shot.result.startsWith("NOT_FIRED")) c.notFired++;
            else {
                c.resolved++;
                if (shot.usefulHpLost > 0) {
                    if ("HIT_FULL".equals(shot.result)) c.fullHits++;
                    else if ("HIT_PARTIAL_LETHAL".equals(shot.result)) c.partialHits++;
                    else c.reducedHits++;
                } else c.noHp++;
            }
            reasons.computeIfAbsent(shot.scenario, k -> new TreeMap<>()).merge(shot.result, 1, Integer::sum);
            shots.append(shot.seq).append(',').append(shot.scenario).append(',')
                    .append(shot.shooterId).append(',').append(shot.shooterTeam).append(',')
                    .append(shot.targetId).append(',').append(shot.targetTeam).append(',')
                    .append(shot.targetKind).append(',').append(shot.arrowId).append(',')
                    .append(shot.arrowId != -1).append(',').append(fmt(shot.distance)).append(',')
                    .append(shot.delayTicks).append(',').append(shot.viewers).append(',')
                    .append(shot.pendingAtLaunch).append(',')
                    .append(fmt(shot.targetHpAtFire)).append(',').append(fmt(shot.hpBefore))
                    .append(',').append(fmt(shot.hpAfter)).append(',')
                    .append(fmt(shot.usefulHpLost)).append(',').append(shot.damageReturned)
                    .append(',').append(shot.eventSeen).append(',').append(shot.eventCancelled)
                    .append(',').append(fmt(shot.damageEventAmount)).append(',')
                    .append(shot.targetInvulnerable).append(',').append(shot.result).append(',').append(fmt(shot.configuredDamage)).append('\n');
        }
        StringBuilder caseCsv = new StringBuilder("scenario,ai_shoot_events,attempts,launched," +
                "resolved,full_hp_hits,partial_lethal_hits,reduced_hp_hits,zero_hp_shots,in_flight_at_end,not_fired," +
                "naive_damage_launched,actual_arrow_hp_lost,melee_hp_lost,initial_hp,final_hp," +
                "observed_hp_lost,unexplained_hp_change\n");
        for (var e : cases.entrySet()) {
            Totals t = e.getValue();
            caseCsv.append(e.getKey()).append(',').append(t.aiShootEvents).append(',')
                    .append(t.attempts).append(',').append(t.fired).append(',').append(t.resolved)
                    .append(',').append(t.fullHits).append(',').append(t.partialHits).append(',')
                    .append(t.reducedHits).append(',').append(t.noHp).append(',')
                    .append(t.inFlight).append(',').append(t.notFired)
                    .append(',').append(fmt(t.launchedDamage)).append(',')
                    .append(fmt(t.usefulArrowHp)).append(',').append(fmt(t.usefulMeleeHp))
                    .append(',').append(fmt(t.startHp)).append(',').append(fmt(t.endHp))
                    .append(',').append(fmt(t.startHp - t.endHp)).append(',')
                    .append(fmt((t.startHp - t.endHp) - t.usefulArrowHp - t.usefulMeleeHp)).append('\n');
        }
        StringBuilder teamsCsv = new StringBuilder("scenario,team,kind,starting_hp,final_hp," +
                "observed_hp_lost,arrow_hp_lost,melee_hp_lost,unexplained_hp_change\n");
        for (var e : teams.entrySet()) {
            Totals t = e.getValue();
            teamsCsv.append(e.getKey()).append(',').append(fmt(t.startHp)).append(',')
                    .append(fmt(t.endHp)).append(',').append(fmt(t.startHp - t.endHp))
                    .append(',').append(fmt(t.usefulArrowHp)).append(',').append(fmt(t.usefulMeleeHp))
                    .append(',').append(fmt((t.startHp - t.endHp) - t.usefulArrowHp - t.usefulMeleeHp))
                    .append('\n');
        }
        StringBuilder reasonsCsv = new StringBuilder("scenario,result,count\n");
        for (var e : reasons.entrySet()) for (var reason : e.getValue().entrySet())
            reasonsCsv.append(e.getKey()).append(',').append(reason.getKey()).append(',').append(reason.getValue()).append('\n');
        String meta = "CF HIT AUDIT (does not alter combat)\nmode=" + s.mode + "\nstartedUtc=" + s.startedUtc +
                "\nshotAttempts=" + s.shots.size() + "\nunitCount=" + s.units.size() + "\n" +
                "damagePerArrow=per-shot configured_damage, not a universal constant\n" +
                "NOTE: actual HP lost is MAX(0,hpBefore)-MAX(0,hpAfter). Lethal overkill is not useful HP.\n" +
                "NOTE: 0-HP shots include rejected damage and dead/removed targets; see per-shot result.\n" +
                "NOTE: TEST_ENDED_IN_FLIGHT shots were still waiting when the arena was cleared.\n" +
                "NOTE: unexplained_hp_change indicates health losses not explained by audited arrows/melee, or healing.\n" +
                "NOTE: EntityDamageEvent final cancelled state is read after damage() returns; damage_returned and HP are authoritative.\n" +
                "NOTE: other_arrows_to_target_at_launch counts already-launched, not-yet-resolved arrows targeting this mob.\n" +
                "NOTE: AI shoot events, shot attempts, and actual launched arrows are three different counts.\n";
        return new Report(shots.toString(), caseCsv.toString(), teamsCsv.toString(), reasonsCsv.toString(), meta);
    }

    private static String fmt(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) return "";
        return String.format(Locale.ROOT, "%.3f", n);
    }
}
