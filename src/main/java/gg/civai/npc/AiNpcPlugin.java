package gg.civai.npc;

import gg.civai.npc.command.NpcCommand;
import gg.civai.npc.npc.NpcManager;
import gg.civai.npc.ai.OllamaClient;
import org.bukkit.plugin.java.JavaPlugin;

public class AiNpcPlugin extends JavaPlugin {

    private NpcManager npcManager;
    private OllamaClient ollamaClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Init Ollama client
        String ollamaHost = getConfig().getString("ollama.host", "http://localhost:11434");
        String ollamaModel = getConfig().getString("ollama.model", "llama3.1");
        int ollamaTimeout = getConfig().getInt("ollama.timeout-seconds", 30);
        ollamaClient = new OllamaClient(ollamaHost, ollamaModel, ollamaTimeout);

        // Init NPC manager
        npcManager = new NpcManager(this, ollamaClient);

        // Register command
        getCommand("ainpc").setExecutor(new NpcCommand(this, npcManager));

        getLogger().info("AI NPC plugin enabled. Ollama: " + ollamaHost + " model: " + ollamaModel);
    }

    @Override
    public void onDisable() {
        if (npcManager != null) {
            npcManager.removeAll();
        }
        getLogger().info("AI NPC plugin disabled.");
    }

    public NpcManager getNpcManager() { return npcManager; }
    public OllamaClient getOllamaClient() { return ollamaClient; }
}