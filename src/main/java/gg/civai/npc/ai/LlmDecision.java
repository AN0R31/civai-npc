package gg.civai.npc.ai;

import gg.civai.npc.goal.Goal;

/**
 * The parsed output of a single Ollama call.
 *
 * v1.2: replaces NpcAction as the return type of OllamaClient.think().
 * The LLM now sets a Goal (executed by GoalEngine) plus optional speech.
 */
public record LlmDecision(Goal goal, String speech, String thought) {

    /** Fallback when Ollama is unreachable or returns garbage. */
    public static LlmDecision fallback(String reason) {
        Goal g = Goal.idle();
        g.status = Goal.Status.ACTIVE;
        return new LlmDecision(g, null, reason);
    }
}
