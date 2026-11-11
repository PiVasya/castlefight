package dev.cf;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Tests the exact production policy and reverse-link implementation; no stubs of policy code. */
public final class CF268TargetPolicySelfTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static final class Mob {
        final int id, team;
        final Env world;
        final CFTargetBrain<Mob> brain;
        boolean alive = true, visible = true;
        double x;
        int arena;
        Mob(Env env, int id, int team, boolean ranged, double x) {
            this.world = env; this.id = id; this.team = team; this.x = x;
            brain = new CFTargetBrain<>(this, env, ranged, 20, id);
            env.mobs.add(this);
        }
        void die() { alive = false; brain.links.retire(); }
    }
    private static final class Env implements CFTargetBrain.World<Mob> {
        final List<Mob> mobs = new ArrayList<>();
        int calls, changes;
        boolean raceOnNextSearch;
        @Override public boolean alive(Mob m) { return m.alive && !m.brain.links.closed(); }
        @Override public boolean enemy(Mob a, Mob b) {
            return b != null && b != a && alive(b) && a.team != b.team && a.arena == b.arena;
        }
        @Override public boolean inRange(Mob a, Mob b) { return Math.abs(a.x-b.x) <= (a.brain.ranged() ? 16 : 12); }
        @Override public CFTargetLinks<Mob> links(Mob m) { return m.brain.links; }
        @Override public Mob nearest(Mob a) {
            calls++;
            Mob best = null; double distance = Double.MAX_VALUE;
            for (Mob m : mobs) {
                double d = Math.abs(a.x-m.x);
                if (enemy(a,m) && inRange(a,m) && (!a.brain.ranged() || m.visible)
                        && (d < distance || (d == distance && best != null && m.id < best.id))) {
                    best=m;distance=d;
                }
            }
            if (raceOnNextSearch && best != null) { raceOnNextSearch=false;best.die(); }
            return best;
        }
        @Override public void changed(Mob old, Mob next, CFTargetBrain.Reason reason, long tick) { changes++; }
        @Override public void scanned(CFTargetBrain.Reason reason) { }
    }
    public static void main(String[] args) throws Exception {
        Env e=new Env();Mob a=new Mob(e,0,0,false,0);
        for(int t=0;t<60;t++) { a.brain.resolve(t);a.brain.resolve(t);a.brain.resolve(t); }
        check(e.calls==60,"march scans each tick, duplicate callers do not repeat searches");
        Mob x=new Mob(e,1,1,false,5),y=new Mob(e,2,1,false,8);
        check(a.brain.resolve(60)==x,"initial nearest enemy");
        y.x=1;
        for(int t=61;t<80;t++)check(a.brain.resolve(t)==x,"melee holds until scheduled review");
        check(a.brain.resolve(80)==y,"melee really reselects nearest LIVE target on review");
        check(x.brain.links.attackers()==0 && y.brain.links.attackers()==1,"reverse edges move with target");
        check(a.brain.resolve(80)==y,"duplicate review is cached");
        y.die();
        check(a.brain.peek()==null,"death invalidates attacker without scanning all mobs");
        check(a.brain.resolve(80)==x,"same-tick death bypasses the cached result");
        check(a.brain.scans()==63,"exact count after 60 idle ticks, acquire, one review and death");
        x.x=30;
        check(a.brain.resolve(81)==x,"melee displacement has NO arbitrary distance exception");
        check(a.brain.resolve(100)==null,"normal full review releases target outside acquisition range");
        x.x=3;
        check(a.brain.resolve(101)==x,"march acquisition resumes each tick");
        x.arena=1;
        check(a.brain.resolve(102)==null,"cross-world entity is invalid immediately");

        Env r=new Env();Mob archer=new Mob(r,0,0,true,0);
        Mob near=new Mob(r,1,1,false,5),alt=new Mob(r,2,1,false,9);
        check(archer.brain.resolve(0)==near,"ranged initial target");
        alt.x=1;near.visible=false;
        for(int t=1;t<=200;t++)check(archer.brain.resolve(t)==near,"ranged ignores nearer enemies and occlusion");
        check(r.calls==1,"ranged performs no periodic full searches with valid target");
        near.x=16;
        check(archer.brain.resolve(201)==near,"exact range boundary is inclusive");
        near.x=16.000001;
        check(archer.brain.resolve(202)==alt,"out of radius retargets immediately");
        near.x=4;near.visible=true;alt.die();
        check(archer.brain.resolve(202)==near,"same-tick ranged death replaces target");
        near.die();
        check(archer.brain.resolve(202)==null,"all enemies dead releases ranged combat");
        Mob resurrected=new Mob(r,3,1,false,4);
        check(archer.brain.resolve(203)==resurrected,"resurrection is a new entity, not a stale edge");
        check(near.brain.links.attackers()==0,"old corpse has no inbound edges");

        Env mass=new Env();Mob boss=new Mob(mass,10001,1,false,1);
        List<Mob> army=new ArrayList<>();
        for(int i=0;i<1000;i++){Mob m=new Mob(mass,i,0,false,0);army.add(m);m.brain.resolve(0);}
        check(boss.brain.links.attackers()==1000,"all 1000 reverse links registered");
        int before=mass.calls;
        boss.die();
        check(mass.calls==before,"death does not recursively search on behalf of attackers");
        for(Mob m:army)check(m.brain.peek()==null,"death clears every inbound edge");
        check(boss.brain.links.consistent(),"dead target graph clean");
        Mob replacement=new Mob(mass,10002,1,false,2);
        for(Mob m:army)check(m.brain.resolve(0)==replacement,"same-tick mass re-acquisition");
        for(Mob m:army)m.die();
        check(replacement.brain.links.attackers()==0,"round cleanup removes outgoing edges");

        Env race=new Env();Mob racer=new Mob(race,0,0,false,0);
        new Mob(race,1,1,false,1);Mob survivor=new Mob(race,2,1,false,2);
        race.raceOnNextSearch=true;
        check(racer.brain.resolve(0)==survivor,"candidate dying during acquisition cannot become target");

        int[] phases=new int[20];
        for(int id=0;id<1000;id++) phases[(int)CFTargetBrain.nextPhaseTick(0,id%20,20)-1]++;
        for(int n:phases)check(n==50,"periodic searches are staggered 50 per tick, not a 1000-unit burst");
        Env counts=new Env();Mob melee=new Mob(counts,0,0,false,0);new Mob(counts,1,1,false,1);
        for(int t=0;t<1000;t++){melee.brain.resolve(t);melee.brain.resolve(t);melee.brain.resolve(t);}
        check(counts.calls==50,"1000 steady combat ticks = 50 full searches, not 1000/3000");
        System.out.println("[CF268 POLICY] steady melee: 50 full searches / 1000 ticks; ranged: 1 / 201 ticks");

        // Random graph operations include cycles; structural integrity is checked every time.
        List<CFTargetLinks<Integer>> nodes=new ArrayList<>();
        for(int i=0;i<80;i++)nodes.add(new CFTargetLinks<>(i));
        Random random=new Random(268);
        for(int i=0;i<20000;i++){
            var n=nodes.get(random.nextInt(nodes.size()));
            switch(random.nextInt(20)){
                case 0 -> n.disconnect();
                case 1 -> n.retire();
                default -> n.bind(nodes.get(random.nextInt(nodes.size())));
            }
            for(var node:nodes)check(node.consistent(),"reverse graph invariant");
        }
        // Concurrent retarget/retire: no resurrected edge, deadlock or double unlink.
        List<CFTargetLinks<Integer>> concurrent=new ArrayList<>();
        for(int i=0;i<100;i++)concurrent.add(new CFTargetLinks<>(i));
        CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> failed=new AtomicReference<>();
        List<Thread> workers=new ArrayList<>();
        for(int w=0;w<4;w++){
            final int seed=w;
            Thread worker=new Thread(()->{try{start.await();Random rr=new Random(seed);
                for(int i=0;i<20000;i++){
                    var n=concurrent.get(rr.nextInt(100));
                    if(i>10000 && i%127==0)n.retire();
                    else n.bind(concurrent.get(rr.nextInt(100)));
                }
            }catch(Throwable t){failed.set(t);}});
            workers.add(worker);worker.start();
        }
        start.countDown();
        for(Thread worker:workers){worker.join(10000);check(!worker.isAlive(),"no link deadlock");}
        check(failed.get()==null,"concurrent link mutation: "+failed.get());
        for(var node:concurrent)check(node.consistent(),"concurrent reverse graph integrity");
        System.out.println("[CF268 POLICY] PASS "+checks+" assertions; policy, death, removal, radius, graph and concurrency");
    }
}
