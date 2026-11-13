package dev.cf.client.render;

/**
 * Exclusively client-side definitions for 1-byte projectile IDs.
 * The server knows NO materials, colors, shapes, sizes or animations.
 * IDs are stable and may be reused by different mob definitions.
 */
public final class CFProjectilePalette {
    public enum Shape { ARROW, BOLT, ORB, SHARD, SNOWBALL }
    public record Appearance(Shape shape, int primary, int accent, float length, float radius) { }

    private static final Appearance[] TYPES = {
        new Appearance(Shape.ARROW,   0xB88958,0xDAE3EC,.64f,.042f), // 0 plain arrow
        new Appearance(Shape.ARROW,   0xD0D0CF,0xEEE8CE,.76f,.046f), // 1 bone arrow (skeleton)
        new Appearance(Shape.ARROW,   0xC84A1A,0xFFDB38,.77f,.068f), // 2 burning arrow
        new Appearance(Shape.BOLT,    0x8B5CF6,0xF3C9FF,.88f,.090f), // 3 arcane magic
        new Appearance(Shape.BOLT,    0x493085,0xE65AF7,1.10f,.075f), // 4 void lance
        new Appearance(Shape.ORB,     0x73D8C2,0xE4FFF6,.29f,.160f), // 5 spirit sphere
        new Appearance(Shape.ORB,     0x8E42B3,0xFC8CED,.37f,.190f), // 6 hex sphere
        new Appearance(Shape.SHARD,   0x7FCDEA,0xEAFCFF,.72f,.105f), // 7 ice shard
        new Appearance(Shape.SNOWBALL,0xE2F3FC,0xFFFFFF,.24f,.175f), // 8 snowball
        new Appearance(Shape.SHARD,   0x5AB7FA,0xB7ECFF,1.00f,.16f)  // 9 ice comet
    };

    public static Appearance forId(int id) {
        return id >= 0 && id < TYPES.length ? TYPES[id] : TYPES[0];
    }
    private CFProjectilePalette() { }
}
