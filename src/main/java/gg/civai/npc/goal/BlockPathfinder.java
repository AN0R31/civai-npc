package gg.civai.npc.goal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

/**
 * A* block-level pathfinder for AI NPC movement.
 *
 * <ul>
 *   <li>8-directional movement (cardinal + diagonal)</li>
 *   <li>1-block step-up, 2-block step-down</li>
 *   <li>Refuses liquids, non-passable blocks, and tall obstacles (fences, walls)</li>
 *   <li>Max search: {@value MAX_NODES} nodes, {@value MAX_XZ} blocks horizontal</li>
 * </ul>
 *
 * Returns {@link Location}s at entity-foot Y (the Y the entity would be teleported to),
 * centred on each block (+0.5 on x and z).  Start node is excluded.
 *
 * v1.2.1 — new class
 */
public final class BlockPathfinder {

    static final int MAX_NODES = 1500;
    static final int MAX_XZ    = 48;   // max horizontal radius from start

    // 8-directional movement offsets
    private static final int[][] DIRS = {
        { 1,  0}, {-1,  0}, { 0,  1}, { 0, -1},
        { 1,  1}, { 1, -1}, {-1,  1}, {-1, -1}
    };

    private BlockPathfinder() {} // static utility

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Find a block-level path from {@code from} to {@code to}.
     *
     * @param from  entity start location (feet)
     * @param to    goal location
     * @param world the world to search in
     * @return ordered list of waypoints (entity foot Y, block-centred), or empty if unreachable
     */
    public static List<Location> findPath(Location from, Location to, World world) {
        int sx = from.getBlockX(), sy = from.getBlockY(), sz = from.getBlockZ();
        int ex = to.getBlockX(),                          ez = to.getBlockZ();

        if (Math.abs(ex - sx) > MAX_XZ || Math.abs(ez - sz) > MAX_XZ) {
            return Collections.emptyList();
        }

        HashMap<Long, ANode> openMap   = new HashMap<>();
        HashSet<Long>        closed    = new HashSet<>();
        PriorityQueue<ANode> openQueue = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        ANode start = new ANode(sx, sy, sz, 0.0, heuristic(sx, sz, ex, ez), null);
        openMap.put(pack(sx, sy, sz), start);
        openQueue.add(start);

        int expanded = 0;
        while (!openQueue.isEmpty() && expanded < MAX_NODES) {
            ANode cur    = openQueue.poll();
            long  curKey = pack(cur.x, cur.y, cur.z);

            // Skip stale entries (re-inserted with better g)
            ANode live = openMap.get(curKey);
            if (live != cur) continue;
            openMap.remove(curKey);
            closed.add(curKey);
            expanded++;

            // Goal reached (within 1 block horizontally)
            if (Math.abs(cur.x - ex) <= 1 && Math.abs(cur.z - ez) <= 1) {
                return reconstruct(cur, world);
            }

            for (int[] dir : DIRS) {
                int nx = cur.x + dir[0];
                int nz = cur.z + dir[1];

                int ny = walkableY(world, nx, nz, cur.y);
                if (ny == Integer.MIN_VALUE) continue;

                long nKey = pack(nx, ny, nz);
                if (closed.contains(nKey)) continue;

                double stepG = (dir[0] != 0 && dir[1] != 0) ? 1.414 : 1.0;
                double newG  = cur.g + stepG;
                double newF  = newG + heuristic(nx, nz, ex, ez);

                ANode existing = openMap.get(nKey);
                if (existing == null || newG < existing.g) {
                    ANode node = new ANode(nx, ny, nz, newG, newF, cur);
                    openMap.put(nKey, node);
                    openQueue.add(node);
                }
            }
        }

        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Find a walkable Y the entity would occupy when stepping into (nx, nz) from foot Y {@code fromY}.
     * Tries: same Y, 1 up, 1 down, 2 down.  Returns {@link Integer#MIN_VALUE} if blocked.
     */
    static int walkableY(World world, int nx, int nz, int fromY) {
        // step-up first, then same level, then small drops
        for (int dy = 1; dy >= -2; dy--) {
            int testY = fromY + dy;
            if (isWalkable(world, nx, testY, nz)) return testY;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * True if an entity (2 blocks tall) can stand with feet at block (x, y, z).
     * Requirements:
     * <ul>
     *   <li>foot (y) and head (y+1) are passable and not liquid</li>
     *   <li>ground (y-1) is solid, not liquid, not a tall obstacle</li>
     * </ul>
     */
    static boolean isWalkable(World world, int x, int y, int z) {
        Block foot   = world.getBlockAt(x, y,     z);
        Block head   = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);

        if (!foot.isPassable()  || isLiquid(foot))  return false;
        if (!head.isPassable()  || isLiquid(head))  return false;
        if ( ground.isPassable() || isLiquid(ground)) return false;
        if (isTall(ground))                          return false;

        return true;
    }

    private static boolean isLiquid(Block b) {
        return switch (b.getType()) {
            case WATER, LAVA, BUBBLE_COLUMN -> true;
            default -> false;
        };
    }

    /**
     * Blocks taller than 1 block in hitbox height — entities cannot stand on them
     * because the hitbox protrudes into y+1 space (fences 1.5 blocks, walls 1.5 blocks, etc.).
     */
    private static boolean isTall(Block b) {
        String n = b.getType().name();
        return n.contains("_FENCE") || n.contains("_WALL") || n.contains("IRON_BARS");
    }

    /** Chebyshev distance — admissible heuristic for 8-directional grids. */
    private static double heuristic(int x1, int z1, int x2, int z2) {
        return Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
    }

    /**
     * Pack (x, y, z) to a long key.
     * Valid for x, z in ±100 000 (typical playable Minecraft area) and y in −64..320.
     */
    private static long pack(int x, int y, int z) {
        // x, z: +100_000 → 0..200_000 (18 bits each)
        // y: +64 → 0..384 (9 bits)
        // layout: [x:18][y:9][z:18] = 45 bits — fits in a signed long
        return ((long)(x + 100_000) << 27) | ((long)(y + 64) << 18) | (z + 100_000);
    }

    /** Trace parent chain back to root; reverse; skip start node. */
    private static List<Location> reconstruct(ANode goal, World world) {
        List<Location> path = new ArrayList<>();
        for (ANode n = goal; n != null; n = n.parent) {
            path.add(new Location(world, n.x + 0.5, n.y, n.z + 0.5));
        }
        Collections.reverse(path);
        if (!path.isEmpty()) path.remove(0); // we're already at the start
        return path;
    }

    // -------------------------------------------------------------------------
    // Node
    // -------------------------------------------------------------------------

    private static final class ANode {
        final int   x, y, z;
        final double g, f;
        final ANode  parent;

        ANode(int x, int y, int z, double g, double f, ANode parent) {
            this.x = x; this.y = y; this.z = z;
            this.g = g; this.f = f;
            this.parent = parent;
        }
    }
}
