package dev.cf;

/** Deterministic geometry regression; no Minecraft server or world required. */
public final class CFTowerSpacingSelfTest {
    static int checks;
    private static void test(boolean actual, boolean expected, String reason) {
        checks++;
        if(actual != expected) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }
    // Independent reference: two 3x3 footprints are separated by at least one
    // entirely free block iff no cells have Chebyshev distance less than two.
    private static boolean reference(int x, int z) {
        for(int ax=-1;ax<=1;ax++)for(int az=-1;az<=1;az++)
            for(int bx=x-1;bx<=x+1;bx++)for(int bz=z-1;bz<=z+1;bz++)
                if(Math.abs(ax-bx)<=1 && Math.abs(az-bz)<=1) return false;
        return true;
    }
    public static void main(String[] args) {
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++) {
            boolean ok=reference(x,z);
            test(CFTowerSpacing.allows(0,0,x,z),ok,"offset="+x+","+z);
            test(CFTowerSpacing.allows(x,z,0,0),ok,"reverse="+x+","+z);
            test(CFTowerSpacing.allows(-200,300,-200+x,300+z),ok,"translated="+x+","+z);
        }
        test(CFTowerSpacing.allows(0,0,4,0),true,"one cardinal gap");
        test(CFTowerSpacing.allows(0,0,3,0),false,"adjacent cardinal footprints");
        test(CFTowerSpacing.allows(0,0,3,3),false,"diagonal corners touching");
        test(CFTowerSpacing.allows(0,0,4,3),true,"diagonal with free block");
        test(CFTowerSpacing.allows(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0),true,"integer overflow safety");
        System.out.println("[CF TOWER SPACING TEST] PASS " + checks + " assertions: 3x3 + one free block including corners.");
    }
}
