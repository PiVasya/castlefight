package dev.cf;

/** Smooth purely visual heading during navigation; collision and physics are unchanged. */
final class CFViewSmoothing {
    static float yaw(float current, float wanted) {
        if (!Float.isFinite(current) || !Float.isFinite(wanted)) return current;
        // Shortest arc handles 179 -> -179 without turning almost 360 degrees.
        float delta = (wanted - current) % 360f;
        if (delta > 180f) delta -= 360f;
        else if (delta < -180f) delta += 360f;
        if (Math.abs(delta) < .8f) return current;
        float filtered = delta * .25f;
        float step = Math.max(-8f, Math.min(8f, filtered));
        float result = current + step;
        if (result > 180f) result -= 360f;
        else if (result < -180f) result += 360f;
        return result;
    }
    private CFViewSmoothing() { }
}
