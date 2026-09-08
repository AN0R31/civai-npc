package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
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
    private NpcAction           currentAction = NpcAction.idle("Just spawned.");
    private final ConversationMemory memory    = new ConversationMemory(10);

    // Passive chat log — written from async thread, read from main thread
    private final ArrayDeque<String> recentChatLog = new ArrayDeque<>(5);

    // Scheduler handles
    private BukkitTask gameTick;
    private BukkitTask aiTick;

    // Prevents overlapping Ollama calls (regular tick + immediate response)
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

            runThink(null, null); // regular periodic tick (no direct message)
        }, 20L, intervalTicks);
    }

    // -------------------------------------------------------------------------
    // AI dispatch
    // -------------------------------------------------------------------------

    /**
     * Fires an immediate Ollama call when a player @mentions Steve.
     * Safe to call from an async thread — schedules actual work on main thread.
     *
     * @param playerName   the player who mentioned Steve
     * @param message      the message content (prefix already stripped)
     * @param playerLoc    snapshot of player location for proximity check
     */
    public void triggerImmediateResponse(String playerName, String message, Location playerLoc) {
        // Jump to main thread for entity access + lock check
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            // Only respond if the player is within scan radius
            if (!entity.getWorld().equals(playerLoc.getWorld())) return;
            if (entity.getLocation().distance(playerLoc) > scanRadius) return;
            if (thinkingLock) return; // already processing something

            runThink(playerName, message);
        });
    }

    /**
     * Core async think dispatch.
     *
     * @param directPlayerName  null for periodic ticks; set when responding to an @mention
     * @param directMessage     null for periodic ticks; the stripped @mention text
     */
    private void runThink(String directPlayerName, String directMessage) {
        thinkingLock = true;

        // Snapshot chat log (main thread, so no lock needed for the deque)
        List<String> chatSnapshot = new ArrayList<>(recentChatLog);

        WorldState state = new WorldState(
                entity.getLocation(), scanRadius, name,
                entity.getUniqueId(), chatSnapshot);

        // Async: call Ollama (blocking HTTP)
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            NpcAction action = ollamaClient.think(state, memory, directPlayerName, directMessage);
            logger.info("[" + name + "] thought: " + action.thought);

            // Back on main thread: apply + record
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                applyAction(action);

                // Record in memory if this was a direct exchange
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
     * Adds it to the passive chat log (max 5 entries).
     */
    public synchronized void onPassiveChat(String logEntry, Location playerLoc) {
        // Distance check using last-known entity location — safe read from async thread
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
                Location target = new Location(entity.getWorld(), action.targetX, action.targetY, action.targetZ);
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

    private void executeMovement() {
        if (targetLocation == null) return;
        Location current = entity.getLocation();
        double dist = current.distance(targetLocation);
        if (dist < 0.5) return;

        double speed = 0.2;
        double dx = targetLocation.getX() - current.getX();
        double dy = targetLocation.getY() - current.getY();
        double dz = targetLocation.getZ() - current.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double nx = current.getX() + (dx / len) * Math.min(speed, dist);
        double nz = current.getZ() + (dz / len) * Math.min(speed, dist);

        Location next = new Location(current.getWorld(), nx, current.getY(), nz,
                (float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
        entity.teleport(next);
    }

    private void speak(String message) {
        if (entity == null || !entity.isValid()) return;
        entity.getWorld().getNearbyPlayers(entity.getLocation(), scanRadius).forEach(p ->
                p.sendMessage(Component.text("[" + name + "] ", NamedTextColor.YELLOW)
                        .append(Component.text(message, NamedTextColor.WHITE))));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public boolean     isValid()         { return entity != null && entity.isValid(); }
    public Location    getLocation()     { return entity != null ? entity.getLocation() : null; }
    public String      getName()         { return name; }
    public NpcAction   getCurrentAction(){ return currentAction; }
    public ConversationMemory getMemory(){ return memory; }

    private String formatLoc(Location l) {
        return String.format("(%.1f, %.1f, %.1f) in %s", l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
    }
}
