package gg.civai.npc.npc;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Persists NPC spawn locations to plugins/ai-npc/npcs.yml.
 * On plugin enable: call loadAll() → respawn saved NPCs.
 * On spawn: call save().
 * On remove: call delete().
 */
public class NpcPersistenceManager {

    /** Data returned by loadAll(). */
    public record SavedNpc(String name, String world, double x, double y, double z) {}

    private final File file;
    private YamlConfiguration config;
    private final Logger logger;

    public NpcPersistenceManager(File dataFolder, Logger logger) {
        this.logger = logger;
        this.file   = new File(dataFolder, "npcs.yml");
        reload();
    }

    private void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    /** Persist (or overwrite) an NPC entry. */
    public void save(String npcName, Location loc) {
        String path = "npcs." + npcName;
        config.set(path + ".world", loc.getWorld().getName());
        config.set(path + ".x", loc.getX());
        config.set(path + ".y", loc.getY());
        config.set(path + ".z", loc.getZ());
        persist();
    }

    /** Remove an NPC entry. */
    public void delete(String npcName) {
        config.set("npcs." + npcName, null);
        persist();
    }

    /** Returns true if a saved entry exists for this NPC name. */
    public boolean hasSaved(String npcName) {
        return config.contains("npcs." + npcName);
    }

    /** Load all saved NPC records. */
    public List<SavedNpc> loadAll() {
        List<SavedNpc> result = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("npcs");
        if (section == null) return result;
        for (String key : section.getKeys(false)) {
            String path = "npcs." + key;
            String world = config.getString(path + ".world", "world");
            double x = config.getDouble(path + ".x");
            double y = config.getDouble(path + ".y");
            double z = config.getDouble(path + ".z");
            result.add(new SavedNpc(key, world, x, y, z));
        }
        return result;
    }

    private void persist() {
        try {
            config.save(file);
        } catch (IOException e) {
            logger.warning("Failed to save npcs.yml: " + e.getMessage());
        }
    }
}
