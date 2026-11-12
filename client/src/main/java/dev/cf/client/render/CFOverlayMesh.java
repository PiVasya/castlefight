package dev.cf.client.render;

import dev.cf.protocol.CFOverlayWire;
import java.util.*;

/**
 * Immutable overlay tessellation, prepared ONCE when map payload arrives.
 * No block scanning. No files. Key G only changes a boolean.
 *
 * Zone boundaries are extracted from exact carpet-cell adjacency; do NOT
 * merge by team or decide a cell's lane by proximity to a route.
 */
public final class CFOverlayMesh {
    // Palette indices MUST match server CFMarkerPalette.ALL ordering.
    private static final int[] COLORS = {
            0x20BFCB, 0xF2F1E9, 0xA05ACD, 0x826045,
            0x65B8EB, 0x9AA0A0, 0xD84DBC, 0xD53535,
            0x3568E3, 0x575C63, 0xF2A3C9, 0xEC8C32,
            0x448C48, 0x282B31, 0x77D23B, 0xE7CB44
    };
    private static final int[] ROUTES = {0xC49052,0xE44E53,0xF09B38,0xF4D747};
    public record Quad(
            float ax,float ay,float az, float bx,float by,float bz,
            float cx,float cy,float cz, float dx,float dy,float dz,
            int rgb,float alpha) {
        public float midX() { return (ax+bx+cx+dx)*.25f; }
        public float midZ() { return (az+bz+cz+dz)*.25f; }
    }
    private final List<Quad> quads;
    // Chunks of 16x16 quads: drawing a very large map never scans its entire mesh.
    private final Map<Long,List<Quad>> byChunk;
    private final String mapId;
    private static long key(int x,int z) { return ((long)x<<32) | (z&0xffffffffL); }

    private CFOverlayMesh(String mapId,List<Quad> quads) {
        this.mapId = mapId;
        this.quads = List.copyOf(quads);
        Map<Long,List<Quad>> index = new HashMap<>();
        for (var q : quads) {
            int cx = Math.floorDiv((int)Math.floor(q.midX()),16);
            int cz = Math.floorDiv((int)Math.floor(q.midZ()),16);
            index.computeIfAbsent(key(cx,cz),_k->new ArrayList<>()).add(q);
        }
        Map<Long,List<Quad>> frozen = new HashMap<>();
        index.forEach((k,v)->frozen.put(k,List.copyOf(v)));
        byChunk = Map.copyOf(frozen);
    }
    public String mapId() { return mapId; }
    public List<Quad> quads() { return quads; }
    public List<Quad> near(double cameraX,double cameraZ,int radiusChunks) {
        int cx=Math.floorDiv((int)Math.floor(cameraX),16), cz=Math.floorDiv((int)Math.floor(cameraZ),16);
        var visible=new ArrayList<Quad>();
        for (int x=cx-radiusChunks; x<=cx+radiusChunks; x++)
            for (int z=cz-radiusChunks; z<=cz+radiusChunks; z++)
                visible.addAll(byChunk.getOrDefault(key(x,z),List.of()));
        return visible;
    }

    public static CFOverlayMesh build(CFOverlayWire.Geometry geometry) {
        var draw = new ArrayList<Quad>();
        // Same palette, same Y and adjacent X/Z => same build area.
        var present = new HashSet<>(geometry.zones());
        for (var cell : geometry.zones()) {
            float x=cell.x(), z=cell.z();
            float y=cell.y()+.06f;
            int color=COLORS[cell.palette()];
            rect(draw,x,y,z,x+1,z+1,color,.21f);
            // 0.10m black outer border and 0.05m bright inner border.
            // No internal outlines between cells of the SAME carpet color.
            if (!present.contains(new CFOverlayWire.Cell(cell.x()-1,cell.y(),cell.z(),cell.palette()))) {
                rect(draw,x,y+.025f,z,x+.095f,z+1,0x0B0C14,.94f);
                rect(draw,x+.035f,y+.04f,z,x+.090f,z+1,color,.96f);
            }
            if (!present.contains(new CFOverlayWire.Cell(cell.x()+1,cell.y(),cell.z(),cell.palette()))) {
                rect(draw,x+.905f,y+.025f,z,x+1,z+1,0x0B0C14,.94f);
                rect(draw,x+.91f,y+.04f,z,x+.965f,z+1,color,.96f);
            }
            if (!present.contains(new CFOverlayWire.Cell(cell.x(),cell.y(),cell.z()-1,cell.palette()))) {
                rect(draw,x,y+.025f,z,x+1,z+.095f,0x0B0C14,.94f);
                rect(draw,x,y+.04f,z+.035f,x+1,z+.090f,color,.96f);
            }
            if (!present.contains(new CFOverlayWire.Cell(cell.x(),cell.y(),cell.z()+1,cell.palette()))) {
                rect(draw,x,y+.025f,z+.905f,x+1,z+1,0x0B0C14,.94f);
                rect(draw,x,y+.04f,z+.91f,x+1,z+.965f,color,.96f);
            }
        }
        // Draw the authoritative computed path, NOT naive beacon-to-beacon lines.
        for (var route:geometry.routes()) {
            int color=ROUTES[route.lane()-1];
            var nodes=route.nodes();
            for(int i=1;i<nodes.size();i++) {
                var a=nodes.get(i-1); var b=nodes.get(i);
                double len=Math.hypot(b.x()-a.x(),b.z()-a.z());
                if(len < .001 || len > 30) continue; // no giant diagonals
                segment(draw,a,b,.86f,0x111018,.87f,.10f);
                segment(draw,a,b,.58f,color,.78f,.12f);
            }
        }
        return new CFOverlayMesh(geometry.mapId(),draw);
    }
    private static void rect(List<Quad> target,float x1,float y,float z1,float x2,float z2,int color,float alpha) {
        // Vertex order matches Minecraft's positive Y-facing debug-box top face.
        target.add(new Quad(x1,y,z2,x2,y,z2,x2,y,z1,x1,y,z1,color,alpha));
    }
    private static void segment(List<Quad> target, CFOverlayWire.Point a,CFOverlayWire.Point b,
                                float width,int rgb,float alpha,float height) {
        float dx=b.x()-a.x(),dz=b.z()-a.z();
        float length=(float)Math.hypot(dx,dz);
        if(length < .001f)return;
        float px=dz/length*width*.5f, pz=-dx/length*width*.5f;
        float ay=a.y()+height,by=b.y()+height;
        // The four vertices form a lane ribbon even on slopes.
        target.add(new Quad(a.x()-px,ay,a.z()-pz,b.x()-px,by,b.z()-pz,
                b.x()+px,by,b.z()+pz,a.x()+px,ay,a.z()+pz,rgb,alpha));
    }
}
