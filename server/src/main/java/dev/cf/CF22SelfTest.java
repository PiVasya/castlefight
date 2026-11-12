package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.block.Block;
import java.util.*;
import java.nio.file.*;

/** Pure algorithms + actual Minestom collision/path classes. No port/live map/instance touched. */
public final class CF22SelfTest {
    static int checks;
    static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
    static void rejects(Runnable run,String why){try{run.run();}catch(IllegalArgumentException e){checks++;return;}throw new AssertionError(why);}
    private static void checkMark(Block block,String team,int lane){
        var m=CFMarkerPalette.of(block);
        check(m!=null && team.equals(m.team()) && m.lane()==lane,
                block.name()+" must mean exactly "+team+" lane "+lane);
    }
    public static void main(String[] args) throws Exception {
        check(CFMarkerPalette.ALL.size()==16,"all 16 carpets are construction colors in schema=3.0");
        check(CFMarkerPalette.ALL.stream().map(m->m.block().stateId()).distinct().count()==16,"all colors unique");
        check(CFMarkerPalette.ALL.stream().allMatch(m->m.team()!=null),"16 carpets reserved for base construction");
        check(CFMarkerPalette.of(Block.RED_CARPET).team().equals("red"),"red carpet is now red base, not a road");
        check(CFMarkerPalette.of(Block.BLUE_CARPET).lane()==3,"blue carpet remains lane 3");
        checkMark(Block.CYAN_CARPET,"blue",1); checkMark(Block.WHITE_CARPET,"blue",1);
        checkMark(Block.PURPLE_CARPET,"red",1); checkMark(Block.BROWN_CARPET,"red",1);
        checkMark(Block.LIGHT_BLUE_CARPET,"blue",2); checkMark(Block.LIGHT_GRAY_CARPET,"blue",2);
        checkMark(Block.MAGENTA_CARPET,"red",2); checkMark(Block.RED_CARPET,"red",2);
        checkMark(Block.BLUE_CARPET,"blue",3); checkMark(Block.GRAY_CARPET,"blue",3);
        checkMark(Block.PINK_CARPET,"red",3); checkMark(Block.ORANGE_CARPET,"red",3);
        checkMark(Block.GREEN_CARPET,"blue",4); checkMark(Block.BLACK_CARPET,"blue",4);
        checkMark(Block.LIME_CARPET,"red",4); checkMark(Block.YELLOW_CARPET,"red",4);
        check(CFPathBeacons.lane(Block.LODESTONE,Block.BROWN_STAINED_GLASS)==1,"beacon lane 1");
        check(CFPathBeacons.lane(Block.LODESTONE,Block.RED_STAINED_GLASS)==2,"beacon lane 2");
        check(CFPathBeacons.lane(Block.LODESTONE,Block.ORANGE_STAINED_GLASS)==3,"beacon lane 3");
        check(CFPathBeacons.lane(Block.LODESTONE,Block.YELLOW_STAINED_GLASS)==4,"beacon lane 4");
        check(CFPathBeacons.lane(Block.LODESTONE,Block.BLUE_STAINED_GLASS)==0,"not any glass makes beacon");
        check(CFPathBeacons.lane(Block.STONE,Block.YELLOW_STAINED_GLASS)==0,"fake lower block rejected");
        check(CFPathBeacons.lane(Block.LODESTONE,Block.STONE)==0,"fake upper block rejected");
        var onlyFourth=new HashMap<Integer,List<Pos>>();
        onlyFourth.put(4,List.of(new Pos(1,8,1),new Pos(2,8,1)));
        rejects(()->CFRouteMarkers.collect(onlyFourth),"fourth lane without 1-3 rejected");
        check(CFRouteMarkers.collect(Map.of(
                1,List.of(new Pos(1,8,1),new Pos(2,8,1)),
                2,List.of(new Pos(1,8,2),new Pos(2,8,2)),
                3,List.of(new Pos(1,8,3),new Pos(2,8,3)),
                4,List.of(new Pos(1,8,4),new Pos(2,8,4)))).count()==4,
                "four lanes are discovered by on-road beacons, not carpets");
        rejects(()->CFRouteMarkers.collect(Map.of()),"16 carpets cannot create a route: beacons required");
        for(var m:CFMarkerPalette.ALL) {
            check(CFMarkerPalette.of(m.block())==m,"extended color round trip");
            check(m.team()!=null,"every extended carpet belongs to team+lane");
        }
        // Файл маршрутов пишется РЯДОМ с картой, не в JAR и не в build.sh.
        Path fakeWorld=Files.createTempDirectory("cf-cache-test-");
        try {
            Files.createDirectories(fakeWorld.resolve("cf"));
            Files.createDirectories(fakeWorld.resolve("dimensions/minecraft/overworld/region"));
            Files.writeString(fakeWorld.resolve(CFMapFormat.FILE),"schema=3.0\n");
            Path region=fakeWorld.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
            Files.write(region,new byte[]{1,2,3,4,5,6});
            byte[] hash=CFLaneCache.fingerprint(fakeWorld);
            check(hash!=null&&hash.length==32,"hash map source for cache");
            // Far-away region changes must not recompile a bounded arena.
            Path outside=fakeWorld.resolve("dimensions/minecraft/overworld/region/r.2.0.mca");
            Files.write(outside,new byte[]{9,8,7});
            check(java.util.Arrays.equals(hash,CFLaneCache.fingerprint(fakeWorld)),
                    "outside the 50-chunk window does not invalidate route cache");
            Files.write(outside,new byte[]{7,8,9,1});
            check(java.util.Arrays.equals(hash,CFLaneCache.fingerprint(fakeWorld)),
                    "changing ignored regions never changes fingerprint");
            var sampleNodes=List.of(new Pos(1.5,8,1.5),new Pos(2.5,8,2.5));
            Map<Integer,CFLanePath> sample=new HashMap<>();
            for(int id=1;id<=4;id++)sample.put(id,new CFLanePath(id,sampleNodes,sampleNodes));
            var sampleCastles=Map.of("blue",new Pos(-30.5,8,-12.5),
                    "red",new Pos(40.5,8,12.5));
            CFLaneCache.save(fakeWorld,hash,sample,sampleCastles);
            var loaded=CFLaneCache.read(fakeWorld,hash);
            check(loaded!=null&&loaded.routes().size()==4&&loaded.routes().get(4).id==4,
                    "read 4 lanes from persistent cache");
            check(loaded.castles().equals(sampleCastles),"both castle centres persisted in the SAME binary");
            Files.write(region,new byte[]{1,2,3,4,5,7});
            check(CFLaneCache.read(fakeWorld,CFLaneCache.fingerprint(fakeWorld))==null,
                    "source world edit invalidates cached routes");
            byte[] newHash=CFLaneCache.fingerprint(fakeWorld);
            CFLaneCache.save(fakeWorld,newHash,sample,sampleCastles);
            check(CFLaneCache.read(fakeWorld,newHash)!=null,"new cache saved after world change");
            Files.writeString(fakeWorld.resolve(CFMapFormat.FILE),"schema=3.0\ndot.max-gap=11\n");
            check(CFLaneCache.read(fakeWorld,CFLaneCache.fingerprint(fakeWorld))==null,
                    "map settings change invalidates cached routes");
        }finally{
            try(var files=Files.walk(fakeWorld)) {
                for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
            }
        }
        Path wrong=Files.createTempDirectory("cf-old-map-refused-");
        try {
            Files.createDirectories(wrong.resolve("cf"));
            Files.writeString(wrong.resolve(CFMapFormat.FILE),"schema=2.2\n");
            rejects(()->CFMapFormat.read(wrong),"schema 2.2 is forbidden");
            Files.writeString(wrong.resolve(CFMapFormat.FILE),"schema=2.3\n");
            rejects(()->CFMapFormat.read(wrong),"schema 2.3 is forbidden");
            Files.writeString(wrong.resolve(CFMapFormat.FILE),"schema=3.0\n");
            check("3.0".equals(CFMapFormat.read(wrong).getProperty("schema")),"new schema accepted");
            Files.writeString(wrong.resolve(CFMapFormat.FILE),"schema=3.0\nblue.x=0.5\n");
            rejects(()->CFMapFormat.read(wrong),"stale castle coordinates rejected");
        }finally{
            try(var files=Files.walk(wrong)){
                for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
            }
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
        for(var mark:CFMarkerPalette.ALL) {
            cells.clear();for(int x=14;x<=16;x++)for(int z=-2;z<=0;z++)cells.put(new CFPlacementRules.Cell(x,8,z),mark);
            check(CFThreeLanes.hasFootprint(cells,mark.lane(),mark.team()),"9 cells across chunks for "+mark.role());
            check(!CFThreeLanes.hasFootprint(cells,mark.lane(),mark.team().equals("blue")?"red":"blue"),"wrong team");
            check(!CFThreeLanes.hasFootprint(cells,mark.lane()%4+1,mark.team()),"wrong lane");
            cells.remove(new CFPlacementRules.Cell(15,8,-1));check(!CFThreeLanes.hasFootprint(cells,mark.lane(),mark.team()),"hole rejected");
        }
        // В одной 3x3 НЕЛЬЗЯ смешивать два цвета даже одной команды.
        // Это защищает принцип принадлежности башни выбранному laneId.
        cells.clear();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            cells.put(new CFPlacementRules.Cell(dx,8,dz),CFMarkerPalette.of(Block.CYAN_CARPET));
        cells.put(new CFPlacementRules.Cell(1,8,1),CFMarkerPalette.of(Block.WHITE_CARPET));
        check(!CFThreeLanes.hasFootprint(cells,1,"blue"),"mixed same-team lane colors rejected for lane 1");
        check(!CFThreeLanes.hasFootprint(cells,2,"blue"),"mixed same-team lane colors rejected for lane 2");
        check(!CFThreeLanes.hasFootprint(cells,1,"blue"),"even two colors of same team AND lane rejected");
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
