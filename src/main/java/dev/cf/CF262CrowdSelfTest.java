package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;

/** Pure, offline body/swept-path checks: no server, map or socket. */
public final class CF262CrowdSelfTest {
    static int checks;
    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        BoundingBox box = new BoundingBox(.6, 1.9, .6);
        Pos at = new Pos(1.0, 8, 0);
        check(!CFCrowdPhysics.intersects(box,new Pos(0,8,0),box,at,.055),
                "separate creatures do not intersect");
        check(CFCrowdPhysics.intersects(box,new Pos(.6,8,0),box,at,.055),
                "close creatures intersect");
        check(CFCrowdPhysics.sweeps(box,new Pos(0,8,0),new Pos(2,8,0),box,at),
                "fast step cannot pass through a creature");
        check(!CFCrowdPhysics.sweeps(box,new Pos(0,8,0),new Pos(-1,8,0),box,at),
                "moving away from distant creature allowed");
        check(!CFCrowdPhysics.sweeps(box,new Pos(1,8,0),new Pos(.85,8,0),box,at),
                "creatures already overlapping may separate");
        check(CFCrowdPhysics.sweeps(box,new Pos(.85,8,0),new Pos(1,8,0),box,at),
                "creatures already overlapping may not move deeper");
        check(!CFCrowdPhysics.sweeps(box,new Pos(0,12,0),new Pos(2,12,0),box,at),
                "different heights do not collide");
        System.out.println("[CF262 CROWD SELFTEST] PASS " + checks + " checks");
    }
}
