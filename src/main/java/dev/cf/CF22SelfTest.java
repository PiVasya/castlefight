package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.block.Block;
import java.util.*;

/** Pure algorithms + actual Minestom collision/path classes. No port/live map/instance touched. */
public final class CF22SelfTest {
    static int checks;
    static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
    static void rejects(Runnable run,String why){try{run.run();}catch(IllegalArgumentException e){checks++;return;}throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        check(CFMarkerPalette.ALL.size()==9,"exactly 9 roles");
        check(CFMarkerPalette.ALL.stream().map(m->m.block().stateId()).distinct().count()==9,"distinct colors");
        check(CFMarkerPalette.of(Block.RED_CARPET).road(),"red carpet is ROAD, not red team");
        check(CFMarkerPalette.of(Block.BLUE_CARPET).lane()==3,"old blue now lane 3");
        for(var m:CFMarkerPalette.ALL) {
            check(CFMarkerPalette.of(m.block())==m,"round trip color");
            check(m.road()==m.role().startsWith("ROAD"),"role consistency");
        }
        Pos a=new Pos(-12.5,8,.5),b=new Pos(20.5,8,.5);
        var dots=List.of(new Pos(8.5,8,.5),new Pos(-6.5,8,.5),new Pos(.5,8,.5),new Pos(14.5,8,.5));
        var chain=CFMarkerChain.order(dots,a,b,12);
        check(chain.getFirst().x()==-6.5&&chain.getLast().x()==14.5,"unordered dots oriented castles");
        check(CFMarkerChain.order(dots,b,a,12).getFirst().x()==14.5,"reverse castle order");
        rejects(()->CFMarkerChain.order(List.of(new Pos(0,8,0),new Pos(30,8,0)),a,b,12),"gap rejected");
        rejects(()->CFMarkerChain.order(List.of(new Pos(0,8,0),new Pos(0,8,5),new Pos(5,8,0),new Pos(-5,8,0)),a,b,12),"branch rejected");
        rejects(()->CFMarkerChain.order(dots,a,a,12),"same end rejected");
        var cells=new HashMap<CFPlacementRules.Cell,CFMarkerPalette.Mark>();
        for(var mark:CFMarkerPalette.ALL)if(!mark.road()) {
            cells.clear();for(int x=14;x<=16;x++)for(int z=-2;z<=0;z++)cells.put(new CFPlacementRules.Cell(x,8,z),mark);
            check(CFThreeLanes.hasFootprint(cells,mark.lane(),mark.team()),"9 cells across chunks for "+mark.role());
            check(!CFThreeLanes.hasFootprint(cells,mark.lane(),mark.team().equals("blue")?"red":"blue"),"wrong team");
            check(!CFThreeLanes.hasFootprint(cells,mark.lane()%3+1,mark.team()),"wrong lane");
            cells.remove(new CFPlacementRules.Cell(15,8,-1));check(!CFThreeLanes.hasFootprint(cells,mark.lane(),mark.team()),"hole rejected");
        }
        var box=new BoundingBox(.6,1.99,.6);
        Block.Getter flat=(x,y,z,c)->y<0?Block.STONE:Block.AIR;
        Pos from=new Pos(.5,0,.5),to=new Pos(10.5,0,.5);
        var route=CFLaneSearch.find(flat,box,from,to,CFLaneSearch.tube(from,to,3));
        check(route.getLast().distanceSquared(to)<.0001,"native ground path reaches end");
        check(route.size()>=10,"dense verified path");
        var rev=CFLaneSearch.find(flat,box,to,from,CFLaneSearch.tube(from,to,3));
        var lane=new CFLanePath(1,route,rev);
        check(lane.contains(new Pos(5.5,0,.5)),"own lane included");
        check(!lane.contains(new Pos(5.5,0,30.5)),"other distant lane excluded");
        check(lane.forTeam("red").getLast().distanceSquared(from)<.0001,"red reaches blue castle");
        Block.Getter wall=(x,y,z,c)->(x==5&&y<5)||y<0?Block.STONE:Block.AIR;
        rejects(()->CFLaneSearch.find(wall,box,from,to,CFLaneSearch.tube(from,to,3)),"wall cannot be certified");
        Block.Getter water=(x,y,z,c)->y<0?(x==5?Block.WATER:Block.STONE):Block.AIR;
        rejects(()->CFLaneSearch.find(water,box,from,to,CFLaneSearch.tube(from,to,3)),"river is not a walkable bridge");
        Block.Getter ceiling=(x,y,z,c)->y<0||y==1?Block.STONE:Block.AIR;
        rejects(()->CFLaneSearch.find(ceiling,box,from,to,CFLaneSearch.tube(from,to,3)),"headroom checked");
        Block.Getter slabs=(x,y,z,c)->y<0?Block.STONE:y==0&&x>=2?Block.OAK_PLANKS:y==0&&x>=1?Block.OAK_SLAB:Block.AIR;
        Pos up=new Pos(3.5,1,.5);
        var stairs=CFLaneSearch.find(slabs,box,from,up,CFLaneSearch.tube(from,up,2));
        check(stairs.stream().anyMatch(p->Math.abs(p.y()-.5)<.01),"collision planner retains half-slab height");
        check(CFLaneSearch.find(slabs,box,up,from,CFLaneSearch.tube(from,up,2)).getLast().distanceSquared(from)<.0001,"slab path both ways");
        check(CFLaneSearch.surface(flat,box,from).distanceSquared(from)<.0001,"castle surface");
        rejects(()->CFLaneSearch.find(flat,box,from,new Pos(200,0,0),p->true),"bounded long link rejected");
        System.out.println("[CF22 TEST] PASS "+checks+" assertions; actual collision/path routines exercised, no world opened.");
    }
}
