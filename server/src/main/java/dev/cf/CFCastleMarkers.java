package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Замки CF Map 3.0: ровно девять BLUE_GLAZED_TERRACOTTA или
 * RED_GLAZED_TERRACOTTA на одной высоте, 3x3 без смешивания цветов.
 *
 * Как ковры и маяки, керамика сканируется при загрузке каждого Anvil-чанка,
 * включая площадки на границах чанков. В отличие от ковров и маяков керамика
 * НЕ скрывается: это настоящий пол трона, на котором стоит король.
 * Никаких координат замков в map.properties, WorldEdit или записи в .mca.
 */
final class CFCastleMarkers {
    private final Map<Long,Map<CFPlacementRules.Cell,String>> chunks=new ConcurrentHashMap<>();

    void preparePlayChunk(Chunk chunk) {
        if(chunk==null)return;
        var found=new HashMap<CFPlacementRules.Cell,String>();
        // Scan immutable palette data, no block mutations and no cross-chunk queries.
        chunk.lockReadLock();
        try {
            for(int section=chunk.getMinSection();section<chunk.getMaxSection();section++) {
                var palette=chunk.getSection(section).blockPalette();
                int sy=section*16;
                palette.getAllPresent((x,y,z,state)->{
                    Block block=Block.fromStateId(state);
                    if(block==null)return;
                    // Match the block TYPE, not directional ceramic variants.
                    String team=block.name().equals(Block.BLUE_GLAZED_TERRACOTTA.name()) ? "blue" :
                            block.name().equals(Block.RED_GLAZED_TERRACOTTA.name()) ? "red" : null;
                    if(team!=null)found.put(new CFPlacementRules.Cell(
                            chunk.getChunkX()*16+x,sy+y,chunk.getChunkZ()*16+z),team);
                });
            }
        }finally{chunk.unlockReadLock();}
        chunks.put(CFCarpetZones.key(chunk.getChunkX(),chunk.getChunkZ()),Map.copyOf(found));
    }

    Map<CFPlacementRules.Cell,String> snapshot(Instance world) {
        var all=new HashMap<CFPlacementRules.Cell,String>();
        for(Chunk chunk:world.getChunks())
            all.putAll(chunks.getOrDefault(CFCarpetZones.key(chunk.getChunkX(),chunk.getChunkZ()),Map.of()));
        return Map.copyOf(all);
    }

    /** Pure verification, shared by the compiler and regression tests. */
    static Map<String,Pos> locate(Map<CFPlacementRules.Cell,String> blocks) {
        var result=new HashMap<String,Pos>();
        for(var entry:blocks.entrySet()) {
            var center=entry.getKey();String team=entry.getValue();
            if(!isExactSquare(blocks,center,team))continue;
            Pos throne=new Pos(center.x()+.5,center.y()+1,center.z()+.5);
            Pos previous=result.putIfAbsent(team,throne);
            if(previous!=null && !previous.samePoint(throne))
                throw new IllegalArgumentException("Найдено НЕСКОЛЬКО замков команды "+team+
                        ": "+previous+" и "+throne+". Требуется ровно один квадрат 3x3 на команду");
        }
        for(String team:List.of("blue","red"))if(!result.containsKey(team))
            throw new IllegalArgumentException("Не найден замок "+team+
                    ": выложи ОДИН ровный квадрат 3x3 из "+
                    (team.equals("blue")?"BLUE_GLAZED_TERRACOTTA":"RED_GLAZED_TERRACOTTA")+
                    ", все 9 блоков на одной высоте. Одинокие декоративные блоки игнорируются");
        if(result.get("blue").distance(result.get("red"))<4)
            throw new IllegalArgumentException("Замки должны быть дальше 4 блоков друг от друга");
        return Map.copyOf(result);
    }

    private static boolean isExactSquare(Map<CFPlacementRules.Cell,String> blocks,
                                         CFPlacementRules.Cell center,String team) {
        int x=center.x(),y=center.y(),z=center.z();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if(!team.equals(blocks.get(new CFPlacementRules.Cell(x+dx,y,z+dz))))return false;
        // Strict 3x3, not a sliding window cut from a bigger ceramic carpet.
        for(int offset=-1;offset<=1;offset++) {
            if(team.equals(blocks.get(new CFPlacementRules.Cell(x-2,y,z+offset))) ||
               team.equals(blocks.get(new CFPlacementRules.Cell(x+2,y,z+offset))) ||
               team.equals(blocks.get(new CFPlacementRules.Cell(x+offset,y,z-2))) ||
               team.equals(blocks.get(new CFPlacementRules.Cell(x+offset,y,z+2))))return false;
        }
        return true;
    }
}
