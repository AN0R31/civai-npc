package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.logging.Logger;

/**
 * A single AI-controlled NPC. Uses a Villager entity for now (easy to extend).
 */
public class AiNpc {

    private final AiNpcPlugin plugin;
    private final OllamaClient ollamaClient;
    private final Logger logger;
    private final String name;
    private final int scanRadius;

    private Villager entity;
    private Location targetLocation;
    private NpcAction currentAction = NpcAction.idle("Just spawned.");
    private String lastPlayerMessage = "";

    private BukkitTask gameTick;
    private BukkitTask aiTick;
    private boolean thinkingLock = false; // prevent overlapping AI calls

    public AiNpc(AiNpcPlugin plugin, OllamaClient ollamaClient, String name, int scanRadius) {
        this.plugin = plugin;
        this.ollamaClient = ollamaClient;
        this.name = name;
        this.scanRadius = scanRadius;
        this.logger = plugin.getLogger();
    }

    public void spawn(Location location) {
        entity = location.getWorld().spawn(location, Villager.class, v -> {
            v.customName(Component.text(name, NamedTextColor.YELLOW));
            v.setCustomNameVisible(true);
            v.setAI(false);          // disable vanilla AI, we control it
            v.setInvulnerable(true); // don't want it dying on us
            v.setSilent(true);       // we handle speech ourselves
            v.setRemoveWhenFarAway(false);
        });

        targetLocation = location.clone();

        startGameTick();
        startAiTick();

        logger.info(name + " spawned at " + formatLoc(location));
    }

    private void startGameTick() {
        long intervalTicks = 4L; // 4 ticks = 200ms
        gameTick = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            executeMovement();
        }, 0L, intervalTicks);
    }

    private void startAiTick() {
        long aiIntervalTicks = 20L * plugin.getConfig().getInt("npc.ai-tick-seconds", 10);
        aiTick = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            if (thinkingLock) return;

            thinkingLock = true;
            WorldState state = new WorldState(entity.getLocation(), scanRadius, lastPlayerMessage, name);
            lastPlayerMessage = ""; // clear after consuming

            // Run Ollama call async
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                NpcAction action = ollamaClient.think(state);
                logger.info("[" + name + "] thought: " + action.thought);

                // Apply action back on main thread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    applyAction(action);
                    thinkingLock = false;
                });
            });

        }, 20L, aiIntervalTicks); // first call after 1 second
    }

    private void applyAction(NpcAction action) {
        currentAction = action;

        switch (action.type) {
            case MOVE_TO, MOVE_AND_SPEAK -> {
                Location target = new Location(entity.getWorld(), action.targetX, action.targetY, action.targetZ);
                // Clamp movement: don't let AI teleport the NPC huge distances
                if (entity.getLocation().distance(target) < 50) {
                    targetLocation = target;
                }
                if (action.type == NpcAction.Type.MOVE_AND_SPEAK && action.speech != null) {
                    speak(action.speech);
                }
            }
            case SPEAK -> {
                if (action.speech != null) speak(action.speech);
            }
            case IDLE -> {
                // do nothing, NPC stays put
            }
        }
    }

    private void executeMovement() {
        if (targetLocation == null) return;
        Location current = entity.getLocation();
        double dist = current.distance(targetLocation);

        if (dist < 0.5) return; // close enough

        // Move NPC toward target (simple lerp, 0.2 blocks per game tick)
        double speed = 0.2;
        double dx = targetLocation.getX() - current.getX();
        double dy = targetLocation.getY() - current.getY();
        double dz = targetLocation.getZ() - current.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double nx = current.getX() + (dx / length) * Math.min(speed, dist);
        double nz = current.getZ() + (dz / length) * Math.min(speed, dist);

        Location next = new Location(current.getWorld(), nx, current.getY(), nz,
                (float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);

        entity.teleport(next);
    }

    private void speak(String message) {
        if (entity == null || !entity.isValid()) return;
        entity.getWorld().getNearbyPlayers(entity.getLocation(), scanRadius).forEach(player ->
            player.sendMessage(Component.text("[" + name + "] ", NamedTextColor.YELLOW)
                    .append(Component.text(message, NamedTextColor.WHITE)))
        );
    }

    public void onPlayerMessage(String playerName, String message) {
        lastPlayerMessage = playerName + " said: \"" + message + "\"";
    }

    public void remove() {
        if (gameTick != null) gameTick.cancel();
        if (aiTick != null) aiTick.cancel();
        if (entity != null && entity.isValid()) entity.remove();
        logger.info(name + " removed.");
    }

    public boolean isValid() {
        return entity != null && entity.isValid();
    }

    public Location getLocation() {
        return entity != null ? entity.getLocation() : null;
    }

    public String getName() { return name; }

    public NpcAction getCurrentAction() { return currentAction; }

    private String formatLoc(Location l) {
        return String.format("(%.1f, %.1f, %.1f) in %s", l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
    }
}