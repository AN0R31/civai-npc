package gg.civai.npc.command;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.npc.AiNpc;
import gg.civai.npc.npc.NpcManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class NpcCommand implements CommandExecutor {

    private final AiNpcPlugin plugin;
    private final NpcManager  npcManager;

    public NpcCommand(AiNpcPlugin plugin, NpcManager npcManager) {
        this.plugin     = plugin;
        this.npcManager = npcManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("ainpc.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {

            case "spawn" -> {
                String npcName = plugin.getConfig().getString("npc.name", "SuperSteve");
                if (npcManager.hasNpc(npcName)) {
                    player.sendMessage(Component.text(
                            npcName + " is already active or saved. Use /ainpc remove first.",
                            NamedTextColor.RED));
                    return true;
                }
                AiNpc npc = npcManager.spawnAt(player.getLocation());
                player.sendMessage(Component.text(
                        "Spawned " + npc.getName() + " at your location.", NamedTextColor.GREEN));
            }

            case "remove" -> {
                boolean removed = npcManager.removeNearest(player.getLocation());
                if (removed) {
                    player.sendMessage(Component.text("Nearest AI NPC removed.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text("No AI NPC found nearby.", NamedTextColor.RED));
                }
            }

            case "status" -> {
                var npcs = npcManager.getNpcs();
                if (npcs.isEmpty()) {
                    player.sendMessage(Component.text("No AI NPCs active.", NamedTextColor.GRAY));
                } else {
                    for (AiNpc npc : npcs) {
                        var loc    = npc.getLocation();
                        var action = npc.getCurrentAction();
                        int memSize  = npc.getMemory().size();
                        int memCap   = npc.getMemory().capacity();
                        player.sendMessage(Component.text(
                                "[" + npc.getName() + "] "
                                + String.format("pos=(%.1f,%.1f,%.1f) ", loc.getX(), loc.getY(), loc.getZ())
                                + "action=" + action.type + " "
                                + "memory=" + memSize + "/" + memCap + " "
                                + "thought=\"" + action.thought + "\"",
                                NamedTextColor.AQUA));
                    }
                }
            }

            default -> sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("--- AI NPC Commands ---", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/ainpc spawn   - Spawn NPC at your location (warns if already exists)", NamedTextColor.WHITE));
        player.sendMessage(Component.text("/ainpc remove  - Remove nearest AI NPC (also deletes save)", NamedTextColor.WHITE));
        player.sendMessage(Component.text("/ainpc status  - Show NPC status, action, and memory count", NamedTextColor.WHITE));
    }
}
