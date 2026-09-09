package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the list of active AI NPCs and routes player chat to them.
 *
 * v1.2 additions:
 *  - Night-fallen detection: scheduler polls every 30 s, fires LLM when world crosses tick 13000
 *  - EntityDamageEvent: fires LLM with reason "npc_attacked" if an NPC is hit
 *
 * Chat model (unchanged from v1.1):
 *  - "@Steve <message>" → immediate LLM call, memory recorded
 *  - Any other message within scan radius → passive chat log
 */
public class NpcManager implements Listener {

    private final AiNpcPlugin            plugin;
    private final OllamaClient           ollamaClient;
    private final NpcPersistenceManager  persistence;
    private final List<AiNpc>            npcs      = new ArrayList<>();

    private final String npcName;
    private final int    scanRadius;

    // Night-fall detection
    private boolean lastWasDay = true;
    private BukkitTask nightCheckTask;

    public NpcManager(AiNpcPlugin plugin, OllamaClient ollamaClient, NpcPersistenceManager persistence) {
        this.plugin       = plugin;
        this.ollamaClient = ollamaClient;
        this.persistence  = persistence;
        this.npcName      = plugin.getConfig().getString("npc.name", "SuperSteve");
        this.scanRadius   = plugin.getConfig().getInt("npc.scan-radius", 10);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startNightCheck();
    }

    // -------------------------------------------------------------------------
    // Spawn / remove
    // -------------------------------------------------------------------------

    /**
     * Spawn a new NPC at the given location and persist it.
     * Caller should check {@link #hasNpc(String)} first to avoid duplicates.
     */
    public AiNpc spawnAt(Location location) {
        AiNpc npc = new AiNpc(plugin, ollamaClient, npcName, scanRadius);
        npc.spawn(location);
        npcs.add(npc);
        persistence.save(npcName, location);
        return npc;
    }

    /** Remove the nearest NPC (also deletes its persistence entry). */
    public boolean removeNearest(Location location) {
        AiNpc nearest = null;
        double minDist = Double.MAX_VALUE;
        for (AiNpc npc : npcs) {
            if (!npc.isValid()) continue;
            double d = npc.getLocation().distance(location);
            if (d < minDist) { minDist = d; nearest = npc; }
        }
        if (nearest != null) {
            persistence.delete(nearest.getName());
            nearest.remove();
            npcs.remove(nearest);
            return true;
        }
        return false;
    }

    /** Despawn and remove all NPCs (called on plugin disable — does NOT delete persistence). */
    public void removeAll() {
        if (nightCheckTask != null) nightCheckTask.cancel();
        npcs.forEach(AiNpc::remove);
        npcs.clear();
    }

    /**
     * Restore NPCs from npcs.yml on plugin enable.
     * Called after NpcManager is constructed and events are registered.
     */
    public void loadSavedNpcs() {
        for (NpcPersistenceManager.SavedNpc saved : persistence.loadAll()) {
            World world = plugin.getServer().getWorld(saved.world());
            if (world == null) {
                plugin.getLogger().warning("Cannot restore NPC '" + saved.name()
                        + "': world '" + saved.world() + "' not found.");
                continue;
            }
            Location loc = new Location(world, saved.x(), saved.y(), saved.z());
            AiNpc npc = new AiNpc(plugin, ollamaClient, saved.name(), scanRadius);
            npc.spawn(loc);
            npcs.add(npc);
            plugin.getLogger().info("Restored NPC: " + saved.name() + " in " + saved.world());
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public List<AiNpc> getNpcs() { return npcs; }

    /** True if an NPC with this name is active OR has a saved persistence entry. */
    public boolean hasNpc(String name) {
        boolean active = npcs.stream().anyMatch(n -> n.getName().equals(name) && n.isValid());
        return active || persistence.hasSaved(name);
    }

    // -------------------------------------------------------------------------
    // Event: Player chat (unchanged from v1.1)
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        Player   player    = event.getPlayer();
        String   message   = PlainTextComponentSerializer.plainText().serialize(event.message());
        Location playerLoc = player.getLocation();

        String  tag    = "@" + npcName;
        boolean direct = message.toLowerCase().startsWith(tag.toLowerCase());

        if (direct) {
            String stripped = message.substring(tag.length()).trim();
            for (AiNpc npc : npcs) {
                if (!npc.isValid()) continue;
                npc.triggerImmediateResponse(player.getName(), stripped, playerLoc);
            }
        } else {
            String logEntry = "[" + player.getName() + "]: " + message;
            for (AiNpc npc : npcs) {
                if (!npc.isValid()) continue;
                npc.onPassiveChat(logEntry, playerLoc);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Event: NPC attacked (v1.2)
    // -------------------------------------------------------------------------

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity damaged = event.getEntity();
        for (AiNpc npc : npcs) {
            if (!npc.isValid()) continue;
            if (npc.getEntity() != null && npc.getEntity().getUniqueId().equals(damaged.getUniqueId())) {
                plugin.getLogger().info("[" + npcName + "] NPC attacked — triggering LLM");
                npc.triggerLlmCall("npc_attacked", "");
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Night-fall detection scheduler (v1.2)
    // -------------------------------------------------------------------------

    /**
     * Polls every 30 seconds. When the primary NPC's world crosses from day (tick < 13000)
     * to night (tick >= 13000), fires an LLM call with reason "night_fallen".
     */
    private void startNightCheck() {
        nightCheckTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (AiNpc npc : npcs) {
                if (!npc.isValid()) continue;
                Location loc = npc.getLocation();
                if (loc == null) continue;

                World  w      = loc.getWorld();
                boolean isDay = w.getTime() < 13000;

                if (lastWasDay && !isDay) {
                    plugin.getLogger().info("[" + npcName + "] Night fallen — triggering LLM");
                    npc.triggerLlmCall("night_fallen", "");
                }
                lastWasDay = isDay;
                break; // only check based on first valid NPC's world
            }
        }, 20L * 30, 20L * 30); // every 30 seconds
    }
}
