package dev.cf;

import java.nio.file.Path;
import java.util.Set;

/** Fast startup-only regression: exact original HP/damage ×10, tiers, roles and ability data. */
public final class CF268FactionContentSelfTest {
    private static int checks;
    private static void ok(boolean condition,String reason) {
        checks++;if(!condition)throw new AssertionError(reason);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content root required");
        var c=new CFContentRegistry(Path.of(args[0]));
        var extras=new CFFactionSpecs(Path.of(args[0],"mobs"));
        ok(c.mobs().size()==33,"33 mobs, 25 faction + 8 internal ability/compatibility profiles");
        ok(c.towers().size()==31,"31 towers, 28 faction + 3 transferred Dark");
        String[] names={"dark_graveyard_l1","dark_graveyard_l2","dark_graveyard_l3",
                "dark_vampire_coffin_l1","dark_vampire_coffin_l2",
                "dark_hell_graveyard_l1","dark_hell_graveyard_l2",
                "dark_old_cottage_l1","dark_old_cottage_l2",
                "dark_bone_dump_l1","dark_bone_dump_l2",
                "dark_altar_l1","dark_altar_l2",
                "snow_wolf_cave_l1","snow_wolf_cave_l2","snow_wolf_cave_l3",
                "snow_bear_cave_l1","snow_bear_cave_l2",
                "snow_snowman_house_l1","snow_snowman_house_l2",
                "snow_offering_temple_l1","snow_offering_temple_l2",
                "snow_clan_hall_l1","snow_clan_hall_l2","snow_ancient_burials_l1"};
        int[] hp={60,120,2500,140,280,170,320,70,110,100,140,150,260,
                60,120,190,160,340,70,130,90,200,220,370,700};
        int[] damage={10,20,160,20,30,30,60,30,64,20,40,35,70,
                18,28,46,20,40,5,10,40,70,50,90,200};
        Set<String> ranged=Set.of("dark_old_cottage_l1","dark_old_cottage_l2",
                "dark_bone_dump_l1","dark_bone_dump_l2","dark_altar_l1","dark_altar_l2",
                "snow_snowman_house_l1","snow_snowman_house_l2","snow_ancient_burials_l1");
        for(int i=0;i<names.length;i++) {
            var mob=c.mob(names[i]);var tower=c.tower(names[i]);
            ok(mob!=null && tower!=null,"mob + tower="+names[i]);
            ok(mob.health10()==hp[i] && mob.damage10()==damage[i],"exact HP/damage10 "+names[i]);
            ok(mob.behavior().equals(ranged.contains(names[i])?"ranged_hold":"melee"),"combat role="+names[i]);
            ok(tower.recipe().size()==1 && tower.recipe().getFirst().mob().equals(names[i]),"spawn recipe "+names[i]);
        }
        int[] bites={23,44,92};
        for(int i=0;i<3;i++) {
            var mob=c.mob("snow_wolf_cave_l"+(i+1));
            ok(mob.critChancePercent()==25,"wolf crit chance");
            ok(mob.critDamage10()==bites[i],"wolf precomputed bite damage, no combat multiplication");
        }
        for(String id:new String[]{"dark_necromancer_tower","dark_death_altar","dark_toxic_factory"})
            ok(c.tower(id)!=null,"retained Dark building "+id);
        var necromancer=c.tower("dark_necromancer_tower");
        ok(necromancer.ability().equals("necromancy") && necromancer.recipe().isEmpty()
                && necromancer.intervalSeconds()==17,
                "necromancer raises a grave zombie after enemy death, not a periodic servant");
        ok(c.mob("grave_zombie")!=null,"necromancy can resolve its grave zombie profile");
        ok(c.tower("dark_death_altar").ability().equals("resurrection"),"death altar keeps resurrection");
        ok(c.tower("dark_toxic_factory").ability().equals("toxic"),"toxic factory keeps poison");
        for(String id:ListOfSnowSupports.IDS) {
            var tower=c.tower(id);
            ok(tower!=null && tower.recipe().isEmpty() && !tower.ability().isEmpty(),"snow support: "+id);
        }
        ok(extras.get("snow_snowman_house_l1").frostChance()==3,"snow frost 3%");
        ok(extras.get("snow_snowman_house_l2").frostChance()==4,"king frost 4%");
        ok(extras.get("snow_clan_hall_l2").cleaveDamage10()==30,"yarl AoE 3.0 HP");
        ok(extras.get("dark_vampire_coffin_l2").killSummonChance()==60,"lord summon 60%");
        ok(c.mob("risen_arsonist").behavior().equals("ranged_hold"),"legacy arsonist is ranged");
        ok(c.mob("risen_arsonist").health10()==140 && c.mob("risen_arsonist").damage10()==40,
                "legacy arsonist exact stats");
        ok(c.mob("mutated_zombie").health10()==120 && c.mob("mutated_zombie").damage10()==20,
                "legacy mutant corrected");
        for(var t:c.towers())if(!t.upgradeTo().isEmpty()) {
            ok(c.tower(t.upgradeTo())!=null,"upgrade resolves: "+t.id());
            ok(!c.tower(t.upgradeTo()).recipe().isEmpty(),"upgrade spawns mob: "+t.id());
        }
        System.out.println("[CF FACTIONS] PASS "+checks+" assertions: integer stats, 25 units, 15 branches, 3 supports");
    }
    private static final class ListOfSnowSupports {
        static final String[] IDS={"snow_winter_cannon","snow_ice_altar","snow_crystal_obelisk"};
    }
}
