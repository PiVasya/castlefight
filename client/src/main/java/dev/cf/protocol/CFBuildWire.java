package dev.cf.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** One catalog per join/race change; one tiny build request only after local validation.
 * Shared byte-for-byte between Minestom and Fabric. Never trust incoming requests.
 */
public final class CFBuildWire {
    public static final String CATALOG_CHANNEL="castlefight:tower_catalog_v1";
    public static final String BUILD_CHANNEL="castlefight:tower_build_v1";
    public static final int VERSION=1, MAX_CATALOG_BYTES=64_000, MAX_RAW_BYTES=256_000;
    private CFBuildWire() { }

    public record Mob(String name,int count,int health10,int damage10,int criticalChance,
                      int criticalDamage10, double speed,double range,double detection,
                      long cooldownMs,String damageType,String armorType,String behavior) { }
    public record Tower(String id,String name,String race,String ability,String upgradeTo,
                        int intervalSeconds, List<Mob> mobs) {
        public Tower { mobs=List.copyOf(mobs); }
    }
    public record Catalog(String race,String team,List<Tower> towers) {
        public Catalog { towers=List.copyOf(towers); }
    }
    public record Build(String id,int x,int y,int z) { }

    public static byte[] build(Build b) throws IOException {
        checkId(b.id());
        ByteArrayOutputStream out=new ByteArrayOutputStream(96);
        try(DataOutputStream d=new DataOutputStream(out)) {
            d.writeByte(VERSION); d.writeUTF(b.id());d.writeInt(b.x());d.writeInt(b.y());d.writeInt(b.z());
        }
        return out.toByteArray();
    }
    public static Build readBuild(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length<17||bytes.length>90)throw new IOException("Invalid build length");
        try(DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(d.readUnsignedByte()!=VERSION)throw new IOException("Invalid build version");
            String id=d.readUTF();checkId(id);
            Build b=new Build(id,d.readInt(),d.readInt(),d.readInt());
            if(d.available()!=0||b.y() < -64||b.y()>320 || Math.abs((long)b.x())>30_000_000L || Math.abs((long)b.z())>30_000_000L)
                throw new IOException("Invalid build position");
            return b;
        }
    }
    private static void checkId(String id) throws IOException {
        if(id==null||!id.matches("[a-z][a-z0-9_-]{0,63}"))throw new IOException("Invalid tower id");
    }
    public static byte[] catalog(Catalog catalog) throws IOException {
        if(!Set.of("dark","snow").contains(catalog.race()) || !Set.of("red","blue").contains(catalog.team())
                || catalog.towers().size()>64)throw new IOException("Invalid catalog");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(DataOutputStream d=new DataOutputStream(new DeflaterOutputStream(out,new Deflater(Deflater.BEST_SPEED)))) {
            d.writeByte(VERSION);d.writeUTF(catalog.race());d.writeUTF(catalog.team());
            d.writeByte(catalog.towers().size());
            for(Tower t:catalog.towers()) {
                checkId(t.id());d.writeUTF(t.id());d.writeUTF(t.name());d.writeUTF(t.race());
                d.writeUTF(t.ability());d.writeUTF(t.upgradeTo());d.writeShort(t.intervalSeconds());
                if(t.mobs().size()>32)throw new IOException("Too many mob profiles");
                d.writeByte(t.mobs().size());
                for(Mob m:t.mobs()) {
                    d.writeUTF(m.name());d.writeByte(m.count());d.writeInt(m.health10());d.writeInt(m.damage10());
                    d.writeByte(m.criticalChance());d.writeInt(m.criticalDamage10());
                    d.writeFloat((float)m.speed());d.writeFloat((float)m.range());d.writeFloat((float)m.detection());
                    d.writeInt((int)Math.min(Integer.MAX_VALUE,m.cooldownMs()));
                    d.writeUTF(m.damageType());d.writeUTF(m.armorType());d.writeUTF(m.behavior());
                }
            }
        }
        if(out.size()>MAX_CATALOG_BYTES)throw new IOException("Catalog too large");
        return out.toByteArray();
    }
    public static Catalog readCatalog(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_CATALOG_BYTES)throw new IOException("Invalid catalog size");
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        try(InputStream in=new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1) {if(raw.size()+n>MAX_RAW_BYTES)throw new IOException("Catalog decompression limit");raw.write(buf,0,n);}
        }
        try(DataInputStream d=new DataInputStream(new ByteArrayInputStream(raw.toByteArray()))) {
            if(d.readUnsignedByte()!=VERSION)throw new IOException("Unknown catalog version");
            String race=d.readUTF(),team=d.readUTF();
            if(!Set.of("dark","snow").contains(race)||!Set.of("red","blue").contains(team))throw new IOException("Invalid team/race");
            int count=d.readUnsignedByte();if(count>64)throw new IOException("Too many towers");
            List<Tower> towers=new ArrayList<>(count);
            for(int i=0;i<count;i++) {
                String id=d.readUTF();checkId(id);String name=d.readUTF(),r=d.readUTF(),ability=d.readUTF(),upgrade=d.readUTF();
                int interval=d.readUnsignedShort(),mobCount=d.readUnsignedByte();
                if(mobCount>32)throw new IOException("Too many mobs");
                List<Mob> mobs=new ArrayList<>();
                for(int j=0;j<mobCount;j++)mobs.add(new Mob(d.readUTF(),d.readUnsignedByte(),d.readInt(),d.readInt(),
                        d.readUnsignedByte(),d.readInt(),d.readFloat(),d.readFloat(),d.readFloat(),
                        d.readInt(),d.readUTF(),d.readUTF(),d.readUTF()));
                towers.add(new Tower(id,name,r,ability,upgrade,interval,mobs));
            }
            if(d.available()!=0)throw new IOException("Catalog trailing bytes");
            return new Catalog(race,team,towers);
        }
    }
}
