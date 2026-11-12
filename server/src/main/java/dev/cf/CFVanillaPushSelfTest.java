package dev.cf;

/** No Minestom dependency: compile with javac CFVanillaPushMath.java CFVanillaPushSelfTest.java. */
public final class CFVanillaPushSelfTest {
    private static int assertions;
    private static void check(boolean ok, String msg) {
        assertions++;
        if (!ok) throw new AssertionError(msg);
    }
    private static void near(double got, double expected, String msg) {
        check(Math.abs(got-expected) < 1.e-12, msg + ": " + got + " vs " + expected);
    }
    public static void main(String[] args) {
        var zero = CFVanillaPushMath.fromCenterDelta(0,0);
        check(zero.isZero(), "exact same position keeps the vanilla zero-vector behaviour");
        check(CFVanillaPushMath.fromCenterDelta(.009,0).isZero(), "tiny delta ignored");
        var x = CFVanillaPushMath.fromCenterDelta(.5,0);
        near(x.x(), .5 / Math.sqrt(.5) * (double).05F, "vanilla X impulse");
        near(x.z(), 0, "no vertical or sideways impulse");
        var opposite = CFVanillaPushMath.fromCenterDelta(-.5,0);
        near(x.x()+opposite.x(), 0, "symmetric pushes");
        var diagonal = CFVanillaPushMath.fromCenterDelta(.3,-.5);
        check(diagonal.x()>0 && diagonal.z()<0, "2D direction preserved");
        var distant = CFVanillaPushMath.fromCenterDelta(4,0);
        near(distant.x(), (double).05F, "normalization cap on large delta");
        // Two stationary mobs at distinct overlapping positions separate.
        double left = 0, right = .45;
        for(int tick=0;tick<25;tick++) {
            if(right-left>=.6) break;
            var impulse = CFVanillaPushMath.fromCenterDelta(right-left,0);
            left -= impulse.x(); right += impulse.x();
        }
        check(right-left>=.6, "standing mobs eventually separate with velocity impulses");
        System.out.println("[CF VANILLA PUSH] PASS " + assertions + " assertions");
    }
}
