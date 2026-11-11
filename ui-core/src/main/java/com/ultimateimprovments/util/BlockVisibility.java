package com.ultimateimprovments.util;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * 🛡 BlockVisibility — «no breaking through walls» filter for area-breakers.
 * <p>
 * Area enchantments (AoE, VeinMiner, TreeCapitator and anything similar) must not
 * reach blocks that are hidden behind other blocks. Without this filter a naive
 * cube scan finds a block only by its TYPE: a player places, say, magma on the
 * outside of a bedrock box, breaks it — and the magma sealed inside the box is
 * mined as well, even though there is no physical path to it.
 * <p>
 * The sweep here is a <b>6-direction flood fill</b> from the broken block that may
 * only travel through:
 * <ul>
 *   <li>blocks of the <b>target type</b> (they are broken anyway, so the sweep is
 *       allowed to continue through the hole they leave behind);</li>
 *   <li><b>see-through</b> blocks — air, liquids, plants, torches and other
 *       non-solid blocks ({@link #isSeeThrough(Material)}).</li>
 * </ul>
 * Any other block (stone, bedrock, planks, glass, ...) is a wall: the sweep stops
 * there and nothing behind it is broken. The result is a normal "line of sight"
 * behaviour: you can never mine a block you could not physically reach.
 * <p>
 * The scan stays inside the same cube the callers used before
 * (offset −radius..+radius on every axis, clamped to the world height) and skips
 * unloaded chunks, so it never loads a chunk.
 */
public final class BlockVisibility {

    /** Hard cap on the scan radius (matches the AoE max level: 21×21×21 cube). */
    public static final int MAX_RADIUS = 10;

    /** 6-direction offsets (axis-aligned neighbours, no diagonal leakage). */
    private static final int[][] DIRS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private BlockVisibility() {}

    /**
     * Collects every block of {@code type} reachable from {@code origin} without
     * passing through a solid foreign block.
     *
     * @param world   world of the origin block
     * @param origin  the block the player just broke (the start of the sweep)
     * @param type    material to collect (usually the origin block's type)
     * @param radius  cube radius around the origin (clamped to {@link #MAX_RADIUS})
     * @return the matching blocks (never {@code null}; the origin is not included
     *         unless the sweep walks back to it through the same type)
     */
    public static List<Block> collectReachable(World world, Block origin, Material type, int radius) {
        List<Block> found = new ArrayList<>();
        if (world == null || origin == null || type == null || radius < 0) return found;

        int r = Math.min(radius, MAX_RADIUS);
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        int minX = ox - r;
        int maxX = ox + r;
        int minZ = oz - r;
        int maxZ = oz + r;
        int minY = Math.max(world.getMinHeight(), oy - r);
        int maxY = Math.min(world.getMaxHeight() - 1, oy + r);

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int total = sizeX * sizeY * sizeZ;

        boolean[] visited = new boolean[total];
        // Every cell is enqueued at most once, so the queue never exceeds the cube size.
        int[] queue = new int[total];

        int start = index(ox - minX, oy - minY, oz - minZ, sizeY, sizeZ);
        visited[start] = true;
        queue[0] = start;
        int head = 0;
        int tail = 1;

        while (head < tail) {
            int cur = queue[head++];

            int iz = cur % sizeZ;
            int rest = cur / sizeZ;
            int iy = rest % sizeY;
            int ix = rest / sizeY;

            int x = minX + ix;
            int y = minY + iy;
            int z = minZ + iz;

            for (int[] dir : DIRS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];

                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue;
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) continue;

                int neighbour = index(nx - minX, ny - minY, nz - minZ, sizeY, sizeZ);
                if (visited[neighbour]) continue;

                Block block = world.getBlockAt(nx, ny, nz);
                Material blockType = block.getType();
                boolean target = blockType == type;

                // A solid block of another type is a wall: the sweep stops here.
                // It is marked as visited so it is not re-tested from every
                // adjoining open cell.
                if (!target && !isSeeThrough(blockType)) {
                    visited[neighbour] = true;
                    continue;
                }

                visited[neighbour] = true;
                if (target) found.add(block);
                queue[tail++] = neighbour;
            }
        }

        return found;
    }

    /**
     * Whether the sweep may travel through a block of this material.
     * <p>
     * Air, liquids, plants and other non-solid blocks do not obstruct; every
     * solid block does (including glass — a glass wall must not leak an
     * area-break into the room behind it).
     */
    public static boolean isSeeThrough(Material material) {
        if (material == null) return true;
        if (material.isAir()) return true;
        if (!material.isBlock()) return true;
        return !material.isSolid();
    }

    /** Flat index of a cell inside the 3D visited/queue arrays. */
    private static int index(int ix, int iy, int iz, int sizeY, int sizeZ) {
        return (ix * sizeY + iy) * sizeZ + iz;
    }
}
