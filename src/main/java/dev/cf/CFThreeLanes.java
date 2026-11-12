package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.command.CommandSender;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Compiles three carpet-marked routes from a prepared external map at runtime. */
final class CFThreeLanes {
    private final InstanceContainer world;
    private final Path mapRoot;
    private final CFCarpetZones carpets;
    private final Map<String,Pos> castles = new HashMap<>();
    private volatile Map<Integer,CFLanePath> lanes = Map.of();
    private volatile String status = "NOT_CHECKED";
    private volatile CompletableFuture<Void> checking;
    /** Terrain uses a point probe, independently of unit size. */
    private static final BoundingBox TERRAIN_POINT = new BoundingBox(.04,.04,.04);
    private final double gap;

    CFThreeLanes(InstanceContainer world, Path mapRoot, CFCarpetZones carpets) {
        this.world = world;
        this.mapRoot = mapRoot;
        this.carpets = carpets;
        Path file = mapRoot.resolve("cf/lanes-v2.properties");
        if (!Files.isRegularFile(file))
            throw new IllegalStateException("Missing map config: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            if (!"2.2".equals(p.getProperty("schema")))
                throw new IllegalArgumentException("Expected schema=2.2");
            for (String team : List.of("blue", "red"))
                castles.put(team, readPos(p, team));
            gap = Double.parseDouble(p.getProperty("dot.max-gap", "12"));
            if (!Double.isFinite(gap) || gap < 2 || gap > 16)
                throw new IllegalArgumentException("dot.max-gap must be 2..16");
        } catch (Exception e) {
            throw new IllegalStateException("Invalid map config " + file, e);
        }
    }

    static BoundingBox terrainPoint() { return TERRAIN_POINT; }
    BoundingBox envelope() { return TERRAIN_POINT; }
    synchronized Pos castle(String team) { return castles.get(team); }
    boolean busy(){var c=checking;return c!=null&&!c.isDone();}
    boolean ready(){return lanes.size()==3&&!busy();}
    CFLanePath lane(int id){return lanes.get(id);}
    String status(){return "[CF ROUTES] "+status+" blue="+castle("blue")+" red="+castle("red");}
    boolean reserved(int x,int y,int z){for(var p:lanes.values())if(p.reserved(x,y,z))return true;return false;}

    synchronized CompletableFuture<Void> check(CommandSender sender) {
        if(busy())return checking;
        var done=new CompletableFuture<Void>();checking=done;lanes=Map.of();status="LOADING_MAP";
        Pos blue=castles.get("blue"),red=castles.get("red");
        if(blue==null||red==null){fail(done,sender,new IllegalArgumentException("Missing castles in map metadata"));return done;}
        if(blue.distance(red)<4){fail(done,sender,new IllegalArgumentException("Castle points are too close"));return done;}
        Thread.ofVirtual().name("cf-route-chunk-index").start(()->{
            try {
                List<Long> chunks=CFMapChunks.discover(mapRoot,world);
                world.scheduler().scheduleNextTick(()->loadBatch(chunks,0,blue,red,done,sender));
            }catch(Exception e){fail(done,sender,e);}
        });
        if(sender!=null)sender.sendMessage("[CF ROUTES] Validating map chunks, three lanes and both directions.");
        return done;
    }
    private void loadBatch(List<Long> keys,int offset,Pos blue,Pos red,CompletableFuture<Void> done,CommandSender sender) {
        if(done.isDone())return;
        try {
            if(offset>=keys.size()){prepare(blue,red,done,sender);return;}
            var loads=new ArrayList<CompletableFuture<?>>();int end=Math.min(offset+16,keys.size());
            for(int i=offset;i<end;i++){long k=keys.get(i);loads.add(world.loadChunk((int)(k>>32),(int)k));}
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).orTimeout(30,TimeUnit.SECONDS)
                    .whenComplete((v,e)->world.scheduler().scheduleNextTick(()->{
                        if(e!=null)fail(done,sender,e);else loadBatch(keys,end,blue,red,done,sender);
                    }));
        }catch(Exception e){fail(done,sender,e);}
    }
    private record Segment(int lane,boolean reverse,Pos from,Pos to,boolean castleLink){}
    private void prepare(Pos blue,Pos red,CompletableFuture<Void> done,CommandSender sender) {
        try {
            var snapshot=carpets.snapshot(world);Block.Getter terrain=CFLaneSearch.terrain(world);
            blue=CFLaneSearch.surface(terrain,TERRAIN_POINT,blue);red=CFLaneSearch.surface(terrain,TERRAIN_POINT,red);
            var queue=new ArrayDeque<Segment>();
            for(int id=1;id<=3;id++) {
                var dots=new ArrayList<Pos>();
                for(var e:snapshot.entrySet())if(e.getValue().road()&&e.getValue().lane()==id){var c=e.getKey();dots.add(new Pos(c.x()+.5,c.y(),c.z()+.5));}
                List<Pos> ordered;
                try{ordered=CFMarkerChain.order(dots,blue,red,gap);}catch(Exception e){throw new IllegalArgumentException("LANE "+id+": "+e.getMessage());}
                for(String team:List.of("blue","red"))if(!hasFootprint(snapshot,id,team))
                    throw new IllegalArgumentException("LANE "+id+" has no complete 3x3 "+team+" build area. /cf carpets");
                var anchors=new ArrayList<Pos>();anchors.add(blue);
                for(Pos p:ordered)anchors.add(CFLaneSearch.surface(terrain,TERRAIN_POINT,p));anchors.add(red);
                for(int i=1;i<anchors.size();i++) {
                    boolean end=i==1||i==anchors.size()-1;
                    queue.add(new Segment(id,false,anchors.get(i-1),anchors.get(i),end));
                    queue.add(new Segment(id,true,anchors.get(i),anchors.get(i-1),end));
                }
            }
            Map<Integer,List<List<Pos>>> forward=new HashMap<>(),backward=new HashMap<>();
            for(int i=1;i<=3;i++){forward.put(i,new ArrayList<>());backward.put(i,new ArrayList<>());}
            compileNext(queue,forward,backward,done,sender);
        }catch(Exception e){fail(done,sender,e);}
    }
    static boolean hasFootprint(Map<CFPlacementRules.Cell,CFMarkerPalette.Mark> cells,int lane,String team) {
        for(var e:cells.entrySet()) {
            if(e.getValue().lane()!=lane||!team.equals(e.getValue().team()))continue;
            var c=e.getKey();boolean ok=true;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                var m=cells.get(new CFPlacementRules.Cell(c.x()+dx,c.y(),c.z()+dz));
                if(m==null||m.lane()!=lane||!team.equals(m.team()))ok=false;
            }if(ok)return true;
        }return false;
    }
    private void compileNext(ArrayDeque<Segment> queue,Map<Integer,List<List<Pos>>> f,Map<Integer,List<List<Pos>>> r,
                             CompletableFuture<Void> done,CommandSender sender) {
        if(done.isDone())return;
        if(queue.isEmpty()) {
            try{
                Map<Integer,CFLanePath> built=new HashMap<>();
                for(int i=1;i<=3;i++) {Collections.reverse(r.get(i));built.put(i,new CFLanePath(i,flatten(f.get(i)),flatten(r.get(i)),1.05));}
                lanes=Map.copyOf(built);status="VALID: all 3 lanes reach both castles; no mob size checks";
                writeReport(status,null);done.complete(null);
                if(sender!=null){sender.sendMessage("[CF ROUTES] VALID: 3/3 lanes, both directions.");
                    for(var e:lanes.entrySet())sender.sendMessage("[CF ROUTES] Lane "+e.getKey()+": blue="+e.getValue().blue.size()+" red="+e.getValue().red.size()+" validated ground nodes.");}
                System.out.println(status());
            }catch(Exception e){fail(done,sender,e);}return;
        }
        Segment s=queue.removeFirst();status="CHECKING lane "+s.lane+" "+(s.reverse?"RED->BLUE":"BLUE->RED")+" remaining="+queue.size();
        try{
            List<Pos> segment=CFLaneSearch.find(CFLaneSearch.terrain(world),TERRAIN_POINT,s.from,s.to,
                    CFLaneSearch.tube(s.from,s.to,s.castleLink?8:4.5));
            (s.reverse?r:f).get(s.lane).add(segment);
            world.scheduler().scheduleNextTick(()->compileNext(queue,f,r,done,sender));
        }catch(Exception e){fail(done,sender,new IllegalArgumentException("LANE "+s.lane+" "+(s.reverse?"RED->BLUE":"BLUE->RED")+
                " blocked link "+s.from+" -> "+s.to+": "+e.getMessage()));}
    }
    private static List<Pos> flatten(List<List<Pos>> parts){var out=new ArrayList<Pos>();for(var list:parts)for(Pos p:list)
        if(out.isEmpty()||p.distanceSquared(out.getLast())>.0001)out.add(p);return List.copyOf(out);}
    private void fail(CompletableFuture<Void> done,CommandSender sender,Throwable e) {
        lanes=Map.of();status="INVALID: "+e.getMessage();System.err.println(status());
        writeReport(status,e);done.completeExceptionally(e);if(sender!=null)sender.sendMessage(status());
    }
    private void writeReport(String message,Throwable e){
        CFSkeletonMindLog.systemEvent("ROUTE_VALIDATION","result",message);
        try{
            Path out=Path.of("diagnostics/routes");Files.createDirectories(out);
            var text=new StringBuilder("CF 2.2 route validation\n").append(Instant.now()).append('\n').append(message).append('\n');
            for(var entry:new TreeMap<>(lanes).entrySet())for(String team:List.of("blue","red")){
                int i=0;for(Pos p:entry.getValue().forTeam(team))text.append(entry.getKey()).append(',').append(team).append(',').append(i++).append(',').append(CFTrace.xyz(p)).append('\n');
            }
            Files.writeString(out.resolve("last-check.txt"),text);
        }catch(IOException ex){System.err.println("[CF ROUTES] Report write failed: "+ex);}
    }
    static Pos readPos(Properties p,String key){
        Pos pos=new Pos(Double.parseDouble(p.getProperty(key+".x")),Double.parseDouble(p.getProperty(key+".y")),Double.parseDouble(p.getProperty(key+".z")));
        if(!Double.isFinite(pos.x())||!Double.isFinite(pos.y())||!Double.isFinite(pos.z())||Math.abs(pos.x())>30000000||Math.abs(pos.z())>30000000||pos.y()<-64||pos.y()>319)
            throw new IllegalArgumentException("Invalid castle position");return pos;
    }
}
