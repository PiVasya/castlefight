package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.*;
import net.minestom.server.instance.block.Block;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Integration test on a synthetic Minecraft world: four lanes via stacked road beacons. */
public final class CF23FourLaneRouteSmoke {
    private static int checks;
    private static void check(boolean good,String why){checks++;if(!good)throw new AssertionError(why);}
    private static void pump(InstanceContainer world,CompletableFuture<?> task) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(!task.isDone()&&System.nanoTime()<deadline) {
            world.scheduler().processTick();world.scheduler().process();Thread.sleep(1);
        }
        if(!task.isDone())throw new AssertionError("Four-lane compiler timed out");
    }
    private static void paintCastle(InstanceContainer world,int x,int z,Block ceramic) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            world.setBlock(x+dx,7,z+dz,ceramic);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Temporary output directory argument required");
        Path root=Path.of(args[0]).toAbsolutePath();
        if(Files.exists(root))throw new IllegalArgumentException("Directory already exists: "+root);
        Files.createDirectories(root.resolve("cf"));
        // Tiny synthetic Anvil location header: declare one region chunk at (0,0).
        // The actual smoke world uses a generator; this file is only needed to
        // exercise the *real* fingerprint + persistent-cache + chunk-load path.
        Path regions=root.resolve("dimensions/minecraft/overworld/region");
        Files.createDirectories(regions);
        byte[] header=new byte[8192];
        header[3]=1; // nonzero location for chunk (0,0), per Anvil region header
        Files.write(regions.resolve("r.0.0.mca"),header);
        Properties properties=new Properties();
        properties.setProperty("schema","3.0");
        properties.setProperty("dot.max-gap","12");
        try(var out=Files.newOutputStream(root.resolve(CFMapFormat.FILE))) {
            properties.store(out,"synthetic four-lane beacon test");
        }
        MinecraftServer.init();
        try {
            InstanceContainer world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(chunk->chunk.modifier().fillHeight(0,8,Block.STONE));
            for(int cx=-2;cx<=3;cx++)for(int cz=-1;cz<=2;cz++)world.loadChunk(cx,cz).get(10,TimeUnit.SECONDS);
            Block[] glasses={Block.BROWN_STAINED_GLASS,Block.RED_STAINED_GLASS,
                    Block.ORANGE_STAINED_GLASS,Block.YELLOW_STAINED_GLASS};
            Block[] blue={Block.CYAN_CARPET,Block.LIGHT_BLUE_CARPET,Block.BLUE_CARPET,Block.GREEN_CARPET};
            Block[] red={Block.PURPLE_CARPET,Block.MAGENTA_CARPET,Block.PINK_CARPET,Block.LIME_CARPET};
            for(int i=0;i<4;i++) {
                int z=(i-1)*12;
                for(int x=0;x<=24;x+=4) {
                    // Дорога: камень на Y=7; магнетит НА ДОРОГЕ: Y=8;
                    // цветное стекло НА МАГНЕТИТЕ: Y=9. Не прятать под пол!
                    world.setBlock(x,8,z,Block.LODESTONE);
                    world.setBlock(x,9,z,glasses[i]);
                }
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                    world.setBlock(-12+dx,8,z+5+dz,blue[i]);
                    world.setBlock(38+dx,8,z+5+dz,red[i]);
                }
            }
            // Thrones are genuine 3x3 floor patches, including one across
            // a chunk border (red x=31..33). Neither is hidden in runtime.
            paintCastle(world,-9,6,Block.BLUE_GLAZED_TERRACOTTA);
            paintCastle(world,32,6,Block.RED_GLAZED_TERRACOTTA);
            CFCarpetZones masks=new CFCarpetZones();
            CFPathBeacons beaconMasks=new CFPathBeacons();
            CFCastleMarkers throneMasks=new CFCastleMarkers();
            for(Chunk chunk:List.copyOf(world.getChunks())) {
                throneMasks.preparePlayChunk(chunk);
                beaconMasks.preparePlayChunk(chunk);
                masks.preparePlayChunk(chunk);
            }
            var thrones=CFCastleMarkers.locate(throneMasks.snapshot(world));
            check(thrones.get("blue").samePoint(new Pos(-8.5,8,6.5)),"blue 3x3 detected");
            check(thrones.get("red").samePoint(new Pos(32.5,8,6.5)),"red 3x3 across chunks");
            check(world.getBlock(32,7,6).compare(Block.RED_GLAZED_TERRACOTTA),
                    "throne floor remains visible, unlike carpets and beacons");
            var discovered=beaconMasks.snapshot(world);
            // Не изменяем файлы карты: в игровой памяти маяки исчезают,
            // иначе 2-блочные колонны перекроют проход мобильным существам.
            check(world.getBlock(0,8,-12).air(),"lodestone hidden in runtime memory");
            check(world.getBlock(0,9,-12).air(),"glass hidden in runtime memory");
            check(discovered.size()==4,"four beacon colors detected");
            for(int id=1;id<=4;id++)check(discovered.get(id).size()==7,"seven beacon points on lane "+id);
            check(masks.footprintLane("blue",-12,8,29)==4,"green 3x3 builds on lane 4");
            check(masks.footprintLane("red",38,8,29)==4,"lime 3x3 builds on lane 4");
            var lanes=new CFThreeLanes(world,root,masks,beaconMasks,throneMasks);
            var running=lanes.check(null);pump(world,running);running.join();
            check(lanes.ready()&&lanes.laneCount()==4,"exactly four independent certified paths");
            for(int id=1;id<=4;id++) {
                CFLanePath path=lanes.lane(id);
                check(path!=null&&path.id==id,"correct lane identity");
                check(path.blue.getLast().distanceSquared(new Pos(32.5,8,6.5))<.001,"blue reaches red castle");
                check(path.red.getLast().distanceSquared(new Pos(-8.5,8,6.5))<.001,"red reaches blue castle");
            }
            check(lanes.lane(5)==null,"lane five is unavailable");
            check(Files.isRegularFile(root.resolve("cf/compiled-routes-v3.bin")),
                    "four-lane routes persisted beside the map");
            var compiled=CFLaneCache.read(root,CFLaneCache.fingerprint(root));
            check(compiled!=null && compiled.routes().size()==4,
                    "compiled 4-lane cache has all the routes");
            check(compiled.castles().equals(thrones),
                    "blue and red castles persisted alongside the 4 routes");
            // Regression: a cache HIT must still load the map chunks. A former
            // implementation incorrectly returned ready() with zero loaded chunks.
            InstanceContainer cachedWorld=MinecraftServer.getInstanceManager().createInstanceContainer();
            cachedWorld.setGenerator(chunk->chunk.modifier().fillHeight(0,8,Block.STONE));
            check(cachedWorld.getChunks().isEmpty(),"cold cache test starts without chunks");
            // Preload only throne chunks and collect the markers. The route
            // chunk at (0,0) is intentionally COLD and must still be loaded.
            CFCastleMarkers cachedThrones=new CFCastleMarkers();
            for(int cx:new int[]{-1,1,2})cachedWorld.loadChunk(cx,0).get(10,TimeUnit.SECONDS);
            paintCastle(cachedWorld,-9,6,Block.BLUE_GLAZED_TERRACOTTA);
            paintCastle(cachedWorld,32,6,Block.RED_GLAZED_TERRACOTTA);
            for(Chunk chunk:List.copyOf(cachedWorld.getChunks()))cachedThrones.preparePlayChunk(chunk);
            check(cachedWorld.getChunks().stream().noneMatch(c->c.getChunkX()==0&&c.getChunkZ()==0),
                    "route chunk is cold despite preloaded thrones");
            var cachedRoutes=new CFThreeLanes(cachedWorld,root,new CFCarpetZones(),new CFPathBeacons(),cachedThrones);
            var cacheTask=cachedRoutes.check(null);pump(cachedWorld,cacheTask);cacheTask.join();
            check(cachedRoutes.ready() && cachedRoutes.laneCount()==4,
                    "cold cache hit restored four routes");
            check(cachedRoutes.castle("red").samePoint(thrones.get("red")),
                    "castle 3x3 is restored from compiled map cache");
            check(cachedWorld.getChunks().stream().anyMatch(c->c.getChunkX()==0&&c.getChunkZ()==0),
                    "cache hit loaded source region chunk before declaring routes ready");
            System.out.println("[КФ 4 ДОРОЖКИ] ПРОЙДЕНО "+checks+
                    " проверок: маяки, ковры 3x3, маршруты, холодный кеш и чанки.");
        }finally{MinecraftServer.stopCleanly();}
    }
}
