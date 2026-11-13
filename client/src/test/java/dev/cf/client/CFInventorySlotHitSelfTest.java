package dev.cf.client;

/** A regression test for GUI scale, recipe-book shifts and slot boundaries. */
public final class CFInventorySlotHitSelfTest {
    private static int checks;
    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    private static void testAt(int left, int top) {
        int sx=44, sy=62;
        check(CFInventorySlotHit.contains(left+sx,top+sy,left,top,sx,sy),"slot center");
        check(CFInventorySlotHit.contains(left+sx+15,top+sy+15,left,top,sx,sy),"slot far corner");
        check(CFInventorySlotHit.contains(left+sx-1,top+sy-1,left,top,sx,sy),"padding");
        check(!CFInventorySlotHit.contains(left+sx+17,top+sy,left,top,sx,sy),"right edge");
        check(!CFInventorySlotHit.contains(left+sx,top+sy+17,left,top,sx,sy),"bottom edge");
        check(!CFInventorySlotHit.contains(left+sx-2,top+sy,left,top,sx,sy),"outside left");
        check(!CFInventorySlotHit.contains(left+sx,top+sy-2,left,top,sx,sy),"outside top");
    }
    public static void main(String[] args) {
        testAt(0,0);
        testAt(110,38);
        testAt(3,127);
        testAt(-13,-7); // origin may shift when recipe-book is opened
        System.out.println("[КФ ИНВЕНТАРЬ] ПРОЙДЕНО "+checks+" проверок: слот, границы, смещение окна.");
    }
}
