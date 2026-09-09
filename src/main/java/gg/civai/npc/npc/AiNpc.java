package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A single AI-controlled NPC.
 *
 * v1.1 additions:
 *  - ConversationMemory (last 10 exchanges, in-RAM)
 *  - Passive chat log (last 5 nearby messages not directed at Steve)
 *  - Immediate Ollama call when a player @mentions Steve
 *  - Self UUID passed to WorldState so Steve doesn't see himself
 *  - Player location forwarded to OllamaClient so MOVE_AND_SPEAK knows where to go
 *
 * Movement (v1.1 patch):
 *  - Terrain-following via getHighestBlockYAt
 *  - Simple 1-block step-up for obstacles (fences, walls)
 *  - Cliff guard: stops if ground delta > 3 blocks
 */
public class AiNpc {

    private final AiNpcPlugin  plugin;
    private final OllamaClient ollamaClient;
    private final Logger       logger;
    private final String       name;
    private final int          scanRadius;

    // Entity
    private Villager entity;
    private Location targetLocation;

    // State
    private NpcAction            currentAction = NpcAction.idle("Just spawned.");
    private final ConversationMemory memory    = new ConversationMemory(10);

    // Persistent activity — what the NPC is currently doing between AI ticks.
    // Fed back into the prompt each tick so the NPC has continuity of purpose.
    private String currentActivity = "just arrived, looking around";

    // Speech cooldown — prevents the NPC from narrating its existence every 10 s.
    // Direct @mentions always bypass this; autonomous speech is gated here.
    private long   lastSpeechMs             = 0L;
    private static final long SPEECH_COOLDOWN_MS = 45_000L; // 45 s between unsolicited speech

    // Continuous wander — when true, the game tick picks new sub-targets automatically
    // whenever the NPC arrives at its destination, so it keeps moving without waiting
    // for the next Ollama tick. Set by MOVE_TO (autonomous); cleared by IDLE or any
    // player-directed action.
    private boolean continuousWander = false;
    private static final int WANDER_RADIUS = 14; // max blocks from current pos per sub-target

    // Passive chat log — written from async thread, accessed from main thread
    private final ArrayDeque<String> recentChatLog = new ArrayDeque<>(5);

    // Scheduler handles
    private BukkitTask gameTick;
    private BukkitTask aiTick;

    // Prevents overlapping Ollama calls
    private volatile boolean thinkingLock = false;

    // Queued @mention — stored when thinkingLock is busy, fired after think completes.
    // Only the most recent @mention is kept; older ones are overwritten.
    private String   pendingPlayerName = null;
    private String   pendingMessage    = null;
    private Location pendingPlayerLoc  = null;

    public AiNpc(AiNpcPlugin plugin, OllamaClient ollamaClient, String name, int scanRadius) {
        this.plugin       = plugin;
        this.ollamaClient = ollamaClient;
        this.name         = name;
        this.scanRadius   = scanRadius;
        this.logger       = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void spawn(Location location) {
        entity = location.getWorld().spawn(location, Villager.class, v -> {
            v.customName(Component.text(name, NamedTextColor.YELLOW));
            v.setCustomNameVisible(true);
            v.setAI(false);              // we control movement
            v.setInvulnerable(true);
            v.setSilent(true);           // we handle speech
            v.setRemoveWhenFarAway(false);
        });

        targetLocation = location.clone();
        startGameTick();
        startAiTick();
        logger.info(name + " spawned at " + formatLoc(location));
    }

    public void remove() {
        if (gameTick != null) gameTick.cancel();
        if (aiTick   != null) aiTick.cancel();
        if (entity != null && entity.isValid()) entity.remove();
        logger.info(name + " removed.");
    }

    // -------------------------------------------------------------------------
    // Schedulers
    // -------------------------------------------------------------------------

    private void startGameTick() {
        gameTick = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            executeMovement();
        }, 0L, 4L); // 4 ticks = 200 ms
    }

    private void startAiTick() {
        long intervalTicks = 20L * plugin.getConfig().getInt("npc.ai-tick-seconds", 10);
        aiTick = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            if (thinkingLock) return; // immediate response in flight — skip this tick

            runThink(null, null, null); // periodic tick — no direct message
        }, 20L, intervalTicks);
    }

    // -------------------------------------------------------------------------
    // AI dispatch
    // -------------------------------------------------------------------------

    /**
     * Fires an immediate Ollama call when a player @mentions Steve.
     * Safe to call from an async thread — all entity access is deferred to main thread.
     *
     * @param playerName  the player who mentioned Steve
     * @param message     message content (prefix already stripped)
     * @param playerLoc   snapshot of player location for proximity check + prompt hint
     */
    public void triggerImmediateResponse(String playerName, String message, Location playerLoc) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            if (!entity.getWorld().equals(playerLoc.getWorld())) return;
            if (entity.getLocation().distance(playerLoc) > scanRadius) return;

            if (thinkingLock) {
                // Ollama is busy — queue this mention so it fires when the current think finishes.
                // Most-recent-wins: overwrite any previously queued mention.
                pendingPlayerName = playerName;
                pendingMessage    = message;
                pendingPlayerLoc  = playerLoc;
                logger.info("[" + name + "] @mention queued (busy): " + playerName + ": " + message);
                return;
            }

            runThink(playerName, message, playerLoc);
        });
    }

    /**
     * Core async think dispatch.
     *
     * @param directPlayerName  null for periodic ticks
     * @param directMessage     null for periodic ticks; the stripped @mention text
     * @param directPlayerLoc   null for periodic ticks; player location passed to prompt
     */
    private void runThink(String directPlayerName, String directMessage, Location directPlayerLoc) {
        thinkingLock = true;
        boolean isDirect = directPlayerName != null;

        // Snapshot mutable state on main thread before going async
        List<String> chatSnapshot    = new ArrayList<>(recentChatLog);
        String       activitySnapshot = currentActivity;

        WorldState state = new WorldState(
                entity.getLocation(), scanRadius, name,
                entity.getUniqueId(), chatSnapshot);

        // Async: call Ollama (blocking HTTP)
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            NpcAction action = ollamaClient.think(
                    state, memory, directPlayerName, directMessage, directPlayerLoc,
                    activitySnapshot);
            logger.info("[" + name + "] activity: " + activitySnapshot
                    + " → " + action.nextActivity
                    + " | action: " + action.type
                    + (action.speech != null ? " | speech: \"" + action.speech + "\"" : ""));

            // Back on main thread: apply + record in memory + drain any queued @mention
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                applyAction(action, isDirect);

                if (isDirect && action.speech != null) {
                    memory.add(directPlayerName, directMessage, action.speech);
                }

                thinkingLock = false;

                // If a player @mentioned while we were busy, respond now
                if (pendingPlayerName != null) {
                    String pName = pendingPlayerName;
                    String pMsg  = pendingMessage;
                    Location pLoc = pendingPlayerLoc;
                    pendingPlayerName = null;
                    pendingMessage    = null;
                    pendingPlayerLoc  = null;
                    logger.info("[" + name + "] firing queued @mention from " + pName);
                    runThink(pName, pMsg, pLoc);
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Chat input
    // -------------------------------------------------------------------------

    /**
     * Called from async thread when a player sends a message NOT directed at Steve.
     * Adds to the passive chat log if the player is within scan radius.
     */
    public synchronized void onPassiveChat(String logEntry, Location playerLoc) {
        if (entity == null || !entity.isValid()) return;
        Location npcLoc = entity.getLocation();
        if (!npcLoc.getWorld().equals(playerLoc.getWorld())) return;
        if (npcLoc.distance(playerLoc) > scanRadius) return;

        recentChatLog.addLast(logEntry);
        while (recentChatLog.size() > 5) recentChatLog.pollFirst();
    }

    // -------------------------------------------------------------------------
    // Action execution
    // -------------------------------------------------------------------------

    private void applyAction(NpcAction action, boolean isDirectMessage) {
        currentAction = action;

        // Update persistent activity descriptor for next tick
        if (action.nextActivity != null && !action.nextActivity.isBlank()) {
            currentActivity = action.nextActivity;
        }

        // Any new AI decision resets wander mode; MOVE_TO (autonomous only) re-enables it.
        continuousWander = false;

        switch (action.type) {
            case MOVE_TO -> {
                Location target = new Location(
                        entity.getWorld(), action.targetX, action.targetY, action.targetZ);
                if (entity.getLocation().distance(target) < 50) {
                    targetLocation = target;
                }
                // Autonomous MOVE_TO → keep wandering after arrival without waiting for Ollama
                if (!isDirectMessage) continuousWander = true;
            }
            case MOVE_AND_SPEAK -> {
                Location target = new Location(
                        entity.getWorld(), action.targetX, action.targetY, action.targetZ);
                if (entity.getLocation().distance(target) < 50) {
                    targetLocation = target;
                }
                if (action.speech != null) trySpeak(action.speech, isDirectMessage);
            }
            case SPEAK, REPORT, TIME_REPORT -> {
                if (action.speech != null) trySpeak(action.speech, isDirectMessage);
            }
            case IDLE -> {
                // stay put — most common autonomous state
            }
        }
    }

    /**
     * Speaks out loud, enforcing a cooldown on autonomous (non-directed) speech.
     * Direct @mention responses always bypass the cooldown.
     */
    private void trySpeak(String message, boolean isDirectMessage) {
        long now = System.currentTimeMillis();
        if (!isDirectMessage && (now - lastSpeechMs) < SPEECH_COOLDOWN_MS) {
            logger.fine("[" + name + "] speech suppressed (cooldown): " + message);
            return;
        }
        speak(message);
        lastSpeechMs = now;
    }

    // -------------------------------------------------------------------------
    // Movement — terrain-following with step-up
    // -------------------------------------------------------------------------

    private void executeMovement() {
        if (targetLocation == null) return;
        Location current = entity.getLocation();

        // Horizontal-only distance for "arrived" check
        double dx = targetLocation.getX() - current.getX();
        double dz = targetLocation.getZ() - current.getZ();
        double xzDist = Math.sqrt(dx * dx + dz * dz);
        if (xzDist < 0.5) {
            if (continuousWander) pickNextWanderTarget(); // pick next sub-target automatically
            return;
        }

        double speed = 0.2;
        double scale = Math.min(speed, xzDist) / xzDist;
        double nx = current.getX() + dx * scale;
        double nz = current.getZ() + dz * scale;

        World w  = current.getWorld();
        int bx   = (int) Math.floor(nx);
        int bz2  = (int) Math.floor(nz);
        int byFeet = (int) Math.floor(current.getY()); // block at foot level

        double ny = resolveY(w, bx, bz2, byFeet, current.getY());
        if (Double.isNaN(ny)) return; // obstacle with no step-up available

        // Cliff guard — don't jump or fall more than 3 blocks per step
        if (Math.abs(ny - current.getY()) > 3.0) return;

        Location next = new Location(w, nx, ny, nz,
                (float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
        entity.teleport(next);
    }

    /**
     * Determines the Y the NPC should stand at after stepping to (bx, bz).
     *
     * Strategy:
     *  1. If the destination column is clear at current foot height → stay on ground.
     *     If the block under the new position is air → step down to the highest solid block.
     *  2. If the destination foot block is impassable → try to step up one block.
     *  3. If that's also blocked → return NaN (stay put).
     *
     * @param byFeet  the block-Y at the NPC's current feet level (floor of entity Y)
     * @param currentY entity Y (may be fractional)
     */
    private double resolveY(World w, int bx, int bz, int byFeet, double currentY) {
        Block foot  = w.getBlockAt(bx, byFeet, bz);
        Block head  = w.getBlockAt(bx, byFeet + 1, bz);
        Block under = w.getBlockAt(bx, byFeet - 1, bz);

        // Never enter liquid — water is isPassable()=true in Bukkit, so must check explicitly
        if (isLiquid(foot) || isLiquid(head)) return Double.NaN;

        if (foot.isPassable() && head.isPassable()) {
            // Path is clear — check if ground dropped away
            if (under.isPassable()) {
                // Ground dropped; find solid surface
                int groundY = w.getHighestBlockYAt(bx, bz);
                // Don't step down into a lake or lava pool
                if (isLiquid(w.getBlockAt(bx, groundY, bz))) return Double.NaN;
                return groundY + 1.0;
            }
            return currentY; // flat or slight terrain, keep Y
        }

        // Foot is blocked — try stepping up one block
        Block stepFoot = w.getBlockAt(bx, byFeet + 1, bz);
        Block stepHead = w.getBlockAt(bx, byFeet + 2, bz);
        if (stepFoot.isPassable() && stepHead.isPassable()
                && !isLiquid(stepFoot) && !isLiquid(stepHead)) {
            return byFeet + 1.0;
        }

        return Double.NaN; // completely blocked
    }

    /**
     * Picks a random nearby point as the next wander sub-target.
     * Tries up to 8 candidates; skips any that land on liquid or a steep cliff.
     * Called from the game tick — runs on the main thread.
     */
    private void pickNextWanderTarget() {
        if (entity == null || !entity.isValid()) return;
        Location loc = entity.getLocation();
        World    w   = loc.getWorld();

        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = Math.random() * 2 * Math.PI;
            double dist  = 4 + Math.random() * WANDER_RADIUS;
            int    nx    = (int) Math.floor(loc.getX() + Math.cos(angle) * dist);
            int    nz    = (int) Math.floor(loc.getZ() + Math.sin(angle) * dist);
            int    ny    = w.getHighestBlockYAt(nx, nz); // top solid/liquid surface

            // Reject liquid surfaces (lake, ocean, lava)
            if (isLiquid(w.getBlockAt(nx, ny, nz))) continue;
            // Reject steep drops or climbs (> 5 blocks from current Y)
            if (Math.abs(ny - loc.getBlockY()) > 5) continue;

            targetLocation = new Location(w, nx + 0.5, ny + 1.0, nz + 0.5);
            return;
        }
        // All candidates rejected — stop wandering until next Ollama tick decides
        continuousWander = false;
    }

    /** Returns true for blocks the NPC should never step into or onto. */
    private static boolean isLiquid(Block b) {
        return switch (b.getType()) {
            case WATER, LAVA, BUBBLE_COLUMN -> true;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void speak(String message) {
        if (entity == null || !entity.isValid()) return;
        entity.getWorld().getNearbyPlayers(entity.getLocation(), scanRadius).forEach(p ->
                p.sendMessage(Component.text("[" + name + "] ", NamedTextColor.YELLOW)
                        .append(Component.text(message, NamedTextColor.WHITE))));
    }

    public boolean     isValid()         { return entity != null && entity.isValid(); }
    public Location    getLocation()     { return entity != null ? entity.getLocation() : null; }
    public String      getName()         { return name; }
    public NpcAction   getCurrentAction(){ return currentAction; }
    public ConversationMemory getMemory(){ return memory; }

    private String formatLoc(Location l) {
        return String.format("(%.1f, %.1f, %.1f) in %s",
                l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
    }
}
