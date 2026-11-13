package dev.cf;

/** Regression for 15-degree left/right oscillation on stairs and wraparound. */
public final class CFViewSmoothingSelfTest {
    public static void main(String[] args) {
        int checks=0;
        float a=CFViewSmoothing.yaw(0,15);
        if (a <= 0 || a > 8 || a >= 15) throw new AssertionError("Too much instant rotation");
        checks++;
        float b=CFViewSmoothing.yaw(a,-15);
        if (Math.abs(b-a)>8) throw new AssertionError("Alternating turn jerk");
        checks++;
        float wrap=CFViewSmoothing.yaw(179,-179);
        if (wrap < 179 || wrap > 180) throw new AssertionError("Yaw wrap should take shortest arc");
        checks++;
        float turn=0;
        for(int i=0;i<30;i++) {
            float next=CFViewSmoothing.yaw(turn,90);
            if(Math.abs(next-turn)>8.0001) throw new AssertionError("Max step exceeded");
            if(next < turn) throw new AssertionError("Turn direction changed");
            turn=next;checks++;
        }
        if(turn < 85 || turn > 90) throw new AssertionError("Yaw did not converge");
        checks++;
        System.out.println("[CF AI] PASS " + checks + " checks: smooth yaw, stair oscillation and wraparound");
    }
}
