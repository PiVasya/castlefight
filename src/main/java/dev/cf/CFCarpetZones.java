package dev.cf;

import net.minestom.server.instance.*;
import net.minestom.server.instance.block.Block;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ВСЕ 16 ЦВЕТОВ КОВРОВ = ТОЛЬКО ЗОНЫ СТРОИТЕЛЬСТВА, НЕ МАРШРУТ.
 * Команда и дорожка определяются ТОЛЬКО цветом ковра; вся площадка 3x3
 * должна иметь девять ковров ТОЧНО ОДНОГО ЦВЕТА. Старой схемы не существует.
 * См. обязательный комментарий автора в CFMarkerPalette.
 */
final class CFCarpetZones {
    private final Map<Long,Map<CFPlacementRules.Cell,CFMarkerPalette.Mark>> playChunks=new ConcurrentHashMap<>();
    CFCarpetZones() { }
    static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    void preparePlayChunk(Chunk chunk) {
        if(chunk==null)return;
        Map<CFPlacementRules.Cell,CFMarkerPalette.Mark> cells;
        chunk.lockWriteLock();
        try {cells=scan(chunk);for(var c:cells.keySet())chunk.setBlock(c.x(),c.y(),c.z(),Block.AIR);}
        finally {chunk.unlockWriteLock();}
        playChunks.put(key(chunk.getChunkX(),chunk.getChunkZ()),Map.copyOf(cells));
    }
    private Map<CFPlacementRules.Cell,CFMarkerPalette.Mark> scan(Chunk c) {
        var out=new HashMap<CFPlacementRules.Cell,CFMarkerPalette.Mark>();
        for(int section=c.getMinSection();section<c.getMaxSection();section++) {
            var p=c.getSection(section).blockPalette();boolean any=false;
            for(var marker:CFMarkerPalette.ALL)if(p.any(marker.block().stateId())){any=true;break;}
            if(!any)continue; final int sy=section*16;
            p.getAllPresent((x,y,z,state)->{var mark=CFMarkerPalette.of(state);
                if(mark!=null)out.put(new CFPlacementRules.Cell(c.getChunkX()*16+x,sy+y,c.getChunkZ()*16+z),mark);});
        }return out;
    }
    Map<CFPlacementRules.Cell,CFMarkerPalette.Mark> snapshot(Instance world) {
        var out=new HashMap<CFPlacementRules.Cell,CFMarkerPalette.Mark>();
        for(Chunk c:world.getChunks()) {
            out.putAll(playChunks.getOrDefault(key(c.getChunkX(),c.getChunkZ()),Map.of()));
        }return Map.copyOf(out);
    }
    CFMarkerPalette.Mark markAt(int x,int y,int z) {
        return playChunks.getOrDefault(key(Math.floorDiv(x,16),Math.floorDiv(z,16)),Map.of())
                .get(new CFPlacementRules.Cell(x,y,z));
    }
    String teamAt(int x,int y,int z) {var m=markAt(x,y,z);return m==null?null:m.team();}
    int laneAt(String team,int x,int y,int z) {var m=markAt(x,y,z);return m==null||!team.equals(m.team())?0:m.lane();}
    // АРХИТЕКТУРНОЕ ТРЕБОВАНИЕ: возвращаем laneId ТОЛЬКО из цвета всех 9
    // ковров ОДНОЙ команды и ОДНОЙ дорожки. Никаких «ближайших путей»!
    // Это правило обязательно и для 4+ дорожек. Подробнее: CFMarkerPalette.
    int footprintLane(String team,int x,int y,int z) {
        CFMarkerPalette.Mark center=markAt(x,y,z);
        if(center==null||!team.equals(center.team()))return 0;
        // НЕЛЬЗЯ смешивать два цвета, даже если у них ОДНИ team и laneId.
        // Все девять ковров должны иметь точно один block state ID.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            CFMarkerPalette.Mark cell=markAt(x+dx,y,z+dz);
            if(cell==null||cell.block().stateId()!=center.block().stateId())return 0;
        }
        return center.lane();
    }
    boolean covers(String team,int x,int y,int z){return footprintLane(team,x,y,z)>0;}
    String summary(Instance world){
        var counts=new TreeMap<String,Integer>();snapshot(world).values().forEach(m->counts.merge(m.role(),1,Integer::sum));
        return "[КФ КОВРЫ] Скрытые маркеры в загруженных чанках: "+counts+"; для башни нужны 9 ковров одной команды и одной дорожки на одной высоте.";
    }
}
