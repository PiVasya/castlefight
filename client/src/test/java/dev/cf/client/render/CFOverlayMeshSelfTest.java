package dev.cf.client.render;

import dev.cf.protocol.CFOverlayWire;
import java.util.List;

public final class CFOverlayMeshSelfTest {
    public static void main(String[] args) {
        var a = new CFOverlayWire.Cell(1,8,1,0);
        var b = new CFOverlayWire.Cell(2,8,1,0);
        var path = new CFOverlayWire.Route(1,List.of(
                new CFOverlayWire.Point(0,9,0),new CFOverlayWire.Point(3,9,0)));
        var same = CFOverlayMesh.build(new CFOverlayWire.Geometry("test", List.of(a,b),List.of(path)));
        if (same.quads().size() != 16) throw new AssertionError("Expected 2 fills, 12 border quads, 2 path ribbons: "+same.quads().size());
        var different = CFOverlayMesh.build(new CFOverlayWire.Geometry("test",
                List.of(a,new CFOverlayWire.Cell(2,8,1,1)),List.of(path)));
        if (different.quads().size() != 20) throw new AssertionError("Different carpet colors must have separate borders");
        if (different.near(1,1,2).size() != different.quads().size())
            throw new AssertionError("Spatial chunk lookup mismatch");
        if (!different.near(1000,1000,2).isEmpty()) throw new AssertionError("Far geometry was not culled");
        if (different.quads().getLast().alpha() <= 0) throw new AssertionError();
        System.out.println("[КФ МОД] PASS: реальные границы зоны по цвету, 2 полосы пути, spatial culling");
    }
}
