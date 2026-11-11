package dev.cf;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.StringWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Small, BUILD-only WorldEdit-style editor; no external plugin or per-tick scan.
 * World edits have no artificial volume/chunk count limit: coordinates are streamed
 * in batches and undo history is journaled to disk, never accumulated in RAM.
 * PLAY maps and live route checks are never edited.
 */
final class CFWorldEdit {
    static final int BLOCKS_PER_TICK = 256;
    static final int HISTORY_LIMIT = 8;  // number of undo operations, not area size
    private static final int JOURNAL_ENTRY_BYTES = 20; // x,y,z,before state,after state
    private static final Path HISTORY_DIR = Paths.get("diagnostics", "worldedit", "history");
    private static final Tag<String> WAND = Tag.String("cf.we.wand");

    record Cell(int x, int y, int z) {
        static Cell from(Point p) { return new Cell(p.blockX(), p.blockY(), p.blockZ()); }
    }
    record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Region between(Cell a, Cell b) {
            return new Region(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z),
                    Math.max(a.x,b.x),Math.max(a.y,b.y),Math.max(a.z,b.z));
        }
        long volume() {
            return ((long)maxX-minX+1) * ((long)maxY-minY+1) * ((long)maxZ-minZ+1);
        }
        long chunkCount() {
            return ((long)Math.floorDiv(maxX,16)-Math.floorDiv(minX,16)+1)
                    * ((long)Math.floorDiv(maxZ,16)-Math.floorDiv(minZ,16)+1);
        }
        void validate() {
            if(minY < -64 || maxY > 319 || minX < -29_999_984 || maxX > 29_999_984 ||
                    minZ < -29_999_984 || maxZ > 29_999_984)
                throw new IllegalArgumentException("Selection outside world height/border.");
        }
        Cursor cursor() {validate(); return new Cursor(this);}
        boolean wall(Cell c) {return c.x==minX||c.x==maxX||c.z==minZ||c.z==maxZ;}
        boolean floor(Cell c) {return c.y==minY;}
        static final class Cursor {
            private final Region r;
            private long visited;
            private int x,y,z;
            Cursor(Region r) {this.r=r;this.x=r.minX;this.y=r.minY;this.z=r.minZ;}
            boolean hasNext(){return visited<r.volume();}
            Cell peek(){if(!hasNext()) throw new NoSuchElementException();return new Cell(x,y,z);}
            void advance(){
                if(!hasNext())throw new NoSuchElementException();
                visited++;
                if(x<r.maxX)x++;
                else {x=r.minX;if(z<r.maxZ)z++;else{z=r.minZ;if(y<r.maxY)y++;}}
            }
            long position(){return visited;}
        }
    }
    private static final class Selection {
        Cell one, two;
    }
    // Journal entries are fixed 20-byte records, written to disk as edits happen.
    // A million changed blocks require ~20 MB on disk and O(1) journal RAM.
    record Entry(Cell pos, int beforeId, int afterId) {}
    record Edit(Path file, long count) {}
    static final class JournalWriter implements AutoCloseable {
        final Path file;
        final DataOutputStream out;
        long count;
        JournalWriter() throws IOException {
            Files.createDirectories(HISTORY_DIR);
            file=Files.createTempFile(HISTORY_DIR,"cfwe-", ".bin");
            out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file),64*1024));
        }
        void append(Cell c,Block before,Block after) throws IOException {
            out.writeInt(c.x);out.writeInt(c.y);out.writeInt(c.z);
            out.writeInt(before.stateId());out.writeInt(after.stateId());count++;
        }
        void flush() throws IOException {out.flush();}
        public void close() throws IOException {out.close();}
        Edit edit(){return new Edit(file,count);}
    }
    static final class JournalReader implements AutoCloseable {
        final DataInputStream in;
        final long length;
        long index;
        JournalReader(Edit edit) throws IOException {
            if(edit.count()<0 || edit.count()>Long.MAX_VALUE/JOURNAL_ENTRY_BYTES ||
                    Files.size(edit.file())!=edit.count()*JOURNAL_ENTRY_BYTES)
                throw new IOException("WorldEdit undo journal is incomplete: "+edit.file());
            length=edit.count();
            in=new DataInputStream(new BufferedInputStream(Files.newInputStream(edit.file()),64*1024));
        }
        boolean hasNext(){return index<length;}
        Entry read() throws IOException {
            if(!hasNext())throw new java.io.EOFException();
            int x=in.readInt(),y=in.readInt(),z=in.readInt();
            int before=in.readInt(),after=in.readInt();index++;
            return new Entry(new Cell(x,y,z),before,after);
        }
        public void close() throws IOException {in.close();}
    }
    private enum EditType { SET, REPLACE, WALLS, FLOOR, UNDO, REDO }

    private final InstanceContainer world;
    private final BooleanSupplier build, validationBusy;
    private final Consumer<String> routesDirty;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<Edit>> undo = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<Edit>> redo = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> current;
    private volatile String operation = "idle";
    private volatile boolean cancelRequested;
    private volatile UUID activeOwner;
    private volatile Batch activeBatch;
    private volatile String lastDiagnostic = "No WorldEdit operations yet.";
    private volatile String lastFailure = "none";
    private final AtomicLong diagSequence = new AtomicLong();
    private final Path diagFile = Paths.get("diagnostics", "worldedit",
            "worldedit-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".log");
    private final ExecutorService diagWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-worldedit-diagnostics");
        t.setDaemon(true);
        return t;
    });
    private void trace(long seq, String detail) {
        String line = "[CF WE TRACE #" + seq + "] " + detail;
        System.out.println(line);
        try {
            diagWriter.execute(() -> {
                try {
                    Files.createDirectories(diagFile.getParent());
                    Files.writeString(diagFile, line + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception ioe) {
                    System.err.println("[CF WE TRACE] Could not write diagnostic log " + diagFile + ": " + ioe);
                }
            });
        } catch (RejectedExecutionException ignored) { /* stopping */ }
    }
    private static void cleanAbandonedJournals() {
        // Undo is session-only. Delete journals left by previous shutdowns/crashes.
        if(!Files.isDirectory(HISTORY_DIR))return;
        try(DirectoryStream<Path> stale=Files.newDirectoryStream(HISTORY_DIR,"cfwe-*.bin")){
            for(Path f:stale)try{Files.deleteIfExists(f);}catch(IOException ignored){}
        }catch(IOException e){
            System.err.println("[CF WE] Cannot clean previous-session undo journals: "+e);
        }
    }
    private static String describe(Block block) {
        return block == null ? "null" : block.key() + "[id=" + block.id() +
                ",nbt=" + block.hasNbt() + ",blockEntity=" + block.blockEntityType() + "]";
    }
    private void traceThrowable(long seq, String context, Throwable failure) {
        StringWriter sw = new StringWriter();
        failure.printStackTrace(new PrintWriter(sw));
        trace(seq, "EXCEPTION " + context + " type=" + failure.getClass().getName() +
                " message=" + String.valueOf(failure.getMessage()));
        for (String line : sw.toString().split("\\R")) trace(seq, "STACK " + line);
        lastFailure = context + " | " + failure.getClass().getSimpleName() +
                ": " + String.valueOf(failure.getMessage());
    }

    CFWorldEdit(InstanceContainer world, BooleanSupplier build, BooleanSupplier validationBusy,
                Consumer<String> routesDirty) {
        this.world=world; this.build=build; this.validationBusy=validationBusy;
        this.routesDirty=routesDirty;
        cleanAbandonedJournals();
        var events=MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            Player p=e.getPlayer();
            if (!isWand(p) || p.getInstance()!=world || !build.getAsBoolean()) return;
            e.setCancelled(true); // selecting a corner must NOT destroy its block
            if (!allowed(p)) return;
            corner(p,true,Cell.from(e.getBlockPosition()));
        });
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            Player p=e.getPlayer();
            if(p.getInstance()!=world || !build.getAsBoolean() || p.getPermissionLevel()<4 ||
                    !"1".equals(p.getItemInHand(e.getHand()).getTag(WAND))) return;
            e.setCancelled(true);
            e.setBlockingItemUse(true);
            if (!allowed(p)) return;
            corner(p,false,Cell.from(e.getBlockPosition()));
        });
        events.addListener(PlayerDisconnectEvent.class, e -> {
            // History is intentionally session-only; no file IO or match state.
            UUID id=e.getPlayer().getUuid();
            selections.remove(id);clearHistory(undo.remove(id));clearHistory(redo.remove(id));
        });
    }

    boolean busy() { var running=current; return running!=null && !running.isDone(); }
    CompletableFuture<Void> whenIdle() {
        var running=current; return running==null?CompletableFuture.completedFuture(null):running;
    }

    void register() {
        Command we=new Command("we", "worldedit");
        we.setDefaultExecutor((s,c)->help(s));
        we.addSyntax((s,c)->help(s),ArgumentType.Literal("help"));
        we.addSyntax((s,c)->{
            if(!allowed(s) || !(s instanceof Player p)) return;
            ItemStack item=ItemStack.of(Material.WOODEN_AXE).withTag(WAND,"1")
                    .withCustomName(Component.text("CF WorldEdit Wand (BUILD)",NamedTextColor.AQUA));
            p.getInventory().addItemStack(item);
            s.sendMessage("[CF WE] Wooden axe: LEFT click = pos1, RIGHT click = pos2. Selection does not break blocks.");
        },ArgumentType.Literal("wand"));
        we.addSyntax((s,c)->standingCorner(s,true),ArgumentType.Literal("pos1"));
        we.addSyntax((s,c)->standingCorner(s,false),ArgumentType.Literal("pos2"));
        we.addSyntax((s,c)->selectionStatus(s),ArgumentType.Literal("size"));
        we.addSyntax((s,c)->selectionStatus(s),ArgumentType.Literal("status"));
        we.addSyntax((s,c)->debugStatus(s),ArgumentType.Literal("debug"));
        we.addSyntax((s,c)->cancel(s),ArgumentType.Literal("cancel"));
        var set=ArgumentType.Word("we-set-block");
        var replaceFrom=ArgumentType.Word("we-replace-from");
        var replaceTo=ArgumentType.Word("we-replace-to");
        var walls=ArgumentType.Word("we-walls-block");
        var floor=ArgumentType.Word("we-floor-block");
        we.addSyntax((s,c)->start(s,EditType.SET,null,block(c.get(set))),ArgumentType.Literal("set"),set);
        we.addSyntax((s,c)->start(s,EditType.REPLACE,block(c.get(replaceFrom)),block(c.get(replaceTo))),
                ArgumentType.Literal("replace"),replaceFrom,replaceTo);
        we.addSyntax((s,c)->start(s,EditType.WALLS,null,block(c.get(walls))),ArgumentType.Literal("walls"),walls);
        we.addSyntax((s,c)->start(s,EditType.FLOOR,null,block(c.get(floor))),ArgumentType.Literal("floor"),floor);
        we.addSyntax((s,c)->start(s,EditType.UNDO,null,null),ArgumentType.Literal("undo"));
        we.addSyntax((s,c)->start(s,EditType.REDO,null,null),ArgumentType.Literal("redo"));
        MinecraftServer.getCommandManager().register(we);
    }

    private void debugStatus(CommandSender s) {
        if (!(s instanceof Player p) || p.getPermissionLevel() < 4) {
            s.sendMessage("[CF WE] OP permission required."); return;
        }
        s.sendMessage("[CF WE DEBUG] busy=" + busy() + " operation=" + operation);
        s.sendMessage("[CF WE DEBUG] last=" + lastDiagnostic);
        s.sendMessage("[CF WE DEBUG] failure=" + lastFailure);
        s.sendMessage("[CF WE DEBUG] log=" + diagFile.toAbsolutePath());
    }
    private void help(CommandSender s) {
        s.sendMessage("[CF WE] BUILD-only: /we wand | /we pos1 | /we pos2 | /we size");
        s.sendMessage("[CF WE] /we set stone | /we replace dirt grass_block | /we walls stone | /we floor stone");
        s.sendMessage("[CF WE] /we undo | /we redo | /we cancel | /we debug. No block/chunk selection cap; 256 blocks/tick; disk-backed undo. /cf save after editing.");
    }
    private void cancel(CommandSender sender) {
        if(!(sender instanceof Player p) || p.getPermissionLevel()<4 || p.getInstance()!=world ||
                !build.getAsBoolean()) {sender.sendMessage("[CF WE] BUILD OP only.");return;}
        if(!busy()) {sender.sendMessage("[CF WE] Nothing to cancel.");return;}
        if(!p.getUuid().equals(activeOwner)) {
            sender.sendMessage("[CF WE] Only the editor who started this operation can cancel it.");return;
        }
        cancelRequested=true;
        Batch running=activeBatch;
        if(running!=null)world.scheduler().scheduleNextTick(running::tick);
        sender.sendMessage("[CF WE] Cancel requested. Partial changes will remain undoable.");
    }
    private boolean allowed(CommandSender s) {
        if(!(s instanceof Player p) || p.getInstance()!=world) {
            s.sendMessage("[CF WE] Use WorldEdit in-game on the Castle Fight map."); return false;
        }
        if (p.getPermissionLevel()<4) {s.sendMessage("[CF WE] OP permission required.");return false;}
        if (!build.getAsBoolean()) {s.sendMessage("[CF WE] Only in BUILD. /cf mode build + restart.");return false;}
        if (validationBusy.getAsBoolean()) {s.sendMessage("[CF WE] Route validation running. Try after it finishes.");return false;}
        if (busy()) {s.sendMessage("[CF WE] Editing in progress: "+operation);return false;}
        return true;
    }
    private static Block block(String value) {
        if (value == null || value.isBlank()) return null;
        String name=value.contains(":")?value:"minecraft:"+value;
        try {
            Block b=Block.fromKey(name);
            // Never create block entities with missing NBT/handlers via bulk edits.
            if (b!=null && b.blockEntityType()!=null) return null;
            return b;
        } catch (IllegalArgumentException e) { return null; }
    }
    private boolean isWand(Player p) {return p.getPermissionLevel()>=4 && "1".equals(p.getItemInMainHand().getTag(WAND));}
    private void corner(Player p,boolean first,Cell at) {
        Selection sel=selections.computeIfAbsent(p.getUuid(), ignored->new Selection());
        if(first)sel.one=at;else sel.two=at;
        p.sendMessage("[CF WE] pos"+(first?"1":"2")+" = "+at.x+","+at.y+","+at.z);
        if(sel.one!=null && sel.two!=null) {
            Region r=Region.between(sel.one,sel.two);
            p.sendMessage("[CF WE] Selection: "+r.volume()+" blocks, "+r.chunkCount()+" chunks. /we size");
        }
    }
    private void standingCorner(CommandSender sender,boolean first) {
        if(!allowed(sender))return;
        Player p=(Player)sender;
        var pos=p.getPosition();
        // Use the block beneath the feet (not a floating coordinate inside the player).
        corner(p,first,new Cell(pos.blockX(),pos.blockY()-1,pos.blockZ()));
    }
    private void selectionStatus(CommandSender sender) {
        if(!allowed(sender)) return;
        Player p=(Player)sender;
        Selection s=selections.get(p.getUuid());
        if(s==null || s.one==null || s.two==null) {
            sender.sendMessage("[CF WE] Need both corners. /we wand, LEFT/RIGHT click blocks.");return;
        }
        Region r=Region.between(s.one,s.two);
        sender.sendMessage("[CF WE] pos1="+s.one+" pos2="+s.two+" blocks="+r.volume()+" chunks="+r.chunkCount()+
                " undo="+stack(undo,p.getUuid()).size()+" redo="+stack(redo,p.getUuid()).size());
    }

    private void start(CommandSender sender,EditType type,Block from,Block to) {
        if(!allowed(sender))return;
        Player player=(Player)sender;
        UUID id=player.getUuid();
        Region region=null;
        Edit prior=null;
        if(type==EditType.UNDO || type==EditType.REDO){
            prior=stack(type==EditType.UNDO?undo:redo,id).peekFirst();
            if(prior==null){sender.sendMessage("[CF WE] Nothing to "+type.name().toLowerCase(Locale.ROOT));return;}
        }else{
            if(to==null || (type==EditType.REPLACE && from==null)){
                sender.sendMessage("[CF WE] Unknown/unsupported block. Use stone, air, minecraft:oak_planks. Block entities are excluded.");return;
            }
            Selection selection=selections.get(id);
            if(selection==null||selection.one==null||selection.two==null){
                sender.sendMessage("[CF WE] Set pos1 and pos2 first: /we wand.");return;
            }
            region=Region.between(selection.one,selection.two);
            try{region.validate();}catch(IllegalArgumentException e){sender.sendMessage("[CF WE] "+e.getMessage());return;}
        }
        long total=prior!=null?prior.count():region.volume();
        long opId=diagSequence.incrementAndGet();
        trace(opId,"START type="+type+" player="+player.getUsername()+" cells="+total+
                " chunks="+(region==null?"journal":region.chunkCount())+
                " from="+describe(from)+" to="+describe(to)+" world="+world.getUuid()+
                " streaming=true diskUndo=true");
        JournalWriter writer=null;
        JournalReader reader=null;
        try {
            if(prior==null)writer=new JournalWriter();
            else reader=new JournalReader(prior);
        }catch(IOException e){
            if(writer!=null)try{writer.close();Files.deleteIfExists(writer.file);}catch(IOException ignored){}
            sender.sendMessage("[CF WE] ERROR: Cannot open disk undo journal. No blocks changed: "+e.getMessage());
            traceThrowable(opId,"journal-open",e);return;
        }
        CompletableFuture<Void> done=new CompletableFuture<>();
        current=done;
        activeOwner=id;
        cancelRequested=false;
        operation=type.name()+" "+total+" blocks";
        sender.sendMessage("[CF WE] "+operation+"; no block/chunk limits. Batches of "+BLOCKS_PER_TICK+
                " blocks/tick. /we cancel to stop; /we undo for changed blocks.");
        Batch batch=new Batch(sender,id,type,from,to,region,prior,writer,reader,done,opId,total);
        activeBatch=batch;
        world.scheduler().scheduleNextTick(batch::tick);
    }

    private final class Batch {
        final CommandSender sender;
        final UUID owner;
        final EditType type;
        final Block from,to;
        final Region region;
        final Region.Cursor cursor;
        final Edit history;
        final JournalWriter writer;
        final JournalReader reader;
        final CompletableFuture<Void> done;
        final long opId,total;
        long processed,changed,skipped,already,unmatched;
        long ticks;
        Entry pending;
        Cell problem;
        String phase="not-started";
        Block debugBefore,debugAfter;
        long lastProgressTime=System.nanoTime();
        int pendingChunkX=Integer.MIN_VALUE,pendingChunkZ=Integer.MIN_VALUE,chunkLoadTries;
        boolean finished;

        Batch(CommandSender sender,UUID owner,EditType type,Block from,Block to,Region region,
                Edit history,JournalWriter writer,JournalReader reader,CompletableFuture<Void> done,
                long opId,long total){
            this.sender=sender;this.owner=owner;this.type=type;this.from=from;this.to=to;
            this.region=region;this.cursor=region==null?null:region.cursor();
            this.history=history;this.writer=writer;this.reader=reader;
            this.done=done;this.opId=opId;this.total=total;
        }
        boolean hasNext(){return processed<total;}
        Cell nextCell() throws IOException {
            if(history==null)return cursor.peek();
            if(pending==null)pending=reader.read();
            return pending.pos();
        }
        void advance(){
            if(history==null)cursor.advance();else pending=null;
            processed++;
            pendingChunkX=Integer.MIN_VALUE;chunkLoadTries=0;
        }
        void tick(){
            if(finished)return;
            try{
                if(!build.getAsBoolean()) throw new IllegalStateException("BUILD ended during edit");
                if(cancelRequested){complete(true);return;}
                ticks++;
                int batch=0;
                while(hasNext() && batch<BLOCKS_PER_TICK){
                    if(cancelRequested){complete(true);return;}
                    phase="select-next";
                    Cell c=nextCell();
                    problem=c;
                    int cx=Math.floorDiv(c.x,16),cz=Math.floorDiv(c.z,16);
                    Chunk loaded=world.getChunk(cx,cz);
                    if(loaded==null || !loaded.isLoaded()){
                        if(writer!=null)writer.flush();
                        loadChunk(cx,cz);return;
                    }
                    phase="read-block";
                    Block before=world.getBlock(c.x,c.y,c.z,Block.Getter.Condition.NONE);
                    debugBefore=before;
                    Block after;
                    phase="compute-after";
                    if(type==EditType.UNDO)after=Block.fromStateId(pending.beforeId());
                    else if(type==EditType.REDO)after=Block.fromStateId(pending.afterId());
                    else{
                        if(type==EditType.REPLACE && before.id()!=from.id()){
                            unmatched++;advance();batch++;continue;
                        }
                        if(type==EditType.WALLS && !region.wall(c)){
                            advance();batch++;continue;
                        }
                        if(type==EditType.FLOOR && !region.floor(c)){
                            advance();batch++;continue;
                        }
                        after=to;
                    }
                    debugAfter=after;
                    if(after==null)throw new IOException("Unknown block state in undo journal at "+c);
                    if(before.equals(after)){
                        already++;advance();batch++;continue;
                    }
                    if(before.blockEntityType()!=null||before.hasNbt()||after.blockEntityType()!=null||after.hasNbt()){
                        skipped++;advance();batch++;continue;
                    }
                    phase="write-block";
                    // Record BEFORE world mutation. On any partial failure, replaying the
                    // journal restores the old block, including if setBlock itself failed.
                    if(writer!=null)writer.append(c,before,after);
                    world.setBlock(c.x,c.y,c.z,after);
                    changed++;
                    advance();batch++;
                }
                if(writer!=null)writer.flush();
                long now=System.nanoTime();
                if(now-lastProgressTime>5_000_000_000L || !hasNext()){
                    lastProgressTime=now;
                    trace(opId,"PROGRESS processed="+processed+"/"+total+" changed="+changed+
                            " skippedNBT="+skipped+" noops="+already+" unmatched="+unmatched);
                    if(hasNext())sender.sendMessage("[CF WE] "+type+" progress "+processed+"/"+total+
                            " ("+String.format(Locale.ROOT,"%.1f",100.0*processed/total)+"%), changed="+changed);
                }
                if(hasNext())world.scheduler().scheduleNextTick(this::tick);
                else complete(false);
            }catch(Throwable failure){
                fail(failure);
            }
        }
        void loadChunk(int cx,int cz){
            phase="load-chunk";
            if(cx==pendingChunkX && cz==pendingChunkZ)chunkLoadTries++;
            else{pendingChunkX=cx;pendingChunkZ=cz;chunkLoadTries=1;}
            if(chunkLoadTries>3){fail(new IOException("Chunk never stays loaded: "+cx+","+cz));return;}
            trace(opId,"CHUNK_LOAD x="+cx+" z="+cz+" attempt="+chunkLoadTries+
                    " processed="+processed+"/"+total);
            try{
                world.loadOptionalChunk(cx,cz).whenComplete((chunk,error)->
                        world.scheduler().scheduleNextTick(()->{
                            if(finished)return;
                            if(error!=null){fail(new IOException("Chunk load failed: "+cx+","+cz,error));return;}
                            if(chunk==null){fail(new IOException("Chunk absent in saved map: "+cx+","+cz+
                                    " (WorldEdit never generates new chunks)"));return;}
                            tick();
                        }));
            }catch(Throwable e){fail(e);}
        }
        void fail(Throwable failure){
            if(finished)return;
            String context="type="+type+" phase="+phase+" pos="+problem+
                    " before="+describe(debugBefore)+" after="+describe(debugAfter)+
                    " processed="+processed+"/"+total+" changed="+changed;
            traceThrowable(opId,context,failure);
            lastDiagnostic="ERROR "+type+" at "+problem+" changed="+changed+
                    " cause="+failure.getClass().getSimpleName()+": "+failure.getMessage();
            finishBatch(false,false);
            sender.sendMessage("[CF WE] ERROR after "+changed+" changed blocks; phase="+phase+
                    ". "+failure.getMessage()+(history==null&&writer!=null&&writer.count>0?
                    ". Partial edit can be rolled back with /we undo.":""));
        }
        void complete(boolean cancelled){
            if(finished)return;
            lastDiagnostic=(cancelled?"CANCELLED ":"DONE ")+type+": changed="+changed+
                    " processed="+processed+"/"+total+" skippedNBT="+skipped+
                    " noops="+already+" unmatched="+unmatched;
            trace(opId,"COMPLETE "+lastDiagnostic);
            finishBatch(!cancelled,cancelled);
            sender.sendMessage("[CF WE] "+lastDiagnostic+
                    (changed>0?"; /cf save to persist. /we undo available.":"; nothing changed."));
        }
        void finishBatch(boolean success,boolean cancelled){
            if(finished)return;
            finished=true;
            try{
                if(reader!=null)reader.close();
                if(writer!=null){
                    writer.close();
                    if(writer.count>0){
                        push(stack(undo,owner),writer.edit());
                        clearHistory(stack(redo,owner));
                    }else Files.deleteIfExists(writer.file);
                }else if(success){
                    // A complete undo/redo moves its disk journal to the opposite stack.
                    var source=stack(type==EditType.UNDO?undo:redo,owner);
                    var dest=stack(type==EditType.UNDO?redo:undo,owner);
                    Edit sourceEdit=source.pollFirst();
                    if(sourceEdit!=null)push(dest,sourceEdit);
                }
                if(changed>0||(writer!=null&&writer.count>0))
                    routesDirty.accept("WORLDEDIT_"+type+(cancelled?"_PARTIAL":""));
            }catch(IOException e){
                traceThrowable(opId,"journal-close",e);
                sender.sendMessage("[CF WE] WARNING: Could not finalize undo journal: "+e.getMessage());
            }finally{
                operation="idle";
                activeOwner=null;
                activeBatch=null;
                cancelRequested=false;
                done.complete(null); // BUILD /cf save and route validation may proceed.
            }
        }
    }
    private static ArrayDeque<Edit> stack(Map<UUID,ArrayDeque<Edit>> all,UUID id){
        return all.computeIfAbsent(id,key->new ArrayDeque<>());
    }
    private static void clearHistory(ArrayDeque<Edit> edits){
        if(edits==null)return;
        while(!edits.isEmpty()){
            try{Files.deleteIfExists(edits.removeLast().file());}
            catch(IOException ignored){}
        }
    }
    private static void push(ArrayDeque<Edit> dest,Edit edit){
        dest.addFirst(edit);
        while(dest.size()>HISTORY_LIMIT){
            Edit removed=dest.removeLast();
            try{Files.deleteIfExists(removed.file());}catch(IOException ignored){}
        }
    }
}
