package dev.cf;

import net.minestom.server.instance.Instance;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/** Discover only the 10,201 possible Anvil chunks in the map window. */
final class CFMapChunks {
    static List<Long> discover(Path root, Instance world) throws IOException {
        var chunks = new TreeSet<Long>();
        // Synthetic regression worlds can already have chunks in memory.
        for (var chunk : world.getChunks()) {
            if (CFMapWindow.includesChunk(chunk.getChunkX(), chunk.getChunkZ()))
                chunks.add(CFCarpetZones.key(chunk.getChunkX(), chunk.getChunkZ()));
        }
        chunks.addAll(CFMapWindow.discoverAnvil(root));
        if (chunks.isEmpty())
            throw new IOException("Не найдено чанков карты внутри квадрата X/Z от -50 до +50. "
                    + "Проверь расположение арены относительно нуля координат.");
        if (chunks.size() > CFMapWindow.MAX_CHUNKS)
            throw new IOException("Количество чанков превысило окно CF Map 3.0: " + chunks.size());
        System.out.println("[КФ КАРТА] Активное окно: X/Z чанков от -" + CFMapWindow.RADIUS_CHUNKS
                + " до +" + CFMapWindow.RADIUS_CHUNKS + "; найдено " + chunks.size()
                + " из максимум " + CFMapWindow.MAX_CHUNKS
                + ". Остальные чанки мира игнорируются, исходные .mca не меняются.");
        return List.copyOf(chunks);
    }
    private CFMapChunks() { }
}
