package dev.cf;

import java.nio.file.Path;
import java.util.Properties;

/** Read-only startup validation. Does not open the real world or network port. */
public final class CFAbilitiesSelfTest {
    static int checks;
    static void check(boolean v,String why) { checks++;if(!v)throw new AssertionError(why); }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content root required");
        Path root=Path.of(args[0]);
        var content=new CFContentRegistry(root);
        var abilities=new CFAbilitySpecs(root.resolve("mobs"));
        check(content.mobs().size()==33,"8 dark aliases plus 25 faction levels");
        check(content.towers().size()==31,"25 faction levels + 3 snow supports + 3 transferred Dark towers");
        for(String id:new String[]{"grave_zombie","risen","vampire","hell_skeleton","banshee",
                "acolyte","mutated_zombie","risen_arsonist"}){
            check(content.mob(id)!=null,"legacy ability mob profile exists: "+id);
            check(content.tower(id)==null,"legacy tower definition removed: "+id);
        }
        check(content.mob("skeleton")==null && content.mob("zombie")==null,
                "legacy testing types absent");
        check(content.tower("skeleton")==null && content.tower("zombie")==null,
                "legacy test towers absent");
        for(var mob:content.mobs()) {
            boolean expected = java.util.Set.of("risen","banshee","acolyte","risen_arsonist",
                    "dark_old_cottage_l1","dark_old_cottage_l2",
                    "dark_bone_dump_l1","dark_bone_dump_l2",
                    "dark_altar_l1","dark_altar_l2",
                    "snow_snowman_house_l1","snow_snowman_house_l2", "snow_ancient_burials_l1").contains(mob.id());
            check(mob.behavior().equals("ranged_hold")==expected,
                    "declared ranged mob set correct: "+mob.id());
        }
        check(content.mob("risen").entity()==net.minestom.server.entity.EntityType.SKELETON,
                "risen wears skeleton model, not drowned");
        check(abilities.get("grave_zombie").poisonPulses()==3,"grave zombie poison stacks via 3 pulses");
        check(abilities.get("vampire").lifestealChance()==.5,"vampire heals on 50 percent of hits");
        check(abilities.get("banshee").dodge()==.75,"banshee dodge chance");
        check(abilities.get("banshee").splashDamage()==2,"banshee splash damage");
        check(abilities.get("hell_skeleton").waveCooldownMs()==20000,"hell skeleton wave cooldown");
        check(abilities.get("risen").negativeImmune(),"risen negative immunity");
        check(abilities.get("acolyte").summonIntervalMs()==24000,"acolyte summon period");
        for(String id:new String[]{"mutation_altar","toxic_altar","resurrection_altar"}) {
            check(content.tower(id)==null,"removed legacy support tower: "+id);
        }
        var necromancer=content.tower("dark_necromancer_tower");
        check(necromancer!=null && necromancer.ability().equals("necromancy")
                && necromancer.recipe().isEmpty() && necromancer.intervalSeconds()==17,
                "Dark necromancer creates zombies after enemy deaths, not acolytes");
        var deathAltar=content.tower("dark_death_altar");
        check(deathAltar!=null && deathAltar.ability().equals("resurrection")
                && deathAltar.recipe().isEmpty(),"Dark death altar retains resurrection ability");
        var toxicFactory=content.tower("dark_toxic_factory");
        check(toxicFactory!=null && toxicFactory.ability().equals("toxic")
                && toxicFactory.recipe().isEmpty(),"Dark toxic factory retains poison ability");
        var from=new net.minestom.server.coordinate.Pos(0,8,0);
        var toward=new net.minestom.server.coordinate.Pos(1,8,0);
        check(CFArenaAbilities.inForwardWave(from,toward,new net.minestom.server.coordinate.Pos(4,8,0),5,2),"wave reaches forward");
        check(!CFArenaAbilities.inForwardWave(from,toward,new net.minestom.server.coordinate.Pos(-1,8,0),5,2),"wave cannot hit behind");
        check(!CFArenaAbilities.inForwardWave(from,toward,new net.minestom.server.coordinate.Pos(2,8,3),5,2),"wave has finite width");
        // Invalid values must be rejected before a match begins.
        Properties p=new Properties();p.setProperty("ability.dodge.chance","1.2");
        boolean rejected=false;try{CFAbilitySpecs.parse(p);}catch(java.io.IOException e){rejected=true;}
        check(rejected,"reject dodge > 100 percent");
        p.clear();p.setProperty("ability.poison.chance","1");
        p.setProperty("ability.poison.damage","0.6");p.setProperty("ability.poison.duration-ms","1501");
        rejected=false;try{CFAbilitySpecs.parse(p);}catch(java.io.IOException e){rejected=true;}
        check(rejected,"reject poison with mismatched interval/duration");
        System.out.println("[CF ABILITY TEST] PASS "+checks+" checks; no live world opened.");
    }
}
