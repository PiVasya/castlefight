package dev.cf;

import net.minestom.server.coordinate.Pos;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Результат одноразовой компиляции маршрутов живёт РЯДОМ с миром.
 * Он не входит в JAR, не требует изменений build.sh и не затрагивает .mca.
 * При каждом запуске проверяется SHA-256 исходной карты и настроек.
 * Если карта или формат изменились, испорченный/старый кеш игнорируется,
 * а маршрут строится заново и сохраняется атомарной заменой файла.
 */
final class CFLaneCache {
    private static final int MAGIC=0x43464C52; // "CFLR"
    // Увеличивать при изменениях алгоритма компиляции или формата маршрутов.
    private static final int VERSION=3; // Только CF Map 3.0, никаких старых путевых ковров
    private static final int HASH_SIZE=32;
    static final String NAME="compiled-routes-v3.bin";

    static byte[] fingerprint(Path root) throws IOException {
        Path regions=root.resolve("dimensions/minecraft/overworld/region");
        if(!Files.isDirectory(regions))return null; // Synthetic tests: do not cache worlds with no Anvil files.
        List<Path> worlds;
        try(var files=Files.list(regions)) {
            worlds=files.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                .sorted().toList();
        }
        if(worlds.isEmpty())return null;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(("CF-ROUTES-COMPILER-"+VERSION+"\\n").getBytes(StandardCharsets.UTF_8));
            digestFile(digest,root.resolve(CFMapFormat.FILE),CFMapFormat.FILE);
            for(Path file:worlds)digestFile(digest,file,"region/"+file.getFileName());
            return digest.digest();
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 отсутствует",e);}
    }
    private static void digestFile(MessageDigest digest,Path file,String name) throws IOException {
        digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
        try(InputStream stream=Files.newInputStream(file)) {
            byte[] buffer=new byte[65536];int n;
            while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        digest.update((byte)0);
    }
    static Map<Integer,CFLanePath> read(Path root,byte[] hash) {
        if(hash==null)return null;
        Path file=root.resolve("cf").resolve(NAME);
        if(!Files.isRegularFile(file))return null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)return null;
            byte[] actual=new byte[HASH_SIZE];in.readFully(actual);
            if(!Arrays.equals(actual,hash))return null;
            int count=in.readInt();
            if(count<1||count>CFMarkerPalette.MAX_LANES)return null;
            Map<Integer,CFLanePath> routes=new HashMap<>();
            for(int id=1;id<=count;id++) {
                if(in.readInt()!=id)return null;
                var blue=readPoints(in);var red=readPoints(in);
                routes.put(id,new CFLanePath(id,blue,red,1.05));
            }
            if(in.read()!=-1)return null; // trailing/corrupt data
            return Map.copyOf(routes);
        } catch(IOException|RuntimeException bad) {
            System.err.println("[КФ КАРТА] Кеш маршрутов повреждён; будет пересчитан: "+bad.getMessage());
            return null;
        }
    }
    private static List<Pos> readPoints(DataInputStream in) throws IOException {
        int count=in.readInt();if(count<2||count>20000)throw new IOException("Неверное число узлов: "+count);
        var points=new ArrayList<Pos>(count);
        for(int i=0;i<count;i++) {
            double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||
                    Math.abs(x)>30000000||Math.abs(z)>30000000||y< -64||y>320)
                throw new IOException("Неверные координаты узла "+i);
            points.add(new Pos(x,y,z));
        }
        return List.copyOf(points);
    }
    private static void writePoints(DataOutputStream out,List<Pos> points) throws IOException {
        if(points.size()<2||points.size()>20000)throw new IOException("Неверное число узлов: "+points.size());
        out.writeInt(points.size());
        for(Pos p:points){out.writeDouble(p.x());out.writeDouble(p.y());out.writeDouble(p.z());}
    }
    static void save(Path root,byte[] hash,Map<Integer,CFLanePath> routes) throws IOException {
        if(hash==null)return; // No real Anvil region files in synthetic unit tests.
        if(hash.length!=HASH_SIZE)throw new IOException("Неверный SHA-256");
        int count=routes.size();
        if(count<1||count>CFMarkerPalette.MAX_LANES)throw new IOException("Неверное число дорожек: "+count);
        Path dir=root.resolve("cf");Files.createDirectories(dir);
        Path tmp=Files.createTempFile(dir,".cf-routes-",".tmp");
        boolean done=false;
        try {
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);out.writeInt(VERSION);out.write(hash);out.writeInt(count);
                for(int id=1;id<=count;id++) {
                    var lane=routes.get(id);if(lane==null)throw new IOException("Отсутствует дорожка "+id);
                    out.writeInt(id);writePoints(out,lane.blue);writePoints(out,lane.red);
                }
            }
            Path dst=dir.resolve(NAME);
            try{Files.move(tmp,dst,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING);}
            done=true;
        }finally{if(!done)Files.deleteIfExists(tmp);}
    }
    private CFLaneCache() { }
}
