package dev.cf.client.render;

import dev.cf.client.CFMobHover;
import dev.cf.protocol.CFMobStatsWire;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import java.util.Locale;

/**
 * Короткая панель характеристик при наведении. HP здесь только полоска:
 * НИКАКИХ HP/MAX HP, цифр и процентов возле полоски.
 */
public final class CFMobHud {
    private CFMobHud() { }
    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("castlefight", "mob_hover_stats"), (graphics, delta) -> draw(graphics));
    }
    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        CFMobStatsWire.Stats s = CFMobHover.current();
        if (s == null || minecraft.player == null || minecraft.gui.hud.isHidden()) return;
        final int width = 184, left = 12, top = 30;
        final int rows = s.critChance() == 0 ? 5 : 6;
        final int height = 40 + rows * 12;
        graphics.fill(left, top, left + width, top + height, 0xB510151D);
        graphics.text(minecraft.font, s.name(), left + 9, top + 7, 0xFFFFFFFF, true);
        String subtitle = ("blue".equals(s.team()) ? "\u0421\u0438\u043d\u0438\u0435" : "\u041a\u0440\u0430\u0441\u043d\u044b\u0435") + " \u00b7 " + (s.lane() == 0 ? "\u0437\u0430\u043c\u043e\u043a" : "\u043f\u0443\u0442\u044c " + s.lane());
        graphics.text(minecraft.font, subtitle, left + 9, top + 19, 0xFFAAAAAA, false);
        // A plain HP bar: no label, numbers, percentage, icons or segmented cells.
        final int bx = left + 9, by = top + 31, barW = width - 18, barH = 5;
        int fill = (int) Math.round(barW * s.healthRatio());
        graphics.fill(bx, by, bx + barW, by + barH, 0xFF303840);
        int barColor = s.healthRatio() > 0.5 ? 0xFF57C66A :
                s.healthRatio() > 0.25 ? 0xFFE0B947 : 0xFFE35E58;
        if (fill > 0) graphics.fill(bx, by, bx + fill, by + barH, barColor);
        int y = top + 43;
        text(graphics, minecraft, "Урон: " + value10(s.damage10()) + " · " + damageType(s.damageType()), left+9, y);
        text(graphics, minecraft, "Броня: " + armorType(s.armorType()), left+9, y+=12);
        text(graphics, minecraft, "Скорость: " + String.format(Locale.ROOT, "%.3f", s.speed()), left+9, y+=12);
        text(graphics, minecraft, "Дальность: " + String.format(Locale.ROOT, "%.1f", s.attackRange()), left+9, y+=12);
        text(graphics, minecraft, "Атака: " + String.format(Locale.ROOT, "%.2f с", s.cooldownMs()/1000.0), left+9, y+=12);
        if (s.critChance() > 0)
            text(graphics, minecraft, "Крит: " + s.critChance()+"% · " + value10(s.critDamage10()), left+9, y+12);
    }
    private static void text(GuiGraphicsExtractor graphics, Minecraft client,String text,int x,int y) {
        graphics.text(client.font,text,x,y,0xFFDEE2E8,false);
    }
    private static String value10(int amount10) {
        return amount10 % 10 == 0 ? Integer.toString(amount10 / 10) :
                (amount10 / 10) + "." + (amount10 % 10);
    }
    private static String damageType(String id) {
        return switch(id) {
            case "slashing" -> "режущий";
            case "piercing" -> "колющий";
            case "magic" -> "магический";
            case "siege" -> "осадный";
            case "chaos" -> "хаос";
            default -> "обычный";
        };
    }
    private static String armorType(String id) {
        return switch(id) {
            case "light" -> "лёгкая";
            case "medium" -> "средняя";
            case "heavy" -> "тяжёлая";
            case "fortified" -> "укреплённая";
            default -> "нет";
        };
    }
}
