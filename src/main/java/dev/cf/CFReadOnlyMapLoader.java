package dev.cf;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.anvil.AnvilLoader;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loads a prepared external Anvil map, masking its carpet markers in memory.
 * Never writes changes to the source map. Match towers are temporary.
 */
final class CFReadOnlyMapLoader implements ChunkLoader {
    private final AnvilLoader delegate;
    private final CFCarpetZones carpets;
    private final AtomicBoolean warned = new AtomicBoolean();

    CFReadOnlyMapLoader(Path mapRoot, CFCarpetZones carpets) {
        this.delegate = new AnvilLoader(mapRoot, Key.key("minecraft:overworld"));
        this.carpets = carpets;
    }

    @Override public void loadInstance(Instance instance) { delegate.loadInstance(instance); }
    @Override public Chunk loadChunk(Instance instance, int x, int z) {
        Chunk chunk = delegate.loadChunk(instance, x, z);
        carpets.preparePlayChunk(chunk);
        return chunk;
    }
    @Override public void saveChunk(Chunk chunk) { rejectWrite(); }
    @Override public void saveInstance(Instance instance) { rejectWrite(); }
    @Override public void saveChunks(Collection<Chunk> chunks) { rejectWrite(); }
    @Override public void unloadChunk(Chunk chunk) { delegate.unloadChunk(chunk); }
    @Override public boolean supportsParallelLoading() { return delegate.supportsParallelLoading(); }
    @Override public boolean supportsParallelSaving() { return false; }

    private void rejectWrite() {
        if (warned.compareAndSet(false, true))
            System.err.println("[CF MAP] Ignored a request to write to the read-only map.");
    }
}
