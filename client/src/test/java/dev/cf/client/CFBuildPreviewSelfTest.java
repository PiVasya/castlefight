package dev.cf.client;

import dev.cf.protocol.CFOverlayWire;
import dev.cf.protocol.CFBuildWire;
import java.util.*;

public final class CFBuildPreviewSelfTest {
    private static int checks;
    private static void check(boolean yes) {if(!yes)throw new AssertionError("check "+checks);checks++;}
    public static void main(String[] args) throws Exception {
        List<CFOverlayWire.Cell> cells=new ArrayList<>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            cells.add(new CFOverlayWire.Cell(dx,-49,dz,0)); // cyan / blue / lane1
            cells.add(new CFOverlayWire.Cell(20+dx,-49,20+dz,2)); // purple / red / lane1
            cells.add(new CFOverlayWire.Cell(50+dx,-49,50+dz,4)); // light blue / blue / lane2
        }
        List<CFOverlayWire.Route> paths=List.of(new CFOverlayWire.Route(1,List.of(
                new CFOverlayWire.Point(0,-49,0),new CFOverlayWire.Point(20,-49,20))));
        var g=new CFOverlayWire.Geometry("test",cells,paths);
        var p=new CFBuildPreview(g);
        check(p.check("blue",0,-49,0).valid());
        check(p.check("blue",0,-49,0).lane()==1);
        check(!p.check("red",0,-49,0).valid());
        check(!p.check("blue",1,-49,1).valid());
        check(!p.check("blue",0,-48,0).valid());
        check(p.check("red",20,-49,20).valid());
        check(!p.check("blue",50,-49,50).valid()); // no lane2 route
        List<CFOverlayWire.Cell> mixed=new ArrayList<>(cells);
        mixed.set(0,new CFOverlayWire.Cell(-1,-49,-1,1)); // white alternative != cyan
        check(!new CFBuildPreview(new CFOverlayWire.Geometry("test",mixed,paths)).check("blue",0,-49,0).valid());
        check(!p.check("blue",900,-49,900).valid());
        System.out.println("[КФ КЛИЕНТ] ПРОЙДЕНО "+checks+" проверок ПКМ/3×3/цвета/команды/высоты/лайна.");
    }
}
