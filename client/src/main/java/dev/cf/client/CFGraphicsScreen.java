package dev.cf.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local graphics menu; opens with the rebindable O key. No server requests. */
public final class CFGraphicsScreen extends Screen {
    public CFGraphicsScreen() { super(Component.literal("Castle Fight: графика")); }

    @Override protected void init() {
        int x = this.width / 2 - 115;
        int y = Math.max(34,this.height / 2 - 68);
        var cfg=CFGraphicsSettings.current();
        addRenderableWidget(Button.builder(Component.literal("Толщина маршрутов: " + widthName(cfg)),
                button -> { change(cfg0 -> cfg0.nextWidth());
                    button.setMessage(Component.literal("Толщина маршрутов: " + widthName(CFGraphicsSettings.current())));
                }).bounds(x,y,230,20).build());
        addRenderableWidget(Button.builder(Component.literal("Яркость зон: " + cfg.opacityPercent() + "%"),
                button -> { change(cfg0 -> cfg0.nextOpacity());
                    button.setMessage(Component.literal("Яркость зон: " + CFGraphicsSettings.current().opacityPercent()+"%"));
                }).bounds(x,y+24,230,20).build());
        addRenderableWidget(Button.builder(Component.literal("Снаряды: " + cfg.qualityName()),
                button -> { change(cfg0 -> cfg0.nextQuality());
                    button.setMessage(Component.literal("Снаряды: "+CFGraphicsSettings.current().qualityName()));
                }).bounds(x,y+48,230,20).build());
        addRenderableWidget(Button.builder(Component.literal("Следы снарядов: " + onOff(cfg.trails())),
                button -> { change(cfg0 -> cfg0.toggleTrails());
                    button.setMessage(Component.literal("Следы снарядов: "+onOff(CFGraphicsSettings.current().trails())));
                }).bounds(x,y+72,230,20).build());
        addRenderableWidget(Button.builder(Component.literal("Дальность графики: " + cfg.drawDistance()),
                button -> { change(cfg0 -> cfg0.nextDistance());
                    button.setMessage(Component.literal("Дальность графики: " + CFGraphicsSettings.current().drawDistance()));
                }).bounds(x,y+96,230,20).build());
        addRenderableWidget(Button.builder(Component.literal("Готово"),button -> onClose())
                .bounds(x,y+130,230,20).build());
    }
    private static String onOff(boolean v) { return v ? "ВКЛ" : "ВЫКЛ"; }
    private static String widthName(CFGraphicsSettings.Options cfg) {
        return switch(cfg.routeWidthPreset()) { case 0 -> "УЗКАЯ"; case 1 -> "ТОНКАЯ"; default -> "ШИРОКАЯ"; };
    }
    private static void change(java.util.function.UnaryOperator<CFGraphicsSettings.Options> edit) {
        var before=CFGraphicsSettings.current();
        var after=edit.apply(before);
        CFGraphicsSettings.set(after);
        if (before.routeWidthPreset()!=after.routeWidthPreset()) CFOverlayClient.rebuildCurrentMesh();
    }
    @Override public void onClose() { this.minecraft.gui.setScreen(null); }
}
