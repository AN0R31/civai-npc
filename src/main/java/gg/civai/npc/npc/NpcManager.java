package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.OllamaClient;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;

public class NpcManager implements Listener {

    private final AiNpcPlugin plugin;
    private final OllamaClient ollamaClient;
    private final List<AiNpc> npcs = new ArrayList<>();

    private final String npcName;
    private final int scanRadius;

    public NpcManager(AiNpcPlugin plugin, OllamaClient ollamaClient) {
        this.plugin = plugin;
        this.ollamaClient = ollamaClient;
        this.npcName = plugin.getConfig().getString("npc.name", "SuperSteve");
        this.scanRadius = plugin.getConfig().getInt("npc.scan-radius", 10);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public AiNpc spawnAt(Location location) {
        AiNpc npc = new AiNpc(plugin, ollamaClient, npcName, scanRadius);
        npc.spawn(location);
        npcs.add(npc);
        return npc;
    }

    public boolean removeNearest(Location location) {
        AiNpc nearest = null;
        double minDist = Double.MAX_VALUE;
        for (AiNpc npc : npcs) {
            if (!npc.isValid()) continue;
            double d = npc.getLocation().distance(location);
            if (d < minDist) {
                minDist = d;
                nearest = npc;
            }
        }
        if (nearest != null) {
            nearest.remove();
            npcs.remove(nearest);
            return true;
        }
        return false;
    }

    public void removeAll() {
        npcs.forEach(AiNpc::remove);
        npcs.clear();
    }

    public List<AiNpc> getNpcs() { return npcs; }

    // Player right-clicks the NPC villager entity
    @EventHandler
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager)) return;
        Player player = event.getPlayer();

        for (AiNpc npc : npcs) {
            if (!npc.isValid()) continue;
            if (npc.getLocation().distance(event.getRightClicked().getLocation()) < 1.0) {
                npc.onPlayerMessage(player.getName(), "(right-clicked you)");
                event.setCancelled(true); // prevent vanilla trade GUI
            }
        }
    }

    // Player sends a chat message near the NPC
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        for (AiNpc npc : npcs) {
            if (!npc.isValid()) continue;
            if (npc.getLocation().distance(player.getLocation()) <= scanRadius) {
                npc.onPlayerMessage(player.getName(), event.getMessage());
            }
        }
    }
}