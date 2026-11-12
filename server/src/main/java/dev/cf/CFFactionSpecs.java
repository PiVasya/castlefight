package dev.cf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Startup-only parsing of faction abilities. All battle callbacks read immutable integer data. */
final class CFFactionSpecs {
    record Spec(int frostChance, int frostMs, int frostDamage10, int frostIntervalMs,
                int iceChance, int iceRadius, int iceDamage10, int iceAttackSlowPct,
                int iceSlowMs, int iceCooldownMs,
                int cleaveDamage10, int cleaveRadius,
                int blizzardDamage10, int blizzardRadius, int blizzardAttackSlowPct,
                int blizzardMoveSlowPct, int blizzardSlowMs, int blizzardPrimarySlowPct,
                int killSummonChance, String killSummonMob,
                int counterPoisonDamage10, int counterPoisonDurationMs,
                int witherDamage10, int witherIntervalMs, int witherDurationMs) { }
    private static final Spec NONE = new Spec(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,"",0,0,0,0,0);
    private final Map<String,Spec> specs;
    CFFactionSpecs(Path root) throws IOException {
        var map=new HashMap<String,Spec>();
        try (var files=Files.list(root)) {
            for(Path file:files.filter(f->f.getFileName().toString().endsWith(".properties")).toList()) {
                Properties p=new Properties();
                try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
                String id=file.getFileName().toString().replace(".properties","");
                Spec s=new Spec(
                  n(p,"ability.frost.chance-percent",0,100),n(p,"ability.frost.duration-ms",0,120000),
                  n(p,"ability.frost.damage10",0,10000),n(p,"ability.frost.interval-ms",0,120000),
                  n(p,"ability.iceburst.chance-percent",0,100),n(p,"ability.iceburst.radius",0,32),
                  n(p,"ability.iceburst.damage10",0,10000),n(p,"ability.iceburst.attack-slow-percent",0,99),
                  n(p,"ability.iceburst.slow-ms",0,120000),n(p,"ability.iceburst.cooldown-ms",0,120000),
                  n(p,"ability.cleave.damage10",0,10000),n(p,"ability.cleave.radius",0,32),
                  n(p,"ability.blizzard.damage10",0,10000),n(p,"ability.blizzard.radius",0,32),
                  n(p,"ability.blizzard.attack-slow-percent",0,99),n(p,"ability.blizzard.move-slow-percent",0,99),
                  n(p,"ability.blizzard.slow-ms",0,120000),n(p,"ability.blizzard.primary-slow-percent",0,99),
                  n(p,"ability.kill-summon.chance-percent",0,100),p.getProperty("ability.kill-summon.mob",""),
                  n(p,"ability.counter-poison.damage10",0,10000),n(p,"ability.counter-poison.duration-ms",0,120000),
                  n(p,"ability.wither.damage10",0,10000),n(p,"ability.wither.interval-ms",0,120000),
                  n(p,"ability.wither.duration-ms",0,120000));
                if(s.frostChance()>0 && s.frostMs()==0 || s.frostDamage10()>0 && s.frostIntervalMs()==0 ||
                   s.iceChance()>0 && (s.iceDamage10()==0 || s.iceRadius()==0) ||
                   s.iceDamage10()>0 && s.iceChance()==0 ||
                   s.cleaveDamage10()>0 && s.cleaveRadius()==0 ||
                   s.blizzardDamage10()>0 && s.blizzardRadius()==0 ||
                   s.killSummonChance()>0 && s.killSummonMob().isBlank() ||
                   s.witherDamage10()>0 && (s.witherIntervalMs()==0 || s.witherDurationMs()==0))
                    throw new IOException("Incomplete faction ability in "+file);
                map.put(id,s);
            }
        }
        specs=Map.copyOf(map);
    }
    Spec get(String id){return specs.getOrDefault(id,NONE);}
    private static int n(Properties p,String key,int min,int max) throws IOException {
        String raw=p.getProperty(key);
        if(raw==null)return 0;
        try {
            int n=Integer.parseInt(raw.trim());if(n<min||n>max)throw new NumberFormatException();return n;
        } catch(NumberFormatException e){throw new IOException("Invalid integer "+key+"="+raw,e);}
    }
}
