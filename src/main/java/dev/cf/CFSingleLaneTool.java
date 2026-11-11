package dev.cf;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.*;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.ItemStack;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Compatibility class name only. CF 2.2 uses three independent carpet-defined lanes. */
final class CFSingleLaneTool {
    private final CFThreeLanes routes;
    private final CFTowerSystem buildings;
    private final CFWorldEdit worldEdit;
    private final BooleanSupplier build;
    CFSingleLaneTool(InstanceContainer world,Set<CFUnit> units,BooleanSupplier duel,BooleanSupplier bars,
                     BooleanSupplier build,Path mapRoot,CFCarpetZones carpets) {
        this.build=build;routes=new CFThreeLanes(world,mapRoot,carpets,build);
        buildings=new CFTowerSystem(world,units,duel,bars,build,carpets,routes);
        worldEdit=new CFWorldEdit(world,build,routes::busy,routes::invalidate);
        var events=MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerBlockPlaceEvent.class,e->{
            if(e.getPlayer().getInstance()!=world||!build.getAsBoolean()||e.isCancelled())return;
            if(editorBusy()){e.setCancelled(true);e.consumeBlock(false);e.getPlayer().sendMessage("[CF] Route check running; editing temporarily locked.");}
            else routes.invalidate("BLOCK_PLACED");
        });
        events.addListener(PlayerBlockBreakEvent.class,e->{
            if(e.getPlayer().getInstance()!=world||!build.getAsBoolean()||e.isCancelled())return;
            if(editorBusy()){e.setCancelled(true);e.getPlayer().sendMessage("[CF] Route check running; editing temporarily locked.");}
            else routes.invalidate("BLOCK_BROKEN");
        });
    }
    void register(Command cf) {
        cf.addSyntax((s,c)->{if(admin(s)){if(worldEdit.busy())s.sendMessage("[CF] WorldEdit still editing; retry after /we status.");else if(build.getAsBoolean())routes.check(s);else s.sendMessage("[CF] PLAY validated at startup. Recheck edits in BUILD; /cf routes status");}},ArgumentType.Literal("routes"),ArgumentType.Literal("check"));
        cf.addSyntax((s,c)->s.sendMessage(routes.status()),ArgumentType.Literal("routes"),ArgumentType.Literal("status"));
        cf.addSyntax((s,c)->{
            if(!admin(s)||!(s instanceof Player p))return;
            if(!build.getAsBoolean()){s.sendMessage("[CF] Marker kit requires BUILD + restart.");return;}
            for(var m:CFMarkerPalette.ALL)p.getInventory().addItemStack(ItemStack.of(m.item(),64)
                    .withCustomName(Component.text(m.role()+" / lane "+m.lane())));
            s.sendMessage("[CF] Roads: BROWN=1 RED=2 ORANGE=3; BLUE team: CYAN=1 LIGHT_BLUE=2 BLUE=3; RED team: PURPLE=1 MAGENTA=2 PINK=3.");
            s.sendMessage("[CF] Road dots 3-6 blocks apart, max 12. Build areas: filled 3x3 SAME color. /cf castle blue|red; /cf routes check");
        },ArgumentType.Literal("carpets"));
        cf.addSyntax((s,c)->s.sendMessage(CFSkeletonMindLog.status()),ArgumentType.Literal("ai"),ArgumentType.Literal("status"));
        cf.addSyntax((s,c)->{
            if(!admin(s)||!build.getAsBoolean())return;
            try{saveLayout();s.sendMessage("[CF] Castle/settings saved. Carpet blocks: /cf save. Validation: /cf routes check.");}
            catch(IOException e){s.sendMessage("[CF] Save failed: "+e);}
        },ArgumentType.Literal("editor"),ArgumentType.Literal("save"));
        cf.addSyntax((s,c)->s.sendMessage(routes.status()),ArgumentType.Literal("editor"),ArgumentType.Literal("status"));
        cf.addSyntax((s,c)->s.sendMessage("[CF 2.2] Old waypoint wand is retired, its file is preserved. Use /cf carpets to paint three road chains."),
                ArgumentType.Literal("editor"),ArgumentType.Literal("wand"));
        buildings.register(cf);
        if (!Boolean.getBoolean("cf.play.only")) worldEdit.register();
    }
    private static boolean admin(CommandSender s){if(s instanceof Player p&&p.getPermissionLevel()<4){s.sendMessage("[CF] Operator required.");return false;}return true;}
    Pos castle(String team){return routes.castle(team);}
    void start(){buildings.start();}
    CFArenaAbilities abilities(){return buildings.abilities();}
    String consoleSoakStart(int rounds,int seconds){return buildings.consoleSoakStart(rounds,seconds);}
    String consoleSoakVerify(){return buildings.consoleSoakVerify();}
    String consoleSoakNight(int rounds,int seconds){return buildings.consoleSoakNight(rounds,seconds);}
    String consoleSoakBurn(int rounds,int seconds){return buildings.consoleSoakBurn(rounds,seconds);}
    String consoleSoakStress(int rounds,int seconds){return buildings.consoleSoakStress(rounds,seconds);}
    String consoleSoakStatus(){return buildings.consoleSoakStatus();}
    String consoleSoakStop(){return buildings.consoleSoakStop();}
    void saveLayout()throws IOException{routes.save();}
    Path layoutFile(){return routes.file();}
    boolean editorBusy(){return routes.busy() || worldEdit.busy();}
    CompletableFuture<Void> whenWorldEditIdle(){return worldEdit.whenIdle();}
    void setCastle(String t,Pos p)throws IOException{routes.setCastle(t,p);}
    CompletableFuture<Void> validateForPlay(CommandSender sender){
        if(worldEdit.busy())return CompletableFuture.failedFuture(new IllegalStateException("WorldEdit operation not finished"));
        return routes.check(sender);
    }
}
