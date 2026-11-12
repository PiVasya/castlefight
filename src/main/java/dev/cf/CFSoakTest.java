package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Game-runtime-only, opt-in, bounded-memory, many-round battle test. This is not a fake combat simulation:
 * every initial unit enters the real validated lane near its own castle, using the normal CFUnit AI.
 * Every recorded game event is sent to one asynchronous disk writer, not written on the tick thread.
 * No global tracing overhead while the suite is inactive; no map data is ever saved or edited.
 */
final class CFSoakTest {
    @FunctionalInterface interface Spawn { boolean at(String team,String mob,int lane,Pos near); }
    private static volatile Run ACTIVE;
    private static volatile Path LAST_DIR;
    private final InstanceContainer world;
    private final Set<CFUnit> units;
    private final CFContentRegistry content;
    private final CFArenaAbilities abilities;
    private final CFThreeLanes lanes;
    private final Spawn spawn;
    private final Runnable clear;
    private Run last;

    CFSoakTest(InstanceContainer world,Set<CFUnit> units,CFContentRegistry content,
               CFArenaAbilities abilities,CFThreeLanes lanes,Spawn spawn,Runnable clear) {
        this.world=world;this.units=units;this.content=content;this.abilities=abilities;
        this.lanes=lanes;this.spawn=spawn;this.clear=clear;
    }

    static boolean isRunning() {
        Run r=ACTIVE;
        return r!=null && r.running;
    }
    /** Preserve every combat/AI decision, reduce only redundant position snapshots at high load. */
    static boolean highLoadSampling() {
        Run r=ACTIVE;
        return r!=null && r.running && r.stressTarget>=100;
    }
    /** Let the disk writer drain before console stop/JVM shutdown. The map is untouched. */
    static void shutdownActive() {
        Run r=ACTIVE;
        if(r==null || !r.running)return;
        r.abortRound("server-shutdown");
        CFSkeletonMindLog.finishCapture(r.dir);
        r.event("SOAK_INTERRUPTED","reason","server-shutdown","round",r.round,
                "roundsCompleted",r.roundsCompleted);
        r.running=false;r.stage="INTERRUPTED";r.finishedReason="server-shutdown";
        if(r.task!=null)r.task.cancel();
        ACTIVE=null;
        r.closeRequested=true;
        r.writeSummary(CFArenaAbilities.active()==null?Map.of():CFArenaAbilities.active().counters());
        r.awaitWriter();
    }

    /** Called by CFTrace.event. Fast exit when no soak test is active. */
    static void record(String kind,Object... attrs) {
        Run r=ACTIVE;
        if(r!=null && r.running)r.event(kind,attrs);
    }

    /** Real server tick time, measured by Minestom's tick monitor (milliseconds). */
    /** Optional maximal per-unit movement trace; off by default to protect TPS and disk. */
    static void unitTick(CFUnit u) {
        Run r=ACTIVE;
        if(r==null || !r.running || !r.fullUnitTicks || u.isRemoved())return;
        Entity target=u.traceTarget();
        r.event("UNIT_TICK","unit",u.getEntityId(),"team",u.team(),"mob",u.contentId(),
                "lane",u.laneId(),"hp",u.getHealth(),"pos",CFTrace.xyz(u.getPosition()),
                "velocity",CFTrace.vel(u.getVelocity()),"target",CFTrace.id(target),
                "nav",u.getNavigator().getState().name(),"ageTicks",u.getAliveTicks());
    }

    static void methodMetric(String name,long elapsedNanos) {
        Run r=ACTIVE;
        if(r!=null && r.running && elapsedNanos>=0)r.method(name,elapsedNanos);
    }

    static void tickMetric(double tickMs,double acquisitionMs,int liveUnits) {
        Run r=ACTIVE;
        if(r==null || !r.running || r.roundStartedNs==0)return;
        r.tick(tickMs,acquisitionMs,liveUnits);
    }


    static String deepProfile(int round) {
        String[] profiles={"MIXED_FROM_CASTLES","CASTLE_RACE","STRESS_100","STRESS_200",
                "STRESS_300","STRESS_500","STRESS_750","STRESS_1000",
                "ELEMENTAL_STACKS","RANGED_CROWD"};
        if(round<1)throw new IllegalArgumentException("Round must be positive");
        return profiles[(round-1)%profiles.length];
    }

    /** Pressure test remains constrained by normal map, world and configured unit cap. */
    static String burnProfile(int round) {
        String[] profiles={"STRESS_300","STRESS_500","STRESS_750","STRESS_1000",
                "RANGED_CROWD_500","ELEMENTAL_STACKS_500","STRESS_1000",
                "RANGED_CROWD_750","ELEMENTAL_STACKS_750","STRESS_1000"};
        if(round<1)throw new IllegalArgumentException("Round must be positive");
        return profiles[(round-1)%profiles.length];
    }

    String start(int rounds,int seconds) { return start(rounds,seconds,"MIXED_SOAK"); }
    String startVerify() { return start(12,60,"VERIFY_12"); }
    // Dedicated unattended ten-profile test: fixed round seeds for fair 1/4/5 push comparisons.
    String startNight(int rounds,int seconds) { return start(rounds,seconds,"NIGHT_DEEP_10_PROFILES"); }
    String startBurn(int rounds,int seconds) { return start(rounds,seconds,"BURN_10_PROFILES"); }
    static Path lastDirectory() { Run r=ACTIVE;return r!=null?r.dir:LAST_DIR; }
    String startStress(int rounds,int seconds) { return start(rounds,seconds,"STRESS_100_500"); }
    private String start(int rounds,int seconds,String mode) {
        boolean verify="VERIFY_12".equals(mode);
        if(isRunning()) return "[CF SOAK] Already running: "+status();
        if(rounds<1 || rounds>512 || seconds<20 || seconds>900)
            return "[CF SOAK] Allowed: 1..512 rounds and 20..900 seconds/round. Default: 128 x 120s.";
        if(!lanes.ready() || lanes.castle("blue")==null || lanes.castle("red")==null)
            return "[CF SOAK] Both castles and all 3 validated lanes are required.";
        if(content.mobs().isEmpty())return "[CF SOAK] No content mobs loaded.";
        try {
            Run r=new Run(rounds,seconds,lanes.status(),content.mobs().size(),mode);
            last=r;
            LAST_DIR=r.dir;
            ACTIVE=r;
            r.running=true;
            r.aiDroppedStart=CFSkeletonMindLog.droppedCount();
            CFSkeletonMindLog.beginCapture(r.dir);
            r.event("COMBAT_AUDIT_SCHEMA","version","2.6.4",
                    "damageContexts",true,"meleeSwingIds",true,
                    "arrowShotToImpact",true,"poisonStackIds",true,
                    "healthLabelRefresh",true,"gameplayAltered",true, "burnStacks",true, "deadMeleeBlocked",true);
            r.event("CROWD_AUDIT_SCHEMA","version","2.6.5-abd",
                    "netOverlapRelease",true,"localRecovery",true,"stallClassification",true,
                    "spawnIndexComparison",true,"castleArrivalChanged",false);
            r.event("SOAK_BEGIN","rounds",rounds,"secondsPerRound",seconds,
                    "map",System.getProperty("cf.map.path","<unconfigured>"),
                    "routes",lanes.status(),"mobs",content.mobs().size(),
                    "profile",mode,
                    "supportMode",verify?"none except final mixed case":"six test-only virtual altars; real buildings paused",
                    "castleHpMode","TEST_ONLY_VIRTUAL_NO_BLOCK_EDITS","eventSegmentsMiB",32,"nightProfiles",mode.equals("NIGHT_DEEP_10_PROFILES")||mode.equals("BURN_10_PROFILES")?10:8);
            r.stage="LOADING_CASTLES";
            System.out.println("[CF SOAK] Starting "+rounds+" live fights, "+seconds+
                    "s each; logs: "+r.dir.toAbsolutePath());
            r.task=world.scheduler().buildTask(()->update(r))
                    .delay(TaskSchedule.tick(1)).repeat(TaskSchedule.tick(20)).schedule();
            // Warm the route ends: no assumption that player presence guarantees the same chunks.
            Set<Long> chunks=new HashSet<>();
            List<java.util.concurrent.CompletableFuture<?>> loads=new ArrayList<>();
            for(int id=1;id<=3;id++)for(String team:List.of("red","blue")) {
                CFLanePath lane=lanes.lane(id);
                if(lane==null)continue;
                List<Pos> path=lane.forTeam(team);
                int lastIndex=Math.min(path.size()-1,60);
                for(int j=0;j<=lastIndex;j+=3) {
                    Pos at=path.get(j);
                    int cx=Math.floorDiv(at.blockX(),16),cz=Math.floorDiv(at.blockZ(),16);
                    long key=((long)cx<<32)^(cz&0xffffffffL);
                    if(chunks.add(key))loads.add(world.loadChunk(cx,cz));
                }
            }
            r.event("SOAK_CHUNK_LOAD_BEGIN","chunks",chunks.size());
            java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture<?>[0]))
                    .whenComplete((unused,error)->world.scheduler().scheduleNextTick(()->{
                        if(!r.running)return;
                        if(error!=null){
                            r.event("SOAK_FATAL","stage","LOAD_CASTLE_CHUNKS","error",String.valueOf(error));
                            finish(r,"chunk-load-failure");return;
                        }
                        r.event("SOAK_CHUNK_LOAD_OK","chunks",chunks.size());
                        beginRound(r);
                    }));
            return "[CF SOAK] Started "+rounds+" x "+seconds+"s from both castles / lanes 1-3. " +
                    "RUN="+r.dir+". /cf soak status /cf soak stop. At finish: scripts/pack-soak.sh";
        }catch(Exception e){
            Run r=last;
            if(r!=null && r.running)finish(r,"start-failed:"+e);
            System.err.println("[CF SOAK] Startup failed: "+e);
            e.printStackTrace();
            return "[CF SOAK] Cannot start: "+e;
        }
    }

    String status() {
        Run r=last;
        if(r==null)return "[CF SOAK] No run. /cf soak start [rounds] [seconds]";
        long elapsed=r.roundStartedNs==0?0:TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-r.roundStartedNs);
        return "[CF SOAK] "+(r.running?r.stage:r.finishedReason)+" mode="+r.mode+" round="+r.round+"/"+r.rounds+
                " completed="+r.roundsCompleted+" elapsed="+elapsed+"/"+r.seconds+"s alive="+units.size()+
                " peakAlive="+r.peakAlive+" target="+r.stressTarget+
                " castles="+String.format(Locale.ROOT,"%.1f/%.1f",r.blueCastleHp,r.redCastleHp)+
                " events="+r.eventCount.get()+" dropped="+r.dropped.sum()+
                " heapPeakMB="+r.peakHeap/1048576+" log="+r.dir+
                " writerComplete="+r.writerComplete+" writeError="+r.writeError;
    }

    String stop() {
        Run r=last;
        if(r==null || !r.running)return "[CF SOAK] Not running. "+status();
        finish(r,"operator-stopped");
        return "[CF SOAK] Stopped at round "+r.round+". Reports in "+r.dir+
                ". Run bash ~/castlefight-dev/scripts/pack-soak.sh after flushing.";
    }

    private void beginRound(Run r) {
        if(!r.running)return;
        if(r.round>=r.rounds){finish(r,"completed");return;}
        try {
            r.stage="PREPARING";
            clear.run(); // Clears ONLY match units and virtual altars; never touches placed towers or map.
            r.round++;
            r.roundStartedNs=0;
            r.resetRound();
            if(r.verify){beginVerifyRound(r);return;}
            if(r.mode.equals("BURN_10_PROFILES")) {
                r.roundProfile=burnProfile(r.round);
                r.stressTarget=Integer.parseInt(r.roundProfile.substring(r.roundProfile.lastIndexOf('_')+1));
            } else if(r.mode.startsWith("NIGHT") || r.mode.startsWith("STRESS")) {
                String[] names=r.mode.equals("NIGHT_DEEP_10_PROFILES")
                        ? new String[]{"MIXED_FROM_CASTLES","CASTLE_RACE","STRESS_100","STRESS_200",
                            "STRESS_300","STRESS_500","STRESS_750","STRESS_1000",
                            "ELEMENTAL_STACKS","RANGED_CROWD"}
                        : new String[]{"MIXED_FROM_CASTLES","CASTLE_RACE","STRESS_100","STRESS_200",
                            "STRESS_300","STRESS_500","ELEMENTAL_STACKS","RANGED_CROWD"};
                int profile=(r.round-1)%names.length;
                r.roundProfile=r.mode.equals("NIGHT_DEEP_10_PROFILES") ? deepProfile(r.round) :
                        r.mode.startsWith("STRESS")?"STRESS_"+
                        new int[]{100,200,350,500}[(r.round-1)%4]:names[profile];
                if(r.roundProfile.startsWith("STRESS_"))
                    r.stressTarget=Integer.parseInt(r.roundProfile.substring(7));
                else if("RANGED_CROWD".equals(r.roundProfile))r.stressTarget=100;
                else if("ELEMENTAL_STACKS".equals(r.roundProfile))r.stressTarget=80;
            } else r.roundProfile="MIXED_FROM_CASTLES";
            long scenarioSeed=0x5A17C0FFEE1234ABL ^ (0x9E3779B97F4A7C15L*r.round);
            Random scenario=new Random(scenarioSeed);
            for(String team:List.of("blue","red"))
                for(String ability:List.of("mutation","toxic","resurrection"))
                    abilities.addVirtualBuilding(team,ability);
            r.event("ROUND_BEGIN","round",r.round,"seed",scenarioSeed,
                    "map",System.getProperty("cf.map.path","<unconfigured>"),
                    "testAltars",6,"laneCount",3,
                    "profile",r.roundProfile,"stressTarget",r.stressTarget,
                    "virtualCastleHp",r.blueCastleHp,"seedControlsAbilityRng",false);
            if(r.stressTarget>=100)
                r.event("LOG_SAMPLING_POLICY","kinds","PUSH_CONTACT,PUSH_APPLY,CROWD_DETOUR",
                        "ratio",20,"combatEventsSampled",false,"snapshotsSampled",false);
            // Every round includes every mob type on each team. Rotate across lane 1-3 between
            // rounds; extra units vary per round, producing collisions, poisons and area fights.
            List<CFContentRegistry.Mob> mobTypes=new ArrayList<>(content.mobs());
            mobTypes.sort(Comparator.comparing(CFContentRegistry.Mob::id));
            int index=0,success=0,attempted=0;
            for(CFContentRegistry.Mob mob:mobTypes) {
                int laneId=(index+r.round-1)%3+1;
                int ordinal=index/3;
                for(String team:List.of("blue","red")) {
                    attempted++;
                    if(spawnNearCastle(team,mob.id(),laneId,ordinal,r.round,scenario))success++;
                }
                index++;
            }
            int extras=Math.min(6,Math.max(3,mobTypes.size()/2));
            for(int k=0;k<extras;k++){
                int laneId=(k+r.round)%3+1;
                CFContentRegistry.Mob mob=mobTypes.get(scenario.nextInt(mobTypes.size()));
                for(String team:List.of("blue","red")) {
                    attempted++;
                    if(spawnNearCastle(team,mob.id(),laneId,4+k/3,r.round,scenario))success++;
                }
            }
            r.roundRequested=attempted;
            r.roundAccepted=success;
            r.roundStartedNs=System.nanoTime();
            r.roundStartMillis=System.currentTimeMillis();
            r.stage="FIGHTING";
            r.peakAlive=Math.max(r.peakAlive,units.size());
            r.event("ROUND_WAVES_ISSUED","attempted",attempted,"accepted",success,
                    "rejected",attempted-success,"allTypes",mobTypes.size(),
                    "bothCastles",true,"lanes","1,2,3","stressTarget",r.stressTarget);
            System.out.println("[CF SOAK] ROUND "+r.round+"/"+r.rounds+
                    " accepted="+success+"/"+attempted+" across lanes 1-3; seed="+scenarioSeed);
            if(success==0){r.event("SOAK_FATAL","reason","all-initial-spawns-failed");finish(r,"all-spawns-failed");}
        } catch(Exception e) {
            r.event("SOAK_FATAL","stage","beginRound","error",e.toString());
            e.printStackTrace();finish(r,"round-start-error");
        }
    }

    private void beginVerifyRound(Run r) {
        String[] left={"grave_zombie","grave_zombie","risen_arsonist","risen_arsonist",
                "grave_zombie","grave_zombie","risen_arsonist","vampire","banshee","risen","acolyte"};
        String[] right={"mutated_zombie","mutated_zombie","mutated_zombie","mutated_zombie",
                "vampire","risen","risen","grave_zombie","grave_zombie","banshee","hell_skeleton"};
        String[] cases={"POISON_SINGLE","POISON_STACKS","BURN_SINGLE","BURN_STACKS","POISON_AND_BURN",
                "POISON_IMMUNITY","BURN_IMMUNITY","VAMPIRE","DODGE","RANGED_CROWD","SUMMON_WAVE","ALL_WITH_ALTARS"};
        int[] sizes={1,6,1,6,6,4,4,6,8,12,8};
        int k=r.round-1;
        r.roundProfile="VERIFY_"+cases[k];
        r.contactWaveSent=true; // no unrelated reinforcement to contaminate focused cases
        r.event("ROUND_BEGIN","profile","VERIFY_12","case",cases[k],"round",r.round,
                "testAltars",k==11?6:0,"note","near-midpoint combat; not castle travel benchmark");
        int requested=0,accepted=0;
        if(k==11) {
            for(String team:List.of("blue","red"))
                for(String ability:List.of("mutation","toxic","resurrection"))abilities.addVirtualBuilding(team,ability);
            int n=0;
            for(var mob:content.mobs()) {
                int lane=n%3+1;
                for(String team:List.of("blue","red"))for(int copy=0;copy<2;copy++){
                    requested++;if(verifySpawn(team,mob.id(),lane,n/3+copy*3))accepted++;
                }
                n++;
            }
        } else {
            int lane=k%3+1;
            for(int j=0;j<sizes[k];j++) {
                String blue=k==4 && j%2==1?"risen_arsonist":left[k];
                requested+=2;
                if(verifySpawn("blue",blue,lane,j))accepted++;
                if(verifySpawn("red",right[k],lane,j))accepted++;
            }
        }
        r.roundRequested=requested;r.roundAccepted=accepted;
        r.roundStartedNs=System.nanoTime();r.roundStartMillis=System.currentTimeMillis();r.stage="FIGHTING";
        r.event("ROUND_WAVES_ISSUED","attempted",requested,"accepted",accepted,"rejected",requested-accepted,"case",cases[k]);
        System.out.println("[CF VERIFY] "+r.round+"/12 "+cases[k]+" accepted="+accepted+"/"+requested);
        if(accepted==0)finish(r,"all-spawns-failed");
    }
    private boolean verifySpawn(String team,String mob,int laneId,int index) {
        CFLanePath lane=lanes.lane(laneId);
        if(lane==null || content.mob(mob)==null)return false;
        List<Pos> route=lane.forTeam(team);
        int node=Math.max(1,Math.min(route.size()-2,route.size()/2-4-index*2));
        return spawn.at(team,mob,laneId,route.get(node));
    }

    private boolean spawnNearCastle(String team,String mob,int laneId,int ordinal,int round,Random rng) {
        CFLanePath lane=lanes.lane(laneId);
        if(lane==null)return false;
        List<Pos> path=lane.forTeam(team);
        int max=Math.max(1,path.size()-2);
        // Stay in the first part of the certified route, near this team's castle.
        // Multiple start points prevent all units from occupying one physical cell.
        int index=Math.min(max,Math.max(1,3+ordinal*6+(round%3)*2));
        for(int retry=0;retry<4;retry++) {
            int j=Math.min(max,index+retry*4);
            if(spawn.at(team,mob,laneId,path.get(j)))return true;
        }
        CFTrace.event("SOAK_SPAWN_REJECTED","team",team,"mob",mob,
                "lane",laneId,"castle",CFTrace.xyz(lanes.castle(team)),"attempts",4);
        return false;
    }

    /** Rate-limited stress wave: never bypasses the normal Minestom unit spawn/collision checks.
     * Actual peak may be below requested population when the lane geometry cannot fit it.
     */
    private void stressStep(Run r) {
        int issuedLimit=r.stressTarget*("BURN_10_PROFILES".equals(r.mode)?8:3);
        if(r.stressTarget<=0 || units.size()>=r.stressTarget || r.stressIssued>=issuedLimit)return;
        List<CFContentRegistry.Mob> types=new ArrayList<>(content.mobs());
        types.sort(Comparator.comparing(CFContentRegistry.Mob::id));
        // BURN uses 4x the replenishment pressure, rather than illegally bypassing
        // the configured global unit cap. 8/s in the night suite frequently could
        // not accumulate 750-1000 LIVE units before the earlier waves died.
        int budget="BURN_10_PROFILES".equals(r.mode) ? 32 : 8;
        while(budget-->0 && units.size()<r.stressTarget && r.stressIssued<issuedLimit) {
            int index=r.stressIssued++;
            int laneId=(index+r.round)%3+1;
            String team=(index%2==0)?"blue":"red";
            int kindIndex=Math.floorMod(index/2 + r.round*7,types.size());
            CFContentRegistry.Mob mob=types.get(kindIndex);
            if(r.roundProfile.startsWith("RANGED_CROWD")) {
                var ranged=types.stream().filter(t->"ranged_hold".equals(t.behavior())).toList();
                if(!ranged.isEmpty())mob=ranged.get(Math.floorMod(index,ranged.size()));
            } else if(r.roundProfile.startsWith("ELEMENTAL_STACKS")) {
                var wanted=(index%3==0)?"risen_arsonist":"grave_zombie";
                if(content.mob(wanted)!=null)mob=content.mob(wanted);
            }
            CFLanePath lane=lanes.lane(laneId);
            if(lane==null)continue;
            List<Pos> route=lane.forTeam(team);
            if(route.size()<4)continue;
            int usable=Math.max(1,route.size()-8);
            int node=Math.min(route.size()-2,Math.max(1,2+Math.floorMod(index*7+r.round*17,usable)));
            // BURN only: distribute candidates ACROSS the existing wide corridor
            // instead of repeatedly contesting the exact carpet centre.
            // Normal spawn and collision/geometric safety checks still decide acceptance.
            Pos candidate=route.get(node);
            if("BURN_10_PROFILES".equals(r.mode)) {
                Pos prev=route.get(node-1),next=route.get(node+1);
                double dx=next.x()-prev.x(),dz=next.z()-prev.z();
                double length=Math.hypot(dx,dz);
                if(length>1.e-6) {
                    double lateral=(Math.floorMod(index/3+r.round,5)-2)*1.25;
                    candidate=candidate.add(-dz/length*lateral,0,dx/length*lateral);
                }
            }
            boolean ok=spawn.at(team,mob.id(),laneId,candidate);
            r.roundRequested++;
            if(ok){r.roundAccepted++;r.stressAccepted++;}
            else r.stressRejected++;
        }
        if(r.stressIssued%64<8 || r.stressIssued==issuedLimit)
            r.event("STRESS_PROGRESS","profile",r.roundProfile,"targetAlive",r.stressTarget,
                    "live",units.size(),"peak",r.peakAlive,"issued",r.stressIssued,
                    "accepted",r.stressAccepted,"rejected",r.stressRejected,
                    "cap",content.limits().units(),"budgetPerSecond",("BURN_10_PROFILES".equals(r.mode)?32:8));
    }

    /** Only for diagnostic rounds. Castles are virtual HP counters: NO map block changes.
     * One hit per unit per 2 seconds when physically within 8 blocks of the enemy castle.
     */
    private void sampleCastleAssault(Run r,double elapsed) {
        if(elapsed-r.lastCastleSample<2.0)return;
        r.lastCastleSample=elapsed;
        for(CFUnit u:units) {
            if(!u.combatAlive())continue;
            String enemy="blue".equals(u.team())?"red":"blue";
            Pos castle=lanes.castle(enemy);
            if(castle==null || u.getPosition().distanceSquared(castle)>64.0)continue;
            float damage=Math.max(.5f,Math.min(6f,u.attackDamage()));
            double old="blue".equals(enemy)?r.blueCastleHp:r.redCastleHp;
            if(old<=0)continue;
            double updated=Math.max(0,old-damage);
            if("blue".equals(enemy))r.blueCastleHp=updated;else r.redCastleHp=updated;
            r.castleHits++;
            r.event("CASTLE_HIT_TEST","team",enemy,"attacker",u.getEntityId(),
                    "attackerTeam",u.team(),"mob",u.contentId(),"lane",u.laneId(),
                    "distance",Math.sqrt(u.getPosition().distanceSquared(castle)),
                    "damage",Math.min(old,damage),"hpBefore",old,"hpAfter",updated,
                    "testOnly",true);
            if(updated==0)r.event("CASTLE_DESTROYED_TEST","team",enemy,"winner",u.team(),
                    "round",r.round,"testOnly",true);
        }
    }

    private void update(Run r) {
        if(!r.running)return;
        try {
            if(r.queueFailure) {finish(r,"log-overflow-or-write-failure");return;}
            if(CFSkeletonMindLog.droppedCount()>r.aiDroppedStart || !"none".equals(CFSkeletonMindLog.writerError())) {
                r.event("SOAK_SAFETY_STOP","reason","AI_LOG_DROPPED_OR_FAILED",
                        "aiDropped",CFSkeletonMindLog.droppedCount()-r.aiDroppedStart,
                        "error",CFSkeletonMindLog.writerError());
                finish(r,"safety-ai-log-failure");return;
            }
            if(r.slowTicks100>=120 || r.consecutiveSlow>=30) {
                r.event("SOAK_SAFETY_STOP","reason","SUSTAINED_SLOW_TICKS",
                        "slow100Ms",r.slowTicks100,"consecutiveSlow",r.consecutiveSlow);
                finish(r,"safety-sustained-slow-ticks");return;
            }
            if(Runtime.getRuntime().maxMemory()>0 && r.peakHeap>Runtime.getRuntime().maxMemory()*0.88) {
                r.event("SOAK_SAFETY_STOP","reason","HEAP_PRESSURE","peakHeap",r.peakHeap);
                finish(r,"safety-heap-pressure");return;
            }
            if(!"FIGHTING".equals(r.stage))return;
            long now=System.nanoTime();
            double elapsed=(now-r.roundStartedNs)/1.0e9;
            Runtime runtime=Runtime.getRuntime();
            long heap=runtime.totalMemory()-runtime.freeMemory();
            r.peakHeap=Math.max(r.peakHeap,heap);
            if((int)elapsed>0 && (int)elapsed%60==0 && (int)elapsed!=r.lastDiskCheckSecond) {
                r.lastDiskCheckSecond=(int)elapsed;
                long free=Files.getFileStore(r.dir).getUsableSpace();
                if(free<2L*1024*1024*1024) {
                    r.event("SOAK_SAFETY_STOP","reason","LOW_DISK_SPACE","freeBytes",free);
                    finish(r,"safety-low-disk-space");return;
                }
            }
            r.peakAlive=Math.max(r.peakAlive,units.size());
            stressStep(r);
            r.peakAlive=Math.max(r.peakAlive,units.size()); // include newly spawned same-tick units
            sampleCastleAssault(r,elapsed);
            r.event("RUNTIME_SNAPSHOT","elapsedSec",Math.round(elapsed*10)/10.0,
                    "heapUsedBytes",heap,"heapCommittedBytes",runtime.totalMemory(),
                    "heapMaxBytes",runtime.maxMemory(),"livingUnits",units.size(),
                    "scheduledAbilities",abilities.pendingCount(),
                    "activePoisonStacks",abilities.activePoisonStacks(),"activeBurnStacks",abilities.activeBurnStacks(),
                    "peakAlive",r.peakAlive,"stressTarget",r.stressTarget,
                    "castleBlueHp",r.blueCastleHp,"castleRedHp",r.redCastleHp,
                    "stressRejected",r.stressRejected,"slowTicksOver100ms",r.slowTicks100);
            // One snapshot per second per living unit: full HP/position/AI target/lane trajectory.
            int blue=0,red=0,unitSamples=0;
            List<CFSoakCrowdAudit.Snapshot> crowdSnapshots=new ArrayList<>();
            for(CFUnit u:units) {
                if(u.isRemoved()||u.isDead())continue;
                if("blue".equals(u.team()))blue++;else red++;
                Entity target=u.traceTarget();
                r.event("UNIT_STATE","unit",u.getEntityId(),"team",u.team(),"mob",u.contentId(),
                        "lane",u.laneId(),"hp",u.getHealth(),
                        "position",CFTrace.xyz(u.getPosition()),"velocity",CFTrace.vel(u.getVelocity()),
                        "target",CFTrace.id(target),"goal",CFTrace.goal(u),
                        "nav",u.getNavigator().getState().name(),"ageTicks",u.getAliveTicks());
                unitSamples++;
                var p=u.getPosition();
                crowdSnapshots.add(new CFSoakCrowdAudit.Snapshot(u.getEntityId(),u.team(),u.laneId(),p.x(),p.y(),p.z()));
            }
            var crowdResult=r.crowdAudit.observe(crowdSnapshots,r::event);
            r.event("CROWD_OVERLAP_SUMMARY","pairsUnder0_7",crowdResult.nearPairs(),
                    "persistentPairs",crowdResult.persistentPairs(),
                    "longestSeconds",crowdResult.longestSeconds(),"truncated",crowdResult.truncated());
            r.event("ROUND_HEARTBEAT","aliveBlue",blue,"aliveRed",red,
                    "unitSnapshots",unitSamples,"elapsedSec",Math.round(elapsed));
            // If combat truly ended, do not waste two minutes on an empty arena. A 25s grace
            // allows resurrection and delayed summons to finish after the final damage.
            if(!r.contactWaveSent && elapsed>=Math.min(45,r.seconds*0.45)) {
                r.contactWaveSent=true;
                contactWave(r);
            }
            if("CASTLE_RACE".equals(r.roundProfile) && (r.blueCastleHp==0 || r.redCastleHp==0)) {
                endRound(r,"castle-destroyed-test");
            } else if(elapsed>=r.seconds || (r.contactWaveSent && elapsed>=Math.min(r.seconds-1,70) && blue+red==0)) {
                endRound(r,elapsed>=r.seconds?"time-limit":"no-survivors");
            } else if((int)elapsed%20==0 && Math.abs(elapsed-Math.rint(elapsed/20)*20)<1.1){
                System.out.println("[CF SOAK] "+r.round+"/"+r.rounds+
                        " "+(int)elapsed+"s live="+(blue+red)+" peak="+r.peakAlive+
                        " target="+r.stressTarget+" dropped="+r.dropped.sum());
            }
        }catch(Exception e){
            r.event("SOAK_FATAL","stage","tick","error",e.toString());
            e.printStackTrace();finish(r,"tick-exception");
        }
    }

    /** Second phase: a compact real-combat encounter near lane midpoints. It does not replace
     * the castle-born march; it specifically checks on-hit skills if long routes delay contact. */
    private void contactWave(Run r) {
        String[][] pairings={
                {"grave_zombie","banshee"},
                {"vampire","risen"},
                {"hell_skeleton","acolyte"}
        };
        int success=0;
        for(int i=0;i<3;i++){
            CFLanePath lane=lanes.lane(i+1);
            if(lane==null)continue;
            String blue=(r.round%2==0?pairings[i][0]:pairings[i][1]);
            String red=(r.round%2==0?pairings[i][1]:pairings[i][0]);
            if(content.mob(blue)!=null && spawn.at("blue",blue,i+1,
                    lane.blue.get(Math.max(1,lane.blue.size()/2-3))))success++;
            if(content.mob(red)!=null && spawn.at("red",red,i+1,
                    lane.red.get(Math.max(1,lane.red.size()/2-3))))success++;
        }
        r.roundRequested+=6;
        r.roundAccepted+=success;
        r.event("SOAK_CONTACT_WAVE","round",r.round,"accepted",success,"attempted",6,
                "reason","guarantee live combat opportunities after castle march");
    }

    private void endRound(Run r,String reason) {
        if(!r.running || !"FIGHTING".equals(r.stage))return;
        r.stage="ROUND_FINISHED";
        r.roundsCompleted++;
        long elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-r.roundStartedNs);
        Map<String,Long> abilitiesNow=abilities.counters();
        long poison=diff(abilitiesNow,r.abilityBaseline,"poisonStacks"),
             dodges=diff(abilitiesNow,r.abilityBaseline,"dodges"),
             lifesteals=diff(abilitiesNow,r.abilityBaseline,"lifesteals"),
             waves=diff(abilitiesNow,r.abilityBaseline,"waves"),
             splashes=diff(abilitiesNow,r.abilityBaseline,"splashes"),
             summons=diff(abilitiesNow,r.abilityBaseline,"summons"),
             mutations=diff(abilitiesNow,r.abilityBaseline,"mutations"),
             revivals=diff(abilitiesNow,r.abilityBaseline,"revivals"),
             toxic=diff(abilitiesNow,r.abilityBaseline,"toxicCasts");
        double p50=r.tickPercentile(.50),p95=r.tickPercentile(.95),p99=r.tickPercentile(.99);
        r.event("ROUND_END","reason",reason,"durationMs",elapsedMs,
                "profile",r.roundProfile,"peakAlive",r.peakAlive,"stressTarget",r.stressTarget,
                "stressIssued",r.stressIssued,"stressRejected",r.stressRejected,
                "castleHpBlue",r.blueCastleHp,"castleHpRed",r.redCastleHp,
                "castleHits",r.castleHits,"winner",r.castleWinner(),
                "longestOverlapSeconds",r.crowdAudit.longestSeconds(),
                "spawnsRequested",r.roundRequested,"spawnsAccepted",r.roundAccepted,
                "blueDeaths",r.blueDeaths,"redDeaths",r.redDeaths,
                "poisonStacks",poison,"dodges",dodges,"lifesteals",lifesteals,
                "waves",waves,"splash",splashes,"summons",summons,
                "mutations",mutations,"revivals",revivals,"toxic",toxic,
                "tickP50Ms",p50,"tickP95Ms",p95,"tickP99Ms",p99,"tickMaxMs",r.tickMax,
                "tickSamples",r.tickSize,"dropped",r.dropped.sum(),
                "movementSamplesSkipped",r.movementSampledOut.sum()-r.roundSampleBaseline,
                "burnStacks",diff(abilitiesNow,r.abilityBaseline,"burnStacks"),
                "burnPulses",diff(abilitiesNow,r.abilityBaseline,"burnPulses"));
        String csv=r.round+","+reason+","+elapsedMs+","+r.roundRequested+","+r.roundAccepted+","+
                r.blueDeaths+","+r.redDeaths+","+poison+","+dodges+","+lifesteals+","+waves+","+
                splashes+","+summons+","+mutations+","+revivals+","+toxic+","+
                String.format(Locale.ROOT,"%.3f,%.3f,%.3f,%.3f",p50,p95,p99,r.tickMax)+","+
                r.tickSize+","+r.dropped.sum()+",true,"+
                diff(abilitiesNow,r.abilityBaseline,"burnStacks")+","+diff(abilitiesNow,r.abilityBaseline,"burnPulses")+","+
                r.roundProfile+","+r.peakAlive+","+r.stressTarget+","+r.stressIssued+","+r.stressRejected+","+
                String.format(Locale.ROOT,"%.2f,%.2f",r.blueCastleHp,r.redCastleHp)+","+r.castleHits+","+r.castleWinner()+"\n";
        try{Files.writeString(r.roundCsv,csv,StandardOpenOption.APPEND);}catch(IOException e){
            r.queueFailure=true;r.writeError="round-csv:"+e;
        }
        System.out.println("[CF SOAK] END ROUND "+r.round+"/"+r.rounds+
                " "+reason+" accepted="+r.roundAccepted+" deaths="+(r.blueDeaths+r.redDeaths)+
                " p99Tick="+String.format(Locale.ROOT,"%.2f",p99)+"ms");
        if(r.roundEvents.getOrDefault("HIT_CONFIRMED",new LongAdder()).sum()==0) {
            r.event("ROUND_ANOMALY","reason","NO_CONFIRMED_HITS",
                    "note","Check route speed, collision, attack resolution and spawn positions");
        }
        if(r.stressTarget>0 && r.peakAlive<r.stressTarget)
            r.event("ROUND_ANOMALY","reason","STRESS_TARGET_NOT_REACHED", "target",r.stressTarget,
                    "peakAlive",r.peakAlive,"issued",r.stressIssued,"rejected",r.stressRejected);
        if(r.roundAccepted<r.roundRequested)r.event("ROUND_ANOMALY","reason","SPAWN_REJECTED",
                "requested",r.roundRequested,"accepted",r.roundAccepted);
        r.roundStartedNs=0;
        if(r.round>=r.rounds)finish(r,"completed");
        else beginRound(r);
    }

    private static long diff(Map<String,Long> now,Map<String,Long> old,String k) {
        return now.getOrDefault(k,0L)-old.getOrDefault(k,0L);
    }

    private void finish(Run r,String reason) {
        if(!r.running)return;
        r.abortRound(reason);
        CFSkeletonMindLog.finishCapture(r.dir);
        r.event("SOAK_END","reason",reason,"roundsStarted",r.round,"roundsCompleted",r.roundsCompleted,
                "events",r.eventCount.get(),"dropped",r.dropped.sum(),
                "movementSamplesSkipped",r.movementSampledOut.sum(),
                "heapPeakBytes",r.peakHeap,"logWriteError",r.writeError);
        r.running=false;
        r.stage="FINISHED";
        r.finishedReason=reason;
        if(r.task!=null)r.task.cancel();
        if(ACTIVE==r)ACTIVE=null;
        try {clear.run();} catch(Exception e) {System.err.println("[CF SOAK] Cleanup: "+e);}
        r.closeRequested=true;
        r.writeSummary(abilities.counters());
        System.out.println("[CF SOAK] FINISHED: "+reason+"; "+r.dir.toAbsolutePath()+
                "; lost log events="+r.dropped.sum()+". Run ~/castlefight-dev/scripts/pack-soak.sh");
    }

    /** Offline logging self-test. NEVER starts a Minestom world or touches saved maps. */
    static void selfTestJournal() throws Exception {
        Run r=new Run(2,20,"VALID SELFTEST",10);
        r.running=true;r.round=1;
        r.event("UNIT_DIED","team","blue","unit",42,"reason","quoted \"value\" and newline\n");
        r.event("POISON_APPLY","damage",0.6,"success",true);
        r.tick(42.1,1.5,12);
        r.tick(8.4,0.2,11);
        r.method("CFUnit.update",850);
        if(r.blueDeaths!=1 || r.redDeaths!=0 || r.tickSize!=2 || r.tickPercentile(.99)!=42.1)
            throw new AssertionError("Journal counters or percentile incorrect");
        r.running=false;r.closeRequested=true;r.awaitWriter();
        if(!r.writerComplete || r.dropped.sum()!=0 || !Files.exists(r.eventsFile))
            throw new AssertionError("Writer did not flush without drops");
        List<String> lines=Files.readAllLines(r.eventsFile);
        if(lines.size()!=4 || !lines.get(0).contains("\\\"value\\\""))
            throw new AssertionError("Journal did not escape JSON or lost entries: "+lines.size());
        r.finishedReason="selftest-passed";
        r.writeSummary(Map.of("poisonStacks",1L));
        if(!Files.readString(r.dir.resolve("summary.txt")).contains("POISON_APPLY=1"))
            throw new AssertionError("Summary counters missing");
        System.out.println("[CF SOAK SELFTEST] PASS: async JSONL, escaping, tick percentiles, counters, summary; "+r.dir);
    }

    /** Isolated event rotation test: custom small segment cap, no active arena needed. */
    static void selfTestSegments() throws Exception {
        Run r=new Run(2,20,"SEGMENT_REGRESSION",8);
        r.running=true;r.round=1;
        final int count=1200;
        for(int i=0;i<count;i++)r.event("STRESS_TEST_EVENT","index",i,"payload","ASCII abc / escaped \"quote\"");
        r.running=false;r.closeRequested=true;r.awaitWriter();
        if(!r.writerComplete || r.queueFailure || r.dropped.sum()!=0)
            throw new AssertionError("Rotation writer could not flush all entries");
        List<Path> files=new ArrayList<>();files.add(r.eventsFile);
        try(var stream=Files.list(r.dir)) {
            stream.filter(p->p.getFileName().toString().matches("events-[0-9]{4}\\.jsonl"))
                    .sorted().forEach(files::add);
        }
        if(files.size()<3)throw new AssertionError("Segment cap did not trigger rotation");
        long lines=0,bytes=0;
        for(Path file:files) {
            long len=Files.size(file);
            if(len<=0 || len>Run.MAX_EVENT_SEGMENT)
                throw new AssertionError("Segment too large/empty: "+file+" bytes="+len);
            bytes+=len;
            try(var input=Files.lines(file,StandardCharsets.UTF_8)) {
                lines+=input.peek(line->{if(!line.startsWith("{\"seq\":"))throw new AssertionError("Invalid JSONL prefix");}).count();
            }
        }
        String index=Files.readString(r.dir.resolve("EVENT_SEGMENTS.json"));
        if(lines!=count || !index.contains("\"totalLines\":"+count) ||
                !index.contains("\"totalBytes\":"+bytes))
            throw new AssertionError("Rotated event manifest mismatched payload");
        System.out.println("[CF264 SEGMENTS] PASS "+count+" events, "+files.size()+
                " independent JSONL parts, "+bytes+" bytes");
    }

    static void selfTestInterruptedRound() throws Exception {
        Run r=new Run(12,60,"SYNTHETIC",8,true);
        r.running=true;r.round=2;r.roundsCompleted=1;r.stage="FIGHTING";
        r.roundStartedNs=System.nanoTime()-1_000_000_000L;
        r.abortRound("operator-stopped");
        if(r.roundsCompleted!=1 || r.roundStartedNs!=0 || !Files.readString(r.roundCsv).contains(",false,"))
            throw new AssertionError("Interrupted battle counted as completed or partial CSV lost");
        r.running=false;r.closeRequested=true;r.awaitWriter();
        if(!r.writerComplete || r.totalEvents.get("ROUND_ABORTED").sum()!=1)
            throw new AssertionError("Interrupted-round event missing");
        System.out.println("[CF263 ROUND] PASS: started=2 completed=1, partial round persisted");
    }

    private static final class Run {
        final Path dir,eventsFile,roundCsv;
        final ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(65536);
        final AtomicLong eventCount=new AtomicLong();
        final LongAdder dropped=new LongAdder();
        final LongAdder movementSampledOut=new LongAdder();
        private long noisyObserved;
        long roundSampleBaseline;
        final Map<String,LongAdder> totalEvents=new java.util.concurrent.ConcurrentHashMap<>();
        final int rounds,seconds,mobTypes;
        final boolean verify;
        final String mode;
        final boolean fullUnitTicks=Boolean.getBoolean("cf.soak.fullUnitTicks");
        final String routesStatus;
        volatile boolean running,closeRequested,queueFailure,writerComplete;
        volatile Thread writerThread;
        volatile String stage="INITIALIZING",finishedReason="not-finished",writeError="none";
        volatile int round,roundsCompleted;
        volatile long roundStartedNs,roundStartMillis,peakHeap;
        Task task;
        int roundRequested,roundAccepted,blueDeaths,redDeaths;
        boolean contactWaveSent;
        final CFSoakCrowdAudit crowdAudit=new CFSoakCrowdAudit();
        String roundProfile="MIXED_FROM_CASTLES";
        int stressTarget,stressIssued,stressAccepted,stressRejected,peakAlive,castleHits;
        int lastDiskCheckSecond=-1;
        long aiDroppedStart;
        long slowTicks100;
        int consecutiveSlow;
        double blueCastleHp=240,redCastleHp=240,lastCastleSample=-2;
        String castleWinner(){
            if(blueCastleHp>0 && redCastleHp>0)return "none";
            if(blueCastleHp<=0 && redCastleHp<=0)return "draw";
            return blueCastleHp<=0?"red":"blue";
        }
        Map<String,LongAdder> roundEvents=new HashMap<>();
        final Map<String,MethodPerf> methods=new java.util.concurrent.ConcurrentHashMap<>();
        Map<String,Long> abilityBaseline=Map.of();
        double[] ticks=new double[4096];int tickSize;double tickMax;

        Run(int rounds,int seconds,String routesStatus,int mobTypes) throws IOException {
            this(rounds,seconds,routesStatus,mobTypes,"MIXED_SOAK");
        }
        Run(int rounds,int seconds,String routesStatus,int mobTypes,boolean verify) throws IOException {
            this(rounds,seconds,routesStatus,mobTypes,verify?"VERIFY_12":"MIXED_SOAK");
        }
        Run(int rounds,int seconds,String routesStatus,int mobTypes,String mode) throws IOException {
            this.mode=mode;this.verify="VERIFY_12".equals(mode);
            this.rounds=rounds;this.seconds=seconds;this.routesStatus=routesStatus;this.mobTypes=mobTypes;
            String name="soak-"+DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC).format(Instant.now())+"-"+ProcessHandle.current().pid();
            dir=Path.of("diagnostics","soak",name);
            Files.createDirectories(dir);
            eventsFile=dir.resolve("events.jsonl");
            roundCsv=dir.resolve("rounds.csv");
            Files.writeString(roundCsv,"round,reason,elapsed_ms,requested,accepted,blue_deaths,red_deaths,"+
                    "poison_stacks,dodges,lifesteals,waves,splashes,summons,mutations,revivals,toxic_casts,"+
                    "tick_p50_ms,tick_p95_ms,tick_p99_ms,tick_max_ms,tick_samples,dropped_events,completed,burn_stacks,burn_pulses,"+
                    "profile,peak_alive,stress_target,stress_issued,stress_rejected,castle_blue_hp,castle_red_hp,castle_hits,castle_winner\n",
                    StandardOpenOption.CREATE_NEW);
            StringBuilder meta=new StringBuilder("Castle Fight SOAK test\nstart="+Instant.now()+"\n"+
                    "rounds="+rounds+"\nsecondsPerRound="+seconds+"\nprofile="+mode+"\n"+
                    "map="+System.getProperty("cf.map.path","<unconfigured>")+"\n"+
                    "routes="+routesStatus+"\ncontentMobTypes="+mobTypes+"\n"+
                    "java="+System.getProperty("java.version")+"\nos="+System.getProperty("os.name")+"\n"+
                    "maxHeapBytes="+Runtime.getRuntime().maxMemory()+"\n"+
                    "serverConsoleLog="+CFConsoleCapture.logFile()+"\n"+
                    "timing=Minestom ServerTickMonitorEvent in milliseconds\n"+
                    "fullUnitTicks="+fullUnitTicks+"\n"+
                    "NOTE: seeds control initial mob distribution, not ability RNG.\n"+
                    "NOTE: normal placed buildings pause during run and resume afterward.\n");
            for(GarbageCollectorMXBean gc:ManagementFactory.getGarbageCollectorMXBeans())
                meta.append("gc=").append(gc.getName()).append('\n');
            Files.writeString(dir.resolve("environment.txt"),meta,StandardOpenOption.CREATE_NEW);
            // Immutable provenance: packing later must not silently substitute updated configs.
            for(String folder:List.of("content","navigation")) {
                Path root=Path.of(folder);
                if(Files.isDirectory(root))try(var paths=Files.walk(root)) {
                    for(Path f:paths.filter(Files::isRegularFile).toList()) {
                        Path dest=dir.resolve("config-at-start").resolve(folder).resolve(root.relativize(f));
                        Files.createDirectories(dest.getParent());Files.copy(f,dest,StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if(Files.isRegularFile(Path.of("CF_BUILD_INFO.txt")))
                Files.copy(Path.of("CF_BUILD_INFO.txt"),dir.resolve("CF_BUILD_INFO.txt"));
            writerThread=Thread.ofVirtual().name("cf-soak-log-writer").start(this::writeEvents);
        }

        void abortRound(String reason) {
            if(roundStartedNs==0 || !"FIGHTING".equals(stage))return;
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-roundStartedNs);
            event("ROUND_ABORTED","reason",reason,"durationMs",elapsed,"completed",false,
                    "spawnsRequested",roundRequested,"spawnsAccepted",roundAccepted,
                    "blueDeaths",blueDeaths,"redDeaths",redDeaths,"tickSamples",tickSize,
                    "profile",roundProfile,"peakAlive",peakAlive,
                    "castleHpBlue",blueCastleHp,"castleHpRed",redCastleHp);
            // Preserve a partial CSV row but do NOT count it as a completed battle.
            Map<String,Long> a=CFArenaAbilities.active()==null?Map.of():CFArenaAbilities.active().counters();
            String row=round+",operator-interrupted,"+elapsed+","+roundRequested+","+roundAccepted+","+
                    blueDeaths+","+redDeaths;
            for(String key:List.of("poisonStacks","dodges","lifesteals","waves","splashes","summons","mutations","revivals","toxicCasts"))
                row+=","+diff(a,abilityBaseline,key);
            row+=String.format(Locale.ROOT,",%.3f,%.3f,%.3f,%.3f,%d,%d,false,%d,%d\n",
                    tickPercentile(.5),tickPercentile(.95),tickPercentile(.99),tickMax,tickSize,dropped.sum(),
                    diff(a,abilityBaseline,"burnStacks"),diff(a,abilityBaseline,"burnPulses"));
            row=row.trim()+","+roundProfile+","+peakAlive+","+stressTarget+","+stressIssued+","+
                    stressRejected+","+String.format(Locale.ROOT,"%.2f,%.2f",blueCastleHp,redCastleHp)+","+
                    castleHits+","+castleWinner()+"\n";
            try{Files.writeString(roundCsv,row,StandardOpenOption.APPEND);}catch(IOException e){writeError=e.toString();queueFailure=true;}
            roundStartedNs=0;
        }

        void resetRound() {
            blueDeaths=redDeaths=roundRequested=roundAccepted=0;
            contactWaveSent=false;
            crowdAudit.reset();
            roundProfile="MIXED_FROM_CASTLES";stressTarget=stressIssued=stressAccepted=stressRejected=peakAlive=castleHits=0;
            blueCastleHp=redCastleHp=240;lastCastleSample=-2;
            consecutiveSlow=0;slowTicks100=0;
            roundEvents=new HashMap<>();
            tickSize=0;tickMax=0;
            abilityBaseline=CFArenaAbilities.active().counters();
            roundSampleBaseline=movementSampledOut.sum();
        }

        synchronized void event(String kind,Object... attrs) {
            totalEvents.computeIfAbsent(kind,k->new LongAdder()).increment();
            roundEvents.computeIfAbsent(kind,k->new LongAdder()).increment();
            // At >=100 target units, sample only repetitive contact diagnostics 1:20.
            // All combat, death, castle, and per-second UNIT_STATE events are retained.
            if(stressTarget>=100 && ("PUSH_CONTACT".equals(kind) || "PUSH_APPLY".equals(kind)
                    || "CROWD_DETOUR".equals(kind))) {
                if(++noisyObserved%20!=0) {movementSampledOut.increment();return;}
            }
            long seq=eventCount.incrementAndGet();
            StringBuilder b=new StringBuilder(256).append('{');
            field(b,"seq",seq);field(b,"ts",System.currentTimeMillis());
            field(b,"round",round);field(b,"event",kind);
            for(int i=0;i+1<attrs.length;i+=2)field(b,String.valueOf(attrs[i]),attrs[i+1]);
            b.append('}');
            if("UNIT_DIED".equals(kind)) {
                Object team=fieldValue(attrs,"team");
                if("blue".equals(team))blueDeaths++;
                if("red".equals(team))redDeaths++;
            }
            if(!queue.offer(b.toString())){dropped.increment();queueFailure=true;}
        }

        static Object fieldValue(Object[] attrs,String k) {
            for(int i=0;i+1<attrs.length;i+=2)if(k.equals(attrs[i]))return attrs[i+1];
            return null;
        }

        void tick(double duration,double acquisition,int living) {
            if(tickSize==ticks.length)ticks=Arrays.copyOf(ticks,ticks.length*2);
            ticks[tickSize++]=duration;
            tickMax=Math.max(tickMax,duration);
            if(duration>=100){slowTicks100++;consecutiveSlow++;}else consecutiveSlow=0;
            event("TICK","tickMs",duration,"acquisitionMs",acquisition,"units",living);
        }

        double tickPercentile(double percentile){
            if(tickSize==0)return 0;
            double[] copy=Arrays.copyOf(ticks,tickSize);
            Arrays.sort(copy);
            int index=(int)Math.ceil(percentile*copy.length)-1;
            return copy[Math.max(0,Math.min(copy.length-1,index))];
        }

        void method(String name,long nanos) {
            methods.computeIfAbsent(name,k->new MethodPerf()).observe(nanos);
        }
        private static final long MAX_EVENT_SEGMENT = Math.max(4096L,Math.min(32L * 1024 * 1024,
                Long.getLong("cf.soak.eventSegmentBytes",32L * 1024 * 1024)));
        private void writeEvents() {
            BufferedWriter writer=null;
            int index=0;
            long bytes=0, lines=0, totalBytes=0;
            List<String> segments=new ArrayList<>();
            long lastFlush=System.nanoTime();
            try {
                writer=Files.newBufferedWriter(eventsFile,StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                while(!closeRequested || !queue.isEmpty()) {
                    String message=queue.poll(200,TimeUnit.MILLISECONDS);
                    if(message!=null) {
                        byte[] encoded=message.getBytes(StandardCharsets.UTF_8);
                        int len=encoded.length+1;
                        if(len>MAX_EVENT_SEGMENT)throw new IOException("Single JSONL entry exceeds event segment limit");
                        if(bytes>0 && bytes+len>MAX_EVENT_SEGMENT) {
                            writer.flush();writer.close();writer=null;
                            Path file=index==0?eventsFile:dir.resolve(String.format(Locale.ROOT,"events-%04d.jsonl",index));
                            segments.add("{\"file\":\""+file.getFileName()+"\",\"bytes\":"+bytes+
                                    ",\"lines\":"+lines+"}");
                            totalBytes+=bytes;index++;
                            Path next=dir.resolve(String.format(Locale.ROOT,"events-%04d.jsonl",index));
                            writer=Files.newBufferedWriter(next,StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                            bytes=0;lines=0;
                        }
                        writer.write(message);writer.write('\n');
                        bytes+=len;lines++;
                    }
                    if(System.nanoTime()-lastFlush>=500_000_000L){writer.flush();lastFlush=System.nanoTime();}
                }
                writer.flush();writer.close();writer=null;
                Path file=index==0?eventsFile:dir.resolve(String.format(Locale.ROOT,"events-%04d.jsonl",index));
                segments.add("{\"file\":\""+file.getFileName()+"\",\"bytes\":"+bytes+
                        ",\"lines\":"+lines+"}");
                totalBytes+=bytes;
                Files.writeString(dir.resolve("EVENT_SEGMENTS.json"),"{\"schema\":1,\"segmentLimitBytes\":"+
                        MAX_EVENT_SEGMENT+",\"totalBytes\":"+totalBytes+
                        ",\"totalLines\":"+eventCount.get()+",\"segments\":["+
                        String.join(",",segments)+"]}\n",StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("WRITER_COMPLETE.txt"),
                        "complete="+(dropped.sum()==0 && "none".equals(writeError))+"\n"+
                        "dropped="+dropped.sum()+"\nwriteError="+writeError+"\n"+
                        "linesEnqueued="+eventCount.get()+"\nsegments="+segments.size()+"\n"+
                        "totalBytes="+totalBytes+"\n"+
                        "movementSamplesSkipped="+movementSampledOut.sum()+"\n");
                writerComplete=true;
            }catch(Exception e){writeError=e.toString();queueFailure=true;
                System.err.println("[CF SOAK LOG FAILURE] "+e);
            }finally{if(writer!=null)try{writer.close();}catch(IOException ignored){}}
        }
        void awaitWriter() {
            if(writerThread!=null && Thread.currentThread()!=writerThread) {
                try{writerThread.join(15000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
        }

        void writeSummary(Map<String,Long> abilities) {
            StringBuilder text=new StringBuilder();
            text.append("Castle Fight endurance test summary\nfinished=").append(Instant.now())
                    .append("\nresult=").append(finishedReason)
                    .append("\nroundsStarted=").append(round).append('/').append(rounds)
                    .append("\nroundsCompleted=").append(roundsCompleted)
                    .append("\nsecondsPerRound=").append(seconds)
                    .append("\nrouteStatus=").append(routesStatus)
                    .append("\nmobTypes=").append(mobTypes)
                    .append("\nrecordsEnqueued=").append(eventCount.get())
                    .append("\ndropped=").append(dropped.sum())
                    .append("\nmovementSamplesSkipped=").append(movementSampledOut.sum())
                    .append("\nwriteError=").append(writeError)
                    .append("\npeakHeapBytes=").append(peakHeap)
                    .append("\npeakAliveLastRound=").append(peakAlive)
                    .append("\nconsoleLog=").append(CFConsoleCapture.logFile())
                    .append("\n\nEVENT COUNTS:\n");
            totalEvents.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e->text.append(e.getKey()).append('=').append(e.getValue().sum()).append('\n'));
            text.append("\nMETHOD COSTS (only instrumented methods, cumulative):\n");
            methods.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                    text.append(e.getKey()).append(" calls=").append(e.getValue().calls.sum())
                            .append(" totalMs=").append(e.getValue().nanos.sum()/1e6)
                            .append(" maxMs=").append(e.getValue().maxNs.get()/1e6).append('\n'));
            text.append("\nEXPECTED ABILITY COVERAGE (missing != passed):\n");
            for(String kind:List.of("MELEE_DAMAGE","ARROW_DAMAGE","HIT_CONFIRMED",
                    "DAMAGE_BEGIN","DAMAGE_RESULT","DAMAGE_EVENT_OBSERVED",
                    "POISON_STACK_STOP","UNIT_DEATH_CAUSE","HEALTH_LABEL_REFRESH",
                    "POISON_APPLY","POISON_PULSE","POISON_IMMUNE","BURN_APPLY","BURN_PULSE","BURN_IMMUNE","DODGE_ROLL",
                    "LIFESTEAL_ROLL","WAVE_ROLL","SPLASH_ROLL","SUMMON_TRIGGER",
                    "MUTATION_TRIGGER","TOXIC_ALTAR_TICK","RESURRECTION_BLESS","REVIVE_TRIGGER")) {
                long count=totalEvents.getOrDefault(kind,new LongAdder()).sum();
                text.append(count>0?"SEEN ":"MISSING ").append(kind).append('=').append(count).append('\n');
            }
            text.append("\nABILITY COUNTERS (since server startup):\n");
            abilities.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e->text.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
            text.append("\nUpload events.jsonl, rounds.csv, environment.txt, full console log. ")
                    .append("Do NOT confuse a 'completed' run with zero logging drops: verify WRITER_COMPLETE.txt.\n");
            try{Files.writeString(dir.resolve("summary.txt"),text);}catch(IOException e){
                System.err.println("[CF SOAK] Summary write failed: "+e);}
        }

        private static final class MethodPerf {
            final LongAdder calls=new LongAdder(),nanos=new LongAdder();
            final AtomicLong maxNs=new AtomicLong();
            void observe(long elapsed){
                calls.increment();nanos.add(elapsed);
                maxNs.accumulateAndGet(elapsed,Math::max);
            }
        }
        private static void field(StringBuilder b,String name,Object value) {
            if(b.length()>1)b.append(',');
            str(b,name);b.append(':');
            if(value==null)b.append("null");
            else if(value instanceof Boolean bool)b.append(bool);
            else if(value instanceof Number num && Double.isFinite(num.doubleValue()))b.append(value);
            else str(b,String.valueOf(value));
        }
        private static void str(StringBuilder b,String s) {
            b.append('"');
            for(int i=0;i<s.length();i++) {
                char c=s.charAt(i);
                switch(c) {
                    case '\\'->b.append("\\\\");
                    case '"'->b.append("\\\"");
                    case '\n'->b.append("\\n");
                    case '\r'->b.append("\\r");
                    case '\t'->b.append("\\t");
                    default->{if(c<32)b.append(' ');else b.append(c);}
                }
            }
            b.append('"');
        }
    }
}
