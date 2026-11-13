package dev.cf;

/** Lightweight swing-cadence regression; no world or combat loop required. */
public final class CFMeleeContactStepSelfTest {
    public static void main(String[] args) {
        int checks = 0;
        CFMeleeContactStep step = new CFMeleeContactStep();
        if (step.takeStep()) throw new AssertionError("Idle melee must not move"); checks++;
        step.swing();
        if (!step.takeStep()) throw new AssertionError("First movement tick missing"); checks++;
        if (!step.takeStep()) throw new AssertionError("Second movement tick missing"); checks++;
        if (step.takeStep()) throw new AssertionError("Unexpected third movement tick"); checks++;
        if (Math.abs(2 * CFMeleeContactStep.STRIDE_PER_TICK - 0.30) > 0.000001)
            throw new AssertionError("Contact step must total 0.30 blocks at most"); checks++;
        step.swing();
        if (!step.takeStep()) throw new AssertionError("New swing must rearm movement"); checks++;
        step.clear();
        if (step.takeStep()) throw new AssertionError("Target loss must cancel movement"); checks++;
        System.out.println("[КФ БЛИЖНИЙ БОЙ] ПРОЙДЕНО " + checks
                + " проверок: два коротких шага на удар, сброс при смене цели, без поиска промахов.");
    }
}
