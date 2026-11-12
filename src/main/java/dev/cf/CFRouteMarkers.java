package dev.cf;

import net.minestom.server.coordinate.Pos;
import java.util.*;

/** Проверяем ТОЛЬКО маяки на поверхности дороги. Ковры не участвуют в путях. */
final class CFRouteMarkers {
    record Routes(Map<Integer,List<Pos>> dots) {
        int count(){return dots.size();}
        List<Pos> lane(int id){return dots.get(id);}
    }
    static Routes collect(Map<Integer,List<Pos>> beacons) {
        var ids=new TreeSet<>(beacons.keySet());
        if(ids.isEmpty())throw new IllegalArgumentException(
                "Нет путевых маяков: установите НА ДОРОГЕ магнетит и сверху цветное стекло нужного пути");
        if(ids.first()<1||ids.last()>CFMarkerPalette.MAX_LANES)
            throw new IllegalArgumentException("Поддерживаются дорожки от 1 до "+CFMarkerPalette.MAX_LANES);
        for(int id=1;id<=ids.last();id++)if(!ids.contains(id))
            throw new IllegalArgumentException("Отсутствуют маяки дорожки "+id+
                    ": номера дорожек должны идти подряд с единицы");
        var points=new TreeMap<Integer,List<Pos>>();
        for(int id:ids) {
            var list=beacons.get(id);
            if(list==null||list.size()<2)
                throw new IllegalArgumentException("Для дорожки "+id+" нужно не меньше двух маяков");
            points.put(id,List.copyOf(list));
        }
        return new Routes(Map.copyOf(points));
    }
    private CFRouteMarkers() { }
}
