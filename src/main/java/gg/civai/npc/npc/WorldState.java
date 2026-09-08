package gg.civai.npc.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the NPC's surroundings, passed to the AI.
 */
public class WorldState {

    public final double x, y, z;
    public final String world;
    public final String timeOfDay;
    public final boolean isRaining;
    public final List<String> nearbyPlayers = new ArrayList<>();
    public final List<String> nearbyMobs = new ArrayList<>();
    public final List<String> nearbyBlocks = new ArrayList<>();
    public final String lastPlayerMessage;
    public final String npcName;

    public WorldState(Location loc, int scanRadius, String lastPlayerMessage, String npcName) {
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
        this.world = loc.getWorld().getName();
        this.npcName = npcName;
        this.lastPlayerMessage = lastPlayerMessage;
        this.isRaining = loc.getWorld().hasStorm();

        long time = loc.getWorld().getTime();
        if (time < 6000) this.timeOfDay = "morning";
        else if (time < 12000) this.timeOfDay = "afternoon";
        else if (time < 14000) this.timeOfDay = "evening";
        else this.timeOfDay = "night";

        // Scan nearby entities
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, scanRadius, scanRadius, scanRadius)) {
            if (entity instanceof Player p) {
                double dist = Math.round(entity.getLocation().distance(loc) * 10.0) / 10.0;
                nearbyPlayers.add(p.getName() + " (" + dist + " blocks away)");
            } else if (entity instanceof LivingEntity le) {
                double dist = Math.round(entity.getLocation().distance(loc) * 10.0) / 10.0;
                nearbyMobs.add(le.getType().name().toLowerCase() + " (" + dist + " blocks away)");
            }
        }

        // Sample a few notable blocks around NPC (ground level)
        World w = loc.getWorld();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                String type = w.getBlockAt(bx + dx, by - 1, bz + dz).getType().name().toLowerCase();
                if (!type.equals("air")) {
                    nearbyBlocks.add(type + " at +" + dx + ",+" + dz);
                }
            }
        }
    }

    /**
     * Converts the world state to a human-readable string for the AI prompt.
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== WORLD STATE ===\n");
        sb.append("NPC: ").append(npcName).append("\n");
        sb.append("Position: x=").append(Math.round(x)).append(" y=").append(Math.round(y)).append(" z=").append(Math.round(z)).append("\n");
        sb.append("World: ").append(world).append("\n");
        sb.append("Time: ").append(timeOfDay).append("\n");
        sb.append("Weather: ").append(isRaining ? "raining" : "clear").append("\n");

        if (!nearbyPlayers.isEmpty()) {
            sb.append("Nearby players: ").append(String.join(", ", nearbyPlayers)).append("\n");
        } else {
            sb.append("Nearby players: none\n");
        }

        if (!nearbyMobs.isEmpty()) {
            sb.append("Nearby mobs: ").append(String.join(", ", nearbyMobs)).append("\n");
        }

        if (!nearbyBlocks.isEmpty()) {
            sb.append("Ground blocks: ").append(String.join(", ", nearbyBlocks)).append("\n");
        }

        if (lastPlayerMessage != null && !lastPlayerMessage.isEmpty()) {
            sb.append("Last player message to you: ").append(lastPlayerMessage).append("\n");
        }

        return sb.toString();
    }
}