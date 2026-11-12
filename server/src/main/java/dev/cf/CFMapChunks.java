package dev.cf;

import net.minestom.server.instance.Instance;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Read Anvil location headers only; never guesses loaded-player chunks are the whole map. */
final class CFMapChunks {
    static List<Long> discover(Path root,Instance world) throws IOException {
        var out=new TreeSet<Long>();
        for(var c:world.getChunks())out.add(CFCarpetZones.key(c.getChunkX(),c.getChunkZ()));
        Path folder=root.resolve("dimensions/minecraft/overworld/region");
        if(Files.isDirectory(folder))try(var stream=Files.list(folder)) {
            Pattern regex=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
            for(Path file:stream.sorted().toList()) {
                var m=regex.matcher(file.getFileName().toString());if(!m.matches())continue;
                int rx=Integer.parseInt(m.group(1)),rz=Integer.parseInt(m.group(2));
                try(var in=new DataInputStream(Files.newInputStream(file))) {
                    for(int i=0;i<1024;i++)if(in.readInt()!=0)out.add(CFCarpetZones.key(rx*32+i%32,rz*32+i/32));
                }
            }
        }
        if(out.size()>2048)throw new IOException("Map contains "+out.size()+" chunks; safety limit=2048. Use a cropped arena, not an entire generated world.");
        return List.copyOf(out);
    }
    private CFMapChunks() { }
}
