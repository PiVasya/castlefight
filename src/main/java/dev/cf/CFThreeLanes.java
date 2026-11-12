package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.command.CommandSender;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Игровой компилятор маршрутов для 1..4 дорожек.
 * Ковры ТОЛЬКО для баз; маяки НА дороге ТОЛЬКО для маршрутов.
 * Никакой совместимости со старыми картами или дорожными коврами нет.
 * Имя класса историческое; количество дорожек всегда вычисляется по маякам.
 */
final class CFThreeLanes {
    private final InstanceContainer world;
    private final Path mapRoot;
    private final CFCarpetZones carpets;
    private final CFPathBeacons beacons;
    private final Map<String,Pos> castles = new HashMap<>();
    private volatile Map<Integer,CFLanePath> lanes = Map.of();
    private volatile int laneCount;
    private volatile byte[] cacheFingerprint;
    private volatile String status = "NOT_CHECKED";
    private volatile CompletableFuture<Void> checking;
    /** Terrain uses a point probe, independently of unit size. */
    private static final BoundingBox TERRAIN_POINT = new BoundingBox(.04,.04,.04);
    private final double gap;

    CFThreeLanes(InstanceContainer world, Path mapRoot, CFCarpetZones carpets, CFPathBeacons beacons) {
        this.world = world;
        this.mapRoot = mapRoot;
        this.carpets = carpets;
        this.beacons = Objects.requireNonNull(beacons,"Путевые маяки обязательны в CF Map 3.0");
        Path file = mapRoot.resolve(CFMapFormat.FILE);
        Properties p = CFMapFormat.read(mapRoot);
        try {
            for (String team : List.of("blue", "red"))
                castles.put(team, readPos(p, team));
            gap = Double.parseDouble(p.getProperty("dot.max-gap", "12"));
            if (!Double.isFinite(gap) || gap < 2 || gap > 16)
                throw new IllegalArgumentException("dot.max-gap должен быть от 2 до 16");
        } catch (Exception e) {
            throw new IllegalStateException("Неверные настройки карты: " + file, e);
        }
    }

    static BoundingBox terrainPoint() { return TERRAIN_POINT; }
    BoundingBox envelope() { return TERRAIN_POINT; }
    synchronized Pos castle(String team) { return castles.get(team); }
    boolean busy(){var c=checking;return c!=null&&!c.isDone();}
    boolean ready(){return laneCount>0&&lanes.size()==laneCount&&!busy();}
    int laneCount(){return ready()?laneCount:0;}
    CFLanePath lane(int id){return lanes.get(id);}
    String status(){return "[КФ МАРШРУТЫ] "+status+" | дорожек="+laneCount+" | синий="+castle("blue")+" красный="+castle("red");}
    boolean reserved(int x,int y,int z){for(var p:lanes.values())if(p.reserved(x,y,z))return true;return false;}

    synchronized CompletableFuture<Void> check(CommandSender sender) {
        if(busy())return checking;
        var done=new CompletableFuture<Void>();checking=done;lanes=Map.of();laneCount=0;cacheFingerprint=null;status="ЗАГРУЗКА_КАРТЫ";
        Pos blue=castles.get("blue"),red=castles.get("red");
        if(blue==null||red==null){fail(done,sender,new IllegalArgumentException("На карте не заданы оба замка"));return done;}
        if(blue.distance(red)<4){fail(done,sender,new IllegalArgumentException("Замки расположены слишком близко"));return done;}
        Thread.ofVirtual().name("cf-route-map-cache").start(()->{
            try {
                byte[] fingerprint=CFLaneCache.fingerprint(mapRoot);
                var cached=CFLaneCache.read(mapRoot,fingerprint);
                // КРИТИЧНО: кеш хранит только маршруты, НЕ чанки мира!
                // Даже при попадании в кеш необходимо загрузить ВСЕ чанки
                // карты через CFReadOnlyMapLoader: он скрывает маяки и ковры
                // в памяти и подготавливает реальные блоки для AI и башен.
                // Запрещено делать done.complete() до полной загрузки мира.
                cacheFingerprint=fingerprint;
                List<Long> chunks=CFMapChunks.discover(mapRoot,world);
                world.scheduler().scheduleNextTick(()->loadBatch(chunks,0,blue,red,done,sender,cached));
            }catch(Exception e){fail(done,sender,e);}
        });
        if(sender!=null)sender.sendMessage("[КФ МАРШРУТЫ] Проверяю карту, маркеры и проходимость дорожек в обе стороны.");
        return done;
    }
    private void loadBatch(List<Long> keys,int offset,Pos blue,Pos red,CompletableFuture<Void> done,
                           CommandSender sender,Map<Integer,CFLanePath> cached) {
        if(done.isDone())return;
        try {
            if(offset>=keys.size()){
                if(cached!=null){
                    // Только теперь, когда карта загружена, кеш безопасно использовать.
                    lanes=cached;laneCount=cached.size();
                    status="ГОТОВО: "+laneCount+" дорожек из кеша; чанки карты загружены";
                    done.complete(null);System.out.println(status());
                    if(sender!=null)sender.sendMessage(status());
                }else prepare(blue,red,done,sender);
                return;
            }
            var loads=new ArrayList<CompletableFuture<?>>();int end=Math.min(offset+16,keys.size());
            for(int i=offset;i<end;i++){long k=keys.get(i);loads.add(world.loadChunk((int)(k>>32),(int)k));}
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).orTimeout(30,TimeUnit.SECONDS)
                    .whenComplete((v,e)->world.scheduler().scheduleNextTick(()->{
                        if(e!=null)fail(done,sender,e);else loadBatch(keys,end,blue,red,done,sender,cached);
                    }));
        }catch(Exception e){fail(done,sender,e);}
    }
    private record Segment(int lane,boolean reverse,Pos from,Pos to,boolean castleLink){}
    private void prepare(Pos blue,Pos red,CompletableFuture<Void> done,CommandSender sender) {
        try {
            var snapshot=carpets.snapshot(world);Block.Getter terrain=CFLaneSearch.terrain(world);
            blue=CFLaneSearch.surface(terrain,TERRAIN_POINT,blue);red=CFLaneSearch.surface(terrain,TERRAIN_POINT,red);
            // Путевые точки приходят ИСКЛЮЧИТЕЛЬНО от двухблочных маяков,
            // стоящих НА дороге. Ковры для маршрутов НЕ читаются никогда.
            var markers=CFRouteMarkers.collect(beacons.snapshot(world));
            int count=markers.count();
            for(var mark:snapshot.values())if(mark.lane()>count)
                throw new IllegalArgumentException("Найдены строительные ковры дорожки "+mark.lane()+
                        ", но путевых маркеров для неё нет");
            var queue=new ArrayDeque<Segment>();
            for(int id=1;id<=count;id++) {
                List<Pos> ordered;
                try{ordered=CFMarkerChain.order(markers.lane(id),blue,red,gap);}
                catch(Exception e){throw new IllegalArgumentException("ДОРОЖКА "+id+": "+e.getMessage());}
                for(String team:List.of("blue","red"))if(!hasFootprint(snapshot,id,team))
                    throw new IllegalArgumentException("Дорожка "+id+": нет полной зоны 3x3 для "+team+". /cf carpets");
                var anchors=new ArrayList<Pos>();anchors.add(blue);
                for(Pos p:ordered)anchors.add(CFLaneSearch.surface(terrain,TERRAIN_POINT,p));anchors.add(red);
                for(int i=1;i<anchors.size();i++) {
                    boolean end=i==1||i==anchors.size()-1;
                    queue.add(new Segment(id,false,anchors.get(i-1),anchors.get(i),end));
                    queue.add(new Segment(id,true,anchors.get(i),anchors.get(i-1),end));
                }
            }
            Map<Integer,List<List<Pos>>> forward=new HashMap<>(),backward=new HashMap<>();
            for(int i=1;i<=count;i++){forward.put(i,new ArrayList<>());backward.put(i,new ArrayList<>());}
            compileNext(queue,forward,backward,count,done,sender);
        }catch(Exception e){fail(done,sender,e);}
    }
    // Проверка карты должна требовать 3x3 ОДНОЙ дорожки и ОДНОЙ команды.
    // Нельзя принимать 3x3 просто «синих» или «красных» ковров разных путей.
    // При добавлении 4+ дорожек сохранить эту проверку для каждого laneId.
    static boolean hasFootprint(Map<CFPlacementRules.Cell,CFMarkerPalette.Mark> cells,int lane,String team) {
        for(var e:cells.entrySet()) {
            if(e.getValue().lane()!=lane||!team.equals(e.getValue().team()))continue;
            var c=e.getKey();boolean ok=true;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                var m=cells.get(new CFPlacementRules.Cell(c.x()+dx,c.y(),c.z()+dz));
                // Два альтернативных цвета одной пары team+lane НЕ смешивать.
                if(m==null||m.block().stateId()!=e.getValue().block().stateId())ok=false;
            }if(ok)return true;
        }return false;
    }
    private void compileNext(ArrayDeque<Segment> queue,Map<Integer,List<List<Pos>>> f,Map<Integer,List<List<Pos>>> r,
                             int count,CompletableFuture<Void> done,CommandSender sender) {
        if(done.isDone())return;
        if(queue.isEmpty()) {
            try{
                Map<Integer,CFLanePath> built=new HashMap<>();
                for(int i=1;i<=count;i++) {Collections.reverse(r.get(i));built.put(i,new CFLanePath(i,flatten(f.get(i)),flatten(r.get(i)),1.05));}
                lanes=Map.copyOf(built);laneCount=count;status="ГОТОВО: "+count+" дорожек ведут к обоим замкам";
                try {
                    CFLaneCache.save(mapRoot,cacheFingerprint,lanes);
                    if(cacheFingerprint!=null)System.out.println("[КФ КАРТА] Маршруты сохранены рядом с картой: cf/"+CFLaneCache.NAME);
                } catch(IOException writeError) {
                    System.err.println("[КФ КАРТА] Не удалось сохранить кеш; при следующем запуске будет повторная проверка: "+writeError);
                }
                writeReport(status,null);done.complete(null);
                if(sender!=null){sender.sendMessage("[КФ МАРШРУТЫ] ПРОВЕРЕНО: "+count+" дорожек, оба направления.");
                    for(var e:new TreeMap<>(lanes).entrySet())sender.sendMessage("[КФ МАРШРУТЫ] Дорожка "+e.getKey()+": синие="+e.getValue().blue.size()+", красные="+e.getValue().red.size()+" узлов.");}
                System.out.println(status());
            }catch(Exception e){fail(done,sender,e);}return;
        }
        Segment s=queue.removeFirst();status="Проверяю дорожку "+s.lane+" "+(s.reverse?"к синему замку":"к красному замку")+"; осталось сегментов="+queue.size();
        try{
            List<Pos> segment=CFLaneSearch.find(CFLaneSearch.terrain(world),TERRAIN_POINT,s.from,s.to,
                    CFLaneSearch.tube(s.from,s.to,s.castleLink?8:4.5));
            (s.reverse?r:f).get(s.lane).add(segment);
            world.scheduler().scheduleNextTick(()->compileNext(queue,f,r,count,done,sender));
        }catch(Exception e){fail(done,sender,new IllegalArgumentException("ДОРОЖКА "+s.lane+" "+(s.reverse?"К СИНЕМУ":"К КРАСНОМУ")+
                " — непроходимый отрезок "+s.from+" -> "+s.to+": "+e.getMessage()));}
    }
    private static List<Pos> flatten(List<List<Pos>> parts){var out=new ArrayList<Pos>();for(var list:parts)for(Pos p:list)
        if(out.isEmpty()||p.distanceSquared(out.getLast())>.0001)out.add(p);return List.copyOf(out);}
    private void fail(CompletableFuture<Void> done,CommandSender sender,Throwable e) {
        if(done.isDone())return;
        lanes=Map.of();laneCount=0;status="ОШИБКА: "+e.getMessage();System.err.println(status());
        writeReport(status,e);done.completeExceptionally(e);if(sender!=null)sender.sendMessage(status());
    }
    private void writeReport(String message,Throwable e){
        CFSkeletonMindLog.systemEvent("ROUTE_VALIDATION","result",message);
        try{
            Path out=Path.of("diagnostics/routes");Files.createDirectories(out);
            var text=new StringBuilder("КФ: проверка дорожек карты\n").append(Instant.now()).append('\n').append(message).append('\n');
            for(var entry:new TreeMap<>(lanes).entrySet())for(String team:List.of("blue","red")){
                int i=0;for(Pos p:entry.getValue().forTeam(team))text.append(entry.getKey()).append(',').append(team).append(',').append(i++).append(',').append(CFTrace.xyz(p)).append('\n');
            }
            Files.writeString(out.resolve("last-check.txt"),text);
        }catch(IOException ex){System.err.println("[CF ROUTES] Report write failed: "+ex);}
    }
    static Pos readPos(Properties p,String key){
        Pos pos=new Pos(Double.parseDouble(p.getProperty(key+".x")),Double.parseDouble(p.getProperty(key+".y")),Double.parseDouble(p.getProperty(key+".z")));
        if(!Double.isFinite(pos.x())||!Double.isFinite(pos.y())||!Double.isFinite(pos.z())||Math.abs(pos.x())>30000000||Math.abs(pos.z())>30000000||pos.y()<-64||pos.y()>319)
            throw new IllegalArgumentException("Некорректные координаты замка");return pos;
    }
}
