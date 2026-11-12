package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.*;
import net.minestom.server.instance.block.Block;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real route compiler integration gate on a temporary synthetic arena, with no socket opened. */
public final class CF22RouteSmoke {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void pump(InstanceContainer w,CompletableFuture<?> f)throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(!f.isDone()&&System.nanoTime()<limit){w.scheduler().processTick();w.scheduler().process();Thread.sleep(1);}
        if(!f.isDone())throw new AssertionError("Route compiler timed out");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("New temporary directory required");
        Path root=Path.of(args[0]).toAbsolutePath();if(Files.exists(root))throw new IllegalArgumentException("Test directory exists");
        Files.createDirectories(root);
        if(!Path.of("").toAbsolutePath().normalize().equals(root.getParent()))throw new IllegalArgumentException("Run this gate inside the temp directory, not in the project");
        MinecraftServer.init();
        try {
            InstanceContainer w=MinecraftServer.getInstanceManager().createInstanceContainer();
            w.setGenerator(u->u.modifier().fillHeight(0,8,Block.STONE));
            for(int cx=-2;cx<=3;cx++)for(int cz=-1;cz<=2;cz++)w.loadChunk(cx,cz).get(15,TimeUnit.SECONDS);
            for(var m:CFMarkerPalette.ALL) {
                int z=(m.lane()-1)*12;
                if(m.road()){for(int x=0;x<=24;x+=4)w.setBlock(x,8,z,m.block());}
                else {int x=m.team().equals("blue")?-12:38;
                    for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)w.setBlock(x+dx,8,z+5+dz,m.block());}
            }
            Path config=root.resolve("cf/lanes-v2.properties");
            Files.createDirectories(config.getParent());
            var configProperties = new Properties();
            configProperties.setProperty("schema","2.2");
            configProperties.setProperty("blue.x","-8.5");
            configProperties.setProperty("blue.y","8.0");
            configProperties.setProperty("blue.z","12.5");
            configProperties.setProperty("red.x","32.5");
            configProperties.setProperty("red.y","8.0");
            configProperties.setProperty("red.z","12.5");
            try(var out=Files.newOutputStream(config)){configProperties.store(out,"synthetic test map");}
            var masks=new CFCarpetZones();
            for(Chunk chunk:List.copyOf(w.getChunks()))masks.preparePlayChunk(chunk);
            var routes=new CFThreeLanes(w,root,masks);
            var f=routes.check(null);pump(w,f);f.join();check(routes.ready(),"all three lanes valid");
            for(int id=1;id<=3;id++) {
                var lane=routes.lane(id);check(lane!=null&&lane.id==id,"separate lane id");
                check(lane.blue.getFirst().distanceSquared(new Pos(-8.5,8,12.5))<.001,"blue castle connected");
                check(lane.blue.getLast().distanceSquared(new Pos(32.5,8,12.5))<.001,"red castle connected");
                check(lane.red.getLast().distanceSquared(lane.blue.getFirst())<.001,"reverse reaches opposite castle");
                var risen=new CFContentRegistry.Mob("risen","Risen",
                        net.minestom.server.entity.EntityType.SKELETON,
                        "ranged_hold",10f,.1035,2f,16,17,6250,
                        net.minestom.server.item.Material.BOW);
                var unit=new CFUnit("blue",risen,lane.blue.getFirst(),lane.blue.getLast());
                unit.assignValidatedLane(lane,lane.blue);check(unit.laneId()==id,"unit binds own lane");
                boolean denied=false;try{unit.assignValidatedLane(routes.lane(id%3+1),lane.blue);}catch(IllegalStateException expected){denied=true;}
                check(denied&&unit.laneId()==id,"lane reassignment forbidden");
            }
            var loaded=new CFThreeLanes(w,root,masks);
            check(loaded.castle("blue").equals(routes.castle("blue")),"castle config reload");
            check(!loaded.ready(),"unvalidated cached config cannot authorize a match");
            for(int z=-16;z<48;z++)for(int y=8;y<14;y++)w.setBlock(12,y,z,Block.STONE);
            var bad=routes.check(null);pump(w,bad);
            check(bad.isCompletedExceptionally()&&!routes.ready(),"wall rejects route, towers remain disabled");
            check(routes.status().contains("LANE"),"diagnostic includes lane");
            System.out.println("[CF22 ROUTE TEST] PASS "+checks+" integration assertions. Synthetic arena only.");
        }finally{MinecraftServer.stopCleanly();}
    }
}
