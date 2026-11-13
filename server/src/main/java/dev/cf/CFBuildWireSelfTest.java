package dev.cf;

import dev.cf.protocol.CFBuildWire;
import java.util.*;
import java.io.IOException;

/** Pure protocol regression; no Minecraft runtime. */
public final class CFBuildWireSelfTest {
    private static int checks;
    private static void check(boolean v) {if(!v)throw new AssertionError("check "+checks);checks++;}
    public static void main(String[] args) throws Exception {
        var mobs=List.of(new CFBuildWire.Mob("Зомби",2,850,120,12,190,.107,2.3,14,900,
                "режущий","лёгкая","melee"));
        var towers=List.of(new CFBuildWire.Tower("dark_graveyard_l1","ТЬМА | Кладбище","dark","",
                "dark_graveyard_l2",29,mobs),
                new CFBuildWire.Tower("dark_necromancy","ТЬМА | Некромантия","dark","necromancy","",12,List.of()));
        var c=new CFBuildWire.Catalog("dark","blue",towers);
        var bytes=CFBuildWire.catalog(c);
        check(bytes.length<16_000);
        var decoded=CFBuildWire.readCatalog(bytes);
        check(decoded.race().equals("dark"));check(decoded.team().equals("blue"));
        check(decoded.towers().size()==2);check(decoded.towers().getFirst().mobs().getFirst().health10()==850);
        check(decoded.towers().getFirst().mobs().getFirst().criticalChance()==12);
        check(decoded.towers().get(1).ability().equals("necromancy"));
        byte[] request=CFBuildWire.build(new CFBuildWire.Build("dark_graveyard_l1",12,-52,120));
        var b=CFBuildWire.readBuild(request);
        check(b.id().equals("dark_graveyard_l1"));check(b.x()==12&&b.y()==-52&&b.z()==120);
        try{CFBuildWire.readBuild(new byte[] {0,1,2});throw new AssertionError();}
        catch(IOException expected){checks++;}
        try{CFBuildWire.readCatalog(new byte[] {1,2,3});throw new AssertionError();}
        catch(IOException expected){checks++;}
        try{CFBuildWire.build(new CFBuildWire.Build("../bad",0,0,0));throw new AssertionError();}
        catch(IOException expected){checks++;}
        System.out.println("[КФ МЕНЮ] ПРОЙДЕНО "+checks+" проверок каталога и безопасного запроса строительства.");
    }
}
