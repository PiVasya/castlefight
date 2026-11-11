package dev.cf;
/** Proves event rotation before publishing an overnight stress tester. */
public final class CF264LogSelfTest {
    public static void main(String[] args)throws Exception {
        System.setProperty("cf.soak.eventSegmentBytes", "4096");
        CFSoakTest.selfTestSegments();
    }
}
