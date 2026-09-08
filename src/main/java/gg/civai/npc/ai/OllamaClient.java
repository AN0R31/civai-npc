package gg.civai.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.civai.npc.npc.ConversationMemory;
import gg.civai.npc.npc.NpcAction;
import gg.civai.npc.npc.WorldState;

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
    // System prompt template — %s is replaced with the NPC name on construction
    // -------------------------------------------------------------------------
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are %s, an AI villager living in a Minecraft world.
        You have a curious, friendly, slightly dramatic personality.
        You explore, react to your environment, and chat naturally with players.

        You will receive a world state snapshot. Before it you may see:
          - "## Recent Conversation History" — your past exchanges with players
          - "## Recent Nearby Chat" — things players said nearby (not directed at you)
          - "## PRIORITY: Direct Message" — a player is talking directly to you RIGHT NOW

        Respond ONLY with a valid JSON object in exactly this format:
        {
          "thought": "your internal reasoning (1-2 sentences)",
          "action": "IDLE | MOVE_TO | SPEAK | MOVE_AND_SPEAK | REPORT | TIME_REPORT",
          "speech": "what you say out loud, or null if silent",
          "target_x": 0.0,
          "target_y": 0.0,
          "target_z": 0.0
        }

        Action rules:
        - IDLE         — stay put, say nothing. Use ONLY when truly nothing is happening and no one is talking to you.
        - MOVE_TO      — walk to target coordinates (set target_x/y/z).
        - SPEAK        — say something without moving. Use when responding to nearby events.
        - MOVE_AND_SPEAK — walk and talk at the same time (set target_x/y/z).
        - REPORT       — narrate what you observe around you in first person, naturally. No movement.
        - TIME_REPORT  — answer a time or weather question using the precomputed time values in the world state.
                         Express the answer naturally and in character (e.g. "The sun sets in about 3 minutes —
                         you'd better find shelter!" not raw tick numbers). No movement needed.

        When a player sends you a PRIORITY message, you MUST respond with SPEAK, MOVE_AND_SPEAK, or
        TIME_REPORT — never IDLE. Acknowledge what they said and stay in character.

        Keep all speech natural, in-character, max 1-2 sentences.
        Do not include any text outside the JSON object.
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
     * Sends the world state (plus memory and optional direct message) to Ollama
     * and returns a parsed NpcAction.
     *
     * Blocking — always call from an async thread.
     *
     * @param state             current world snapshot
     * @param memory            rolling conversation history (may be empty)
     * @param directPlayerName  null for periodic ticks; player name for @mention responses
     * @param directMessage     null for periodic ticks; stripped @mention text
     */
    public NpcAction think(WorldState state, ConversationMemory memory,
                           String directPlayerName, String directMessage) {
        try {
            String userContent = buildUserPrompt(state, memory, directPlayerName, directMessage);

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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warning("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
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
                                   String directPlayerName, String directMessage) {
        StringBuilder sb = new StringBuilder();

        // 1. Rolling conversation history
        List<ConversationMemory.Entry> entries = memory.getEntries();
        if (!entries.isEmpty()) {
            sb.append("## Recent Conversation History\n");
            for (ConversationMemory.Entry e : entries) {
                sb.append("[").append(e.playerName()).append("]: ").append(e.playerMessage())
                  .append(" → [").append(state.npcName).append("]: ").append(e.steveResponse()).append("\n");
            }
            sb.append("\n");
        }

        // 2. Passive chat from nearby players
        if (!state.recentChatLog.isEmpty()) {
            sb.append("## Recent Nearby Chat (not directed at you)\n");
            for (String line : state.recentChatLog) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }

        // 3. Priority direct message (from @mention)
        if (directPlayerName != null && directMessage != null) {
            sb.append("## PRIORITY: Direct Message\n");
            sb.append(directPlayerName).append(" is talking directly to you: \"").append(directMessage).append("\"\n");
            sb.append("You MUST respond with SPEAK, MOVE_AND_SPEAK, or TIME_REPORT — do NOT use IDLE.\n\n");
        }

        // 4. World state
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

            String thought   = json.has("thought") ? json.get("thought").getAsString() : "";
            String actionStr = json.has("action")  ? json.get("action").getAsString()  : "IDLE";
            String speech    = json.has("speech") && !json.get("speech").isJsonNull()
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

            return new NpcAction(type, thought, speech, tx, ty, tz);

        } catch (Exception e) {
            logger.warning("Failed to parse Ollama response: " + e.getMessage()
                    + " | Body: " + responseBody);
            return NpcAction.idle("Parse error, staying put.");
        }
    }
}
