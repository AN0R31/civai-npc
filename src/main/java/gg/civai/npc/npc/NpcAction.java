package gg.civai.npc.npc;

/**
 * Represents a parsed action from the AI response.
 */
public class NpcAction {

    public enum Type {
        IDLE,           // stay put, do nothing
        MOVE_TO,        // walk somewhere
        SPEAK,          // say something without moving
        MOVE_AND_SPEAK, // walk and talk simultaneously
        REPORT,         // narrate surroundings in first person (no movement)
        TIME_REPORT     // answer time/weather question naturally (no movement)
    }

    public final Type type;
    public final String thought;
    public final String speech;
    public final double targetX;
    public final double targetY;
    public final double targetZ;

    /** For actions that don't need a target position (IDLE, SPEAK, REPORT, TIME_REPORT). */
    public NpcAction(Type type, String thought, String speech) {
        this.type    = type;
        this.thought = thought;
        this.speech  = speech;
        this.targetX = 0;
        this.targetY = 0;
        this.targetZ = 0;
    }

    /** For MOVE_TO or MOVE_AND_SPEAK. */
    public NpcAction(Type type, String thought, String speech, double x, double y, double z) {
        this.type    = type;
        this.thought = thought;
        this.speech  = speech;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public static NpcAction idle(String thought) {
        return new NpcAction(Type.IDLE, thought, null);
    }

    @Override
    public String toString() {
        return "NpcAction{type=" + type
                + ", thought='" + thought + '\''
                + ", speech='" + speech + '\''
                + ", target=(" + targetX + "," + targetY + "," + targetZ + ")}";
    }
}
