package gg.civai.npc;

import gg.civai.npc.ai.OllamaClient;
import gg.civai.npc.command.NpcCommand;
import gg.civai.npc.npc.NpcManager;
import gg.civai.npc.npc.NpcPersistenceManager;
import org.bukkit.plugin.java.JavaPlugin;

public class AiNpcPlugin extends JavaPlugin {

    private NpcPersistenceManager persistence;
    private NpcManager            npcManager;
    private OllamaClient          ollamaClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Ollama client
        String ollamaHost    = getConfig().getString("ollama.host", "http://localhost:11434");
        String ollamaModel   = getConfig().getString("ollama.model", "llama3:latest");
        int    ollamaTimeout = getConfig().getInt("ollama.timeout-seconds", 30);
        String npcName       = getConfig().getString("npc.name", "SuperSteve");

        ollamaClient = new OllamaClient(ollamaHost, ollamaModel, ollamaTimeout, npcName);

        // Persistence manager (writes to plugins/ai-npc/npcs.yml)
        persistence = new NpcPersistenceManager(getDataFolder(), getLogger());

        // NPC manager — registers chat event listener
        npcManager = new NpcManager(this, ollamaClient, persistence);

        // Restore any NPCs saved from a previous session
        npcManager.loadSavedNpcs();

        // Commands
        getCommand("ainpc").setExecutor(new NpcCommand(this, npcManager));

        getLogger().info("AI NPC plugin enabled. Ollama: " + ollamaHost + " model: " + ollamaModel);
    }

    @Override
    public void onDisable() {
        if (npcManager != null) {
            npcManager.removeAll(); // despawn entities, keep persistence intact
        }
        getLogger().info("AI NPC plugin disabled.");
    }

    public NpcManager            getNpcManager()  { return npcManager; }
    public OllamaClient          getOllamaClient(){ return ollamaClient; }
    public NpcPersistenceManager getPersistence() { return persistence; }
}
