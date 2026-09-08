package gg.civai.npc.npc;

/**
 * Represents a parsed action from the AI response.
 */
public class NpcAction {

    public enum Type {
        IDLE,
        MOVE_TO,
        SPEAK,
        MOVE_AND_SPEAK
    }

    public final Type type;
    public final String thought;
    public final String speech;
    public final double targetX;
    public final double targetY;
    public final double targetZ;

    // IDLE or SPEAK only
    public NpcAction(Type type, String thought, String speech) {
        this.type = type;
        this.thought = thought;
        this.speech = speech;
        this.targetX = 0;
        this.targetY = 0;
        this.targetZ = 0;
    }

    // MOVE_TO or MOVE_AND_SPEAK
    public NpcAction(Type type, String thought, String speech, double x, double y, double z) {
        this.type = type;
        this.thought = thought;
        this.speech = speech;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public static NpcAction idle(String thought) {
        return new NpcAction(Type.IDLE, thought, null);
    }

    @Override
    public String toString() {
        return "NpcAction{type=" + type + ", thought='" + thought + "', speech='" + speech + "', target=(" + targetX + "," + targetY + "," + targetZ + ")}";
    }
}