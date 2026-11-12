package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** In-memory regression: king throne/return/fixed damage and ranged sight loss. */
public final class CFKingBehaviorSelfTest {
    private static int checks;
    private static final List<CFUnit> spawned = new ArrayList<>();
    private static Field age;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static void tick(CFUnit mob, long t) throws Exception {
        age.setLong(mob, t);
        mob.aiTick(100_000 + t * 50);
    }
    private static CFUnit add(InstanceContainer w, CFContentRegistry.Mob def,
                              String team, Pos p, boolean king) throws Exception {
        CFUnit unit = new CFUnit(team, def, p, king ? p : new Pos(30.5, 8, 4.5), king);
        unit.setInstance(w, p).get(10, TimeUnit.SECONDS);
        unit.markSpawnCompleted();
        spawned.add(unit);
        return unit;
    }
    private static CFContentRegistry.Mob victim(CFDamageTable.Armor armor) {
        return new CFContentRegistry.Mob("king_test_victim", "Враг", EntityType.ZOMBIE,
                "melee", 5000, .1, 10, 10, 0, 2.2, 10, 3000, Material.AIR,
                new CFDamageTable.Profile(CFDamageTable.Type.CHAOS, armor, 10, 10));
    }
    private static CFContentRegistry.Mob archer() {
        return new CFContentRegistry.Mob("king_test_archer", "Лучник", EntityType.SKELETON,
                "ranged_hold", 500, .1, 30, 30, 0, 16, 16, 5000, Material.BOW,
                new CFDamageTable.Profile(CFDamageTable.Type.PIERCING,
                        CFDamageTable.Armor.LIGHT, 30, 30));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Content directory required");
        Pos home = new Pos(8.5, 8, 8.5);
        check(CFKingGuardGoal.reachableFromThrone(home, new Pos(11.5, 8, 8.5), 2.2),
                "enemy near edge is defendable");
        check(!CFKingGuardGoal.reachableFromThrone(home, new Pos(14, 8, 8.5), 2.2),
                "king ignores unreachable enemies beyond ceramic 3x3");
        check(!CFKingGuardGoal.reachableFromThrone(home, new Pos(9, 14, 9), 2.2),
                "vertical separation cannot count as melee reach");
        age = Entity.class.getDeclaredField("ticks");
        age.setAccessible(true);
        MinecraftServer.init();
        try {
            InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(c -> c.modifier().fillHeight(0, 8, Block.STONE));
            world.loadChunk(0, 0).get(10, TimeUnit.SECONDS);
            world.loadChunk(1, 0).get(10, TimeUnit.SECONDS);
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++)
                world.setBlock(x, 7, z, Block.BLUE_GLAZED_TERRACOTTA);
            CFArenaAbilities effects = new CFArenaAbilities(world, new HashSet<>(),
                    Path.of(args[0]), (team, mob, lane, pos) -> false);
            CFUnit king = add(world, CFKingSystem.KING, "blue", home, true);
            check(king.maxHealth10() == 40000 && king.health10() == 40000,
                    "king has 4000 real HP, never 400 HP");
            check(king.attackDamage10() == 100 && king.attackDamage() == 10.0f,
                    "king base hit is 10 real damage, never 1.0");
            for (CFDamageTable.Armor armor : CFDamageTable.Armor.values()) {
                CFUnit target = add(world, victim(armor), "red", home.add(1, 0, 0), false);
                target.getAIGroups().clear();
                effects.grantIceShield(target, 60_000);
                int hp = target.health10();
                check(CFMeleeCombat.attack(king, target), "king hit accepted: " + armor);
                check(target.health10() == hp - 100,
                        "exactly 10 actual HP through armor and shield: " + armor);
                target.remove();
            }
            // There are no enemies. King was displaced by combat to the edge.
            king.teleport(home.add(1, 0, 0)).get(10, TimeUnit.SECONDS);
            tick(king, 0);
            check(king.getAIGroups().iterator().next().getCurrentGoalSelector()
                    instanceof CFKingGuardGoal, "king uses one goal for attack and return");
            CFKingGuardGoal goal = (CFKingGuardGoal) king.getAIGroups().iterator()
                    .next().getCurrentGoalSelector();
            check(goal.returning(), "king enters return mode without enemies");
            check(home.samePoint(goal.requestedDestination()), "return path aims at ceramic centre");

            CFUnit intruder = add(world, victim(CFDamageTable.Armor.LIGHT),
                    "red", home.add(1, 0, 1), false);
            intruder.getAIGroups().clear();
            tick(king, 1);
            check(goal.defending(), "visible intruder interrupts return");
            intruder.remove();
            tick(king, 2);
            check(goal.returning(), "king resumes return when intruder dies/leaves");
            check(home.samePoint(goal.requestedDestination()), "after fight, head back to same throne");
            king.remove();

            // An archer may notice the king, then lose line of sight behind a wall.
            // It must release the target and resume ordinary march instead of freezing.
            InstanceContainer lane = MinecraftServer.getInstanceManager().createInstanceContainer();
            lane.setGenerator(c -> c.modifier().fillHeight(0, 8, Block.STONE));
            lane.loadChunk(0, 0).get(10, TimeUnit.SECONDS);
            lane.loadChunk(1, 0).get(10, TimeUnit.SECONDS);
            CFUnit shooter = add(lane, archer(), "blue", new Pos(3.5, 8, 4.5), false);
            CFUnit enemy = add(lane, victim(CFDamageTable.Armor.NONE),
                    "red", new Pos(8.5, 8, 4.5), false);
            enemy.getAIGroups().clear();
            tick(shooter, 0);
            check(shooter.getTarget() == enemy && shooter.isRangedLocked(),
                    "archer acquires visible target and holds to shoot");
            for (int y = 8; y <= 12; y++) for (int z = 1; z <= 7; z++)
                lane.setBlock(6, y, z, Block.STONE);
            check(!shooter.hasLineOfSight(enemy), "solid wall blocks real line of sight");
            tick(shooter, 1);
            check(shooter.getTarget() == null, "archer releases unreachable target");
            tick(shooter, 2);
            check(!shooter.isRangedLocked(), "archer unlocks movement after LOS loss");
            check(shooter.getAIGroups().iterator().next().getCurrentGoalSelector() instanceof MarchGoal,
                    "archer resumes road march instead of a permanent combat hold");
            System.out.println("[КФ КОРОЛЬ/ДАЛЬНИК] ПРОЙДЕНО " + checks
                    + " проверок: 4000 HP, урон 10, возврат к трону, бой и потеря видимости.");
        } finally {
            for (CFUnit mob : spawned) if (!mob.isRemoved()) mob.remove();
            MinecraftServer.stopCleanly();
        }
    }
}
