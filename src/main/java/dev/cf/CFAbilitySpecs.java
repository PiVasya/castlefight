package dev.cf;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Startup-only ability parsing. Every mob instance shares its immutable specification. */
final class CFAbilitySpecs {
    record Spec(double dodge, boolean negativeImmune,
                double poisonChance,int poisonDamage10,long poisonIntervalMs,int poisonPulses,
                double lifestealChance,int lifestealAmount10,
                double waveChance,double waveRadius,double waveSlowMultiplier,long waveDurationMs,long waveCooldownMs,
                double splashChance,int splashDamage10,double splashRadius,long splashCooldownMs,
                String summonMob,long summonIntervalMs,String attackType,String armorType, Dot burn) {
        float poisonDamage(){return poisonDamage10/10f;} // Float only at Minestom boundary.
        float lifestealAmount(){return lifestealAmount10/10f;}
        float splashDamage(){return splashDamage10/10f;}
    }
    record Dot(double chance, int damage10, long intervalMs, int pulses) {
        float damage(){return damage10/10f;} // Minestom bridge
    }
    static final Dot NO_DOT = new Dot(0,0,1000,0);
    static final Spec NONE=new Spec(0,false,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,"",0,"generic","none",NO_DOT);
    private final Map<String,Spec> types;
    CFAbilitySpecs(Path mobsDir)throws IOException {
        Map<String,Spec> found=new HashMap<>();
        try(var files=Files.list(mobsDir)){
            for(Path file:files.filter(p->p.toString().endsWith(".properties")).toList()){
                Properties p=new Properties();
                try(Reader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);}
                String name=file.getFileName().toString().replaceFirst("\\.properties$","");
                found.put(name,parse(p));
            }
        }
        for(var entry:found.entrySet()){
            String summon=entry.getValue().summonMob();
            if(!summon.isEmpty()&&!found.containsKey(summon))
                throw new IOException("Unknown summon mob "+summon+" in "+entry.getKey());
        }
        types=Map.copyOf(found);
    }
    Spec get(String type){return types.getOrDefault(type,NONE);}
    static Spec parse(Properties p) throws IOException {
        double dodge=number(p,"ability.dodge.chance",0,0,1);
        String immune=p.getProperty("ability.negative-immune","false").trim();
        if(!immune.equals("true")&&!immune.equals("false"))throw new IOException("Invalid ability.negative-immune");
        double poisonChance=number(p,"ability.poison.chance",0,0,1);
        int poisonDamage=tenth(p,"ability.poison.damage",0,0,10000);
        long poisonInterval=(long)number(p,"ability.poison.interval-ms",1000,50,600000);
        long poisonDuration=(long)number(p,"ability.poison.duration-ms",0,0,600000);
        int pulses=0;
        if(poisonChance>0){
            if(poisonDamage<=0 || poisonDuration<poisonInterval || poisonDuration%poisonInterval!=0)
                throw new IOException("Poison needs damage>=0.001 and duration divisible by interval");
            pulses=(int)(poisonDuration/poisonInterval);
        }
        double burnChance=number(p,"ability.burn.chance",0,0,1);
        int burnDamage=tenth(p,"ability.burn.damage",0,0,10000);
        long burnInterval=(long)number(p,"ability.burn.interval-ms",1000,50,600000);
        long burnDuration=(long)number(p,"ability.burn.duration-ms",0,0,600000);
        int burnPulses=0;
        if(burnChance>0) {
            if(burnDamage <= 0 || burnDuration < burnInterval || burnDuration % burnInterval != 0)
                throw new IOException("Burn needs damage>=0.001 and duration divisible by interval");
            burnPulses=(int)(burnDuration/burnInterval);
        }
        double lifesteal=number(p,"ability.lifesteal.chance",0,0,1);
        int heal=tenth(p,"ability.lifesteal.heal",10,1,100000);
        double wave=number(p,"ability.wave.chance",0,0,1);
        double waveRadius=number(p,"ability.wave.radius",5,.1,32);
        double waveFactor=number(p,"ability.wave.slow-multiplier",.5,.01,1);
        long waveDuration=(long)number(p,"ability.wave.duration-ms",7000,50,600000);
        long waveCooldown=(long)number(p,"ability.wave.cooldown-ms",20000,0,600000);
        double splash=number(p,"ability.splash.chance",0,0,1);
        int splashDamage=tenth(p,"ability.splash.damage",20,1,10000);
        double splashRadius=number(p,"ability.splash.radius",5,.1,32);
        long splashCooldown=(long)number(p,"ability.splash.cooldown-ms",15000,0,600000);
        String summon=p.getProperty("ability.summon.mob","").trim();
        long summonInterval=(long)number(p,"ability.summon.interval-ms",24000,50,600000);
        String damageType=p.getProperty("damage.type","generic").trim();
        String armorType=p.getProperty("armor.type","none").trim();
        if(!java.util.Set.of("generic","chaos","piercing","slashing","magic","siege").contains(damageType))
            throw new IOException("Unknown damage.type: "+damageType);
        if(!java.util.Set.of("none","light","medium","heavy","fortified").contains(armorType))
            throw new IOException("Unknown armor.type: "+armorType);
        return new Spec(dodge,Boolean.parseBoolean(immune),poisonChance,poisonDamage,poisonInterval,pulses,
                lifesteal,heal,wave,waveRadius,waveFactor,waveDuration,waveCooldown,
                splash,splashDamage,splashRadius,splashCooldown,summon,summonInterval,damageType,armorType,
                new Dot(burnChance,burnDamage,burnInterval,burnPulses));
    }
    private static int tenth(Properties p,String key,int def,int min,int max)throws IOException {
        String raw=p.getProperty(key);
        if(raw==null)return def;
        try {
            int scaled=new java.math.BigDecimal(raw.trim()).movePointRight(1).intValueExact();
            if(scaled<min||scaled>max)throw new ArithmeticException("range");
            return scaled;
        } catch(NumberFormatException|ArithmeticException e) {
            throw new IOException("Invalid integer-tenths "+key+"="+raw,e);
        }
    }
    private static double number(Properties p,String key,double def,double min,double max)throws IOException{
        String raw=p.getProperty(key);
        if(raw==null)return def;
        try{
            double value=Double.parseDouble(raw.trim());
            if(!Double.isFinite(value)||value<min||value>max)throw new NumberFormatException();
            return value;
        }catch(NumberFormatException ex){throw new IOException("Invalid "+key+"="+raw+" ("+min+".."+max+")",ex);}
    }
}
