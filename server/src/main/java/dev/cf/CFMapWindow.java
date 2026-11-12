package dev.cf;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * CF Map 3.0: the active source world is the inclusive square of chunk
 * coordinates [-50..50] in X and Z, centred on chunk (0,0).
 *
 * No .mca files are edited or deleted. Only Anvil chunks within this window
 * are discovered, read into the game, and eligible for map compilation.
 * Keep this rule independent of the castle / beacon / carpet markers.
 */
final class CFMapWindow {
    static final int RADIUS_CHUNKS = 50;
    static final int SIDE_CHUNKS = RADIUS_CHUNKS * 2 + 1;
    static final int MAX_CHUNKS = SIDE_CHUNKS * SIDE_CHUNKS; // 10,201
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    static boolean includesChunk(int cx, int cz) {
        return cx >= -RADIUS_CHUNKS && cx <= RADIUS_CHUNKS
                && cz >= -RADIUS_CHUNKS && cz <= RADIUS_CHUNKS;
    }

    static boolean includesRegion(int rx, int rz) {
        long left = (long) rx * 32, top = (long) rz * 32;
        return left <= RADIUS_CHUNKS && left + 31 >= -RADIUS_CHUNKS
                && top <= RADIUS_CHUNKS && top + 31 >= -RADIUS_CHUNKS;
    }

    /** Sorted region files that actually overlap the permitted window. */
    static List<Path> relevantRegions(Path mapRoot) throws IOException {
        Path folder = mapRoot.resolve("dimensions/minecraft/overworld/region");
        if (!Files.isDirectory(folder)) return List.of();
        try (var stream = Files.list(folder)) {
            var files = new ArrayList<Path>();
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                var matcher = REGION_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                try {
                    int rx = Integer.parseInt(matcher.group(1));
                    int rz = Integer.parseInt(matcher.group(2));
                    if (includesRegion(rx, rz)) files.add(file);
                } catch (NumberFormatException invalidName) {
                    // Coordinates outside the integer coordinate range cannot
                    // intersect the tiny window around origin.
                }
            }
            files.sort(Path::compareTo);
            return List.copyOf(files);
        }
    }

    /**
     * Read only location tables (4 KiB) of intersecting Anvil regions.
     * Region files may also contain chunks OUTSIDE the square: ignore them.
     * This remains cheap for worlds with vast unexplored or generated areas.
     */
    static List<Long> discoverAnvil(Path mapRoot) throws IOException {
        var found = new TreeSet<Long>();
        for (Path file : relevantRegions(mapRoot)) {
            var m = REGION_FILE.matcher(file.getFileName().toString());
            if (!m.matches()) continue; // protected by relevantRegions
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            try (var in = new DataInputStream(Files.newInputStream(file))) {
                for (int index = 0; index < 1024; index++) {
                    int location = in.readInt();
                    int cx = rx * 32 + index % 32;
                    int cz = rz * 32 + index / 32;
                    if (location != 0 && includesChunk(cx, cz)) {
                        found.add(((long) cx << 32) ^ (cz & 0xffffffffL));
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private CFMapWindow() { }
}
