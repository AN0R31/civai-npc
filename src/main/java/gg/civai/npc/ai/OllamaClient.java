package gg.civai.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.civai.npc.goal.Goal;
import gg.civai.npc.npc.ConversationMemory;
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

/**
 * Calls Ollama /api/chat and returns an {@link LlmDecision} (goal + speech).
 *
 * v1.2 architectural change:
 *  - Response now contains a "goal" (IDLE/WANDER/FOLLOW/GOTO/CONVERSE) and optional
 *    "goal_params" instead of a low-level NpcAction.
 *  - The LLM is given a "reason" for why it was called so it can react appropriately.
 *  - System prompt explains the two-layer architecture (LLM sets goals, GoalEngine executes).
 */
public class OllamaClient {

    // -------------------------------------------------------------------------
    // System prompt  (%s = NPC name)
    // -------------------------------------------------------------------------
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are %s, a villager living in a Minecraft world.
        You live your own life and have your own personality. You are NOT an assistant.

        HOW YOU WORK (two-layer architecture):
        You set GOALS. A local algorithm (GoalEngine) executes them automatically.
        You are only called when something important happens — not on a fixed timer.
        You do NOT micromanage steps. You pick a goal and the algorithm handles the rest.

        AVAILABLE GOALS:
        - IDLE      → Stand still. Use when resting, thinking, or uncertain. Auto-fires after 30s.
        - WANDER    → Roam randomly. Params: radius (blocks, default 20).
        - FOLLOW    → Follow a player. Params: player (name). Runs until you change goals.
        - GOTO      → Walk to coordinates. Params: x, y, z. Completes on arrival (60s timeout).
        - CONVERSE  → Face a player and stay nearby. Params: player (name).

        REASONS YOU ARE CALLED:
        - startup          → You just spawned. Pick an initial goal.
        - goal_completed   → Your last goal finished. Pick what to do next.
        - goal_failed      → Your last goal failed (reason given). Adapt.
        - player_mention   → A player spoke directly to you. React and set an appropriate goal.
        - threat_detected  → A hostile mob is nearby. The engine is already fleeing. Acknowledge it.
        - night_fallen     → Night just fell. React naturally.
        - npc_attacked     → You were just hit. React.

        MAPPING PLAYER REQUESTS TO GOALS (for player_mention):
        - "follow me" / "come here" / "come with me" → FOLLOW that player
        - "go to X Y Z" / "go there" → GOTO those coordinates
        - "stay here" / "wait" / "stop" → IDLE
        - "wander" / "explore" → WANDER
        - "carry on" / "bye" / "later" / "goodbye" / "see you" / "farewell" / "go away" → WANDER (conversation is over, resume wandering)
        - conversational ("hey", "what's up") → CONVERSE that player
        - time/weather questions → CONVERSE + answer using the exact numbers in [QUICK FACTS] if present

        AUTONOMOUS BEHAVIOR (goal_completed / startup):
        - Default: WANDER radius 15-25. Vary the radius for variety.
        - Sometimes IDLE for a rest (you'll be called again in 30s).
        - Speak only when something genuinely interesting happens — arrival, surprise, weather change.

        HARD RULES:
        1. For player_mention: ALWAYS set non-null speech. The player is talking to you — respond.
        2. For threat_detected: set non-null speech (a surprised reaction). IDLE is fine as the goal.
        3. speech: max 2 short sentences. In character as a Minecraft villager.
        4. Respond with ONLY the JSON object below, nothing else.
        5. Never repeat speech from Recent Conversation History — always say something new.
        6. For time/weather questions: copy the exact numbers from [QUICK FACTS]. Never invent values.
        7. "carry on" / "bye" / "goodbye" / "later" / "see you" / "farewell" ALWAYS maps to WANDER.
           The player is dismissing you. Do not stay in CONVERSE. Set goal=WANDER.

        JSON RESPONSE FORMAT:
        {
          "thought": "1-2 sentences of internal reasoning",
          "goal": "IDLE | WANDER | FOLLOW | GOTO | CONVERSE",
          "goal_params": {
            "player": "PlayerName",
            "x": 0.0,
            "y": 0.0,
            "z": 0.0,
            "radius": 20
          },
          "speech": "what you say out loud, or null"
        }
        goal_params fields are optional — only include what is needed for the chosen goal.
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
     * Send context to Ollama and get back a goal decision.
     * Blocking — always call from an async thread.
     *
     * @param state           current world snapshot (includes goal context)
     * @param memory          rolling conversation history
     * @param reason          why the LLM was called (startup / goal_completed / player_mention / …)
     * @param mentionPlayer   null for non-mention calls; player name for @mention
     * @param mentionMessage  null for non-mention calls; the message text
     * @param mentionPlayerLoc null for non-mention calls; player location
     */
    public LlmDecision think(WorldState state, ConversationMemory memory,
                             String reason,
                             String mentionPlayer, String mentionMessage,
                             Location mentionPlayerLoc) {
        try {
            String userContent = buildUserPrompt(state, memory, reason,
                    mentionPlayer, mentionMessage, mentionPlayerLoc);

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
                return LlmDecision.fallback("Ollama error " + response.statusCode());
            }

            return parseResponse(response.body(), state);

        } catch (IOException | InterruptedException e) {
            logger.warning("Failed to reach Ollama: " + e.getMessage());
            return LlmDecision.fallback("Could not reach AI: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Prompt assembly
    // -------------------------------------------------------------------------

    private String buildUserPrompt(WorldState state, ConversationMemory memory,
                                   String reason,
                                   String mentionPlayer, String mentionMessage,
                                   Location mentionPlayerLoc) {
        StringBuilder sb = new StringBuilder();

        // 1. Why we were called
        sb.append("## REASON CALLED: ").append(reason).append("\n\n");

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

        // 3. Passive chat
        if (!state.recentChatLog.isEmpty()) {
            sb.append("## Recent Nearby Chat (not directed at you)\n");
            for (String line : state.recentChatLog) sb.append(line).append("\n");
            sb.append("\n");
        }

        // 4. Direct player mention — highest priority
        if (mentionPlayer != null && mentionMessage != null) {
            sb.append("## PRIORITY: ").append(mentionPlayer)
              .append(" is talking directly to you\n");
            sb.append("Their message: \"").append(mentionMessage).append("\"\n");
            if (mentionPlayerLoc != null) {
                sb.append("Their position: x=").append(Math.round(mentionPlayerLoc.getX()))
                  .append(" y=").append(Math.round(mentionPlayerLoc.getY()))
                  .append(" z=").append(Math.round(mentionPlayerLoc.getZ())).append("\n");
            }

            // Inject pre-computed time/weather facts when the question is about them.
            // Placed right before the response instruction so the model copies them directly.
            String msgLow = mentionMessage.toLowerCase();
            boolean asksTime    = msgLow.matches(".*\\b(time|sunset|sunrise|noon|midnight|"
                    + "how long|when is|when will|morning|afternoon|evening|night|day)\\b.*");
            boolean asksWeather = msgLow.matches(".*\\b(weather|rain|raining|storm|thunder|"
                    + "clear|sunny|cloudy)\\b.*");
            if (asksTime || asksWeather) {
                sb.append("[QUICK FACTS — copy these into your speech, do NOT invent numbers]\n");
                sb.append("  Time of day: ").append(state.timeLabel)
                  .append(state.isDay ? " (daytime)" : " (nighttime)").append("\n");
                sb.append("  Weather: ").append(state.weatherState).append("\n");
                sb.append("  Sunset in:   ").append(state.minUntilSunset).append(" real minutes\n");
                sb.append("  Sunrise in:  ").append(state.minUntilSunrise).append(" real minutes\n");
                sb.append("  Noon in:     ").append(state.minUntilNoon).append(" real minutes\n");
                sb.append("  Midnight in: ").append(state.minUntilMidnight).append(" real minutes\n");
            }

            sb.append("YOU MUST reply with non-null speech.\n\n");
        }

        // 5. World state (includes goal context)
        sb.append(state.toPromptString());

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private LlmDecision parseResponse(String responseBody, WorldState state) {
        try {
            JsonObject root    = JsonParser.parseString(responseBody).getAsJsonObject();
            String     content = root.getAsJsonObject("message").get("content").getAsString();

            // Strip markdown fences
            content = content.replaceAll("```json|```", "").trim();

            JsonObject json = JsonParser.parseString(content).getAsJsonObject();

            String thought  = json.has("thought") ? json.get("thought").getAsString() : "";
            String goalStr  = json.has("goal")    ? json.get("goal").getAsString().toUpperCase() : "IDLE";
            String speech   = json.has("speech") && !json.get("speech").isJsonNull()
                              ? json.get("speech").getAsString() : null;

            // Parse goal_params
            JsonObject params = json.has("goal_params") && json.get("goal_params").isJsonObject()
                                ? json.getAsJsonObject("goal_params") : new JsonObject();

            Goal goal = buildGoal(goalStr, params, state);

            logger.info("[" + state.npcName + "] LLM → goal=" + goalStr
                    + (speech != null ? " | speech=\"" + speech + "\"" : "")
                    + " | thought=" + thought);

            return new LlmDecision(goal, speech, thought);

        } catch (Exception e) {
            logger.warning("Failed to parse Ollama response: " + e.getMessage()
                    + "\nBody: " + responseBody);
            return LlmDecision.fallback("Parse error — defaulting to IDLE");
        }
    }

    private Goal buildGoal(String goalStr, JsonObject params, WorldState state) {
        return switch (goalStr) {
            case "WANDER" -> {
                int radius = params.has("radius") ? params.get("radius").getAsInt() : 20;
                yield Goal.wander(radius);
            }
            case "FOLLOW" -> {
                String player = params.has("player") ? params.get("player").getAsString() : null;
                if (player == null || player.isBlank()) yield Goal.idle();
                yield Goal.follow(player);
            }
            case "GOTO" -> {
                double x = params.has("x") ? params.get("x").getAsDouble() : state.x;
                double y = params.has("y") ? params.get("y").getAsDouble() : state.y;
                double z = params.has("z") ? params.get("z").getAsDouble() : state.z;
                // Sanity: cap travel to 500 blocks
                double dx = x - state.x, dz = z - state.z;
                if (dx * dx + dz * dz > 500 * 500) yield Goal.idle();
                yield Goal.goTo(x, y, z);
            }
            case "CONVERSE" -> {
                String player = params.has("player") ? params.get("player").getAsString() : null;
                if (player == null || player.isBlank()) yield Goal.idle();
                yield Goal.converse(player);
            }
            default -> Goal.idle(); // IDLE or unrecognized
        };
    }
}
