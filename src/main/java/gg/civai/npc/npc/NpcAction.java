package gg.civai.npc.npc;

/**
 * Represents a parsed action from the AI response.
 *
 * v1.2: added nextActivity — a short phrase describing what the NPC is
 *       doing between ticks ("wandering east", "resting by the oak").
 *       Persisted in AiNpc and fed back into the next prompt so the NPC
 *       has continuity rather than starting fresh every 10 seconds.
 */
public class NpcAction {

    public enum Type {
        IDLE,           // stay put, do nothing (DEFAULT — most common)
        MOVE_TO,        // walk somewhere silently
        SPEAK,          // say something without moving
        MOVE_AND_SPEAK, // walk to coords AND speak simultaneously
        REPORT,         // narrate surroundings in first person (no movement)
        TIME_REPORT     // answer time/weather question naturally (no movement)
    }

    public final Type   type;
    public final String thought;
    public final String speech;
    public final String nextActivity; // what the NPC is doing now (persists to next tick)
    public final double targetX;
    public final double targetY;
    public final double targetZ;

    /** For actions that don't need a target position (IDLE, SPEAK, REPORT, TIME_REPORT). */
    public NpcAction(Type type, String thought, String speech, String nextActivity) {
        this.type         = type;
        this.thought      = thought;
        this.speech       = speech;
        this.nextActivity = nextActivity;
        this.targetX      = 0;
        this.targetY      = 0;
        this.targetZ      = 0;
    }

    /** For MOVE_TO or MOVE_AND_SPEAK. */
    public NpcAction(Type type, String thought, String speech, String nextActivity,
                     double x, double y, double z) {
        this.type         = type;
        this.thought      = thought;
        this.speech       = speech;
        this.nextActivity = nextActivity;
        this.targetX      = x;
        this.targetY      = y;
        this.targetZ      = z;
    }

    public static NpcAction idle(String thought) {
        return new NpcAction(Type.IDLE, thought, null, "standing by");
    }

    @Override
    public String toString() {
        return "NpcAction{type=" + type
                + ", thought='" + thought + '\''
                + ", speech='" + speech + '\''
                + ", activity='" + nextActivity + '\''
                + ", target=(" + targetX + "," + targetY + "," + targetZ + ")}";
    }
}
