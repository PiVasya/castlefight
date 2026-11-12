package dev.cf;
/** Offline self-test for the endurance logger. No saved-world access. */
public final class CFSoakSelfTest {
    public static void main(String[] args) throws Exception {
        CFSoakTest.selfTestJournal();
    }
}
