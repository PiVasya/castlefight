package dev.cf;

import java.util.List;

/** Standalone, safe night profile regression test: no map edits, no live server. */
public final class CFNightSelfTest {
    private CFNightSelfTest() { }
    public static void main(String[] args) throws Exception {
        List<String> expected=List.of("MIXED_FROM_CASTLES","CASTLE_RACE","STRESS_100","STRESS_200",
                "STRESS_300","STRESS_500","STRESS_750","STRESS_1000",
                "ELEMENTAL_STACKS","RANGED_CROWD");
        for (int i=0;i<80;i++)
            if(!CFSoakTest.deepProfile(i+1).equals(expected.get(i%expected.size())))
                throw new AssertionError("Wrong profile for round "+(i+1));
        List<String> heavy=List.of("STRESS_300","STRESS_500","STRESS_750","STRESS_1000",
                "RANGED_CROWD_500","ELEMENTAL_STACKS_500","STRESS_1000",
                "RANGED_CROWD_750","ELEMENTAL_STACKS_750","STRESS_1000");
        for(int i=0;i<80;i++)
            if(!CFSoakTest.burnProfile(i+1).equals(heavy.get(i%heavy.size())))
                throw new AssertionError("Wrong burn profile for round "+(i+1));
        try {CFSoakTest.burnProfile(0);throw new AssertionError("Invalid burn round accepted");}
        catch (IllegalArgumentException expectedFailure) { /* correct */ }
        try {CFSoakTest.deepProfile(0);throw new AssertionError("Invalid round accepted");}
        catch (IllegalArgumentException expectedFailure) { /* correct */ }
        System.out.println("CFNightSelfTest OK: eight normal + eight BURN cycles, 10 rounds each");
    }
}
