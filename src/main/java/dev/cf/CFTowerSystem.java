package dev.cf;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.event.player.*;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.*;
import net.minestom.server.item.*;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Temporary match buildings. One due-time queue; no timer per tower or file access on spawn. */
final class CFTowerSystem {
    private static final Tag<String> TOKEN = Tag.String("cf.building.id");
    private final InstanceContainer world;
    private final Set<CFUnit> units;
    private final BooleanSupplier duel, bars;
    private final CFCarpetZones carpets;
    private final CFContentRegistry content;
    private final CFArenaAbilities abilities;
    private final CFSoakTest soak;
    private static final String DEMOLISH = "__cf_demolish__";
    private static final String UPGRADE = "__cf_upgrade__";
    private static final String INSPECT = "__cf_inspect__";
    private final CFThreeLanes routes;
    private final Map<UUID,String> teams = new ConcurrentHashMap<>();
    private final Map<UUID,String> races = new ConcurrentHashMap<>();
    private final Map<UUID,Long> clicks = new ConcurrentHashMap<>();
    private final Map<Long,Tower> towers = new LinkedHashMap<>();
    private final PriorityQueue<Tower> due = new PriorityQueue<>(Comparator
            .comparingLong((Tower t) -> t.next).thenComparingLong(t -> t.id));
    private long sequence;

    private static final class Tower {
        final long id;
        CFContentRegistry.Tower definition;
        final String team;
        final int x,y,z;
        final CFLanePath lane;
        Pos exit;
        List<Pos> journey = List.of();
        List<String> cycle;
        long next, nextWaitLog, nextExitRetry;
        int index, spawned;
        boolean active=true;
        final Map<CFPlacementRules.Cell,Block> previous = new LinkedHashMap<>();
        Tower(long id, CFContentRegistry.Tower d, String team, int x,int y,int z,CFLanePath lane) {
            this.id=id; this.lane=lane; definition=d;this.team=team;this.x=x;this.y=y;this.z=z;
            applyDefinition(d);
        }
        void applyDefinition(CFContentRegistry.Tower d) {
            this.definition=d;
            this.cycle=d.recipe().stream().flatMap(s -> Collections.nCopies(s.count(),s.mob()).stream()).toList();
            this.index=0;
            this.next=System.nanoTime()+TimeUnit.SECONDS.toNanos(d.intervalSeconds());
        }
    }
    CFTowerSystem(InstanceContainer world,Set<CFUnit> units,BooleanSupplier duel,
                  BooleanSupplier bars,CFCarpetZones carpets,
                  CFThreeLanes routes) {
        this.world=world;this.units=units;this.duel=duel;this.bars=bars;
        this.carpets=carpets;this.routes=routes;
        try { content=new CFContentRegistry(Path.of("content")); }
        catch (Exception e) { throw new IllegalStateException("CF content validation failed: " + e.getMessage(),e); }
        try { abilities=new CFArenaAbilities(world,units,Path.of("content"),new CFArenaAbilities.Summoner() {
            @Override public boolean summon(String team,String mob,int lane,Pos near) {
                return spawnAbilityUnit(team,mob,lane,near);
            }
            @Override public boolean summonAhead(CFUnit source,String mob) {
                return spawnSummonedAhead(source,mob);
            }
            @Override public boolean summonAtDeath(String team,String mob,int lane,Pos deathPos) {
                return spawnDeathProcExact(team,mob,lane,deathPos);
            }
        }); }
        catch(Exception e) { throw new IllegalStateException("Abilities failed validation: "+e.getMessage(),e); }
        soak = new CFSoakTest(world, units, content, abilities, routes, this::spawnAbilityUnit, this::clearTestUnits);
        System.out.println("[CF CONTENT] mobs="+content.mobs().size()+" towers="+content.towers().size());
    }
    synchronized int count() { return towers.size(); }
    CFArenaAbilities abilities() { return abilities; }
    String consoleSoakStart(int rounds,int seconds) {
        if(duel.getAsBoolean()||!routes.ready())
            return "[CF SOAK] Requires all three valid routes and no active duel.";
        return soak.start(rounds,seconds);
    }
    String consoleSoakNight(int rounds,int seconds) {
        if(duel.getAsBoolean()||!routes.ready())
            return "[CF NIGHT] Requires valid routes and no active duel.";
        return soak.startNight(rounds,seconds);
    }
    String consoleSoakBurn(int rounds,int seconds) {
        if(duel.getAsBoolean()||!routes.ready())
            return "[CF BURN] Requires valid routes and no active duel.";
        return soak.startBurn(rounds,seconds);
    }
    String consoleSoakStress(int rounds,int seconds) {
        if(duel.getAsBoolean()||!routes.ready())
            return "[CF STRESS] Requires valid routes and no active duel.";
        return soak.startStress(rounds,seconds);
    }
    String consoleSoakVerify() {
        if(duel.getAsBoolean())return "[CF VERIFY] Finish active tests before starting verification.";
        return soak.startVerify();
    }
    String consoleSoakStatus(){return soak.status();}
    String consoleSoakStop(){return soak.stop();}

    void register(Command cf) {
        cf.addSyntax((s,c)->kit(s,1),ArgumentType.Literal("kit"));
        var page=ArgumentType.Integer("kit-page");
        cf.addSyntax((s,c)->kit(s,c.get(page)),ArgumentType.Literal("kit"),page);
        var team=ArgumentType.Word("play-team").from("red","blue");
        var race=ArgumentType.Word("play-race").from("all","dark","snow");
        cf.addSyntax((sender,ctx)->{
            if(!(sender instanceof Player p) || !playAllowed(p))return;
            String selected=ctx.get(race);
            races.put(p.getUuid(),selected);
            giveKit(p,1);
            p.sendMessage("[CF] Палитра: "+CFTowerPresentation.paletteName(selected)+
                    "; команда: "+CFTowerPresentation.teamName(teams.getOrDefault(p.getUuid(),"blue"))+
                    ". В наборе только строящиеся уровни. /cf tower catalog — ветки прокачки.");
        },ArgumentType.Literal("race"),race);
        cf.addSyntax((s,c)->{
            if (!(s instanceof Player p) || p.getInstance()!=world) return;
            teams.put(p.getUuid(),c.get(team));
            s.sendMessage("[CF] Команда: "+CFTowerPresentation.teamName(c.get(team))+
                    ". Строй на цветных коврах своей команды.");
            giveKit(p,1);
        },ArgumentType.Literal("team"),team);
        cf.addSyntax((s,c)->{
            s.sendMessage("[CF] В каталоге: "+content.mobs().size()+" профилей существ, "+
                    content.towers().size()+" уровней башен Тьмы и Снегов.");
            for(var d:content.towers()) s.sendMessage("[CF] "+CFTowerPresentation.title(d,content)+
                    " | "+CFTowerPresentation.summon(d,content)+
                    " | каждые "+d.intervalSeconds()+" с");
        },ArgumentType.Literal("content"),ArgumentType.Literal("list"));
        cf.addSyntax((s,c)->s.sendMessage(carpets.summary(world)),
                ArgumentType.Literal("carpets"),ArgumentType.Literal("status"));
        cf.addSyntax((s,c)->{
            s.sendMessage("[CF] Построено башен: "+count()+"/"+content.limits().towers()+
                    "; существ: "+units.size()+"/"+content.limits().units()+
                    "; маршруты: "+(routes.ready()?"готовы":"не готовы")+".");
            synchronized(this) { for (Tower t:towers.values()) s.sendMessage("[CF] #"+t.id+" "+
                    CFTowerPresentation.title(t.definition,content)+" | "+
                    CFTowerPresentation.teamName(t.team)+" | линия "+t.lane.id+
                    " | "+t.x+","+t.y+","+t.z+" | призвано "+t.spawned); }
        },ArgumentType.Literal("tower"),ArgumentType.Literal("list"));
        cf.addSyntax((s,c)->{
            if (!(s instanceof Player p) || !admin(s) || !playAllowed(p)) return;
            removeNearest(p);
        },ArgumentType.Literal("tower"),ArgumentType.Literal("remove"));
        var infoId=ArgumentType.Integer("tower-info-id");
        cf.addSyntax((sender,ctx)->{
            if(!(sender instanceof Player p)||!playAllowed(p))return;
            long id=ctx.get(infoId).longValue();
            world.scheduler().scheduleNextTick(()->p.sendMessage(inspectById(id)));
        },ArgumentType.Literal("tower"),ArgumentType.Literal("info"),infoId);
        cf.addSyntax((sender,ctx)->{
            if(!(sender instanceof Player p)||!playAllowed(p))return;
            String palette=races.getOrDefault(p.getUuid(),"all");
            p.sendMessage("[CF КАТАЛОГ] "+CFTowerPresentation.paletteName(palette)+
                    ". В строительном наборе всегда первая ступень.");
            for(var d:CFTowerPresentation.palette(content,palette))
                p.sendMessage("[CF КАТАЛОГ] "+CFTowerPresentation.tree(d,content));
            p.sendMessage("[CF КАТАЛОГ] /cf race dark|snow|all — выбрать расу; /cf kit — получить набор.");
        },ArgumentType.Literal("tower"),ArgumentType.Literal("catalog"));
        var upgradeId=ArgumentType.Integer("tower-upgrade-id");
        cf.addSyntax((sender,ctx)->{
            if (!(sender instanceof Player player) || !playAllowed(player))return;
            long id=ctx.get(upgradeId).longValue();
            world.scheduler().scheduleNextTick(()->player.sendMessage(upgradeById(id,teams.getOrDefault(player.getUuid(),"blue"),false,player.getPosition())));
        },ArgumentType.Literal("tower"),ArgumentType.Literal("upgrade"),upgradeId);
        var removeId=ArgumentType.Integer("tower-remove-id");
        cf.addSyntax((s,c)->{
            if(!admin(s) || soak.isRunning()) {
                s.sendMessage("[CF] Demolition requires operator access and no soak test.");return;
            }
            if(s instanceof Player p && p.getInstance()!=world)return;
            final long id=c.get(removeId).longValue();
            world.scheduler().scheduleNextTick(()->s.sendMessage(removeById(id)));
        },ArgumentType.Literal("tower"),ArgumentType.Literal("remove"),removeId);
        cf.addSyntax((s,c)->{
            if(!admin(s)||!(s instanceof Player p)||!playAllowed(p))return;
            p.getInventory().addItemStack(demolitionItem());
            s.sendMessage("[CF] Выдан инструмент сноса: нажми ПКМ на любой блок своей башни.");
        },ArgumentType.Literal("tower"),ArgumentType.Literal("tool"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s, true))return;
            s.sendMessage(soak.start(128,120));
        },ArgumentType.Literal("soak"),ArgumentType.Literal("start"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s,true))return;
            s.sendMessage(soak.startVerify());
        },ArgumentType.Literal("soak"),ArgumentType.Literal("verify"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s, true))return;
            s.sendMessage(soak.startNight(96,300));
        },ArgumentType.Literal("soak"),ArgumentType.Literal("night"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s, true))return;
            s.sendMessage(soak.startStress(8,180));
        },ArgumentType.Literal("soak"),ArgumentType.Literal("stress"));
        var soakRounds=ArgumentType.Integer("soak-rounds");
        var soakSeconds=ArgumentType.Integer("soak-seconds");
        cf.addSyntax((s,c)->{
            if(!testAllowed(s, true))return;
            s.sendMessage(soak.start(c.get(soakRounds),120));
        },ArgumentType.Literal("soak"),ArgumentType.Literal("start"),soakRounds);
        cf.addSyntax((s,c)->{
            if(!testAllowed(s, true))return;
            s.sendMessage(soak.start(c.get(soakRounds),c.get(soakSeconds)));
        },ArgumentType.Literal("soak"),ArgumentType.Literal("start"),soakRounds,soakSeconds);
        cf.addSyntax((s,c)->s.sendMessage(soak.status()),
                ArgumentType.Literal("soak"),ArgumentType.Literal("status"));
        cf.addSyntax((s,c)->{
            if(!admin(s))return;
            s.sendMessage(soak.stop());
        },ArgumentType.Literal("soak"),ArgumentType.Literal("stop"));
        cf.addSyntax((s,c)->s.sendMessage(abilities.status()),
                ArgumentType.Literal("abilities"),ArgumentType.Literal("status"));
        cf.addSyntax((s,c)->{if(s instanceof Player p && admin(s) && playAllowed(p)){
            giveKit(p,1); s.sendMessage("[CF ТЕСТ] Все начальные башни текущей палитры доступны через /cf kit.");
        }},ArgumentType.Literal("test"),ArgumentType.Literal("kit"));
        var testTeam=ArgumentType.Word("test-team").from("blue","red");
        var testType=ArgumentType.Word("test-type");
        var testLane=ArgumentType.Integer("test-lane");
        cf.addSyntax((s,c)->{
            if(!testAllowed(s))return;
            String teamId=c.get(testTeam), mobId=c.get(testType);
            int laneId=c.get(testLane);
            if(content.mob(mobId)==null){s.sendMessage("[CF TEST] Unknown mob: "+mobId);return;}
            CFLanePath lane=routes.lane(laneId);
            if(lane==null){s.sendMessage("[CF TEST] Use lane 1, 2 or 3.");return;}
            List<Pos> points=lane.forTeam(teamId);
            Pos near=points.get(Math.max(1,Math.min(points.size()-2,points.size()/2+(teamId.equals("blue")?-12:12))));
            s.sendMessage(spawnAbilityUnit(teamId,mobId,laneId,near)
                    ? "[CF TEST] Spawned "+teamId+" "+mobId+" lane="+laneId
                    : "[CF TEST] No safe space / unit cap. Try another lane.");
        },ArgumentType.Literal("test"),ArgumentType.Literal("spawn"),testTeam,testType,testLane);
        var testAbility=ArgumentType.Word("test-support").from("mutation","toxic","resurrection");
        cf.addSyntax((s,c)->{
            if(!testAllowed(s))return;
            long id=abilities.addVirtualBuilding(c.get(testTeam),c.get(testAbility));
            s.sendMessage("[CF TEST] Virtual "+c.get(testTeam)+" "+c.get(testAbility)+" activated #"+id);
        },ArgumentType.Literal("test"),ArgumentType.Literal("support"),testTeam,testAbility);
        cf.addSyntax((s,c)->{
            if(!testAllowed(s))return;
            abilities.clearVirtualBuildings();s.sendMessage("[CF TEST] Virtual support buildings cleared.");
        },ArgumentType.Literal("test"),ArgumentType.Literal("support"),ArgumentType.Literal("clear"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s))return;
            s.sendMessage(testAll(false));
        },ArgumentType.Literal("test"),ArgumentType.Literal("all"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s))return;
            s.sendMessage(testAll(true));
        },ArgumentType.Literal("test"),ArgumentType.Literal("battle"));
        cf.addSyntax((s,c)->{
            if(!testAllowed(s))return;
            clearTestUnits();s.sendMessage("[CF TEST] Units + virtual buildings cleared; placed towers remain.");
        },ArgumentType.Literal("test"),ArgumentType.Literal("reset"));
        cf.addSyntax((s,c)->s.sendMessage(abilities.status()),
                ArgumentType.Literal("test"),ArgumentType.Literal("status"));
        var events=MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerSpawnEvent.class,e->{
            if(e.getPlayer().getInstance()==world) {
                teams.putIfAbsent(e.getPlayer().getUuid(),"blue");
                giveKit(e.getPlayer(),1);
            }
        });
        events.addListener(PlayerDisconnectEvent.class,e->{
            teams.remove(e.getPlayer().getUuid());races.remove(e.getPlayer().getUuid());clicks.remove(e.getPlayer().getUuid());
        });
        events.addListener(PlayerBlockInteractEvent.class,e->{
            Player p=e.getPlayer();
            if (p.getInstance()!=world) return;
            e.setCancelled(true); e.setBlockingItemUse(true);
            ItemStack item=p.getItemInHand(e.getHand());
            String id=item.getTag(TOKEN);
            if(id==null) return;
            if(!playAllowed(p)) return;
            if(DEMOLISH.equals(id) || UPGRADE.equals(id) || INSPECT.equals(id)) {
                if(DEMOLISH.equals(id) && !admin(p))return;
                Point clicked=e.getBlockPosition();
                if(p.getPosition().distanceSquared(clicked)>64) {
                    p.sendMessage("[CF] Подойди к башне на расстояние не более 8 блоков.");return;
                }
                if(UPGRADE.equals(id)) {
                    world.scheduler().scheduleNextTick(()->p.sendMessage(upgradeAt(clicked,teams.getOrDefault(p.getUuid(),"blue"),p.getPosition())));
                } else if(INSPECT.equals(id)) {
                    world.scheduler().scheduleNextTick(()->p.sendMessage(inspectAt(clicked)));
                } else {
                    world.scheduler().scheduleNextTick(()->p.sendMessage(removeAt(clicked)));
                }
                return;
            }
            long now=System.nanoTime(); Long previous=clicks.get(p.getUuid());
            if(previous!=null && now-previous<300_000_000L) return;
            clicks.put(p.getUuid(),now);
            if(e.getBlockFace()!=BlockFace.TOP) {
                CFSkeletonMindLog.systemEvent("BUILD_REJECT", "actor",p.getUsername(),
                        "towerType",id,"reason","CLICK_NOT_TOP",
                        "clicked",CFTrace.xyz(e.getBlockPosition()),"face",e.getBlockFace().toString());
                p.sendMessage("[CF СТРОЙКА] Нажми ПКМ по ВЕРХНЕЙ стороне центрального блока площадки 3x3.");return;
            }
            Point clicked=e.getBlockPosition();
            Point at=clicked.relative(BlockFace.TOP);
            if(p.getPosition().distanceSquared(at)>64) {
                CFSkeletonMindLog.systemEvent("BUILD_REJECT", "actor",p.getUsername(),
                        "towerType",id,"reason","TOO_FAR","center",CFTrace.xyz(at),
                        "playerPosition",CFTrace.xyz(p.getPosition()));
                p.sendMessage("[CF СТРОЙКА] Слишком далеко: максимум 8 блоков.");return;
            }
            // Mutate on the instance tick, after the click packet's normal rollback/ack has completed.
            world.scheduler().scheduleNextTick(()->{
                if(!p.isOnline() || p.getInstance()!=world) return;
                p.sendMessage(placeLogged(p.getUsername(),teams.getOrDefault(p.getUuid(),"blue"),id,at.blockX(),at.blockY(),at.blockZ()));
                p.sendPacket(new BlockChangePacket(at,world.getBlock(at)));
                p.getInventory().update(p);
            });
        });
        events.addListener(PlayerBlockPlaceEvent.class,e->{
            if(e.getPlayer().getInstance()==world) {e.setCancelled(true);e.consumeBlock(false);}
        });
        events.addListener(PlayerBlockBreakEvent.class,e->{
            if(e.getPlayer().getInstance()==world) e.setCancelled(true);
        });
        events.addListener(net.minestom.server.event.item.ItemDropEvent.class,e->{
            if(e.getPlayer().getInstance()==world && e.getItemStack().getTag(TOKEN)!=null)
                e.setCancelled(true);
        });
    }
    private static boolean admin(CommandSender s) {
        if(s instanceof Player p && p.getPermissionLevel()<4) {s.sendMessage("[CF] Нужны права оператора.");return false;}
        return true;
    }
    private boolean playAllowed(Player p) {
        if(duel.getAsBoolean() || soak.isRunning()) {p.sendMessage("[CF] Сначала заверши стресс-тест.");return false;}
        return p.getInstance()==world;
    }
    private void kit(CommandSender s,int page) {
        if(s instanceof Player p && playAllowed(p)) giveKit(p,page);
    }
    private void giveKit(Player p,int page) {
        String palette=races.getOrDefault(p.getUuid(),"all");
        List<CFContentRegistry.Tower> all=CFTowerPresentation.palette(content,palette);
        final int slots=33; // 0..32: build palette, 33: inspect, 34: upgrade, 35: admin demolition
        int pages=Math.max(1,(all.size()+slots-1)/slots);
        if(page<1||page>pages){
            p.sendMessage("[CF] Страницы набора: 1.."+pages+". Используй /cf kit <страница>.");
            return;
        }
        p.getInventory().clear();
        int from=(page-1)*slots,to=Math.min(from+slots,all.size());
        for(int i=from;i<to;i++) {
            var d=all.get(i);
            String next=d.upgradeTo().isEmpty()?
                    "Максимальная ступень; улучшений нет":"После постройки улучшай изумрудом";
            p.getInventory().setItemStack(i-from,ItemStack.of(d.item())
                    .withTag(TOKEN,d.id())
                    .withCustomName(Component.text(CFTowerPresentation.title(d,content),NamedTextColor.AQUA))
                    .withLore(
                        Component.text("Строится: "+CFTowerPresentation.stage(d,content),NamedTextColor.GOLD),
                        Component.text(CFTowerPresentation.summon(d,content),NamedTextColor.WHITE),
                        Component.text(CFTowerPresentation.combat(d,content),NamedTextColor.GRAY),
                        Component.text("Появление: каждые "+d.intervalSeconds()+" с",NamedTextColor.GRAY),
                        Component.text(next,NamedTextColor.YELLOW),
                        Component.text(CFTowerPresentation.next(d,content),NamedTextColor.GRAY),
                        Component.text("ПКМ по центру площадки 3x3",NamedTextColor.GREEN)
                    ));
        }
        p.getInventory().setItemStack(33,inspectItem());
        p.getInventory().setItemStack(34,upgradeItem());
        if(p.getPermissionLevel()>=4)p.getInventory().setItemStack(35,demolitionItem());
        p.sendMessage("[CF НАБОР] "+CFTowerPresentation.paletteName(palette)+
                " | страница "+page+"/"+pages+" | команда "+
                CFTowerPresentation.teamName(teams.getOrDefault(p.getUuid(),"blue")));
        p.sendMessage("[CF НАБОР] В наборе первые уровни и особые постройки. Подзорная труба (слот 34) = осмотр; "+
                "изумруд (слот 35) = улучшить. Уровни 2 и 3 — только через улучшение.");
        p.sendMessage("[CF НАБОР] /cf tower catalog — все ветки; /cf race dark|snow|all — выбор палитры.");
    }
    private static ItemStack inspectItem() {
        return ItemStack.of(Material.SPYGLASS).withTag(TOKEN,INSPECT)
                .withCustomName(Component.text("ОСМОТР БАШНИ | узнать уровень",NamedTextColor.AQUA))
                .withLore(Component.text("ПКМ по любому блоку построенной башни.",NamedTextColor.GRAY),
                          Component.text("Покажет уровень, моба, HP, урон и следующую ступень.",NamedTextColor.GRAY));
    }
    private static ItemStack upgradeItem() {
        return ItemStack.of(Material.EMERALD).withTag(TOKEN,UPGRADE)
                .withCustomName(Component.text("УЛУЧШИТЬ БАШНЮ | на 1 ступень",NamedTextColor.GREEN))
                .withLore(Component.text("ПКМ по любому блоку СВОЕЙ башни.",NamedTextColor.GRAY),
                          Component.text("Уровень 1 -> 2 -> 3; улучшение бесплатно.",NamedTextColor.YELLOW),
                          Component.text("Золотая верхушка = ур. 2; незеритовая = ур. 3.",NamedTextColor.GRAY));
    }
    private static ItemStack demolitionItem() {
        return ItemStack.of(Material.BARRIER).withTag(TOKEN,DEMOLISH)
                .withCustomName(Component.text("СНЕСТИ БАШНЮ | оператор",NamedTextColor.RED))
                .withLore(Component.text("ПКМ по любому блоку построенной башни.",NamedTextColor.GRAY));
    }
    private String placeLogged(String actor,String team,String id,int x,int y,int z) {
        String result=place(actor,team,id,x,y,z);
        CFSkeletonMindLog.systemEvent("BUILD_PLACEMENT","actor",actor,"team",team,
                "towerType",id,"x",x,"y",y,"z",z,
                "accepted",result.startsWith("[CF СТРОЙКА] ПОСТРОЕНО:"),
                "result",result);
        return result;
    }
    // Event-only diagnostics. No placement rules, route selection or world state are changed here.
    private String rejectBuild(String actor,String team,String id,int x,int y,int z,
                               String reason,String message,Object... details) {
        Object[] standard={"actor",actor,"team",team,"towerType",id,
                "x",x,"y",y,"z",z,"reason",reason,"message",message};
        Object[] all=Arrays.copyOf(standard,standard.length+details.length);
        System.arraycopy(details,0,all,standard.length,details.length);
        CFSkeletonMindLog.systemEvent("BUILD_REJECT",all);
        return message;
    }
    private synchronized String place(String actor,String team,String id,int x,int y,int z) {
        if(duel.getAsBoolean()||soak.isRunning())
            return rejectBuild(actor,team,id,x,y,z,"MATCH_MODE", "[CF] Нельзя строить во время дуэли или стресс-теста.",
                    "duel",duel.getAsBoolean(),"soak",soak.isRunning());
        if(!routes.ready())return rejectBuild(actor,team,id,x,y,z,"ROUTES_NOT_READY",
                "[CF СТРОЙКА] Маршруты пока не готовы. " + routes.status());
        CFContentRegistry.Tower d=content.tower(id);
        if(d==null)return rejectBuild(actor,team,id,x,y,z,"UNKNOWN_TOWER", "[CF СТРОЙКА] Неизвестная башня: "+id);
        if(d.upgradeOnly())return rejectBuild(actor,team,id,x,y,z,"UPGRADE_ONLY",
                "[CF СТРОЙКА] Вторая и третья ступени доступны только через улучшение построенной башни.");
        if(towers.size()>=content.limits().towers())return rejectBuild(actor,team,id,x,y,z,"TOWER_LIMIT",
                "[CF СТРОЙКА] Достигнут лимит башен.","towerCount",towers.size(),"limit",content.limits().towers());
        int laneId=carpets.footprintLane(team,x,y,z);
        if(laneId==0)return rejectBuild(actor,team,id,x,y,z,"FOOTPRINT_LANE",
                "[CF СТРОЙКА] Нужны все 9 клеток одного цвета одной линии для команды "+team+" на одной высоте. Не смешивай цвета линий; ПКМ по ЦЕНТРУ.");
        CFLanePath lane=routes.lane(laneId);
        if(lane==null)return rejectBuild(actor,team,id,x,y,z,"LANE_NOT_VALIDATED",
                "[CF СТРОЙКА] Выбранная линия ещё не прошла проверку.","lane",laneId);
        // ONLY tower-to-tower placement rule: every 3x3 footprint must have
        // at least one untouched block between it and any other tower, including corners.
        // This applies to both teams and all elevations, with no special exit/road reservation.
        for(Tower other:towers.values())if(!CFTowerSpacing.allows(x,z,other.x,other.z))
            return rejectBuild(actor,team,id,x,y,z,"TOWER_TOO_CLOSE",
                    "[CF СТРОЙКА] Между башнями 3x3 должен оставаться минимум один свободный блок.",
                    "otherTowerId",other.id,"otherTowerType",other.definition.id(),
                    "otherTeam",other.team,"otherCenter",other.x+","+other.y+","+other.z,
                    "requiredCenterGap",4);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            int xx=x+dx,zz=z+dz;
            if(world.getChunk(Math.floorDiv(xx,16),Math.floorDiv(zz,16))==null)
                return rejectBuild(actor,team,id,x,y,z,"CHUNK_UNLOADED",
                        "[CF СТРОЙКА] Участок мира не загружен; подойди ближе.","cellX",xx,"cellY",y,"cellZ",zz);
            Block floor=world.getBlock(xx,y-1,zz);
            if(floor.liquid() || !floor.solid() || !floor.collisionShape().isFaceFull(BlockFace.TOP))
                return rejectBuild(actor,team,id,x,y,z,"BAD_FOUNDATION",
                        "[CF СТРОЙКА] Нужна сплошная ровная площадка 3x3, без воды и полуплит.",
                        "cellX",xx,"cellY",y-1,"cellZ",zz,"floorBlock",floor.name());
            for(int h=0;h<3;h++) {
                Point at=new Vec(xx,y+h,zz);
                Block inVolume=world.getBlock(at);
                if(!inVolume.air())return rejectBuild(actor,team,id,x,y,z,"VOLUME_BLOCKED",
                        "[CF СТРОЙКА] Место для башни занято блоками.","cellX",xx,"cellY",y+h,
                        "cellZ",zz,"block",inVolume.name());
            }
        }
        Point head=new Vec(x,y+3,z);
        Block headBlock=world.getBlock(head);
        if(!headBlock.air())
            return rejectBuild(actor,team,id,x,y,z,"HEAD_BLOCKED",
                    "[CF СТРОЙКА] Освободи блок над верхушкой башни.","headBlock",headBlock.name());
        Tower t=new Tower(++sequence,d,team,x,y,z,lane);
        // The exit is for SPAWNING, not a building restriction. A tower may be built
        // even if there is no current route to its lane. Retry later without rejecting it.
        if(t.definition.ability().isEmpty() && !prepareExit(t)) {
            t.nextExitRetry=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            CFSkeletonMindLog.systemEvent("TOWER_EXIT_PENDING", "tower",t.id,
                    "towerType",id,"team",team,"lane",laneId,"x",x,"y",y,"z",z);
        }
        try {
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int h=0;h<3;h++)
                paint(t,new CFPlacementRules.Cell(x+dx,y+h,z+dz),d.body());
            paint(t,new CFPlacementRules.Cell(x,y+3,z),d.head());
        } catch(Exception e) {
            restore(t);return rejectBuild(actor,team,id,x,y,z,"PAINT_ROLLBACK",
                    "[CF СТРОЙКА] Стройка не удалась; блоки восстановлены: "+e.getMessage(),
                    "exceptionType",e.getClass().getSimpleName());
        }
        towers.put(t.id,t);
        if(t.definition.ability().isEmpty()) due.add(t);
        else abilities.addBuilding(t.id,t.team,t.definition.ability(),t.lane.id,new Pos(t.x+.5,t.y,t.z+.5));
        System.out.println("[CF СТРОЙКА] #"+t.id+" "+team+" "+id+" at "+x+","+y+","+z);
        return "[CF СТРОЙКА] ПОСТРОЕНО: "+CFTowerPresentation.title(d,content)+
                " | башня #"+t.id+" | команда "+CFTowerPresentation.teamName(team)+
                " | линия "+laneId+" | первое появление через "+d.intervalSeconds()+" с. "+
                (d.upgradeTo().isEmpty()?"Улучшений нет.":"Улучшить: изумрудом ПКМ по башне.");
    }
    private void paint(Tower t,CFPlacementRules.Cell c,Block block) {
        t.previous.put(c,world.getBlock(c.x(),c.y(),c.z()));world.setBlock(c.x(),c.y(),c.z(),block);
    }
    private void restore(Tower t) { for(var e:t.previous.entrySet()) {
        var c=e.getKey();world.setBlock(c.x(),c.y(),c.z(),e.getValue());
    } }
    private void removeNearest(Player p) {
        world.scheduler().scheduleNextTick(()->{ synchronized(this) {
            Tower nearest=null;double dist=64;
            for(Tower t:towers.values()) {
                double d=p.getPosition().distanceSquared(new Pos(t.x+.5,t.y,t.z+.5));
                if(d<dist){dist=d;nearest=t;}
            }
            p.sendMessage(nearest==null ? "[CF] Поблизости (8 блоков) нет башен." : removeTower(nearest));
        }});
    }
    private synchronized String inspectAt(Point clicked) {
        CFPlacementRules.Cell cell=new CFPlacementRules.Cell(
                clicked.blockX(),clicked.blockY(),clicked.blockZ());
        for(Tower t:towers.values())if(t.previous.containsKey(cell))
            return CFTowerPresentation.inspect(t.definition,content,t.id,t.team,t.lane.id);
        return "[CF ОСМОТР] Это не блок построенной башни.";
    }
    private synchronized String inspectById(long id) {
        Tower t=towers.get(id);
        return t==null?"[CF ОСМОТР] Башня #"+id+" не найдена.":
                CFTowerPresentation.inspect(t.definition,content,t.id,t.team,t.lane.id);
    }
    private synchronized String upgradeAt(Point clicked,String team,Pos player) {
        for(Tower t:towers.values())if(t.previous.containsKey(new CFPlacementRules.Cell(
                clicked.blockX(),clicked.blockY(),clicked.blockZ())))
            return upgradeById(t.id,team,false,player);
        return "[CF УЛУЧШЕНИЕ] Это не блок построенной башни.";
    }
    private synchronized String upgradeById(long id,String team,boolean admin,Pos player) {
        Tower t=towers.get(id);
        if(t==null)return "[CF УЛУЧШЕНИЕ] Башня #"+id+" не найдена.";
        if(duel.getAsBoolean() || soak.isRunning() || !routes.ready())
            return "[CF УЛУЧШЕНИЕ] Нельзя улучшать во время дуэли или стресс-теста.";
        if(!admin && !t.team.equals(team))return "[CF УЛУЧШЕНИЕ] Это башня другой команды.";
        if(!admin && player.distanceSquared(new Pos(t.x+.5,t.y,t.z+.5))>64)
            return "[CF УЛУЧШЕНИЕ] Подойди ближе, максимум 8 блоков.";
        String next=t.definition.upgradeTo();
        if(next.isEmpty())return "[CF УЛУЧШЕНИЕ] "+CFTowerPresentation.title(t.definition,content)+
                " уже на максимальной ступени. Дальше улучшать нечего.";
        CFContentRegistry.Tower d=content.tower(next);
        if(d==null || d.recipe().isEmpty() || !d.ability().isEmpty())
            return "[CF УЛУЧШЕНИЕ] Ошибка профиля следующего уровня.";
        CFContentRegistry.Tower old=t.definition;
        List<String> oldCycle=t.cycle;
        int oldIndex=t.index;
        long oldNext=t.next;
        Pos oldExit=t.exit;
        List<Pos> oldJourney=t.journey;
        Map<CFPlacementRules.Cell,Block> oldBlocks=new LinkedHashMap<>();
        try {
            due.remove(t);
            t.applyDefinition(d);
            t.exit=null;t.journey=List.of();
            for(var cell:t.previous.keySet()) {
                Block desired=cell.x()==t.x && cell.y()==t.y+3 && cell.z()==t.z?d.head():d.body();
                oldBlocks.put(cell,world.getBlock(cell.x(),cell.y(),cell.z()));
                world.setBlock(cell.x(),cell.y(),cell.z(),desired);
            }
            if(!prepareExit(t))t.nextExitRetry=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            due.add(t);
            CFTrace.event("TOWER_UPGRADED","tower",t.id,"oldType",old.id(),"newType",d.id(),
                    "team",t.team,"level",d.id());
            return "[CF УЛУЧШЕНИЕ] Башня #"+t.id+": "+
                    CFTowerPresentation.shortStage(old,content)+" -> "+
                    CFTowerPresentation.shortStage(d,content)+" | "+
                    CFTowerPresentation.title(d,content)+"\n"+
                    "[CF УЛУЧШЕНИЕ] "+CFTowerPresentation.summon(d,content)+
                    "; "+CFTowerPresentation.combat(d,content)+
                    "; следующее появление через "+d.intervalSeconds()+" с.";
        } catch(Exception ex) {
            due.remove(t);
            t.definition=old;t.cycle=oldCycle;t.index=oldIndex;t.next=oldNext;
            t.exit=oldExit;t.journey=oldJourney;
            for(var entry:oldBlocks.entrySet()){
                var c=entry.getKey();world.setBlock(c.x(),c.y(),c.z(),entry.getValue());
            }
            due.add(t);
            return "[CF УЛУЧШЕНИЕ] Ошибка; башня восстановлена: "+ex.getMessage();
        }
    }
    private synchronized String removeById(long id) {
        Tower t=towers.get(id);
        return t==null?"[CF] No building #"+id : removeTower(t);
    }
    private synchronized String removeAt(Point point) {
        CFPlacementRules.Cell clicked=new CFPlacementRules.Cell(point.blockX(),point.blockY(),point.blockZ());
        for(Tower t:towers.values()) if(t.previous.containsKey(clicked)) return removeTower(t);
        return "[CF] Этот блок не принадлежит башне Castle Fight.";
    }
    private String removeTower(Tower t) {
        t.active=false;
        due.remove(t);
        towers.remove(t.id);
        abilities.removeBuilding(t.id);
        restore(t);
        CFTrace.event("BUILDING_REMOVED","id",t.id,"towerType",t.definition.id(),
                "team",t.team,"lane",t.lane.id,"x",t.x,"y",t.y,"z",t.z);
        System.out.println("[CF СТРОЙКА] Removed #"+t.id+" "+t.definition.id()+" " + t.team);
        return "[CF] Башня #"+t.id+" снесена. Блоки восстановлены, место свободно; уже призванные существа остались.";
    }
    private void clearTestUnits() {
        abilities.clearVirtualBuildings();
        abilities.clearUnits();
        for(CFUnit u:List.copyOf(units))if(!u.isRemoved())u.remove();
        units.clear();
    }

    private boolean testAllowed(CommandSender sender) { return testAllowed(sender,false); }
    private boolean testAllowed(CommandSender sender,boolean allowSoak) {
        if(!admin(sender))return false;
        if((!allowSoak && soak.isRunning())) {
            sender.sendMessage("[CF TEST] Soak running; /cf soak status or /cf soak stop.");return false;
        }
        if(duel.getAsBoolean() || !routes.ready()) {
            sender.sendMessage("[CF TEST] Three validated routes and no active duel required.");return false;
        }
        if(sender instanceof Player p && p.getInstance()!=world){
            sender.sendMessage("[CF TEST] Enter this map first.");return false;
        }
        return true;
    }
    private String testAll(boolean support) {
        if(support) {
            abilities.clearVirtualBuildings();
            for(String team:List.of("blue","red"))
                for(String role:List.of("mutation","toxic","resurrection"))
                    abilities.addVirtualBuilding(team,role);
        }
        int spawned=0;int index=0;
        for(CFContentRegistry.Mob mob:content.mobs()) {
            int laneId=index%3+1;
            CFLanePath lane=routes.lane(laneId);
            if(lane==null)continue;
            List<Pos> bluePath=lane.forTeam("blue");
            int center=bluePath.size()/2;
            int spacing=8+(index/3)*4;
            int blueIndex=Math.max(1,center-spacing);
            int redIndex=Math.min(bluePath.size()-2,center+spacing);
            if(spawnAbilityUnit("blue",mob.id(),laneId,bluePath.get(blueIndex)))spawned++;
            if(spawnAbilityUnit("red",mob.id(),laneId,bluePath.get(redIndex)))spawned++;
            index++;
        }
        return "[CF TEST] "+spawned+" mobs requested from "+content.mobs().size()+
                " types on 3 lanes. "+(support?"6 virtual support buildings ON. ":"")+"/cf test status";
    }
    /** Used by mutations, revivals, periodic summons and OP test spawns. Never changes the saved map. */
    boolean spawnAbilityUnit(String team,String mobId,int laneId,Pos near) {
        if(!routes.ready() || units.size()>=content.limits().units())return false;
        CFContentRegistry.Mob mob=content.mob(mobId);
        CFLanePath lane=routes.lane(laneId);
        if(mob==null || lane==null)return false;
        List<Pos> route=lane.forTeam(team);
        Pos spawn=null; double best=Double.MAX_VALUE;
        var getter=CFLaneSearch.terrain(world);
        int checked=0;
        for(Pos candidate:route) {
            double sq=candidate.distanceSquared(near);
            if(sq>=best || sq>144 || !CFLaneSearch.clear(getter,CFThreeLanes.terrainPoint(),candidate))continue;
            boolean blocked=false;
            for(Entity other:world.getNearbyEntities(candidate,1.2)){
                if(other instanceof CFUnit u && !u.isDead() && !u.isRemoved() &&
                        u.getPosition().distanceSquared(candidate)<1.2){blocked=true;break;}
            }
            if(blocked)continue;
            best=sq;spawn=candidate;
            if(++checked>24)break;
        }
        if(spawn==null){
            CFTrace.event("UNIT_SPAWN_FAILED","team",team,"mob",mobId,"lane",laneId,
                    "reason","NO_SAFE_SPACE","near",CFTrace.xyz(near));
            // Observation only; do not change the accepted/denied result.
            if(CFSoakTest.isRunning()||CFTrace.on()) {
                Pos closest=null;double distance=Double.MAX_VALUE;
                for(Pos candidate:route) {
                    double sq=candidate.distanceSquared(near);
                    if(sq<distance){distance=sq;closest=candidate;}
                }
                if(closest!=null)auditSpawnOccupancy("NO_SAFE_SPACE_NEAREST",closest,null,
                        team,mobId,laneId);
            }
            return false;
        }
        auditSpawnOccupancy("ABILITY_BEFORE_REQUEST",spawn,null,team,mobId,laneId);
        return spawnAt(team,mobId,laneId,mob,lane,route,spawn);
    }

    /** Resurrection and necromancy: do not project a death position onto road nodes. */
    private boolean spawnDeathProcExact(String team,String mobId,int laneId,Pos deathPos) {
        if (!routes.ready() || units.size()>=content.limits().units()) {
            CFTrace.event("DEATH_SPAWN_FAILED","reason","UNAVAILABLE_OR_ARMY_LIMIT",
                    "team",team,"mob",mobId,"lane",laneId,"requested",CFTrace.xyz(deathPos));
            return false;
        }
        CFContentRegistry.Mob mob=content.mob(mobId);
        CFLanePath lane=routes.lane(laneId);
        if (mob==null || lane==null || deathPos==null ||
                !Double.isFinite(deathPos.x()) || !Double.isFinite(deathPos.y()) ||
                !Double.isFinite(deathPos.z())) {
            CFTrace.event("DEATH_SPAWN_FAILED","reason","INVALID_TARGET",
                    "team",team,"mob",mobId,"lane",laneId,"requested",CFTrace.xyz(deathPos));
            return false;
        }
        // Exact death coordinates. Never reject based on a new unit's hitbox.
        // Ordinary world physics remains handled by Minestom after spawn.
        CFTrace.event("DEATH_SPAWN_EXACT","team",team,"mob",mobId,
                "lane",laneId,"requested",CFTrace.xyz(deathPos));
        return spawnAt(team,mobId,laneId,mob,lane,lane.forTeam(team),deathPos);
    }

    private boolean spawnAt(String team,String mobId,int laneId,CFContentRegistry.Mob mob,
                            CFLanePath lane,List<Pos> route,Pos spawn) {
        CFUnit u=new CFUnit(team,mob,spawn,route.getLast());
        u.assignValidatedLane(lane,route);u.setHealthBarVisible(bars.getAsBoolean());
        units.add(u);
        CFTrace.event("UNIT_SPAWN_REQUEST","unit",u.getEntityId(),"team",team,
                "mob",mobId,"lane",laneId,"position",CFTrace.xyz(spawn));
        final Pos targetPos=spawn;
        u.setInstance(world,targetPos).whenComplete((unused,error)->world.scheduler().scheduleNextTick(()->{
            if(error!=null){units.remove(u);u.remove();
                CFTrace.event("UNIT_SPAWN_FAILED","unit",u.getEntityId(),"team",team,"mob",mobId,
                        "lane",laneId,"reason",String.valueOf(error));
                System.err.println("[CF TEST] Spawn failed: "+error);}
            else if(u.isRemoved()){units.remove(u);CFTrace.event("UNIT_SPAWN_CANCELLED","unit",u.getEntityId());}
            else {u.markSpawnCompleted();abilities.onUnitSpawn(u);
                CFTrace.event("UNIT_SPAWN_READY","unit",u.getEntityId(),"team",team,"mob",mobId,
                        "lane",laneId,"position",CFTrace.xyz(u.getPosition()));
                auditSpawnOccupancy("ABILITY_AFTER_READY",u.getPosition(),u,team,mobId,laneId);}
        }));
        return true;
    }

    /** Acolyte summon: take the first safe terrain point AHEAD on its own route.
     * Does not reserve positions or restrict simultaneous tower spawns.
     */
    private boolean spawnSummonedAhead(CFUnit source,String mobId) {
        if (!routes.ready() || source.isDead() || source.isRemoved()
                || source.getInstance()!=world || units.size()>=content.limits().units()) return false;
        CFLanePath lane = routes.lane(source.laneId());
        CFContentRegistry.Mob def = content.mob(mobId);
        if (lane == null || def == null) return false;
        List<Pos> path = lane.forTeam(source.team());
        Pos origin = source.getPosition();
        int nearest = 0;
        double nearestDist = Double.POSITIVE_INFINITY;
        for(int i=0;i<path.size();i++) {
            double dist = path.get(i).distanceSquared(origin);
            if(dist < nearestDist) { nearest=i; nearestDist=dist; }
        }
        var terrain=CFLaneSearch.terrain(world);
        for(int i=nearest+1;i<Math.min(path.size(),nearest+13);i++) {
            Pos candidate=path.get(i);
            double dist=candidate.distanceSquared(origin);
            if(dist < 2.25 || dist > 144) continue;
            if(!CFLaneSearch.clear(terrain,CFThreeLanes.terrainPoint(),candidate)) continue;
            CFTrace.event("SUMMON_AHEAD", "source", source.getEntityId(),
                    "mob",mobId,"lane",lane.id,"candidate",CFTrace.xyz(candidate));
            auditSpawnOccupancy("SUMMON_BEFORE_REQUEST",candidate,null,source.team(),mobId,lane.id);
            return spawnAt(source.team(),mobId,lane.id,def,lane,path,candidate);
        }
        CFTrace.event("SUMMON_AHEAD_FAILED","source",source.getEntityId(),
                "mob",mobId,"lane",lane.id);
        return false;
    }

    void start() {
        routes.check(null).whenComplete((v,e)->System.out.println(e==null?
                "[CF СТРОЙКА] Three routes validated; towers enabled.":"[CF СТРОЙКА] Towers DISABLED: check map carpets and castle positions."));
        world.scheduler().buildTask(this::tick).delay(TaskSchedule.tick(1)).repeat(TaskSchedule.tick(1)).schedule();
    }
    private synchronized void tick() {
        if(!routes.ready()||duel.getAsBoolean()||soak.isRunning()||due.isEmpty())return;
        long now=System.nanoTime();int budget=content.limits().spawnsPerTick();
        while(budget>0&&!due.isEmpty()&&due.peek().next<=now) {
            Tower t=due.poll();if(!t.active)continue;
            if(abilities.towerFrozen(t.id)){
                t.next=now+1_000_000_000L;due.add(t);continue;
            }
            if(units.size()>=content.limits().units()){waitLog(t,now,"ARMY_LIMIT");t.next=now+1_000_000_000L;due.add(t);continue;}
            if(t.exit==null && now>=t.nextExitRetry) {
                t.nextExitRetry=now+TimeUnit.SECONDS.toNanos(10);
                if(prepareExit(t)) CFSkeletonMindLog.systemEvent("TOWER_EXIT_RECOVERED",
                        "tower",t.id,"towerType",t.definition.id(),"lane",t.lane.id,
                        "exit",CFTrace.xyz(t.exit));
            }
            CFContentRegistry.Mob def=content.mob(t.cycle.get(t.index));
            Pos spawn=findSpawn(t,def);
            if(spawn==null){waitLog(t,now,"NO_SAFE_EXIT");t.next=now+2_000_000_000L;due.add(t);budget--;continue;}
            spawn(t,def,spawn);budget--;t.index++;
            if(t.index==t.cycle.size()){t.index=0;t.next=now+TimeUnit.SECONDS.toNanos(t.definition.intervalSeconds());}
            else t.next=now+50_000_000L;
            due.add(t);
        }
    }
    private void waitLog(Tower t,long now,String reason) {
        if(now<t.nextWaitLog)return;
        t.nextWaitLog=now+5_000_000_000L;
        CFSkeletonMindLog.systemEvent("TOWER_SPAWN_WAIT","tower",t.id,"towerType",t.definition.id(),
                "team",t.team,"reason",reason,"armySize",units.size(),"x",t.x,"y",t.y,"z",t.z);
    }
    /** Validate once per building against its OWN lane. Cached connection is reused by every spawn. */
    private boolean prepareExit(Tower t) {
        var terrain=CFLaneSearch.terrain(world);
        Block.Getter simulated=(x,y,z,c)->{
            if(Math.abs(x-t.x)<=1&&Math.abs(z-t.z)<=1&&y>=t.y&&y<t.y+3)return t.definition.body();
            if(x==t.x&&z==t.z&&y==t.y+3)return t.definition.head();
            return terrain.getBlock(x,y,z,c);
        };
        List<Pos> route=t.lane.forTeam(t.team);
        List<Pos> candidates=new ArrayList<>();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))<2)continue;
            Pos p=MapSurface.findFloorOnly(world,t.x+dx,t.z+dz,t.y,2);
            if(p!=null&&CFLaneSearch.clear(simulated,routes.envelope(),p))candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p->route.stream().mapToDouble(p::distanceSquared).min().orElse(Double.MAX_VALUE)));
        int attempts=0;
        for(Pos p:candidates) {
            List<Integer> targets=new ArrayList<>();for(int i=0;i<route.size();i++)if(p.distanceSquared(route.get(i))<=24*24)targets.add(i);
            targets.sort(Comparator.<Integer>comparingDouble(i->p.distanceSquared(route.get(i))).thenComparing(Comparator.reverseOrder()));
            for(int k=0;k<Math.min(2,targets.size());k++) {
                if(++attempts>8)return false;int index=targets.get(k);Pos target=route.get(index);
                try {
                    var connector=CFLaneSearch.find(simulated,routes.envelope(),p,target,CFLaneSearch.tube(p,target,4.5));
                    var joined=new ArrayList<>(connector);
                    joined.addAll(route.subList(index+1,route.size()));
                    t.exit=p;t.journey=List.copyOf(joined);
                    CFSkeletonMindLog.systemEvent("TOWER_LANE_JOIN","tower",t.id,"team",t.team,"lane",t.lane.id,
                            "roadIndex",index,"exit",CFTrace.xyz(p),"connectorNodes",connector.size(),"decision","OWN_LANE_ONLY_NO_RETURN_TO_CASTLE");
                    return true;
                }catch(IllegalArgumentException ignored) { }
            }
        }
        return false;
    }
    /** Spawn does not reject a configured mob because of width, height or occupancy box. */
    private Pos findSpawn(Tower t,CFContentRegistry.Mob def) {
        // The tower exit was already connected to the ground route as a point.
        // The normal Minestom entity physics still applies after spawning.
        return t.exit;
    }
    private void spawn(Tower t,CFContentRegistry.Mob def,Pos pos) {
        auditSpawnOccupancy("TOWER_BEFORE_REQUEST",pos,null,t.team,def.id(),t.lane.id);
        List<Pos> route=t.journey;
        CFUnit u=new CFUnit(t.team,def,pos,route.getLast());
        u.assignValidatedLane(t.lane,route);u.setHealthBarVisible(bars.getAsBoolean());units.add(u);
        CFSkeletonMindLog.event(u,"TOWER_SPAWN_REQUEST","tower",t.id,"towerType",t.definition.id(),
                "lane",t.lane.id,"mobType",def.id(),"spawn",CFTrace.xyz(pos));
        u.setInstance(world,pos).whenComplete((v,e)->world.scheduler().scheduleNextTick(()->{
            if(e!=null){units.remove(u);u.remove();CFSkeletonMindLog.event(u,"SPAWN_FAILED","error",e.toString());}
            else {u.markSpawnCompleted();abilities.onUnitSpawn(u);t.spawned++;CFSkeletonMindLog.event(u,"SPAWN_SUCCESS","tower",t.id);
                auditSpawnOccupancy("TOWER_AFTER_READY",u.getPosition(),u,t.team,def.id(),t.lane.id);}
        }));
    }

    /**
     * D-only probe: compare world spatial index against the live/pending unit
     * registry. Does not reserve, reject, move or otherwise influence any spawn.
     * Runs only while a soak or trace is active.
     */
    private void auditSpawnOccupancy(String stage,Pos at,CFUnit exclude,
                                     String team,String mob,int lane) {
        if(!(CFSoakTest.isRunning()||CFTrace.on()))return;
        int registered=0,pending=0,visible=0;
        StringBuilder registryIds=new StringBuilder();
        double nearest=Double.POSITIVE_INFINITY;
        for(CFUnit u:units) {
            if(u==exclude||u.isDead()||u.isRemoved())continue;
            // Pending units may still have a default Minestom position before
            // setInstance completes. Inspect their *requested* position instead.
            Pos p=u.wasSpawnCompleted()?u.getPosition():u.motionSpawnPosition();
            double horizontal=Math.hypot(p.x()-at.x(),p.z()-at.z());
            if(horizontal>=1.10||Math.abs(p.y()-at.y())>1.4)continue;
            registered++;
            if(!u.wasSpawnCompleted())pending++;
            nearest=Math.min(nearest,horizontal);
            if(registered<=8)registryIds.append(u.getEntityId()).append(':')
                    .append(u.wasSpawnCompleted()?"ready":"pending").append(':')
                    .append(CFTrace.xyz(p)).append(';');
        }
        for(Entity e:world.getNearbyEntities(at,1.85)) {
            if(e==exclude || !(e instanceof CFUnit u)||u.isDead()||u.isRemoved())continue;
            Pos p=u.getPosition();
            if(Math.hypot(p.x()-at.x(),p.z()-at.z())<1.10&&
                    Math.abs(p.y()-at.y())<=1.4)visible++;
        }
        // The two indexes are intentionally not assumed to be synchronized at
        // request time. The event says *what* differs, not *why* it differs.
        if(registered!=visible)CFTrace.event("SPAWN_OCCUPANCY_MISMATCH",
                "stage",stage,"team",team,"mob",mob,"lane",lane,
                "at",CFTrace.xyz(at),"registered",registered,"visible",visible,
                "pending",pending,"nearestDistance",Double.isFinite(nearest)?nearest:-1,
                "registryUnits",registryIds.toString());
        else if("NO_SAFE_SPACE_NEAREST".equals(stage))
            CFTrace.event("SPAWN_OCCUPANCY_PROBE","stage",stage,"team",team,
                    "mob",mob,"lane",lane,"at",CFTrace.xyz(at),
                    "registered",registered,"visible",visible,"pending",pending);
    }
}
