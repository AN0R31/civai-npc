package gg.civai.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.civai.npc.npc.ConversationMemory;
import gg.civai.npc.npc.NpcAction;
import gg.civai.npc.npc.WorldState;
import org.bukkit.Location;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

public class OllamaClient {

    // -------------------------------------------------------------------------
    // System prompt — %s is replaced with the NPC name
    // Design principle: IDLE is the default. The NPC lives its own life;
    // it does NOT narrate constantly. Speech is reserved for meaningful moments.
    // -------------------------------------------------------------------------
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are %s, a villager living in a Minecraft world.
        You live your own life — wandering, exploring, resting, observing. You have your own
        thoughts and goals. You are NOT an assistant and you do NOT narrate yourself constantly.

        You think every ~10 seconds. Most ticks you just continue what you were doing, silently.
        You speak OUT LOUD only when it feels genuinely natural — not on a timer.

        Respond with ONLY this JSON object, nothing else:
        {
          "thought": "1-2 sentences of internal reasoning",
          "action": "IDLE | SPEAK | MOVE_TO | MOVE_AND_SPEAK | REPORT | TIME_REPORT",
          "speech": "what you say out loud, or null",
          "next_activity": "short phrase: what you are doing now, e.g. 'wandering east toward the trees'",
          "target_x": 0.0,
          "target_y": 0.0,
          "target_z": 0.0
        }

        ACTIONS:
        - IDLE          → keep your current activity, no speech. DEFAULT — use this most of the time.
        - MOVE_TO       → walk somewhere silently. Good for autonomous wandering/exploring.
        - SPEAK         → say something without moving.
        - MOVE_AND_SPEAK → walk to coords AND speak.
        - REPORT        → narrate what you currently see around you.
        - TIME_REPORT   → answer a time or weather question. Use the minute values from world state.
                          Express naturally: "Sunset in about 3 minutes — better find shelter!"
                          Never output raw tick numbers.

        AUTONOMOUS BEHAVIOR (no player speaking to you):
        - Pick an interesting goal each time you change activity: wander 5-30 blocks in a direction,
          explore a biome feature, rest by something, observe the surroundings.
        - Use MOVE_TO with realistic target coords when wandering. Use IDLE when resting or already
          mid-journey (the game engine moves you continuously, so you only need a new target
          when your goal changes).
        - Speak autonomously only if something genuinely surprising happened (a mob appeared,
          weather changed, you arrived somewhere) — not every tick.
        - next_activity: always describe what you're doing, e.g.:
          "resting under the oak tree" / "heading north to explore" / "watching the cows"

        HARD RULES — follow every time:
        1. ## PRIORITY section = a player spoke directly to you.
           → MUST reply with non-null speech using SPEAK, MOVE_AND_SPEAK, or TIME_REPORT.
           → NEVER use IDLE or silent MOVE_TO when a player directly addressed you.
        2. Weather question → TIME_REPORT, describe weather naturally.
        3. Time/sunset/sunrise question → TIME_REPORT, use the precomputed minute values.
        4. No text outside the JSON object.
        5. speech: max 2 short sentences. Stay in character as a Minecraft villager.
        6. next_activity: always fill this in — it tells your future self what you decided.
        """;

    private final String     systemPrompt;
    private final HttpClient httpClient;
    private final String     host;
    private final String     model;
    private final Gson       gson   = new Gson();
    private final Logger     logger;

    public OllamaClient(String host, String model, int timeoutSeconds, String npcName) {
        this.host         = host;
        this.model        = model;
        this.systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, npcName);
        this.logger       = Logger.getLogger("OllamaClient");
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends world state + memory + optional direct-message context to Ollama.
     * Blocking — always call from an async thread.
     *
     * @param state               current world snapshot
     * @param memory              rolling conversation history
     * @param directPlayerName    null for periodic ticks; player name for @mention
     * @param directMessage       null for periodic ticks; stripped @mention text
     * @param directPlayerLoc     null for periodic ticks; player location for MOVE_AND_SPEAK hint
     * @param currentActivity     short phrase describing what the NPC was doing last tick
     */
    public NpcAction think(WorldState state, ConversationMemory memory,
                           String directPlayerName, String directMessage,
                           Location directPlayerLoc, String currentActivity) {
        try {
            String userContent = buildUserPrompt(state, memory, directPlayerName,
                                                 directMessage, directPlayerLoc, currentActivity);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);

            JsonArray messages = new JsonArray();

            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", systemPrompt);
            messages.add(systemMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userContent);
            messages.add(userMsg);

            body.add("messages", messages);

            // Force JSON output
            JsonObject format = new JsonObject();
            format.addProperty("type", "object");
            body.add("format", format);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warning("Ollama returned HTTP " + response.statusCode()
                        + ": " + response.body());
                return NpcAction.idle("Ollama error, staying put.");
            }

            return parseResponse(response.body(), state);

        } catch (IOException | InterruptedException e) {
            logger.warning("Failed to reach Ollama: " + e.getMessage());
            return NpcAction.idle("Could not reach AI, staying put.");
        }
    }

    // -------------------------------------------------------------------------
    // Prompt assembly
    // -------------------------------------------------------------------------

    private String buildUserPrompt(WorldState state, ConversationMemory memory,
                                   String directPlayerName, String directMessage,
                                   Location directPlayerLoc, String currentActivity) {
        StringBuilder sb = new StringBuilder();

        // 1. Current NPC activity — gives the model continuity across ticks
        sb.append("## Your Current Activity\n");
        sb.append("You are currently: ").append(currentActivity).append("\n");
        sb.append("Continue this or adapt if something changed.\n\n");

        // 2. Rolling conversation history
        List<ConversationMemory.Entry> entries = memory.getEntries();
        if (!entries.isEmpty()) {
            sb.append("## Recent Conversation History\n");
            for (ConversationMemory.Entry e : entries) {
                sb.append("[").append(e.playerName()).append("]: ").append(e.playerMessage())
                  .append(" → [").append(state.npcName).append("]: ")
                  .append(e.steveResponse()).append("\n");
            }
            sb.append("\n");
        }

        // 3. Passive chat from nearby players
        if (!state.recentChatLog.isEmpty()) {
            sb.append("## Recent Nearby Chat (not directed at you)\n");
            for (String line : state.recentChatLog) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }

        // 4. Priority direct message — most important, placed just before world state
        if (directPlayerName != null && directMessage != null) {
            sb.append("## PRIORITY: ").append(directPlayerName)
              .append(" is talking directly to you\n");
            sb.append("Their message: \"").append(directMessage).append("\"\n");
            if (directPlayerLoc != null) {
                sb.append("Their position: x=").append(Math.round(directPlayerLoc.getX()))
                  .append(" y=").append(Math.round(directPlayerLoc.getY()))
                  .append(" z=").append(Math.round(directPlayerLoc.getZ())).append("\n");
            }
            sb.append("YOU MUST reply — use SPEAK, MOVE_AND_SPEAK, or TIME_REPORT with non-null speech.\n\n");
        }

        // 5. World state
        sb.append(state.toPromptString());

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private NpcAction parseResponse(String responseBody, WorldState state) {
        try {
            JsonObject root    = JsonParser.parseString(responseBody).getAsJsonObject();
            String     content = root.getAsJsonObject("message").get("content").getAsString();

            // Strip markdown fences if the model ignores instructions
            content = content.replaceAll("```json|```", "").trim();

            JsonObject json = JsonParser.parseString(content).getAsJsonObject();

            String thought      = json.has("thought")       ? json.get("thought").getAsString()       : "";
            String actionStr    = json.has("action")        ? json.get("action").getAsString()        : "IDLE";
            String nextActivity = json.has("next_activity") && !json.get("next_activity").isJsonNull()
                                  ? json.get("next_activity").getAsString() : null;
            String speech       = json.has("speech") && !json.get("speech").isJsonNull()
                                  ? json.get("speech").getAsString() : null;
            double tx = json.has("target_x") ? json.get("target_x").getAsDouble() : state.x;
            double ty = json.has("target_y") ? json.get("target_y").getAsDouble() : state.y;
            double tz = json.has("target_z") ? json.get("target_z").getAsDouble() : state.z;

            NpcAction.Type type;
            try {
                type = NpcAction.Type.valueOf(actionStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = NpcAction.Type.IDLE;
            }

            return new NpcAction(type, thought, speech, nextActivity, tx, ty, tz);

        } catch (Exception e) {
            logger.warning("Failed to parse Ollama response: " + e.getMessage()
                    + " | Body: " + responseBody);
            return NpcAction.idle("Parse error, staying put.");
        }
    }
}
