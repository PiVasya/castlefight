package dev.cf;

import java.util.*;
import java.util.regex.*;

/**
 * Russian-facing tower presentation. Internal IDs are never translated, so
 * old builds, saved diagnostics, commands and upgrade links stay compatible.
 * No per-tick code: all functions are used on kit/command/interact only.
 */
final class CFTowerPresentation {
    private static final Pattern LEVEL_SUFFIX=Pattern.compile("_l([1-9][0-9]*)$");
    private static final Pattern NAME_SUFFIX=Pattern.compile("\\s*\\(ур\\.\\s*[1-9][0-9]*\\)$");

    private CFTowerPresentation(){}

    static boolean visibleInPalette(CFContentRegistry.Tower tower,String palette) {
        if(tower.upgradeOnly())return false;
        boolean dark=tower.id().startsWith("dark_");
        boolean snow=tower.id().startsWith("snow_");
        return switch(palette) {
            case "dark" -> dark;
            case "snow" -> snow;
            case "all" -> dark||snow;
            default -> false;
        };
    }
    static List<CFContentRegistry.Tower> palette(CFContentRegistry content,String palette) {
        return content.towers().stream()
                .filter(t -> visibleInPalette(t,palette))
                .sorted(Comparator
                        .comparingInt((CFContentRegistry.Tower t)->t.id().startsWith("dark_")?0:t.id().startsWith("snow_")?1:2)
                        .thenComparing(CFTowerPresentation::baseName,String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
    static String paletteName(String palette) {
        return switch(palette) {
            case "dark" -> "ТЬМА";
            case "snow" -> "СНЕГИ";
            default -> "ТЬМА + СНЕГИ";
        };
    }
    static String faction(CFContentRegistry.Tower tower) {
        return tower.id().startsWith("dark_")?"ТЬМА":
                tower.id().startsWith("snow_")?"СНЕГИ":"НЕИЗВЕСТНАЯ РАСА";
    }
    static String teamName(String team) {return team.equals("red")?"красная":"синяя";}
    static String baseName(CFContentRegistry.Tower t) {
        return NAME_SUFFIX.matcher(t.name()).replaceFirst("");
    }
    static int tier(CFContentRegistry.Tower tower) {
        Matcher m=LEVEL_SUFFIX.matcher(tower.id());
        return m.find()?Integer.parseInt(m.group(1)):0;
    }
    static int maxTier(CFContentRegistry.Tower tower,CFContentRegistry registry) {
        if(tier(tower)==0)return 0;
        CFContentRegistry.Tower at=tower;
        int last=tier(at);
        Set<String> visited=new HashSet<>();
        while(at!=null&&!at.upgradeTo().isEmpty()&&visited.add(at.id())) {
            at=registry.tower(at.upgradeTo());
            if(at!=null)last=Math.max(last,tier(at));
        }
        return last;
    }
    static String roman(int level) {
        return switch(level) {
            case 1->"I";case 2->"II";case 3->"III";
            case 4->"IV";case 5->"V";default->Integer.toString(level);
        };
    }
    static String stage(CFContentRegistry.Tower tower,CFContentRegistry registry) {
        int tier=tier(tower);
        if(tier==0)return "без уровней";
        return "уровень "+tier+" из "+maxTier(tower,registry);
    }
    static String shortStage(CFContentRegistry.Tower tower,CFContentRegistry registry) {
        int tier=tier(tower);
        if(tier==0)return tower.ability().isEmpty()?"без прокачки":"особая";
        return "ур. "+tier+"/"+maxTier(tower,registry);
    }
    static String title(CFContentRegistry.Tower tower,CFContentRegistry registry) {
        return faction(tower)+" | "+baseName(tower)+" | "+shortStage(tower,registry);
    }
    /** CF core stores tenths; show real game HP/damage without trailing .0. */
    static String scaled10(int n) {
        return n%10==0 ? Integer.toString(n/10) : String.format(java.util.Locale.ROOT,"%.1f",n/10.0);
    }
    static String role(CFContentRegistry.Mob mob) {
        return mob.behavior().equals("ranged_hold")?"дальний бой":"ближний бой";
    }
    static String summon(CFContentRegistry.Tower t,CFContentRegistry c) {
        if(!t.ability().isEmpty())return "Эффект: "+ability(t.ability());
        List<String> parts=new ArrayList<>();
        for(var s:t.recipe()) {
            var mob=c.mob(s.mob());
            parts.add((mob==null?s.mob():mob.name())+(s.count()==1?"":" x"+s.count()));
        }
        return "Призывает: "+String.join(", ",parts);
    }
    static String combat(CFContentRegistry.Tower t,CFContentRegistry c) {
        if(!t.ability().isEmpty())return "Особая постройка без обычного моба";
        if(t.recipe().size()!=1)return "Волна: "+t.recipe().size()+" типа существ";
        var mob=c.mob(t.recipe().getFirst().mob());
        if(mob==null)return "Неизвестный профиль существа";
        return "HP "+scaled10(mob.health10())+" | урон "+scaled10(mob.damage10())+
                " | "+role(mob)+" | "+mob.combat().type.label+" / "+mob.combat().armor.label;
    }
    static String next(CFContentRegistry.Tower t,CFContentRegistry c) {
        if(t.upgradeTo().isEmpty())return tier(t)==0?
                "Улучшений нет (без уровней)":"Следующий уровень: нет (максимум)";
        var next=c.tower(t.upgradeTo());
        if(next==null)return "Следующий уровень: ошибка профиля";
        return "Следующий: ур. "+tier(next)+"/"+maxTier(next,c)+
                " — "+summon(next,c)+"; "+combat(next,c);
    }
    static String ability(String id) {
        return switch(id){
            case "mutation" -> "мутация";
            case "necromancy" -> "некромантия: зомби после гибели врага";
            case "toxic" -> "отравление";
            case "resurrection" -> "воскрешение";
            case "snow_cannon" -> "ледяной луч";
            case "snow_shield" -> "ледяной щит";
            case "snow_obelisk" -> "заморозка вражеской постройки";
            default -> id;
        };
    }
    static String inspect(CFContentRegistry.Tower tower,CFContentRegistry c,long placedId,String team,int lane) {
        StringBuilder s=new StringBuilder();
        s.append("[CF БАШНЯ] #").append(placedId).append(" | ").append(title(tower,c)).append("\n");
        s.append("[CF БАШНЯ] Команда: ").append(teamName(team)).append(" | линия: ").append(lane).append("\n");
        s.append("[CF БАШНЯ] ").append(summon(tower,c)).append("\n");
        s.append("[CF БАШНЯ] ").append(combat(tower,c)).append("\n");
        s.append("[CF БАШНЯ] Период: ").append(tower.intervalSeconds()).append(" с\n");
        s.append("[CF БАШНЯ] ").append(next(tower,c));
        return s.toString();
    }
    static String tree(CFContentRegistry.Tower root,CFContentRegistry c) {
        StringBuilder s=new StringBuilder(faction(root)+" | "+baseName(root)+": ");
        var current=root;
        Set<String> seen=new HashSet<>();
        while(current!=null&&seen.add(current.id())) {
            if(seen.size()>1)s.append(" -> ");
            s.append(tier(current)==0?"без улучшений":Integer.toString(tier(current)));
            s.append(" [").append(summon(current,c).replace("Призывает: ","")).append("]");
            current=current.upgradeTo().isEmpty()?null:c.tower(current.upgradeTo());
        }
        return s.toString();
    }
}
