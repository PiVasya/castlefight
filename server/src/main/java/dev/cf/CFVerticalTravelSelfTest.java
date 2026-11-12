package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.pathfinding.PNode;
import net.minestom.server.instance.block.Block;

import java.util.Set;

/**
 * Cold regression: real Minestom terrain collision and path-neighbour rules,
 * no map files, server socket, game entities, or forced vertical teleportation.
 */
public final class CFVerticalTravelSelfTest {
    private static int assertions;
    private static void check(boolean ok, String description) {
        assertions++;
        if (!ok) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        check(CFVerticalTravel.nodeHeightAllowed(8, 6), "down two blocks is allowed");
        check(CFVerticalTravel.nodeHeightAllowed(8, 7), "down one block is allowed");
        check(CFVerticalTravel.nodeHeightAllowed(8, 9), "up one block through a normal jump");
        check(!CFVerticalTravel.nodeHeightAllowed(8, 5), "down three blocks is rejected");
        check(!CFVerticalTravel.nodeHeightAllowed(8, 10), "up two blocks without stairs is rejected");
        check(!CFVerticalTravel.landingWithinDrop(8, 5, false), "deep landing is rejected");
        check(CFVerticalTravel.landingWithinDrop(8, 6, false), "two-block landing is accepted");
        check(!CFVerticalTravel.landingWithinDrop(8, Double.NaN, false), "invalid heights rejected");
        check(CFVerticalTravel.downwardProbe(false)>CFVerticalTravel.MAX_DROP,
                "landing probe reaches a two-block lower floor");
        check(CFVerticalTravel.downwardProbe(false)<3,
                "grounded probe does not treat a three-block pit as a landing");

        var road = new CFWideLaneGeometry(java.util.List.of(
                new CFWideLaneGeometry.Node(0, 8, .5),
                new CFWideLaneGeometry.Node(6, 8, .5)));
        check(road.contains(new CFWideLaneGeometry.Node(2, 6, .5), 0, 6, 4),
                "runtime corridor tolerates walking down two blocks");
        check(!road.contains(new CFWideLaneGeometry.Node(2, 5, .5), 0, 6, 4),
                "a separate level three blocks down stays outside lane");

        // Upper floor y=8 for x<=0; next cell is two blocks lower, y=6.
        Block.Getter twoDown = (x, y, z, c) ->
                y < (x <= 0 ? 8 : 6) ? Block.STONE : Block.AIR;
        BoundingBox point = new BoundingBox(.04, .04, .04);
        Pos high = new Pos(.5, 8, .5), low = new Pos(1.5, 6, .5);
        var landed = CollisionUtils.handlePhysics(twoDown, point,
                new Pos(1.5, 8.01, .5),
                new Vec(0, -CFVerticalTravel.downwardProbe(false), 0), null, true);
        check(landed.collisionY(), "two-block lower terrain is physically detected");
        check(Math.abs(landed.newPosition().y() - 6) < .06,
                "collision finds the actual landing elevation");
        check(CFVerticalTravel.landingWithinDrop(8, landed.newPosition().y(), false),
                "physical landing is within two blocks");
        check(CFLaneSearch.clear(twoDown, point, landed.newPosition()),
                "low surface is dry and has free headroom");

        PNode from = new PNode(high, 0, 0, PNode.Type.WALK, null);
        var neighbors = new CFGroundGenerator().getWalkable(twoDown, Set.of(from), from, low, point);
        check(neighbors.stream().anyMatch(n ->
                Math.abs(n.x() - 1.5) < .01 && Math.abs(n.y() - 6) < .06
                        && Math.abs(n.z() - .5) < .01),
                "A* exposes an actual fall neighbour two blocks below");

        Block.Getter tooDeep = (x, y, z, c) ->
                y < (x <= 0 ? 8 : 5) ? Block.STONE : Block.AIR;
        var pit = CollisionUtils.handlePhysics(tooDeep, point,
                new Pos(1.5, 8.01, .5),
                new Vec(0, -CFVerticalTravel.downwardProbe(false), 0), null, true);
        check(!pit.collisionY(), "three-block drop has no safe support within probe distance");
        check(new CFGroundGenerator().getWalkable(tooDeep, Set.of(from), from,
                new Pos(1.5, 5, .5), point).stream().noneMatch(n ->
                        Math.abs(n.x()-1.5)<.01 && Math.abs(n.z()-.5)<.01),
                "A* does not create a three-block fall node");

        Block.Getter water = (x, y, z, c) ->
                x > 0 && y == 6 ? Block.WATER : twoDown.getBlock(x,y,z,c);
        check(!CFLaneSearch.clear(water, point, low),
                "water on landing is not a dry walking surface");

        Block.Getter oneUp = (x, y, z, c) ->
                y < (x <= 0 ? 8 : 9) ? Block.STONE : Block.AIR;
        check(new CFGroundGenerator().getWalkable(oneUp, Set.of(from), from,
                new Pos(1.5, 9, .5), point).stream().anyMatch(n ->
                        Math.abs(n.x()-1.5)<.01 && Math.abs(n.y()-9)<.06),
                "one-block rise remains available as a jump");
        Block.Getter wall = (x, y, z, c) ->
                y < (x <= 0 ? 8 : 10) ? Block.STONE : Block.AIR;
        check(new CFGroundGenerator().getWalkable(wall, Set.of(from), from,
                new Pos(1.5, 10, .5), point).stream().noneMatch(n ->
                        Math.abs(n.x()-1.5)<.01 && Math.abs(n.z()-.5)<.01),
                "two-block sheer wall stays impassable");

        System.out.println("[КФ РЕЛЬЕФ] ПРОЙДЕНО " + assertions +
                " проверок: спуск 1-2 блока, ступени, глубокая яма, вода, стены, высота лайна.");
    }

    private CFVerticalTravelSelfTest() { }
}
