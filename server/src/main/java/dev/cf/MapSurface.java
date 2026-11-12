package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;

/** Find a conservative full-block floor with two clear blocks above it. */
final class MapSurface {
    private MapSurface() { }

    /** Point-based tower exit; requires a floor and empty feet, never a mob-sized gap. */
    static Pos findFloorOnly(Instance world, int x, int z, double nearY, int radius) {
        if (world.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) == null) return null;
        int center = (int) Math.floor(nearY);
        for (int offset = 0; offset <= radius; offset++) {
            int[] ys = offset == 0 ? new int[]{center} : new int[]{center + offset, center - offset};
            for (int y : ys) {
                if (y < -63 || y > 317) continue;
                Block floor = world.getBlock(x, y - 1, z);
                if (!floor.solid() || floor.liquid() ||
                        !floor.collisionShape().isFaceFull(BlockFace.TOP)) continue;
                Block feet = world.getBlock(x, y, z);
                if (!feet.liquid() && feet.air()) return new Pos(x + .5, y, z + .5);
            }
        }
        return null;
    }

    static Pos find(Instance world, int x, int z, double nearY, int radius) {
        if (world.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) == null) return null;
        int center = (int) Math.floor(nearY);
        for (int offset = 0; offset <= radius; offset++) {
            int[] candidates = offset == 0 ? new int[]{center} :
                    new int[]{center + offset, center - offset};
            for (int y : candidates) {
                if (y < -63 || y > 317) continue;
                Block floor = world.getBlock(x, y - 1, z);
                if (!floor.solid() || floor.liquid() ||
                        !floor.collisionShape().isFaceFull(BlockFace.TOP)) continue;
                if (world.getBlock(x, y, z).air() && world.getBlock(x, y + 1, z).air()) {
                    return new Pos(x + .5, y, z + .5);
                }
            }
        }
        return null;
    }
}
