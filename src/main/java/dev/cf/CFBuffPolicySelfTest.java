package dev.cf;

/** Pure Java tests run before Maven, including on JDK 21. */
public final class CFBuffPolicySelfTest {
    private static int checks;
    private static void check(boolean ok,String reason) {
        checks++;
        if(!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        check(CFBuffPolicy.POISON.stackable(),"poison stacking");
        check(CFBuffPolicy.BURN.stackable(),"burn stacking");
        check(CFBuffPolicy.SLOW.stackable(),"slow stacking");
        check(!CFBuffPolicy.RESURRECTION.stackable(),"resurrection unique");
        check(CFBuffPolicy.RESURRECTION.mode()==CFBuffPolicy.Mode.SINGLE_REFRESH,"refresh policy");
        check(CFBuffPolicy.mergeUniqueExpiry(100,170)==170,"refresh extends");
        check(CFBuffPolicy.mergeUniqueExpiry(170,100)==170,"refresh cannot shorten");
        System.out.println("[CF BUFF POLICY] PASS "+checks+" assertions");
    }
}
