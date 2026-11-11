package dev.cf;

import java.nio.file.Path;
import java.util.*;
import net.minestom.server.instance.block.Block;

/** Regression guard for Russian UI and a readable, strictly chained tower tree. */
public final class CF269TowerUiSelfTest {
    private static int checks;
    private static void ok(boolean good,String reason) {
        checks++;
        if(!good)throw new AssertionError(reason);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Content root required");
        CFContentRegistry c=new CFContentRegistry(Path.of(args[0]));
        var all=CFTowerPresentation.palette(c,"all");
        var dark=CFTowerPresentation.palette(c,"dark");
        var snow=CFTowerPresentation.palette(c,"snow");
        ok(all.size()==dark.size()+snow.size(),"all combines new factions");
        ok(CFTowerPresentation.palette(c,"legacy").isEmpty(),"no legacy palette");
        for(String id:new String[]{"dark_necromancer_tower","dark_death_altar","dark_toxic_factory"})
            ok(c.tower(id)!=null,"migrated into Dark: "+id);
        for(String id:new String[]{"acolyte","banshee","grave_zombie","hell_skeleton",
                "mutated_zombie","risen","risen_arsonist","vampire",
                "mutation_altar","resurrection_altar","toxic_altar"})
            ok(c.tower(id)==null,"legacy tower removed: "+id);
        ok(dark.size()==9,"9 Dark starting towers, including three migrated");
        ok(snow.size()==9,"9 Snow starting buildings");
        ok(all.size()==18,"only Dark and Snow starting buildings");
        for(var tower:c.towers()) {
            String title=CFTowerPresentation.title(tower,c);
            ok(!title.isBlank(),"title "+tower.id());
            ok(!tower.name().matches(".*[A-Za-z].*"),"Russian tower name "+tower.id()+": "+tower.name());
            ok(title.startsWith(CFTowerPresentation.faction(tower)),"faction label "+tower.id());
            ok(!CFTowerPresentation.next(tower,c).isBlank(),"next "+tower.id());
            if(tower.id().matches(".*_l2$"))
                ok(tower.head()==Block.GOLD_BLOCK,"gold stage II "+tower.id());
            if(tower.id().matches(".*_l3$"))
                ok(tower.head()==Block.NETHERITE_BLOCK,"netherite stage III "+tower.id());
            if(tower.upgradeOnly())
                ok(!CFTowerPresentation.visibleInPalette(tower,"all"),"upgrade-only not buildable");
        }
        var grave1=c.tower("dark_graveyard_l1");
        var grave2=c.tower("dark_graveyard_l2");
        var grave3=c.tower("dark_graveyard_l3");
        ok(CFTowerPresentation.shortStage(grave1,c).equals("ур. 1/3"),"1/3");
        ok(CFTowerPresentation.shortStage(grave2,c).equals("ур. 2/3"),"2/3");
        ok(CFTowerPresentation.shortStage(grave3,c).equals("ур. 3/3"),"3/3");
        ok(CFTowerPresentation.next(grave1,c).contains("Мутировавший зомби"),"upgrade preview stage II");
        ok(CFTowerPresentation.next(grave2,c).contains("Зомби-гигант"),"upgrade preview stage III");
        ok(CFTowerPresentation.next(grave3,c).contains("максимум"),"max level");
        ok(CFTowerPresentation.inspect(grave2,c,10,"blue",1).contains("ур. 2/3"),"inspector stage II");
        ok(CFTowerPresentation.scaled10(5).equals("5"),"int10 displayed directly");
        ok(CFTowerPresentation.scaled10(120).equals("120"),"HP displayed as whole int10");
        ok(CFTowerPresentation.scaled10(28).equals("28"),"damage displayed as whole int10");
        ok(CFTowerPresentation.role(c.mob("dark_bone_dump_l2")).equals("дальний бой"),"arsonist ranged");
        for(var tower:all) {
            ok(!tower.upgradeOnly(),"palette only first tower in family");
            ok(CFTowerPresentation.tree(tower,c).contains(CFTowerPresentation.baseName(tower)),
                    "tree shows tower");
        }
        System.out.println("[CF TOWER UI RU] PASS "+checks+" checks: palette, levels, inspector, localization and caps");
    }
}
