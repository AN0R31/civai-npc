package gg.civai.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.civai.npc.npc.NpcAction;
import gg.civai.npc.npc.WorldState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

public class OllamaClient {

    private static final String SYSTEM_PROMPT = """
        You are SuperSteve, an AI villager living in a Minecraft world.
        You have a curious, friendly personality. You explore, react to your environment, and chat with players.

        You will receive a world state snapshot every 10 seconds. Based on it, decide what to do next.

        Respond ONLY with a valid JSON object in this exact format:
        {
          "thought": "your internal reasoning (1-2 sentences)",
          "action": "IDLE | MOVE_TO | SPEAK | MOVE_AND_SPEAK",
          "speech": "what you say out loud, or null if silent",
          "target_x": 0.0,
          "target_y": 0.0,
          "target_z": 0.0
        }

        Rules:
        - Use IDLE if you want to stay put and do nothing
        - Use MOVE_TO to walk somewhere (set target coordinates)
        - Use SPEAK to say something without moving
        - Use MOVE_AND_SPEAK to move and talk at the same time
        - target coordinates are only needed for MOVE_TO and MOVE_AND_SPEAK
        - Keep speech natural and in-character, max 1-2 sentences
        - Do not include any text outside the JSON object
        """;

    private final HttpClient httpClient;
    private final String host;
    private final String model;
    private final Gson gson = new Gson();
    private final Logger logger;

    public OllamaClient(String host, String model, int timeoutSeconds) {
        this.host = host;
        this.model = model;
        this.logger = Logger.getLogger("OllamaClient");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * Sends world state to Ollama and returns a parsed NpcAction.
     * This is a blocking call — always run it off the main thread.
     */
    public NpcAction think(WorldState state) {
        try {
            String prompt = state.toPromptString();

            // Build Ollama /api/chat request body
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);

            JsonArray messages = new JsonArray();

            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", SYSTEM_PROMPT);
            messages.add(systemMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", prompt);
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

    private NpcAction parseResponse(String responseBody, WorldState state) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            String content = root.getAsJsonObject("message").get("content").getAsString();

            // Strip markdown fences if model ignores instructions
            content = content.replaceAll("```json|```", "").trim();

            JsonObject json = JsonParser.parseString(content).getAsJsonObject();

            String thought = json.has("thought") ? json.get("thought").getAsString() : "";
            String actionStr = json.has("action") ? json.get("action").getAsString() : "IDLE";
            String speech = json.has("speech") && !json.get("speech").isJsonNull()
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
            logger.warning("Failed to parse Ollama response: " + e.getMessage() + " | Body: " + responseBody);
            return NpcAction.idle("Parse error, staying put.");
        }
    }
}