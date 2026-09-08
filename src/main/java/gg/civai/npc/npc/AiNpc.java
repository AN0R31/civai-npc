package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
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

    // Passive chat log — written from async thread, accessed from main thread
    private final ArrayDeque<String> recentChatLog = new ArrayDeque<>(5);

    // Scheduler handles
    private BukkitTask gameTick;
    private BukkitTask aiTick;

    // Prevents overlapping Ollama calls
    private volatile boolean thinkingLock = false;

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
            if (thinkingLock) return;

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

        // Snapshot chat log on main thread
        List<String> chatSnapshot = new ArrayList<>(recentChatLog);

        WorldState state = new WorldState(
                entity.getLocation(), scanRadius, name,
                entity.getUniqueId(), chatSnapshot);

        // Async: call Ollama (blocking HTTP)
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            NpcAction action = ollamaClient.think(
                    state, memory, directPlayerName, directMessage, directPlayerLoc);
            logger.info("[" + name + "] thought: " + action.thought
                    + " | action: " + action.type
                    + (action.speech != null ? " | speech: \"" + action.speech + "\"" : ""));

            // Back on main thread: apply + record in memory
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                applyAction(action);

                if (directPlayerName != null && action.speech != null) {
                    memory.add(directPlayerName, directMessage, action.speech);
                }

                thinkingLock = false;
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

    private void applyAction(NpcAction action) {
        currentAction = action;

        switch (action.type) {
            case MOVE_TO, MOVE_AND_SPEAK -> {
                Location target = new Location(
                        entity.getWorld(), action.targetX, action.targetY, action.targetZ);
                if (entity.getLocation().distance(target) < 50) {
                    targetLocation = target;
                }
                if (action.type == NpcAction.Type.MOVE_AND_SPEAK && action.speech != null) {
                    speak(action.speech);
                }
            }
            case SPEAK, REPORT, TIME_REPORT -> {
                if (action.speech != null) speak(action.speech);
            }
            case IDLE -> {
                // stay put
            }
        }
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
        if (xzDist < 0.5) return;

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

        if (foot.isPassable() && head.isPassable()) {
            // Path is clear — check if ground dropped away
            if (under.isPassable()) {
                // Ground dropped; find solid surface
                int groundY = w.getHighestBlockYAt(bx, bz);
                return groundY + 1.0;
            }
            return currentY; // flat or slight terrain, keep Y
        }

        // Foot is blocked — try stepping up one block
        Block stepFoot = w.getBlockAt(bx, byFeet + 1, bz);
        Block stepHead = w.getBlockAt(bx, byFeet + 2, bz);
        if (stepFoot.isPassable() && stepHead.isPassable()) {
            return byFeet + 1.0;
        }

        return Double.NaN; // completely blocked
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
