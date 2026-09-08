package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the list of active AI NPCs and routes player chat to them.
 *
 * Chat model (v1.1):
 *  - "@Steve <message>" → immediate Ollama call, memory recorded
 *  - Any other message within scan radius → passive chat log (Steve observes but doesn't react immediately)
 *  - Right-click interaction removed (was redundant with @mention)
 */
public class NpcManager implements Listener {

    private final AiNpcPlugin            plugin;
    private final OllamaClient           ollamaClient;
    private final NpcPersistenceManager  persistence;
    private final List<AiNpc>            npcs      = new ArrayList<>();

    private final String npcName;
    private final int    scanRadius;

    public NpcManager(AiNpcPlugin plugin, OllamaClient ollamaClient, NpcPersistenceManager persistence) {
        this.plugin       = plugin;
        this.ollamaClient = ollamaClient;
        this.persistence  = persistence;
        this.npcName      = plugin.getConfig().getString("npc.name", "SuperSteve");
        this.scanRadius   = plugin.getConfig().getInt("npc.scan-radius", 10);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
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
    // Chat event — Paper AsyncChatEvent
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        Player player   = event.getPlayer();
        String message  = PlainTextComponentSerializer.plainText().serialize(event.message());
        Location playerLoc = player.getLocation(); // position snapshot (safe to read async)

        String tag     = "@" + npcName;
        boolean direct = message.toLowerCase().startsWith(tag.toLowerCase());

        if (direct) {
            // Strip the @mention prefix and fire an immediate Ollama response
            String stripped = message.substring(tag.length()).trim();
            for (AiNpc npc : npcs) {
                if (!npc.isValid()) continue;
                npc.triggerImmediateResponse(player.getName(), stripped, playerLoc);
            }
        } else {
            // Passive observation — Steve notices but doesn't react immediately
            String logEntry = "[" + player.getName() + "]: " + message;
            for (AiNpc npc : npcs) {
                if (!npc.isValid()) continue;
                npc.onPassiveChat(logEntry, playerLoc);
            }
        }
    }
}
