package dev.cf;

import net.minestom.server.coordinate.*;
import java.util.*;

/** An immutable, single-lane route. Units get this one object, never the three-lane registry. */
final class CFLanePath {
    final int id;
    final List<Pos> blue,red;
    private final Map<Long,List<Double>> corridor;
    private final Map<Long,List<Double>> reserved;
    CFLanePath(int id,List<Pos> blue,List<Pos> red) { this(id,blue,red,1.0); }
    CFLanePath(int id,List<Pos> blue,List<Pos> red,double clearance) {
        if(id<1||id>3||blue.size()<2||red.size()<2)throw new IllegalArgumentException("Invalid lane");
        this.id=id;this.blue=List.copyOf(blue);this.red=List.copyOf(red);
        var all=new ArrayList<>(blue);all.addAll(red);
        corridor=index(all,4.0);reserved=index(all,clearance);
    }
    // Shared per immutable tower journey; weak keys do not retain demolished towers.
    private final Map<List<Pos>,CFWideLaneGeometry> wideCache=new WeakHashMap<>();
    synchronized CFWideLaneGeometry wideGeometry(List<Pos> journey) {
        return wideCache.computeIfAbsent(journey,points->new CFWideLaneGeometry(points.stream()
                .map(p->new CFWideLaneGeometry.Node(p.x(),p.y(),p.z())).toList()));
    }
    List<Pos> forTeam(String team){return "blue".equals(team)?blue:red;}
    boolean contains(Point p){return contains(corridor,p,2.0);}
    boolean reserved(int x,int y,int z){return contains(reserved,new Pos(x+.5,y,z+.5),3.0);}
    private static boolean contains(Map<Long,List<Double>> m,Point p,double tolerance){
        var heights=m.get(CFCarpetZones.key(p.blockX(),p.blockZ()));
        if(heights==null)return false;
        for(double h:heights)if(Math.abs(h-p.y())<=tolerance)return true;return false;
    }
    private static Map<Long,List<Double>> index(List<Pos> points,double radius) {
        Map<Long,List<Double>> map=new HashMap<>();int r=(int)Math.ceil(radius);
        for(Pos p:points)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
            int x=p.blockX()+dx,z=p.blockZ()+dz;
            if(Math.hypot(x+.5-p.x(),z+.5-p.z())>radius)continue;
            var list=map.computeIfAbsent(CFCarpetZones.key(x,z),k->new ArrayList<>());
            if(list.stream().noneMatch(h->Math.abs(h-p.y())<.2))list.add(p.y());
        }
        var frozen=new HashMap<Long,List<Double>>();map.forEach((k,v)->frozen.put(k,List.copyOf(v)));return Map.copyOf(frozen);
    }
}
