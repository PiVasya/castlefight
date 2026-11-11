package dev.cf;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.*;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Installer integration gate on a NEW EMPTY TEMP directory. Never opens the live arena. */
public final class CF22WorldSmoke {
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        System.out.println("[CF22 WORLD TEST] PASS: "+message);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Required: a non-existing temporary test directory");
        Path dir=Path.of(args[0]).toAbsolutePath().normalize();
        if(Files.exists(dir))throw new IllegalArgumentException("Test folder must not exist: "+dir);
        Files.createDirectory(dir);
        // No Main, no CFWorldMode constructor, no lane tool, no real server mode/config files.
        MinecraftServer.init();
        try {
            Path arena=dir.resolve("test-world");
            Files.createDirectory(arena);
            var world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setChunkLoader(new AnvilLoader(arena,Key.key("minecraft:overworld")));
            for(int x=-1;x<=1;x++)world.loadChunk(x,0).orTimeout(15,TimeUnit.SECONDS).join();
            paint(world,15,40,3,Block.CYAN_CARPET); // spans chunks 0/1
            paint(world,-1,40,8,Block.PURPLE_CARPET); // spans negative/zero chunk
            world.setBlock(4,39,14,Block.STONE);world.setBlock(4,40,14,Block.RED_CARPET);
            world.saveChunksToStorage().orTimeout(20,TimeUnit.SECONDS).join();
            for(Chunk c:List.copyOf(world.getChunks()))world.unloadChunk(c);
            Map<String,String> baseline=hashes(arena);
            check(!baseline.isEmpty(),"Anvil wrote temporary .mca files");

            var zones=new CFCarpetZones();
            var play=MinecraftServer.getInstanceManager().createInstanceContainer();
            play.setChunkLoader(new CFWorldMode.ReadOnlyLoader(new AnvilLoader(arena,
                    Key.key("minecraft:overworld")),zones));
            for(int x=-1;x<=1;x++)play.loadChunk(x,0).orTimeout(15,TimeUnit.SECONDS).join();
            check(play.getBlock(15,40,3).air(),"PLAY removes visible blue carpet");
            check(play.getBlock(-1,40,8).air(),"PLAY removes visible red carpet");
            check(play.getBlock(4,40,14).air(),"PLAY hides road dots too");
            check(zones.markAt(4,40,14).road() && zones.markAt(4,40,14).lane()==2,"red = lane 2 road, not build cell");
            check(zones.footprintLane("blue",15,40,3)==1,"cyan footprint binds lane 1");
            check(!zones.covers("red",4,40,14),"road dots cannot place buildings");
            check(zones.covers("blue",15,40,3),"3x3 blue mask crosses a chunk boundary");
            check(zones.covers("red",-1,40,8),"3x3 red mask handles negative coordinates");
            check(!zones.covers("red",15,40,3),"enemy color cannot build on blue mask");
            check(!zones.covers("blue",15,41,3),"mask includes exact height");
            play.setBlock(15,40,3,Block.GRAY_CONCRETE);
            play.saveChunksToStorage().orTimeout(15,TimeUnit.SECONDS).join();
            for(Chunk c:List.copyOf(play.getChunks()))play.unloadChunk(c);
            check(baseline.equals(hashes(arena)),"PLAY save attempt leaves .mca bytes unchanged");

            var again=MinecraftServer.getInstanceManager().createInstanceContainer();
            again.setChunkLoader(new AnvilLoader(arena,Key.key("minecraft:overworld")));
            again.loadChunk(0,0).orTimeout(15,TimeUnit.SECONDS).join();
            check(again.getBlock(15,40,3).compare(Block.CYAN_CARPET),"BUILD reload restores saved carpet, not PLAY cube");
            check(again.getBlock(15,39,3).compare(Block.STONE),"foundation survives roundtrip");
            for(Chunk c:List.copyOf(again.getChunks()))again.unloadChunk(c);
            System.out.println("[CF22 WORLD TEST] SUCCESS. Tested only "+arena);
        } finally { MinecraftServer.stopCleanly(); }
    }
    private static void paint(InstanceContainer w,int x,int y,int z,Block carpet) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            w.setBlock(x+dx,y-1,z+dz,Block.STONE);
            w.setBlock(x+dx,y,z+dz,carpet);
        }
    }
    private static Map<String,String> hashes(Path root)throws Exception {
        Map<String,String> result=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path p:paths.filter(f->Files.isRegularFile(f)&&f.toString().endsWith(".mca")).toList())
                result.put(root.relativize(p).toString(),HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        }
        return result;
    }
}
