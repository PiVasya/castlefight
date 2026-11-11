package dev.cf;

/** CF health has 0.001 HP resolution. Canonicalise float noise at the health-write boundary. */
final class CFHealthMath {
    private CFHealthMath() { }
    static float canonical(float hp) {
        if (!Float.isFinite(hp)) throw new IllegalArgumentException("Non-finite CF health");
        if (hp <= 0) return 0f;
        return (float)(Math.round((double)hp * 1000.0) / 1000.0);
    }
    static String label10(int hp10) {
        if(hp10<=0)return "0";
        return hp10%10==0?Integer.toString(hp10/10):hp10/10+"."+(hp10%10);
    }
    static String label(float hp) {
        // Small positive values never display as a zero. Nonzero CF health is at least .001.
        long milli = Math.max(hp > 0 ? 1 : 0, Math.round((double)Math.max(0,hp) * 1000));
        long fraction = milli % 1000;
        if (fraction == 0) return Long.toString(milli / 1000);
        String digits = Long.toString(1000 + fraction).substring(1);
        while (digits.endsWith("0")) digits = digits.substring(0,digits.length()-1);
        return milli / 1000 + "." + digits;
    }
}
