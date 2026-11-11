package dev.cf;

import java.util.Objects;

/** CF table transcribed from the user image, not Minecraft's native armor formula.
 * HP/damage are int tenths. ONLY armor variants are computed once per immutable
 * content definition. Runtime status effects (ice shield, future shields, buffs)
 * are applied AFTER the armor lookup and are never baked into this matrix.
 * Truncate positive remainders, never round up.
 */
final class CFDamageTable {
    enum Type {
        SLASHING("slashing", "\u0420\u0435\u0436\u0443\u0449\u0438\u0439"),
        PIERCING("piercing", "\u041a\u043e\u043b\u044e\u0449\u0438\u0439"),
        MAGIC("magic", "\u041c\u0430\u0433\u0438\u0447\u0435\u0441\u043a\u0438\u0439"),
        SIEGE("siege", "\u041e\u0441\u0430\u0434\u043d\u044b\u0439"),
        CHAOS("chaos", "\u0425\u0430\u043e\u0441"),
        UNTYPED("generic", "\u0411\u0435\u0437 \u0442\u0438\u043f\u0430");
        final String id, label;
        Type(String id, String label) { this.id=id; this.label=label; }
        static Type parse(String value) {
            return switch (value.trim()) {
                case "slashing" -> SLASHING;
                case "piercing" -> PIERCING;
                case "magic" -> MAGIC;
                case "siege" -> SIEGE;
                case "chaos" -> CHAOS;
                case "generic" -> UNTYPED;
                default -> throw new IllegalArgumentException("Unknown CF damage.type: " + value);
            };
        }
    }
    enum Armor {
        NONE("none", "\u0411\u0435\u0437 \u0437\u0430\u0449\u0438\u0442\u044b"),
        LIGHT("light", "\u041b\u0451\u0433\u043a\u0430\u044f"),
        MEDIUM("medium", "\u0421\u0440\u0435\u0434\u043d\u044f\u044f"),
        HEAVY("heavy", "\u0422\u044f\u0436\u0451\u043b\u0430\u044f"),
        FORTIFIED("fortified", "\u0423\u043a\u0440\u0435\u043f\u043b\u0451\u043d\u043d\u0430\u044f");
        final String id, label;
        Armor(String id, String label) { this.id=id; this.label=label; }
        static Armor parse(String value) {
            return switch (value.trim()) {
                case "none" -> NONE;
                case "light" -> LIGHT;
                case "medium" -> MEDIUM;
                case "heavy" -> HEAVY;
                case "fortified" -> FORTIFIED;
                default -> throw new IllegalArgumentException("Unknown CF armor.type: " + value);
            };
        }
    }
    private static final int ARMOR_COUNT=5;
    private static final int[][] PERCENT={
        // none, light, medium, heavy, fortified -- exact user-image order
        {105, 55,190, 90, 50},
        {105,190, 90, 55, 40},
        {105, 90, 55,190, 40},
        {105, 55, 55, 55,190},
        {105,100,100,100,100},
        // Compatibility only: not a sixth class on the user's balance table.
        {100,100,100,100,100}
    };
    private CFDamageTable() { }
    static int percent(Type type, Armor armor) {
        return PERCENT[Objects.requireNonNull(type).ordinal()][Objects.requireNonNull(armor).ordinal()];
    }
    static int scale10(int raw10, int percent) {
        if(raw10<0 || percent<0)throw new IllegalArgumentException("Negative damage/multiplier");
        long value=(long)raw10*percent/100L;
        if(value>Integer.MAX_VALUE)throw new IllegalArgumentException("Damage10 overflow");
        return (int)value;
    }
    static final class Hit {
        final Type type;
        final int raw10;
        private final int[] normal=new int[ARMOR_COUNT];
        Hit(Type type,int raw10) {
            this.type=Objects.requireNonNull(type);this.raw10=raw10;
            for(Armor armor:Armor.values()) {
                int i=armor.ordinal();
                normal[i]=scale10(raw10,percent(type,armor));
            }
        }
        int amount10(Armor armor) {
            return normal[Objects.requireNonNull(armor).ordinal()];
        }
    }
    static final class Profile {
        final Type type;
        final Armor armor;
        final Hit normal, critical;
        Profile(Type type,Armor armor,int normal10,int critical10) {
            this.type=Objects.requireNonNull(type);this.armor=Objects.requireNonNull(armor);
            normal=new Hit(type,normal10);
            critical=critical10==normal10?normal:new Hit(type,critical10);
        }
    }
}
