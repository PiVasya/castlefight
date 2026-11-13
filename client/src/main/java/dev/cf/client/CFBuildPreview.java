package dev.cf.client;

import dev.cf.protocol.CFOverlayWire;
import java.util.*;

/** Same strict color/height/lane rule as CFCarpetZones, with no world scanning. */
public final class CFBuildPreview {
    public record Cell(int x,int y,int z) { }
    public record Result(boolean valid,int lane,String reason) { }
    private final Map<Cell,Integer> zones;
    private final Set<Integer> lanes;
    public CFBuildPreview(CFOverlayWire.Geometry geometry) {
        Map<Cell,Integer> cells=new HashMap<>();
        for(var c:geometry.zones()) cells.put(new Cell(c.x(),c.y(),c.z()),c.palette());
        zones=Map.copyOf(cells);
        Set<Integer> paths=new HashSet<>();
        for(var r:geometry.routes())paths.add(r.lane());
        lanes=Set.copyOf(paths);
    }
    public Result check(String team,int x,int y,int z) {
        Integer color=zones.get(new Cell(x,y,z));
        if(color==null)return new Result(false,0,"Неверная позиция — нет строительной зоны");
        if(!"red".equals(team)&&!"blue".equals(team))return new Result(false,0,"Неверная команда");
        boolean blue=color%4<2;
        if((blue&&!"blue".equals(team))||(!blue&&!"red".equals(team)))
            return new Result(false,0,"Неверная команда — это чужая территория");
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
            if(!color.equals(zones.get(new Cell(x+dx,y,z+dz))))
                return new Result(false,0,"Неверная позиция — нужны 9 ковров одного цвета");
        int lane=color/4+1;
        if(!lanes.contains(lane))return new Result(false,0,"Маршрут ещё не готов");
        return new Result(true,lane,"Подходит: дорожка "+lane);
    }
}
