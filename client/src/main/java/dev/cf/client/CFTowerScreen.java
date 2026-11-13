package dev.cf.client;

import dev.cf.protocol.CFBuildWire;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Locale;

/** Local strategic tower dossier. Never asks the server for item stats. */
public final class CFTowerScreen extends Screen {
    private static final int BG=0xF2101822, PANEL=0xE91C2934, EDGE=0xFF3B5262;
    private static final int ACCENT=0xFF55D4C2, WHITE=0xFFE8F1F5, MUTED=0xFF9BABB9;
    private final CFBuildWire.Tower original;
    private CFBuildWire.Tower shown;
    private final Screen previous;
    private int left,top,w,h,mobIndex;

    public CFTowerScreen(CFBuildWire.Tower tower,Screen inventoryScreen) {
        super(Component.literal("Castle Fight · Башня"));
        this.original=tower;this.shown=tower;this.previous=inventoryScreen;
    }
    private void navigate(String id) {
        var next=CFBuildClient.tower(id);
        if(next!=null){shown=next;mobIndex=0;this.rebuildWidgets();}
    }
    @Override protected void init() {
        w=Math.min(500,Math.max(280,width-20));
        h=Math.min(294,Math.max(212,height-20));
        left=(width-w)/2;top=(height-h)/2;
        final int btnY=top+h-29;
        if(shown.mobs().size()>1) {
            addRenderableWidget(Button.builder(Component.literal("<"),b->{
                mobIndex=(mobIndex+shown.mobs().size()-1)%shown.mobs().size();
            }).bounds(left+12,top+65,18,18).build());
            addRenderableWidget(Button.builder(Component.literal(">"),b->{
                mobIndex=(mobIndex+1)%shown.mobs().size();
            }).bounds(left+Math.min(500,Math.max(280,width-20))/2-20,top+65,18,18).build());
        }
        if(!shown.upgradeTo().isEmpty()&&CFBuildClient.tower(shown.upgradeTo())!=null)
            addRenderableWidget(Button.builder(Component.literal("След. уровень >"),b->navigate(shown.upgradeTo()))
                    .bounds(left+12,btnY,112,20).build());
        if(!shown.id().equals(original.id()))
            addRenderableWidget(Button.builder(Component.literal("< Начальная"),b->navigate(original.id()))
                    .bounds(left+12,btnY,112,20).build());
        addRenderableWidget(Button.builder(Component.literal("ЗАКРЫТЬ"),b->onClose())
                .bounds(left+w-94,btnY,82,20).build());
    }
    private void label(GuiGraphicsExtractor g,String value,int x,int y,int color) {
        g.text(font,value,x,y,color,false);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        super.extractBackground(g,mouseX,mouseY,delta);
        g.fill(left-2,top-2,left+w+2,top+h+2,EDGE);
        g.fill(left,top,left+w,top+h,BG);
        g.fill(left,top,left+w,top+37,0xFF1A3541);
        g.fill(left,top+36,left+w,top+38,ACCENT);
        int innerTop=top+45,half=(w-34)/2,contentBottom=top+h-39;
        g.fill(left+10,innerTop,left+14+half,contentBottom,PANEL);
        g.fill(left+20+half,innerTop,left+w-10,contentBottom,PANEL);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        super.extractRenderState(g,mouseX,mouseY,delta);
        label(g,shown.race().equals("dark")?"ТЬМА  •  БАШНЯ":"СНЕГИ  •  БАШНЯ",
                left+13,top+8,ACCENT);
        label(g,trim(shown.name(),Math.max(14,(w-28)/6)),left+13,top+21,WHITE);
        int half=(w-34)/2;
        int innerTop=top+45;
        int contentBottom=top+h-39;
        int lx=left+18,rx=left+half+28;
        label(g,"СОЗДАНИЕ АРМИИ",lx,innerTop+8,ACCENT);
        int yy=innerTop+29;
        if(!shown.ability().isEmpty()) {
            label(g,"ПОДДЕРЖКА",lx,yy,0xFFFFCA79); yy+=15;
            label(g,ability(shown.ability()),lx,yy,WHITE);yy+=18;
            label(g,"Не создаёт обычных мобов",lx,yy,MUTED);
            label(g,"Срабатывает по своим правилам",lx,yy+14,MUTED);
        } else if(!shown.mobs().isEmpty()) {
            var mob=shown.mobs().get(Math.min(mobIndex,shown.mobs().size()-1));
            label(g,"Профиль "+(mobIndex+1)+" из "+shown.mobs().size(),lx,innerTop+22,MUTED);
            label(g,trim(mob.name(),25),lx,yy+7,WHITE);yy+=26;
            label(g,"Количество в волне: "+mob.count(),lx,yy,MUTED);yy+=17;
            label(g,"Здоровье: "+scaled10(mob.health10()),lx,yy,0xFFA7E7C0);yy+=16;
            label(g,"Урон: "+scaled10(mob.damage10()),lx,yy,0xFFFFC68E);yy+=16;
            label(g,"Тип урона: "+mob.damageType(),lx,yy,MUTED);yy+=16;
            label(g,"Броня: "+mob.armorType(),lx,yy,MUTED);yy+=16;
            label(g,mob.behavior().equals("ranged_hold")?"Тип: дальний бой":"Тип: ближний бой",lx,yy,MUTED);
        }
        int ry=innerTop+8;
        label(g,"ХАРАКТЕРИСТИКИ",rx,ry,ACCENT);ry+=18;
        label(g,"Цикл: каждые "+shown.intervalSeconds()+" сек.",rx,ry,WHITE);ry+=15;
        label(g,"Команда: "+(CFBuildClient.team().equals("red")?"Красные":"Синие"),rx,ry,MUTED);ry+=15;
        if(!shown.mobs().isEmpty()) {
            var mob=shown.mobs().get(Math.min(mobIndex,shown.mobs().size()-1));
            label(g,"Скорость: "+fmt(mob.speed()),rx,ry,WHITE);ry+=13;
            label(g,"Дальность: "+fmt(mob.range()),rx,ry,WHITE);ry+=13;
            label(g,"Поиск: "+fmt(mob.detection()),rx,ry,MUTED);ry+=13;
            label(g,"Атака: "+fmt(mob.cooldownMs()/1000.0)+" с",rx,ry,MUTED);ry+=13;
            if(mob.criticalChance()>0) {
                label(g,"Крит: "+mob.criticalChance()+"% / "+scaled10(mob.criticalDamage10()),rx,ry,0xFFFFCB7B);ry+=13;
            }
        }
        label(g,"Стоимость: пока без экономики",rx,contentBottom-29,MUTED);
        label(g,shown.upgradeTo().isEmpty()?"Улучшения: максимум":"Есть следующий уровень",
                rx,contentBottom-14,0xFF9EE1D0);
        String state="Для постройки: возьми предмет в руку и ПКМ по центру зоны 3×3";
        label(g,trim(state,Math.max(24,(w-22)/6)),left+12,top+h-45,0xFF80D99A);
    }
    private static String scaled10(int amount) {
        return amount%10==0 ? Integer.toString(amount/10) : String.format(Locale.ROOT,"%.1f",amount/10.0);
    }
    private static String fmt(double n) {return String.format(Locale.ROOT,"%.2f",n);}
    private static String trim(String s,int limit) {
        return s.length()<=limit?s:s.substring(0,Math.max(1,limit-1))+"…";
    }
    private static String ability(String id) {
        return switch(id) {
            case "necromancy"->"Некромантия";case "mutation"->"Мутация";
            case "toxic"->"Отравление";case "resurrection"->"Воскрешение";
            case "snow_cannon"->"Ледяная пушка";case "snow_shield"->"Ледяной щит";
            case "snow_obelisk"->"Морозный обелиск";default->id;
        };
    }
    @Override public void onClose(){minecraft.gui.setScreen(minecraft.player==null ? null : previous);}
}
