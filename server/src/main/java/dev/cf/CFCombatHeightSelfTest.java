package dev.cf;

/** Pure regressions: no world load and no full Minecraft runtime needed. */
public final class CFCombatHeightSelfTest {
    public static void main(String[] args) {
        int n = 0;
        for (double dy : new double[]{-3,-2,-1,0,1,2,3}) {
            if (!CFCombatHeight.meleeAllowed(-52, -52+dy))
                throw new AssertionError("Allowed melee Y delta " + dy);
            n++;
        }
        for (double dy : new double[]{-3.01,3.01,-4,4,9}) {
            if (CFCombatHeight.meleeAllowed(0,dy))
                throw new AssertionError("Illegal melee Y delta " + dy);
            n++;
        }
        if (CFCombatHeight.meleeAllowed(Double.NaN,0))
            throw new AssertionError("NaN must never make a target reachable");
        n++;
        System.out.println("[КФ ВЫСОТА БОЯ] ПРОЙДЕНО " + n + " проверок: ближний бой в пределах ±3");
    }
}
