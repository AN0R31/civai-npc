package gg.civai.npc.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of the NPC's surroundings, passed to the AI as a prompt block.
 * The NPC's own UUID is excluded from entity scans so Steve doesn't see himself.
 */
public class WorldState {

    // --- position ---
    public final double x, y, z;
    public final String world;

    // --- time ---
    public final long   rawTick;           // 0-23999
    public final String timeLabel;         // morning / afternoon / evening / night
    public final boolean isDay;
    public final long   minUntilSunrise;
    public final long   minUntilNoon;
    public final long   minUntilSunset;
    public final long   minUntilMidnight;

    // --- weather ---
    public final String weatherState;      // "clear" | "rain" | "thunder"

    // --- surroundings ---
    public final List<String> nearbyPlayers = new ArrayList<>();
    public final List<String> nearbyMobs    = new ArrayList<>();
    public final List<String> nearbyBlocks  = new ArrayList<>();

    // --- context ---
    public final String       npcName;
    public final List<String> recentChatLog; // passive chat from nearby players

    // Minecraft day/night boundaries (ticks)
    private static final long SUNRISE_TICK  = 0;
    private static final long NOON_TICK     = 6000;
    private static final long SUNSET_TICK   = 12542;
    private static final long MIDNIGHT_TICK = 18000;
    private static final long TICKS_PER_MIN = 1200; // 20 t/s × 60 s

    public WorldState(Location loc, int scanRadius, String npcName, UUID selfUuid,
                      List<String> recentChatLog) {
        World w = loc.getWorld();
        this.x    = loc.getX();
        this.y    = loc.getY();
        this.z    = loc.getZ();
        this.world       = w.getName();
        this.npcName     = npcName;
        this.recentChatLog = new ArrayList<>(recentChatLog);

        // --- Time ---
        rawTick          = w.getTime();
        isDay            = rawTick < SUNSET_TICK;
        minUntilSunrise  = ticksUntil(rawTick, SUNRISE_TICK);
        minUntilNoon     = ticksUntil(rawTick, NOON_TICK);
        minUntilSunset   = ticksUntil(rawTick, SUNSET_TICK);
        minUntilMidnight = ticksUntil(rawTick, MIDNIGHT_TICK);

        if      (rawTick < 6000)  timeLabel = "morning";
        else if (rawTick < 12000) timeLabel = "afternoon";
        else if (rawTick < 14000) timeLabel = "evening";
        else                      timeLabel = "night";

        // --- Weather ---
        if      (w.isThundering()) weatherState = "thunder";
        else if (w.hasStorm())     weatherState = "rain";
        else                       weatherState = "clear";

        // --- Nearby entities (self excluded) ---
        for (Entity entity : w.getNearbyEntities(loc, scanRadius, scanRadius, scanRadius)) {
            if (entity.getUniqueId().equals(selfUuid)) continue; // skip self
            double dist = Math.round(entity.getLocation().distance(loc) * 10.0) / 10.0;
            if (entity instanceof Player p) {
                nearbyPlayers.add(p.getName() + " (" + dist + " blocks away)");
            } else if (entity instanceof LivingEntity le) {
                nearbyMobs.add(le.getType().name().toLowerCase() + " (" + dist + " blocks away)");
            }
        }

        // --- Ground blocks (3×3 sample at foot level) ---
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

    // Returns minutes until the target tick, wrapping around 24000.
    private static long ticksUntil(long current, long target) {
        long diff = (target - current + 24000) % 24000;
        return diff / TICKS_PER_MIN;
    }

    /** Human-readable block for the Ollama prompt. */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== WORLD STATE ===\n");
        sb.append("NPC: ").append(npcName).append("\n");
        sb.append("Position: x=").append(Math.round(x))
          .append(" y=").append(Math.round(y))
          .append(" z=").append(Math.round(z)).append("\n");
        sb.append("World: ").append(world).append("\n");

        // Time
        sb.append("Time: ").append(timeLabel)
          .append(" (tick ").append(rawTick).append(", ")
          .append(isDay ? "daytime" : "nighttime").append(")\n");
        sb.append("Time until: sunrise ~").append(minUntilSunrise)
          .append("min, noon ~").append(minUntilNoon)
          .append("min, sunset ~").append(minUntilSunset)
          .append("min, midnight ~").append(minUntilMidnight).append("min\n");

        // Weather
        sb.append("Weather: ").append(weatherState).append("\n");

        // Players
        if (!nearbyPlayers.isEmpty()) {
            sb.append("Nearby players: ").append(String.join(", ", nearbyPlayers)).append("\n");
        } else {
            sb.append("Nearby players: none\n");
        }

        // Mobs
        if (!nearbyMobs.isEmpty()) {
            sb.append("Nearby mobs: ").append(String.join(", ", nearbyMobs)).append("\n");
        }

        // Blocks
        if (!nearbyBlocks.isEmpty()) {
            sb.append("Ground blocks: ").append(String.join(", ", nearbyBlocks)).append("\n");
        }

        return sb.toString();
    }
}
