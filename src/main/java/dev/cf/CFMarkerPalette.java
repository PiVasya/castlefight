package dev.cf;

import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import java.util.*;

/** Single authoritative palette. No color is both a road and a build mask. */
final class CFMarkerPalette {
    record Mark(int lane, String team, String role, Block block, Material item) {
        boolean road() { return team == null; }
    }
    static final List<Mark> ALL = List.of(
        new Mark(1,null,"ROAD_1",Block.BROWN_CARPET,Material.BROWN_CARPET),
        new Mark(2,null,"ROAD_2",Block.RED_CARPET,Material.RED_CARPET),
        new Mark(3,null,"ROAD_3",Block.ORANGE_CARPET,Material.ORANGE_CARPET),
        new Mark(1,"blue","BLUE_1",Block.CYAN_CARPET,Material.CYAN_CARPET),
        new Mark(2,"blue","BLUE_2",Block.LIGHT_BLUE_CARPET,Material.LIGHT_BLUE_CARPET),
        new Mark(3,"blue","BLUE_3",Block.BLUE_CARPET,Material.BLUE_CARPET),
        new Mark(1,"red","RED_1",Block.PURPLE_CARPET,Material.PURPLE_CARPET),
        new Mark(2,"red","RED_2",Block.MAGENTA_CARPET,Material.MAGENTA_CARPET),
        new Mark(3,"red","RED_3",Block.PINK_CARPET,Material.PINK_CARPET));
    private static final Map<Integer,Mark> BY_STATE;
    static { var m=new HashMap<Integer,Mark>(); for(var mark:ALL)m.put(mark.block().stateId(),mark);BY_STATE=Map.copyOf(m); }
    static Mark of(Block block) { return BY_STATE.get(block.stateId()); }
    static Mark of(int state) { return BY_STATE.get(state); }
    private CFMarkerPalette() { }
}
