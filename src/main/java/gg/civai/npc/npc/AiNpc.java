package gg.civai.npc.npc;

import gg.civai.npc.AiNpcPlugin;
import gg.civai.npc.ai.LlmDecision;
import gg.civai.npc.ai.OllamaClient;
import gg.civai.npc.goal.Goal;
import gg.civai.npc.goal.GoalEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * A single AI-controlled NPC.
 *
 * v1.2 architectural refactor — two-layer AI:
 *  1. LLM layer (event-driven): Ollama sets a Goal. Called only on events, not a fixed timer.
 *  2. GoalEngine layer (200ms tick): deterministic local executor for Goals.
 *
 * The LLM is called when:
 *  - NPC spawns (startup)
 *  - A goal completes or fails (GoalEngine callback)
 *  - A player @mentions the NPC
 *  - A significant event occurs (threat detected, night fallen, NPC attacked)
 *
 * Movement is fully delegated to GoalEngine (removed from this class).
 */
public class AiNpc {

    private final AiNpcPlugin  plugin;
    private final OllamaClient ollamaClient;
    private final Logger       logger;
    private final String       name;
    private final int          scanRadius;

    // Entity
    private Mob entity;

    // Goal engine (created on spawn, handles movement and goal execution)
    private GoalEngine goalEngine;

    // Conversation memory
    private final ConversationMemory memory = new ConversationMemory(10);

    // Passive chat log — async-safe (synchronized on recentChatLog)
    private final ArrayDeque<String> recentChatLog = new ArrayDeque<>(5);

    // Scheduler handle for game tick
    private BukkitTask gameTick;

    // LLM call lock — prevents overlapping Ollama calls
    private volatile boolean thinkingLock = false;

    // Queued @mention — stored when thinkingLock is busy, fired after current think completes
    private volatile String   pendingPlayerName = null;
    private volatile String   pendingMessage    = null;
    private volatile Location pendingPlayerLoc  = null;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public AiNpc(AiNpcPlugin plugin, OllamaClient ollamaClient, String name, int scanRadius) {
        this.plugin       = plugin;
        this.ollamaClient = ollamaClient;
        this.name         = name;
        this.scanRadius   = scanRadius;
        this.logger       = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void spawn(Location location) {
        // Husk: humanoid model, shows armor, does NOT burn in sunlight, setAI(false) safe
        entity = location.getWorld().spawn(location, Husk.class, h -> {
            h.customName(Component.text(name, NamedTextColor.YELLOW));
            h.setCustomNameVisible(true);
            h.setAI(false);               // GoalEngine handles all movement
            h.setInvulnerable(true);
            h.setSilent(true);            // we handle speech
            h.setRemoveWhenFarAway(false);
            h.setConversionTime(-1);      // never convert to Zombie in water
            // Diamond armour — drop chance 0 so nothing falls on (hypothetical) death
            h.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            h.getEquipment().setChestplateDropChance(0f);
            h.getEquipment().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
            h.getEquipment().setLeggingsDropChance(0f);
            h.getEquipment().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
            h.getEquipment().setBootsDropChance(0f);
        });

        // Fetch skin async and apply as player-skull helmet (non-blocking)
        String skinName = plugin.getConfig().getString("npc.skin", "");
        if (skinName != null && !skinName.isBlank()) {
            applySkin(skinName);
        }

        // Build GoalEngine with callbacks that re-invoke the LLM
        goalEngine = new GoalEngine(
                entity, logger, name, scanRadius,
                /* onGoalComplete  */ () -> triggerLlmCall("goal_completed", ""),
                /* onGoalFailed    */ reason -> triggerLlmCall("goal_failed: " + reason, ""),
                /* onThreatDetected*/ () -> triggerLlmCall("threat_detected", "")
        );

        startGameTick();

        // Fire initial LLM call after a short delay so the world has loaded
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> triggerLlmCall("startup", ""),
                40L); // 2-second delay (40 ticks)

        logger.info(name + " spawned at " + formatLoc(location));
    }

    public void remove() {
        if (gameTick != null) gameTick.cancel();
        if (entity   != null && entity.isValid()) entity.remove();
        logger.info(name + " removed.");
    }

    // -------------------------------------------------------------------------
    // Game tick (200 ms) — delegates entirely to GoalEngine
    // -------------------------------------------------------------------------

    private void startGameTick() {
        gameTick = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            goalEngine.tick();
        }, 0L, 4L); // 4 ticks = 200 ms
    }

    // -------------------------------------------------------------------------
    // LLM dispatch
    // -------------------------------------------------------------------------

    /**
     * Triggers an LLM call for a world event or GoalEngine callback.
     * Must be called from the main thread.
     *
     * @param reason  why the LLM is being called (e.g. "goal_completed", "threat_detected")
     * @param context additional context string (may be empty)
     */
    public void triggerLlmCall(String reason, String context) {
        if (entity == null || !entity.isValid()) return;
        if (thinkingLock) {
            // Don't stack LLM calls from automated events — skip silently.
            // @mention calls use the queue mechanism; automated events just drop.
            logger.fine("[" + name + "] LLM busy — skipping event: " + reason);
            return;
        }
        runLlmCall(reason, null, null, null);
    }

    /**
     * Fires an immediate LLM call when a player @mentions the NPC.
     * Safe to call from an async thread — proximity check is deferred to main thread.
     *
     * @param playerName  player who mentioned the NPC
     * @param message     message text (prefix already stripped)
     * @param playerLoc   snapshot of player location
     */
    public void triggerImmediateResponse(String playerName, String message, Location playerLoc) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (entity == null || !entity.isValid()) return;
            if (!entity.getWorld().equals(playerLoc.getWorld())) return;
            if (entity.getLocation().distance(playerLoc) > scanRadius) return;

            if (thinkingLock) {
                // Queue it — most-recent-wins
                pendingPlayerName = playerName;
                pendingMessage    = message;
                pendingPlayerLoc  = playerLoc;
                logger.info("[" + name + "] @mention queued (busy): " + playerName + ": " + message);
                return;
            }

            runLlmCall("player_mention", playerName, message, playerLoc);
        });
    }

    /**
     * Core async LLM dispatch. Must be called from the main thread with thinkingLock == false.
     *
     * @param reason        reason string passed to OllamaClient
     * @param mentionPlayer null for non-mention calls
     * @param mentionMsg    null for non-mention calls
     * @param mentionLoc    null for non-mention calls
     */
    private void runLlmCall(String reason,
                            String mentionPlayer, String mentionMsg, Location mentionLoc) {
        thinkingLock = true;
        boolean isMention = mentionPlayer != null;

        // Snapshot mutable state on main thread
        List<String> chatSnapshot = new ArrayList<>(recentChatLog);
        Goal currentGoal          = goalEngine.getCurrentGoal();
        long goalElapsed          = goalEngine.getGoalElapsedSeconds();
        List<String> goalHistory  = goalEngine.getGoalHistory();

        WorldState state = new WorldState(
                entity.getLocation(), scanRadius, name,
                entity.getUniqueId(), chatSnapshot,
                currentGoal, goalElapsed, goalHistory);

        // Async: blocking HTTP call to Ollama
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            LlmDecision decision = ollamaClient.think(
                    state, memory, reason,
                    mentionPlayer, mentionMsg, mentionLoc);

            // Back on main thread: apply decision
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                applyDecision(decision, isMention, mentionPlayer, mentionMsg);

                thinkingLock = false;

                // Fire queued @mention if any
                if (pendingPlayerName != null) {
                    String pName = pendingPlayerName;
                    String pMsg  = pendingMessage;
                    Location pLoc = pendingPlayerLoc;
                    pendingPlayerName = null;
                    pendingMessage    = null;
                    pendingPlayerLoc  = null;
                    logger.info("[" + name + "] firing queued @mention from " + pName);
                    runLlmCall("player_mention", pName, pMsg, pLoc);
                }
            });
        });
    }

    /**
     * Applies an LLM decision: sets the new Goal, speaks if the LLM provided speech,
     * and records @mention exchanges in conversation memory.
     */
    private void applyDecision(LlmDecision decision, boolean isMention,
                               String mentionPlayer, String mentionMsg) {
        goalEngine.setGoal(decision.goal());

        if (decision.speech() != null && !decision.speech().isBlank()) {
            speak(decision.speech());
        }

        if (isMention && mentionPlayer != null && decision.speech() != null) {
            memory.add(mentionPlayer, mentionMsg, decision.speech());
        }
    }

    // -------------------------------------------------------------------------
    // Chat input
    // -------------------------------------------------------------------------

    /**
     * Called from async thread for messages NOT directed at this NPC.
     * Adds to passive chat log if the player is within scan radius.
     */
    public synchronized void onPassiveChat(String logEntry, Location playerLoc) {
        if (entity == null || !entity.isValid()) return;
        Location npcLoc = entity.getLocation();
        if (!npcLoc.getWorld().equals(playerLoc.getWorld())) return;
        if (npcLoc.distance(playerLoc) > scanRadius) return;

        recentChatLog.addLast(logEntry);
        while (recentChatLog.size() > 5) recentChatLog.pollFirst();
    }

    // -------------------------------------------------------------------------
    // Speech
    // -------------------------------------------------------------------------

    public void speak(String message) {
        if (entity == null || !entity.isValid()) return;
        entity.getWorld().getNearbyPlayers(entity.getLocation(), scanRadius).forEach(p ->
                p.sendMessage(Component.text("[" + name + "] ", NamedTextColor.YELLOW)
                        .append(Component.text(message, NamedTextColor.WHITE))));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public boolean  isValid()            { return entity != null && entity.isValid(); }
    public Location getLocation()        { return entity != null ? entity.getLocation() : null; }
    public String   getName()            { return name; }
    public int      getScanRadius()      { return scanRadius; }
    public Mob      getEntity()          { return entity; }
    public Goal     getCurrentGoal()     { return goalEngine != null ? goalEngine.getCurrentGoal() : null; }
    public ConversationMemory getMemory(){ return memory; }

    // -------------------------------------------------------------------------
    // Skin fetching (async Mojang API)
    // -------------------------------------------------------------------------

    /**
     * Fetch the skin texture for {@code skinName} from Mojang and apply it as
     * a player-skull helmet on the entity.  Runs fully async; applies on main thread.
     *
     * Flow: Mojang UUID lookup → session-server profile → decode base64 texture
     * → build PlayerProfile with texture URL → set as skull helmet.
     */
    private void applySkin(String skinName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();

                // 1. UUID lookup
                HttpResponse<String> uuidResp = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                                        + skinName))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                if (uuidResp.statusCode() != 200) {
                    logger.warning("[" + name + "] Skin: UUID lookup failed (HTTP "
                            + uuidResp.statusCode() + ") for '" + skinName + "'");
                    return;
                }

                JsonObject uuidJson = JsonParser.parseString(uuidResp.body()).getAsJsonObject();
                String rawId = uuidJson.get("id").getAsString();
                UUID uuid = UUID.fromString(rawId.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));

                // 2. Profile with skin
                HttpResponse<String> profileResp = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(
                                        "https://sessionserver.mojang.com/session/minecraft/profile/"
                                        + rawId + "?unsigned=false"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                if (profileResp.statusCode() != 200) {
                    logger.warning("[" + name + "] Skin: profile fetch failed (HTTP "
                            + profileResp.statusCode() + ")");
                    return;
                }

                JsonObject profileJson = JsonParser.parseString(profileResp.body()).getAsJsonObject();
                JsonArray  properties  = profileJson.getAsJsonArray("properties");

                String textureValue = null;
                for (int i = 0; i < properties.size(); i++) {
                    JsonObject prop = properties.get(i).getAsJsonObject();
                    if ("textures".equals(prop.get("name").getAsString())) {
                        textureValue = prop.get("value").getAsString();
                        break;
                    }
                }
                if (textureValue == null) {
                    logger.warning("[" + name + "] Skin: no texture property for '" + skinName + "'");
                    return;
                }

                // 3. Decode base64 → get skin URL + model
                String      decoded   = new String(Base64.getDecoder().decode(textureValue));
                JsonObject  texJson   = JsonParser.parseString(decoded).getAsJsonObject();
                JsonObject  skinObj   = texJson.getAsJsonObject("textures").getAsJsonObject("SKIN");
                String      skinUrl   = skinObj.get("url").getAsString();
                boolean     isSlim    = skinObj.has("metadata")
                        && "slim".equals(skinObj.getAsJsonObject("metadata")
                                              .get("model").getAsString());

                final UUID   fUuid   = uuid;
                final String fUrl    = skinUrl;
                final boolean fSlim  = isSlim;

                // 4. Apply on main thread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (entity == null || !entity.isValid()) return;
                    try {
                        PlayerProfile profile = Bukkit.createPlayerProfile(fUuid, skinName);
                        PlayerTextures textures = profile.getTextures();
                        textures.setSkin(new URL(fUrl),
                                fSlim ? PlayerTextures.SkinModel.SLIM
                                       : PlayerTextures.SkinModel.CLASSIC);
                        profile.setTextures(textures);

                        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                        SkullMeta meta  = (SkullMeta) skull.getItemMeta();
                        meta.setOwnerProfile(profile);
                        skull.setItemMeta(meta);

                        entity.getEquipment().setHelmet(skull);
                        entity.getEquipment().setHelmetDropChance(0f);
                        logger.info("[" + name + "] Skin applied: " + skinName
                                + " (" + (fSlim ? "slim" : "classic") + ")");
                    } catch (Exception e) {
                        logger.warning("[" + name + "] Skin apply error: " + e.getMessage());
                    }
                });

            } catch (Exception e) {
                logger.warning("[" + name + "] Skin fetch error: " + e.getMessage());
            }
        });
    }

    private String formatLoc(Location l) {
        return String.format("(%.1f, %.1f, %.1f) in %s",
                l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
    }
}
