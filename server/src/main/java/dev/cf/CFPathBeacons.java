package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ==========================================================================
 *          МАРКЕРЫ ДОРОЖЕК СТОЯТ ПРЯМО НА ДОРОГЕ — НЕ ПОД НЕЙ!
 * ==========================================================================
 *   Верх дороги: Y. Магнетит (LODESTONE): Y+1. Цветное стекло: Y+2.
 *   НИКАКОГО БЛОКА ПОЛА ПОВЕРХ СТЕКЛА. Пешеходная точка: Y+1, то есть
 *   уровень основания магнетита, а не над стеклом и не под дорогой.
 *
 *   Маркеры обнаруживаются в Anvil-чанке ДО его публикации в игровом мире.
 *   Затем ОБА служебных блока заменяются воздухом ТОЛЬКО В ПАМЯТИ чанка.
 *   Поэтому в режиме игры мобы не сталкиваются с маяками, но на исходной
 *   карте оба блока остаются нетронутыми. Сохранение чанков запрещено
 *   CFReadOnlyMapLoader. По этому же принципу скрываются строительные ковры.
 *   Использовать ковры как маркеры маршрута ЗАПРЕЩЕНО в любом мире КФ.
 * ==========================================================================
 */
final class CFPathBeacons {
    private static final Map<Integer,Integer> GLASS_LANES=Map.of(
        Block.BROWN_STAINED_GLASS.stateId(),1,
        Block.RED_STAINED_GLASS.stateId(),2,
        Block.ORANGE_STAINED_GLASS.stateId(),3,
        Block.YELLOW_STAINED_GLASS.stateId(),4);
    private final Map<Long,Map<Integer,List<Pos>>> playChunks = new ConcurrentHashMap<>();
    private record Beacon(int x,int y,int z,int lane) { }

    static int lane(Block lower,Block upper) {
        if(lower.stateId()!=Block.LODESTONE.stateId())return 0;
        return GLASS_LANES.getOrDefault(upper.stateId(),0);
    }

    void preparePlayChunk(Chunk chunk) {
        if(chunk==null)return;
        var found = new TreeMap<Integer,List<Pos>>();
        var beacons = new ArrayList<Beacon>();
        chunk.lockWriteLock();
        try {
            for(int section=chunk.getMinSection();section<chunk.getMaxSection();section++) {
                var palette=chunk.getSection(section).blockPalette();
                if(!palette.any(Block.LODESTONE.stateId()))continue;
                final int sy=section*16;
                palette.getAllPresent((x,y,z,state)->{
                    if(state!=Block.LODESTONE.stateId())return;
                    int wx=chunk.getChunkX()*16+x, wy=sy+y, wz=chunk.getChunkZ()*16+z;
                    int id=lane(Block.LODESTONE,chunk.getBlock(wx,wy+1,wz));
                    if(id!=0)beacons.add(new Beacon(wx,wy,wz,id));
                });
            }
            // Scan all pairs before masking them, so no discovery depends on
            // iteration order of chunk sections.
            for(var beacon:beacons) {
                Block floor=chunk.getBlock(beacon.x(),beacon.y()-1,beacon.z());
                Block aboveGlass=chunk.getBlock(beacon.x(),beacon.y()+2,beacon.z());
                if(!aboveGlass.air())
                    throw new IllegalArgumentException("Маяк пути "+beacon.lane()+" в "+
                        beacon.x()+","+beacon.y()+','+beacon.z()+
                        ": блок ПОВЕРХ стекла запрещён! Маяк должен стоять НА ОТКРЫТОЙ ДОРОГЕ, не под полом");
                if(floor.liquid() || !floor.solid() || !floor.collisionShape().isFaceFull(BlockFace.TOP))
                    throw new IllegalArgumentException("Маяк пути "+beacon.lane()+" в "+
                        beacon.x()+","+beacon.y()+','+beacon.z()+
                        ": магнетит должен стоять НА сплошном блоке ДОРОГИ (без воды и полуплит)");
                found.computeIfAbsent(beacon.lane(),k->new ArrayList<>())
                        .add(new Pos(beacon.x()+.5,beacon.y(),beacon.z()+.5));
            }
            for(var beacon:beacons) {
                chunk.setBlock(beacon.x(),beacon.y(),beacon.z(),Block.AIR);
                chunk.setBlock(beacon.x(),beacon.y()+1,beacon.z(),Block.AIR);
            }
        }finally{chunk.unlockWriteLock();}
        var frozen=new TreeMap<Integer,List<Pos>>();
        found.forEach((key,points)->frozen.put(key,List.copyOf(points)));
        playChunks.put(CFCarpetZones.key(chunk.getChunkX(),chunk.getChunkZ()),Map.copyOf(frozen));
    }

    Map<Integer,List<Pos>> snapshot(Instance world) {
        var out=new TreeMap<Integer,List<Pos>>();
        for(Chunk chunk:world.getChunks()) {
            var data=playChunks.getOrDefault(CFCarpetZones.key(chunk.getChunkX(),chunk.getChunkZ()),Map.of());
            data.forEach((lane,points)->out.computeIfAbsent(lane,k->new ArrayList<>()).addAll(points));
        }
        var frozen=new TreeMap<Integer,List<Pos>>();
        out.forEach((key,points)->frozen.put(key,List.copyOf(points)));
        return Map.copyOf(frozen);
    }
}
