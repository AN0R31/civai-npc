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
 *  - Execute the current Goal locally (movement, face-toward, follow logic)
 *  - Detect hostile mobs within 5 blocks and flee
 *  - Fire callbacks when a goal completes, fails, or a threat appears
 *
 * The LLM is NEVER called from here — only callbacks propagate up to AiNpc.
 *
 * v1.2 — new class
 */
public class GoalEngine {

    // Movement constants
    private static final double MOVE_SPEED          = 0.2;  // blocks per tick
    private static final double ARRIVAL_THRESHOLD   = 1.0;  // blocks — "close enough"

    // Threat constants
    private static final double THREAT_DETECT_RADIUS = 5.0;
    private static final double THREAT_CLEAR_RADIUS  = 8.0;
    private static final long   THREAT_CLEAR_DELAY_MS = 3_000L; // how long threat must be gone

    // Goal timeouts (also stored in Goal.timeoutMs)
    private static final long IDLE_FIRE_MS  = 30_000L; // IDLE → complete after 30 s
    private static final int  WANDER_RADIUS_DEFAULT = 20;

    // FOLLOW
    private static final double FOLLOW_STOP_DIST   = 3.0;  // stop when within 3 blocks
    private static final double FOLLOW_OFFSET_DIST = 3.0;  // target this far behind player

    // CONVERSE
    private static final double CONVERSE_MAX_DIST = 4.0;

    // -------------------------------------------------------------------------

    private final Villager entity;
    private final Logger   logger;
    private final String   npcName;
    private final int      scanRadius;

    private final Runnable          onGoalComplete;
    private final Consumer<String>  onGoalFailed;
    private final Runnable          onThreatDetected;

    // Current goal — never null after construction
    private Goal currentGoal;

    // Last 3 completed/failed goals (most-recent at tail)
    private final ArrayDeque<String> goalHistory = new ArrayDeque<>(4);

    // WANDER sub-state
    private Location wanderTarget = null;

    // IDLE sub-state
    private long idleStartMs = 0;

    // Threat mode
    private boolean inThreatMode       = false;
    private Goal    preemptedGoal      = null;
    private long    threatClearStartMs = 0;
    private boolean threatCallbackFired = false;

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

        // Start with IDLE so the LLM gets "goal_completed" on first cycle
        currentGoal = Goal.idle();
        currentGoal.status = Goal.Status.ACTIVE;
        idleStartMs = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Main tick — called every 200 ms from AiNpc's game tick
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

    /** Replace the current goal. Resets all sub-state. */
    public void setGoal(Goal goal) {
        if (currentGoal != null && currentGoal.status == Goal.Status.ACTIVE) {
            addHistory(currentGoal.type + " → replaced");
        }
        goal.status  = Goal.Status.ACTIVE;
        currentGoal  = goal;
        wanderTarget = null;
        idleStartMs  = 0;
        // Don't clear threat mode here — threat takes priority
        logger.info("[" + npcName + "] Goal set: " + goal);
    }

    public Goal getCurrentGoal()  { return currentGoal; }

    public long getGoalElapsedSeconds() {
        if (currentGoal == null) return 0;
        return (System.currentTimeMillis() - currentGoal.startedAt) / 1000L;
    }

    /** Returns last 3 goal records as strings (newest first). */
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

        boolean threatClose = w.getNearbyEntities(loc, THREAT_DETECT_RADIUS, THREAT_DETECT_RADIUS, THREAT_DETECT_RADIUS)
                .stream()
                .anyMatch(e -> e instanceof Monster);

        if (threatClose && !inThreatMode) {
            inThreatMode        = true;
            preemptedGoal       = currentGoal;
            threatClearStartMs  = 0;
            threatCallbackFired = false;
            logger.info("[" + npcName + "] Threat detected — fleeing");
            onThreatDetected.run();
            return;
        }

        if (inThreatMode) {
            boolean clearNow = w.getNearbyEntities(loc, THREAT_CLEAR_RADIUS, THREAT_CLEAR_RADIUS, THREAT_CLEAR_RADIUS)
                    .stream()
                    .noneMatch(e -> e instanceof Monster);

            if (clearNow) {
                if (threatClearStartMs == 0) {
                    threatClearStartMs = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - threatClearStartMs >= THREAT_CLEAR_DELAY_MS) {
                    // Threat has been gone 3+ seconds — resume previous goal
                    inThreatMode = false;
                    if (preemptedGoal != null) {
                        logger.info("[" + npcName + "] Threat cleared — resuming " + preemptedGoal.type);
                        preemptedGoal.status = Goal.Status.ACTIVE;
                        currentGoal  = preemptedGoal;
                        preemptedGoal = null;
                        wanderTarget  = null; // force new wander sub-target
                    }
                }
            } else {
                threatClearStartMs = 0; // reset if threat re-appears
            }
        }
    }

    private void executeThreatFlee() {
        Location loc = entity.getLocation();
        World    w   = loc.getWorld();

        // Find nearest hostile mob
        Entity nearest = w.getNearbyEntities(loc, THREAT_DETECT_RADIUS * 3, THREAT_DETECT_RADIUS * 3, THREAT_DETECT_RADIUS * 3)
                .stream()
                .filter(e -> e instanceof Monster)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(loc)))
                .orElse(null);

        if (nearest == null) return;

        // Direction away from threat
        double dx = loc.getX() - nearest.getLocation().getX();
        double dz = loc.getZ() - nearest.getLocation().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.1) { dx = Math.random() - 0.5; dz = Math.random() - 0.5; len = Math.sqrt(dx * dx + dz * dz); }

        moveStep(loc, loc.getX() + (dx / len) * MOVE_SPEED * 1.5,
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
                case WANDER -> completeGoal(); // wander timeout = natural completion
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
        // Otherwise: stand still — nothing to do
    }

    private void executeWander() {
        Location loc = entity.getLocation();

        // Need a sub-target?
        if (wanderTarget == null || hasArrived(loc, wanderTarget)) {
            wanderTarget = pickWanderTarget(loc, currentGoal.wanderRadius > 0
                    ? currentGoal.wanderRadius : WANDER_RADIUS_DEFAULT);
            if (wanderTarget == null) return; // all candidates rejected — wait next tick
            logger.info("[" + npcName + "] Wander sub-target → ("
                    + (int) wanderTarget.getX() + "," + (int) wanderTarget.getY()
                    + "," + (int) wanderTarget.getZ() + ")");
        }

        moveToward(loc, wanderTarget);
    }

    private void executeFollow() {
        String playerName = currentGoal.targetPlayerName;
        if (playerName == null) { failGoal("no player name set"); return; }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            failGoal("player not online: " + playerName);
            return;
        }
        if (!target.getWorld().equals(entity.getWorld())) {
            failGoal("player in different world");
            return;
        }

        Location playerLoc = target.getLocation();
        Location npcLoc    = entity.getLocation();

        double dist = npcLoc.distance(playerLoc);
        if (dist <= FOLLOW_STOP_DIST) {
            // Close enough — face the player, stand still
            faceLocation(playerLoc);
            return;
        }

        // Target = 3 blocks behind the player (opposite of player's facing)
        double yaw    = Math.toRadians(playerLoc.getYaw());
        double offsetX = Math.sin(yaw) * FOLLOW_OFFSET_DIST;
        double offsetZ = -Math.cos(yaw) * FOLLOW_OFFSET_DIST;
        Location followTarget = playerLoc.clone().add(offsetX, 0, offsetZ);

        moveToward(npcLoc, followTarget);
    }

    private void executeGoTo() {
        Location loc    = entity.getLocation();
        Location target = new Location(entity.getWorld(),
                currentGoal.targetX, currentGoal.targetY, currentGoal.targetZ);

        if (hasArrived(loc, target)) {
            completeGoal();
            return;
        }

        moveToward(loc, target);
    }

    private void executeConverse() {
        String playerName = currentGoal.targetPlayerName;
        if (playerName == null) { completeGoal(); return; }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline() || !target.getWorld().equals(entity.getWorld())) {
            completeGoal(); // player left — graceful completion
            return;
        }

        Location playerLoc = target.getLocation();
        Location npcLoc    = entity.getLocation();

        faceLocation(playerLoc);

        // Stay within CONVERSE_MAX_DIST
        if (npcLoc.distance(playerLoc) > CONVERSE_MAX_DIST) {
            moveToward(npcLoc, playerLoc);
        }
        // Never fires completion — runs until the LLM sets a different goal
    }

    // -------------------------------------------------------------------------
    // Movement helpers
    // -------------------------------------------------------------------------

    /**
     * Move one step (0.2 blocks) toward {@code target} from {@code current}.
     */
    private void moveToward(Location current, Location target) {
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        double xzDist = Math.sqrt(dx * dx + dz * dz);
        if (xzDist < 0.1) return;

        double scale = Math.min(MOVE_SPEED, xzDist) / xzDist;
        moveStep(current,
                current.getX() + dx * scale,
                current.getZ() + dz * scale);
    }

    /**
     * Execute a single horizontal step, resolving Y via terrain logic.
     */
    private void moveStep(Location current, double nx, double nz) {
        World w      = current.getWorld();
        int   bx     = (int) Math.floor(nx);
        int   bz     = (int) Math.floor(nz);
        int   byFeet = (int) Math.floor(current.getY());

        double ny = resolveY(w, bx, bz, byFeet, current.getY());
        if (Double.isNaN(ny)) return;                   // blocked — stay put
        if (Math.abs(ny - current.getY()) > 3.0) return; // cliff guard

        double dx = nx - current.getX();
        double dz = nz - current.getZ();
        float  yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        entity.teleport(new Location(w, nx, ny, nz, yaw, 0f));
    }

    /**
     * Face toward {@code target} without moving.
     */
    private void faceLocation(Location target) {
        Location cur = entity.getLocation();
        double dx = target.getX() - cur.getX();
        double dz = target.getZ() - cur.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entity.teleport(new Location(cur.getWorld(), cur.getX(), cur.getY(), cur.getZ(), yaw, 0f));
    }

    private boolean hasArrived(Location current, Location target) {
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        return (dx * dx + dz * dz) < (ARRIVAL_THRESHOLD * ARRIVAL_THRESHOLD);
    }

    /**
     * Pick a safe random wander sub-target within {@code radius} blocks of {@code origin}.
     * Avoids liquid surfaces and steep elevation changes.
     * Returns null if all 8 candidates fail.
     *
     * Uses MOTION_BLOCKING_NO_LEAVES heightmap so decorations (short grass, flowers, snow
     * layers) are ignored and we land on the actual solid ground block — not the decoration
     * sitting on top of it, which isSolid()=false and would reject every grassy candidate.
     */
    private Location pickWanderTarget(Location origin, int radius) {
        World w = origin.getWorld();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = Math.random() * 2 * Math.PI;
            double dist  = 4 + Math.random() * radius;
            int nx = (int) Math.floor(origin.getX() + Math.cos(angle) * dist);
            int nz = (int) Math.floor(origin.getZ() + Math.sin(angle) * dist);
            // MOTION_BLOCKING_NO_LEAVES: highest block that stops entity motion,
            // skipping leaves and non-solid decorations like short grass and flowers.
            int ny = w.getHighestBlockYAt(nx, nz, HeightMap.MOTION_BLOCKING_NO_LEAVES);

            if (isLiquid(w.getBlockAt(nx, ny, nz))) continue;    // no standing on water/lava
            if (Math.abs(ny - origin.getBlockY()) > 5) continue;  // no steep cliff

            return new Location(w, nx + 0.5, ny + 1.0, nz + 0.5);
        }
        logger.fine("[" + npcName + "] pickWanderTarget: all candidates rejected near "
                + (int)origin.getX() + "," + (int)origin.getZ());
        return null;
    }

    // -------------------------------------------------------------------------
    // Terrain resolution — ported from AiNpc v1.1
    // -------------------------------------------------------------------------

    /**
     * Determines the Y the NPC should stand at when stepping into (bx, bz).
     * Returns NaN if completely blocked.
     */
    private double resolveY(World w, int bx, int bz, int byFeet, double currentY) {
        Block foot  = w.getBlockAt(bx, byFeet,     bz);
        Block head  = w.getBlockAt(bx, byFeet + 1, bz);
        Block under = w.getBlockAt(bx, byFeet - 1, bz);

        if (isLiquid(foot) || isLiquid(head)) return Double.NaN;

        if (foot.isPassable() && head.isPassable()) {
            if (under.isPassable()) {
                // Use MOTION_BLOCKING_NO_LEAVES so we land on solid ground, not on a flower
                int groundY = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (isLiquid(w.getBlockAt(bx, groundY, bz))) return Double.NaN;
                return groundY + 1.0;
            }
            return currentY;
        }

        // Try step-up one block
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
    // Completion / failure helpers
    // -------------------------------------------------------------------------

    private void completeGoal() {
        addHistory(currentGoal.type + " → completed");
        currentGoal.status = Goal.Status.COMPLETED;
        logger.info("[" + npcName + "] Goal completed: " + currentGoal.type);
        // Reset to IDLE so we don't fire again before LLM responds
        currentGoal = Goal.idle();
        currentGoal.status = Goal.Status.ACTIVE;
        idleStartMs = Long.MAX_VALUE; // don't auto-fire idle again until LLM sets new goal
        onGoalComplete.run();
    }

    private void failGoal(String reason) {
        addHistory(currentGoal.type + " → failed: " + reason);
        currentGoal.status    = Goal.Status.FAILED;
        currentGoal.failReason = reason;
        logger.info("[" + npcName + "] Goal failed: " + currentGoal.type + " — " + reason);
        // Reset to IDLE
        currentGoal = Goal.idle();
        currentGoal.status = Goal.Status.ACTIVE;
        idleStartMs = Long.MAX_VALUE;
        onGoalFailed.accept(reason);
    }

    private void addHistory(String entry) {
        goalHistory.addLast(entry);
        while (goalHistory.size() > 3) goalHistory.pollFirst();
    }
}
