package dev.cf;

import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable, validated content snapshot. Loading is startup-only, never per mob/tick. */
final class CFContentRegistry {
    record Mob(String id, String name, EntityType entity, String behavior, int health10,
               double speed, int damage10, int critDamage10, int critChancePercent,
               double range, double detection, long cooldownMs, Material weapon,
               CFDamageTable.Profile combat, int projectileStyleId) {
        /** Existing pure-Java/Minestom fixtures do not need cosmetic data. */
        Mob(String id, String name, EntityType entity, String behavior, int health10,
            double speed, int damage10, int critDamage10, int critChancePercent,
            double range, double detection, long cooldownMs, Material weapon,
            CFDamageTable.Profile combat) {
            this(id,name,entity,behavior,health10,speed,damage10,critDamage10,
                    critChancePercent,range,detection,cooldownMs,weapon,combat,0);
        }
        /** Older test fixtures intentionally remain untyped, not silently rebalanced. */
        Mob(String id,String name,EntityType entity,String behavior,int health10,double speed,
            int damage10,int critDamage10,int critChancePercent,double range,double detection,
            long cooldownMs,Material weapon) {
            this(id,name,entity,behavior,health10,speed,damage10,critDamage10,critChancePercent,
                    range,detection,cooldownMs,weapon,
                    new CFDamageTable.Profile(CFDamageTable.Type.UNTYPED,
                            CFDamageTable.Armor.NONE,damage10,critDamage10));
        }
        /** Test-only compatibility constructor; content files use integer tenths. */
        Mob(String id,String name,EntityType entity,String behavior,float health,double speed,
            float damage,double range,double detection,long cooldownMs,Material weapon) {
            this(id,name,entity,behavior,Math.round(health*10),speed,Math.round(damage*10),
                    Math.round(damage*10),0,range,detection,cooldownMs,weapon);
        }
    }
    record Spawn(String mob, int count) { }
    record Tower(String id, String name, Material item, Block body, Block head,
                 int intervalSeconds, List<Spawn> recipe, String ability, String upgradeTo, boolean upgradeOnly) { }
    record Limits(int towers, int units, int spawnsPerTick) { }
    private final Map<String, Mob> mobs;
    private final Map<String, Tower> towers;
    private final Limits limits;

    CFContentRegistry(Path root) throws IOException {
        var mm = new LinkedHashMap<String, Mob>();
        for (Path f : definitions(root.resolve("mobs"))) {
            Properties p = read(f);
            String id = id(f);
            String behavior = req(p, "behavior");
            if (!CFCombatBehaviors.supports(behavior)) throw bad(f, "Unknown behavior " + behavior);
            EntityType entity = EntityType.fromKey(req(p, "entity"));
            if (entity == null || !entity.shouldSendAttributes() || entity == EntityType.PLAYER)
                throw bad(f, "entity must be a living mob, not a projectile/display/player");
            Material weapon = Material.fromKey(p.getProperty("weapon", "minecraft:air"));
            if (weapon == null) throw bad(f, "Unknown weapon");
            double range = number(p, "attack.range", 0.5, 24);
            double detection = number(p, "detect.range", range, 32);
            int health10 = tenthStat(p, "health10", "health", 10, 100000);
            int damage10 = tenthStat(p, "attack.damage10", "attack.damage", 1, 100000);
            int critChance = p.containsKey("ability.crit.chance-percent")
                    ? integer(p,"ability.crit.chance-percent",0,100) : 0;
            int critDamage = p.containsKey("ability.crit.damage10")
                    ? integer(p,"ability.crit.damage10",1,100000) : damage10;
            if(critChance>0 && !behavior.equals("melee"))
                throw bad(f,"Critical bite is only defined for melee");
            CFDamageTable.Profile combat;
            try {
                combat = new CFDamageTable.Profile(
                        CFDamageTable.Type.parse(p.getProperty("damage.type", "generic")),
                        CFDamageTable.Armor.parse(p.getProperty("armor.type", "none")),damage10,critDamage);
            } catch(IllegalArgumentException ex) { throw bad(f, ex.getMessage()); }
            // Ranged attacks transmit exactly one byte of cosmetic type, no styles.
            int projectileStyleId = 0;
            if (behavior.equals("ranged_hold")) {
                projectileStyleId = integer(p, "visual.projectile-id", 0, 255);
            } else if (p.containsKey("visual.projectile-id")) {
                throw bad(f, "visual.projectile-id is only valid for ranged_hold units");
            }
            Mob m = new Mob(id, p.getProperty("name", id), entity, behavior,
                    health10, number(p, "speed", .005, .5), damage10, critDamage, critChance,
                    range, detection, (long) number(p, "attack.cooldown-ms", 100, 600000),
                    weapon,combat,projectileStyleId);
            mm.put(id, m);
        }
        if (mm.isEmpty()) throw new IOException("No mob definitions under " + root);
        var tt = new LinkedHashMap<String, Tower>();
        for (Path f : definitions(root.resolve("towers"))) {
            Properties p = read(f);
            Material item = Material.fromKey(req(p, "item"));
            Block body = Block.fromKey(req(p, "body"));
            Block head = Block.fromKey(req(p, "head"));
            if (item == null || body == null || !body.solid() || body.liquid() ||
                    !body.collisionShape().isFaceFull(net.minestom.server.instance.block.BlockFace.TOP) ||
                    head == null || head.air() || head.liquid()) throw bad(f, "Invalid item/body/head");
            List<Spawn> recipe = new ArrayList<>();
            int total = 0;
            for (int i = 0; p.containsKey("spawn." + i + ".mob"); i++) {
                String mob = req(p, "spawn." + i + ".mob");
                int count = integer(p, "spawn." + i + ".count", 1, 32);
                if (!mm.containsKey(mob)) throw bad(f, "Unknown mob in recipe: " + mob);
                total += count;
                recipe.add(new Spawn(mob, count));
            }
            String ability=p.getProperty("ability", "").trim();
            if (!ability.isEmpty() && !Set.of("mutation","necromancy","toxic","resurrection",
                    "snow_cannon","snow_shield","snow_obelisk").contains(ability))
                throw bad(f, "Unknown support ability: "+ability);
            if (total > 32 || (ability.isEmpty() && recipe.isEmpty()) || (!ability.isEmpty() && !recipe.isEmpty()))
                throw bad(f, "Spawn towers need 1..32 mobs; support buildings need no spawn recipe");
            for (String key : p.stringPropertyNames()) if (key.startsWith("spawn.")) {
                String[] parts = key.split("\\.");
                if (parts.length != 3 || !parts[1].matches("[0-9]+") ||
                        Integer.parseInt(parts[1]) >= recipe.size() ||
                        !(parts[2].equals("mob") || parts[2].equals("count")))
                    throw bad(f, "Non-contiguous/unknown recipe key " + key);
            }
            String id = id(f);
            tt.put(id, new Tower(id, p.getProperty("name", id), item, body, head,
                    integer(p, "interval-seconds", 1, 3600), List.copyOf(recipe),ability,
                    p.getProperty("upgrade-to", "").trim(),
                    Boolean.parseBoolean(p.getProperty("upgrade-only","false"))));
        }
        if (tt.isEmpty()) throw new IOException("No tower definitions under " + root);
        for (Tower t:tt.values()) if (!t.upgradeTo().isEmpty()) {
            Tower upgrade=tt.get(t.upgradeTo());
            if (upgrade==null || t.ability().length()>0 || upgrade.ability().length()>0)
                throw new IOException("Invalid tower upgrade " + t.id() + " -> " + t.upgradeTo());
            if (t.id().equals(upgrade.id())) throw new IOException("Tower upgrade cycle: "+t.id());
            var seen=new HashSet<String>(); Tower cur=t;
            while(cur!=null && !cur.upgradeTo().isEmpty()) {
                if(!seen.add(cur.id())) throw new IOException("Tower upgrade cycle from "+t.id());
                cur=tt.get(cur.upgradeTo());
            }
        }
        Properties p = read(root.resolve("settings.properties"));
        limits = new Limits(integer(p, "max-towers", 1, 4096),
                integer(p, "max-units", 1, 20000), integer(p, "spawns-per-tick", 1, 64));
        mobs = Collections.unmodifiableMap(mm);
        towers = Collections.unmodifiableMap(tt);
    }
    Mob mob(String id) { return mobs.get(id); }
    Tower tower(String id) { return towers.get(id); }
    Collection<Tower> towers() { return towers.values(); }
    Collection<Mob> mobs() { return mobs.values(); }
    Limits limits() { return limits; }

    private static List<Path> definitions(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".properties")).sorted().toList();
        }
    }
    private static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(r); }
        return p;
    }
    private static String id(Path path) throws IOException {
        String n = path.getFileName().toString().replaceFirst("\\.properties$", "");
        if (!n.matches("[a-z][a-z0-9_-]{0,63}")) throw bad(path, "Bad content id: " + n);
        return n;
    }
    private static String req(Properties p, String name) throws IOException {
        String v = p.getProperty(name);
        if (v == null || v.isBlank()) throw new IOException("Missing content property " + name);
        return v.trim();
    }
    private static int tenthStat(Properties p,String exactKey,String oldKey,int min,int max) throws IOException {
        if (p.containsKey(exactKey)) return integer(p,exactKey,min,max);
        // Backward-compatible config migration. Performed ONCE at startup.
        try {
            java.math.BigDecimal value=new java.math.BigDecimal(req(p,oldKey));
            int result=value.movePointRight(1).intValueExact();
            if(result<min || result>max) throw new ArithmeticException("range");
            return result;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IOException("Non-representable decimal combat stat "+oldKey+"; use "+exactKey,ex);
        }
    }
    private static double number(Properties p, String key, double min, double max) throws IOException {
        try {
            double n = Double.parseDouble(req(p, key));
            if (!Double.isFinite(n) || n < min || n > max) throw new NumberFormatException("range " + min + ".." + max);
            return n;
        } catch (NumberFormatException e) { throw new IOException("Invalid " + key + ": " + p.getProperty(key), e); }
    }
    private static int integer(Properties p, String key, int min, int max) throws IOException {
        double n = number(p, key, min, max);
        if (n != Math.rint(n)) throw new IOException(key + " must be an integer");
        return (int)n;
    }
    private static IOException bad(Path p, String message) { return new IOException(p + ": " + message); }
}
