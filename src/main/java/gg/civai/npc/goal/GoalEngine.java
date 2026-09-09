package gg.civai.npc.goal;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Fast-tick deterministic executor for Goals.
 *
 * Called every 200 ms from AiNpc's game tick (main thread only).
 *
 * Responsibilities:
 *  - Execute the current Goal locally (A*-path movement, face-toward, follow logic)
 *  - Detect hostile mobs within 5 blocks and flee
 *  - Fire callbacks when a goal completes, fails, or a threat appears
 *
 * The LLM is NEVER called from here — only callbacks propagate up to AiNpc.
 *
 * v1.2.1 — A* block pathfinding replaces linear interpolation;
 *           position-based stuck detection replaces per-step counter.
 */
public class GoalEngine {

    // Movement
    private static final double MOVE_SPEED        = 0.25; // blocks per tick (bumped slightly)
    private static final double ARRIVAL_THRESHOLD = 1.0;  // blocks — "close enough" to sub-target

    // Threat
    private static final double THREAT_DETECT_RADIUS  = 5.0;
    private static final double THREAT_CLEAR_RADIUS   = 8.0;
    private static final long   THREAT_CLEAR_DELAY_MS = 3_000L;

    // Idle
    private static final long IDLE_FIRE_MS = 30_000L;

    // Follow
    private static final double FOLLOW_STOP_DIST   = 3.0;
    private static final double FOLLOW_OFFSET_DIST = 3.0;
    private static final long   FOLLOW_RECALC_MS   = 2_000L; // recalc follow path every 2 s

    // Converse
    private static final double CONVERSE_MAX_DIST = 4.0;

    // Wander
    private static final int WANDER_RADIUS_DEFAULT = 20;

    // Stuck detection (position-based)
    private static final int  STUCK_TICKS     = 12;   // ~2.4 s of zero movement
    private static final double STUCK_MOVE_SQ = 0.01; // must move this much per tick to reset

    // -------------------------------------------------------------------------

    private final Villager          entity;
    private final Logger            logger;
    private final String            npcName;
    private final int               scanRadius;

    private final Runnable          onGoalComplete;
    private final Consumer<String>  onGoalFailed;
    private final Runnable          onThreatDetected;

    // Current goal — never null after construction
    private Goal currentGoal;

    // Last 3 completed/failed goals (most-recent at tail)
    private final ArrayDeque<String> goalHistory = new ArrayDeque<>(4);

    // WANDER sub-state
    private Location wanderTarget = null;

    // Idle sub-state
    private long idleStartMs = 0;

    // ---- Pathfinding state ----
    private List<Location> currentPath       = null;
    private int            pathIdx           = 0;
    private Location       lastCheckedPos    = null; // for stuck detection
    private int            samePosTicks      = 0;
    private long           lastFollowRecalcMs = 0;   // for FOLLOW periodic recalc

    // Threat mode
    private boolean inThreatMode        = false;
    private Goal    preemptedGoal       = null;
    private long    threatClearStartMs  = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public GoalEngine(Villager entity, Logger logger, String npcName, int scanRadius,
                      Runnable onGoalComplete,
                      Consumer<String> onGoalFailed,
                      Runnable onThreatDetected) {
        this.entity           = entity;
        this.logger           = logger;
        this.npcName          = npcName;
        this.scanRadius       = scanRadius;
        this.onGoalComplete   = onGoalComplete;
        this.onGoalFailed     = onGoalFailed;
        this.onThreatDetected = onThreatDetected;

        currentGoal = Goal.idle();
        currentGoal.status = Goal.Status.ACTIVE;
        idleStartMs = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    public void tick() {
        if (entity == null || !entity.isValid()) return;

        checkForThreats();

        if (inThreatMode) {
            executeThreatFlee();
        } else {
            checkTimeout();
            executeCurrentGoal();
        }
    }

    // -------------------------------------------------------------------------
    // Goal management
    // -------------------------------------------------------------------------

    public void setGoal(Goal goal) {
        if (currentGoal != null && currentGoal.status == Goal.Status.ACTIVE) {
            addHistory(currentGoal.type + " → replaced");
        }
        goal.status         = Goal.Status.ACTIVE;
        currentGoal         = goal;
        wanderTarget        = null;
        currentPath         = null;
        pathIdx             = 0;
        samePosTicks        = 0;
        lastCheckedPos      = null;
        lastFollowRecalcMs  = 0;
        idleStartMs         = 0;
        logger.info("[" + npcName + "] Goal set: " + goal);
    }

    public Goal getCurrentGoal() { return currentGoal; }

    public long getGoalElapsedSeconds() {
        return currentGoal == null ? 0
                : (System.currentTimeMillis() - currentGoal.startedAt) / 1000L;
    }

    public List<String> getGoalHistory() {
        List<String> list = new ArrayList<>(goalHistory);
        Collections.reverse(list);
        return list;
    }

    // -------------------------------------------------------------------------
    // Threat detection
    // -------------------------------------------------------------------------

    private void checkForThreats() {
        Location loc = entity.getLocation();
        World    w   = loc.getWorld();

        boolean close = w.getNearbyEntities(loc,
                THREAT_DETECT_RADIUS, THREAT_DETECT_RADIUS, THREAT_DETECT_RADIUS)
                .stream().anyMatch(e -> e instanceof Monster);

        if (close && !inThreatMode) {
            inThreatMode       = true;
            preemptedGoal      = currentGoal;
            threatClearStartMs = 0;
            logger.info("[" + npcName + "] Threat detected — fleeing");
            onThreatDetected.run();
            return;
        }

        if (inThreatMode) {
            boolean clear = w.getNearbyEntities(loc,
                    THREAT_CLEAR_RADIUS, THREAT_CLEAR_RADIUS, THREAT_CLEAR_RADIUS)
                    .stream().noneMatch(e -> e instanceof Monster);

            if (clear) {
                if (threatClearStartMs == 0) {
                    threatClearStartMs = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - threatClearStartMs >= THREAT_CLEAR_DELAY_MS) {
                    inThreatMode = false;
                    if (preemptedGoal != null) {
                        logger.info("[" + npcName + "] Threat cleared — resuming " + preemptedGoal.type);
                        preemptedGoal.status = Goal.Status.ACTIVE;
                        currentGoal   = preemptedGoal;
                        preemptedGoal = null;
                        currentPath   = null; // force path recalc
                        wanderTarget  = null;
                    }
                }
            } else {
                threatClearStartMs = 0;
            }
        }
    }

    private void executeThreatFlee() {
        Location loc = entity.getLocation();
        World    w   = loc.getWorld();

        Entity nearest = w.getNearbyEntities(loc,
                THREAT_DETECT_RADIUS * 3, THREAT_DETECT_RADIUS * 3, THREAT_DETECT_RADIUS * 3)
                .stream()
                .filter(e -> e instanceof Monster)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(loc)))
                .orElse(null);

        if (nearest == null) return;

        double dx  = loc.getX() - nearest.getLocation().getX();
        double dz  = loc.getZ() - nearest.getLocation().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.1) { dx = Math.random() - 0.5; dz = Math.random() - 0.5; len = Math.sqrt(dx*dx+dz*dz); }

        moveStep(loc,
                loc.getX() + (dx / len) * MOVE_SPEED * 1.5,
                loc.getZ() + (dz / len) * MOVE_SPEED * 1.5);
    }

    // -------------------------------------------------------------------------
    // Timeout check
    // -------------------------------------------------------------------------

    private void checkTimeout() {
        if (currentGoal == null || currentGoal.timeoutMs <= 0) return;
        long elapsed = System.currentTimeMillis() - currentGoal.startedAt;
        if (elapsed >= currentGoal.timeoutMs) {
            switch (currentGoal.type) {
                case WANDER -> completeGoal();
                case GOTO   -> failGoal("could not reach destination within 60s");
                default     -> completeGoal();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Goal executors
    // -------------------------------------------------------------------------

    private void executeCurrentGoal() {
        if (currentGoal == null) return;
        switch (currentGoal.type) {
            case IDLE     -> executeIdle();
            case WANDER   -> executeWander();
            case FOLLOW   -> executeFollow();
            case GOTO     -> executeGoTo();
            case CONVERSE -> executeConverse();
        }
    }

    private void executeIdle() {
        if (idleStartMs == 0) idleStartMs = System.currentTimeMillis();
        if (System.currentTimeMillis() - idleStartMs >= IDLE_FIRE_MS) {
            completeGoal();
        }
    }

    private void executeWander() {
        Location loc = entity.getLocation();

        // Need a new sub-target (or just arrived / path exhausted)?
        if (wanderTarget == null || (isPathDone() && hasArrived(loc, wanderTarget))) {
            wanderTarget = pickWanderTarget(loc, currentGoal.wanderRadius > 0
                    ? currentGoal.wanderRadius : WANDER_RADIUS_DEFAULT);
            if (wanderTarget == null) return;

            startPath(loc, wanderTarget);
            samePosTicks   = 0;
            lastCheckedPos = null;
            logger.info("[" + npcName + "] Wander sub-target → ("
                    + (int) wanderTarget.getX() + "," + (int) wanderTarget.getY()
                    + "," + (int) wanderTarget.getZ() + ")"
                    + (currentPath != null ? " path=" + currentPath.size() + " nodes" : " (no path, direct)"));
        }

        // Move
        if (!isPathDone()) {
            followPath(loc);
        } else {
            // A* failed — direct movement fallback
            moveToward(loc, wanderTarget);
        }

        // Stuck detection: clear path + sub-target so a fresh one is picked next tick
        updateStuckDetection(true);
    }

    private void executeFollow() {
        String playerName = currentGoal.targetPlayerName;
        if (playerName == null) { failGoal("no player name"); return; }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) { failGoal("player offline: " + playerName); return; }
        if (!target.getWorld().equals(entity.getWorld())) { failGoal("player in different world"); return; }

        Location playerLoc = target.getLocation();
        Location npcLoc    = entity.getLocation();
        double   dist      = npcLoc.distance(playerLoc);

        if (dist <= FOLLOW_STOP_DIST) {
            faceLocation(playerLoc);
            samePosTicks   = 0;
            lastCheckedPos = null;
            return;
        }

        // Target = 3 blocks behind the player
        double yaw     = Math.toRadians(playerLoc.getYaw());
        double offsetX = Math.sin(yaw)  * FOLLOW_OFFSET_DIST;
        double offsetZ = -Math.cos(yaw) * FOLLOW_OFFSET_DIST;
        Location followTarget = playerLoc.clone().add(offsetX, 0, offsetZ);

        // Recalculate path periodically
        long now = System.currentTimeMillis();
        if (isPathDone() || now - lastFollowRecalcMs >= FOLLOW_RECALC_MS) {
            startPath(npcLoc, followTarget);
            lastFollowRecalcMs = now;
        }

        if (!isPathDone()) {
            followPath(npcLoc);
        } else {
            moveToward(npcLoc, followTarget);
        }
        updateStuckDetection(true);
    }

    private void executeGoTo() {
        Location loc    = entity.getLocation();
        Location target = new Location(entity.getWorld(),
                currentGoal.targetX, currentGoal.targetY, currentGoal.targetZ);

        if (hasArrived(loc, target)) {
            completeGoal();
            return;
        }

        // (Re)calculate path when needed: first tick OR every 5 s when path is exhausted.
        // If the target is > MAX_XZ blocks away, A* returns empty and we use direct movement
        // to close the gap; once within range the next 5-s retry will find a path.
        long now = System.currentTimeMillis();
        if (isPathDone() && now - lastFollowRecalcMs >= 5_000L) {
            startPath(loc, target);
            lastFollowRecalcMs = now;
            if (currentPath == null) {
                logger.fine("[" + npcName + "] GOTO: no path found (direct-movement fallback)");
            }
        }

        if (!isPathDone()) {
            followPath(loc);
        } else {
            moveToward(loc, target); // fallback: direct movement
        }
        updateStuckDetection(true);
    }

    private void executeConverse() {
        String playerName = currentGoal.targetPlayerName;
        if (playerName == null) { completeGoal(); return; }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline() || !target.getWorld().equals(entity.getWorld())) {
            completeGoal();
            return;
        }

        Location playerLoc = target.getLocation();
        Location npcLoc    = entity.getLocation();

        faceLocation(playerLoc);

        double dist = npcLoc.distance(playerLoc);
        if (dist > CONVERSE_MAX_DIST) {
            // Path toward player
            long now = System.currentTimeMillis();
            if (isPathDone() || now - lastFollowRecalcMs >= FOLLOW_RECALC_MS) {
                startPath(npcLoc, playerLoc);
                lastFollowRecalcMs = now;
            }
            if (!isPathDone()) {
                followPath(npcLoc);
            } else {
                moveToward(npcLoc, playerLoc);
            }
            updateStuckDetection(true);
        } else {
            // Close enough — stand still facing player; reset stuck state
            samePosTicks   = 0;
            lastCheckedPos = null;
        }
    }

    // -------------------------------------------------------------------------
    // Pathfinding helpers
    // -------------------------------------------------------------------------

    /** Calculate a new A* path from {@code from} to {@code to}. Sets currentPath / pathIdx. */
    private void startPath(Location from, Location to) {
        List<Location> path = BlockPathfinder.findPath(from, to, from.getWorld());
        if (path.isEmpty()) {
            currentPath = null;
        } else {
            currentPath = path;
        }
        pathIdx = 0;
    }

    /** True when there is no current path or it has been fully consumed. */
    private boolean isPathDone() {
        return currentPath == null || pathIdx >= currentPath.size();
    }

    /**
     * Advance along the current path toward the next waypoint.
     * Skips waypoints that are already within 0.6 blocks (XZ).
     */
    private void followPath(Location loc) {
        if (isPathDone()) return;

        // Advance past already-reached waypoints
        while (pathIdx < currentPath.size()) {
            Location wp = currentPath.get(pathIdx);
            double   dx = wp.getX() - loc.getX();
            double   dz = wp.getZ() - loc.getZ();
            if (dx * dx + dz * dz < 0.36) { // 0.6-block radius
                pathIdx++;
            } else {
                break;
            }
        }
        if (pathIdx >= currentPath.size()) { currentPath = null; return; }

        moveToward(loc, currentPath.get(pathIdx));
    }

    /**
     * Track whether the entity is actually moving.
     * If {@code expectingMovement} is true and the entity hasn't moved for STUCK_TICKS
     * consecutive ticks, clears the path and wanderTarget.
     */
    private void updateStuckDetection(boolean expectingMovement) {
        if (!expectingMovement) {
            lastCheckedPos = null;
            samePosTicks   = 0;
            return;
        }

        Location now = entity.getLocation();
        if (lastCheckedPos != null) {
            double movedSq = now.distanceSquared(lastCheckedPos);
            if (movedSq < STUCK_MOVE_SQ) {
                samePosTicks++;
                if (samePosTicks >= STUCK_TICKS) {
                    samePosTicks   = 0;
                    currentPath    = null;
                    wanderTarget   = null;
                    lastCheckedPos = null;
                    logger.info("[" + npcName + "] Stuck — clearing path + sub-target");
                }
            } else {
                samePosTicks = 0;
            }
        }
        lastCheckedPos = now.clone();
    }

    // -------------------------------------------------------------------------
    // Movement helpers
    // -------------------------------------------------------------------------

    private void moveToward(Location current, Location target) {
        double dx     = target.getX() - current.getX();
        double dz     = target.getZ() - current.getZ();
        double xzDist = Math.sqrt(dx * dx + dz * dz);
        if (xzDist < 0.1) return;

        double scale = Math.min(MOVE_SPEED, xzDist) / xzDist;
        moveStep(current,
                current.getX() + dx * scale,
                current.getZ() + dz * scale);
    }

    /**
     * Attempt a single horizontal step to (nx, nz), resolving Y via terrain logic.
     * Returns true if the entity actually moved.
     */
    private boolean moveStep(Location current, double nx, double nz) {
        World  w      = current.getWorld();
        int    bx     = (int) Math.floor(nx);
        int    bz     = (int) Math.floor(nz);
        int    byFeet = (int) Math.floor(current.getY());

        double ny = resolveY(w, bx, bz, byFeet, current.getY());
        if (Double.isNaN(ny) || Math.abs(ny - current.getY()) > 3.0) {
            return false;
        }

        double dx  = nx - current.getX();
        double dz2 = nz - current.getZ();
        float  yaw = (float) Math.toDegrees(Math.atan2(-dx, dz2));

        entity.teleport(new Location(w, nx, ny, nz, yaw, 0f));
        return true;
    }

    private void faceLocation(Location target) {
        Location cur = entity.getLocation();
        double   dx  = target.getX() - cur.getX();
        double   dz  = target.getZ() - cur.getZ();
        float    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entity.teleport(new Location(cur.getWorld(), cur.getX(), cur.getY(), cur.getZ(), yaw, 0f));
    }

    private boolean hasArrived(Location current, Location target) {
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        return (dx * dx + dz * dz) < (ARRIVAL_THRESHOLD * ARRIVAL_THRESHOLD);
    }

    /**
     * Pick a safe random wander sub-target within {@code radius} blocks.
     * Uses MOTION_BLOCKING_NO_LEAVES so decorations (short grass, flowers) don't
     * make the spot appear non-solid.
     */
    private Location pickWanderTarget(Location origin, int radius) {
        World w = origin.getWorld();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = Math.random() * 2 * Math.PI;
            double dist  = 4 + Math.random() * radius;
            int nx = (int) Math.floor(origin.getX() + Math.cos(angle) * dist);
            int nz = (int) Math.floor(origin.getZ() + Math.sin(angle) * dist);
            int ny = w.getHighestBlockYAt(nx, nz, HeightMap.MOTION_BLOCKING_NO_LEAVES);

            if (isLiquid(w.getBlockAt(nx, ny, nz))) continue;
            if (Math.abs(ny - origin.getBlockY()) > 5) continue;

            return new Location(w, nx + 0.5, ny + 1.0, nz + 0.5);
        }
        logger.fine("[" + npcName + "] pickWanderTarget: all candidates rejected near "
                + (int) origin.getX() + "," + (int) origin.getZ());
        return null;
    }

    // -------------------------------------------------------------------------
    // Terrain resolution
    // -------------------------------------------------------------------------

    private double resolveY(World w, int bx, int bz, int byFeet, double currentY) {
        Block foot  = w.getBlockAt(bx, byFeet,     bz);
        Block head  = w.getBlockAt(bx, byFeet + 1, bz);
        Block under = w.getBlockAt(bx, byFeet - 1, bz);

        if (isLiquid(foot) || isLiquid(head)) return Double.NaN;

        if (foot.isPassable() && head.isPassable()) {
            if (under.isPassable()) {
                int groundY = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (isLiquid(w.getBlockAt(bx, groundY, bz))) return Double.NaN;
                return groundY + 1.0;
            }
            return currentY;
        }

        // 1-block step-up
        Block stepFoot = w.getBlockAt(bx, byFeet + 1, bz);
        Block stepHead = w.getBlockAt(bx, byFeet + 2, bz);
        if (stepFoot.isPassable() && stepHead.isPassable()
                && !isLiquid(stepFoot) && !isLiquid(stepHead)) {
            return byFeet + 1.0;
        }

        return Double.NaN;
    }

    private static boolean isLiquid(Block b) {
        return switch (b.getType()) {
            case WATER, LAVA, BUBBLE_COLUMN -> true;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Goal completion / failure
    // -------------------------------------------------------------------------

    private void completeGoal() {
        addHistory(currentGoal.type + " → completed");
        currentGoal.status = Goal.Status.COMPLETED;
        logger.info("[" + npcName + "] Goal completed: " + currentGoal.type);
        currentGoal = Goal.idle();
        currentGoal.status = Goal.Status.ACTIVE;
        idleStartMs = Long.MAX_VALUE; // don't auto-fire idle until LLM responds
        currentPath = null;
        onGoalComplete.run();
    }

    private void failGoal(String reason) {
        addHistory(currentGoal.type + " → failed: " + reason);
        currentGoal.status     = Goal.Status.FAILED;
        currentGoal.failReason = reason;
        logger.info("[" + npcName + "] Goal failed: " + currentGoal.type + " — " + reason);
        currentGoal = Goal.idle();
        currentGoal.status = Goal.Status.ACTIVE;
        idleStartMs = Long.MAX_VALUE;
        currentPath = null;
        onGoalFailed.accept(reason);
    }

    private void addHistory(String entry) {
        goalHistory.addLast(entry);
        while (goalHistory.size() > 3) goalHistory.pollFirst();
    }
}
