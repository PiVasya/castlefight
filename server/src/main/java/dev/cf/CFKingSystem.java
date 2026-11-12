package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.Material;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Two permanent throne guards, independent of tower production and /cf clear. */
final class CFKingSystem {
    // Units use int10. King = 4000 real HP and 10 real melee damage.
    static final CFContentRegistry.Mob KING=new CFContentRegistry.Mob(
            "castle_king","Король",EntityType.PIGLIN_BRUTE,"melee",
            40000,.025,100,100,0,2.2,6,2500,Material.GOLDEN_SWORD,
            new CFDamageTable.Profile(CFDamageTable.Type.SLASHING,CFDamageTable.Armor.NONE,100,100));

    private final InstanceContainer world;
    private final CFGameArena arena;
    private final BooleanSupplier healthBars;
    private final Map<String,CFUnit> kings=new ConcurrentHashMap<>();

    CFKingSystem(InstanceContainer world,CFGameArena arena,BooleanSupplier healthBars) {
        this.world=world;
        this.arena=arena;
        this.healthBars=healthBars;
    }

    void start() {
        arena.routesReadyFuture().whenComplete((ignored,error)->world.scheduler().scheduleNextTick(()->{
            if(error!=null) {
                System.err.println("[КФ КОРОЛЬ] Короли не появились: карта не прошла проверку: "+error);
                return;
            }
            for(String team:List.of("blue","red"))spawn(team);
        }));
    }

    private void spawn(String team) {
        if(kings.containsKey(team))return;
        Pos throne=arena.castle(team);
        if(throne==null)throw new IllegalStateException("Не найден трон команды "+team);
        CFUnit king=new CFUnit(team,KING,throne,throne,true);
        king.setHealthBarVisible(healthBars.getAsBoolean());
        kings.put(team,king);
        king.setInstance(world,throne).whenComplete((ignored,error)->{
            if(error!=null) {
                kings.remove(team,king);
                System.err.println("[КФ КОРОЛЬ] Не удалось поставить короля "+team+" на "+throne+": "+error);
            } else {
                king.markSpawnCompleted();
                System.out.println("[КФ КОРОЛЬ] Король "+team+" на троне "+throne+
                        ", HP 4000, урон 10, медленный ближний бой.");
            }
        });
    }

    CFUnit king(String team){return kings.get(team);}
    String status() {
        var out=new StringBuilder("[КФ КОРОЛЬ] ");
        for(String team:List.of("blue","red")) {
            CFUnit king=kings.get(team);
            out.append(team).append('=');
            if (king == null) out.append("не появился");
            else if (!king.combatAlive()) out.append("пал");
            else {
                int hp = (king.health10() + 9) / 10; // readable whole HP, int10 remains authoritative
                out.append("жив (").append(hp).append("/4000 HP, позиция ")
                        .append(CFTrace.xyz(king.getPosition()))
                        .append(", трон ").append(CFTrace.xyz(king.kingThrone()))
                        .append(", навигация ").append(king.getNavigator().getState())
                        .append(')');
            }
            out.append(' ');
        }
        return out.toString();
    }
}
