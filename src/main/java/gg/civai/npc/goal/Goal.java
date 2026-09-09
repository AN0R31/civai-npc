package gg.civai.npc.goal;

/**
 * Represents a high-level goal set by the LLM layer.
 * Executed deterministically by {@link GoalEngine} on the 200ms game tick.
 *
 * v1.2: replaces the old NpcAction tick-based approach.
 *  - The LLM sets a Goal (IDLE / WANDER / FOLLOW / GOTO / CONVERSE).
 *  - GoalEngine ticks it locally until completion or failure.
 *  - Completion/failure fires a callback → LLM picks the next Goal.
 */
public class Goal {

    public enum Type {
        IDLE,     // do nothing; after 30 s, fires completion so LLM picks next
        WANDER,   // roam randomly within wanderRadius blocks
        FOLLOW,   // track a named player (3-block offset behind them)
        GOTO,     // walk to explicit coordinates
        CONVERSE  // face a player, stay close; runs until interrupted
    }

    public enum Status {
        PENDING,   // just created, not yet started
        ACTIVE,    // currently executing
        COMPLETED, // reached target / timed out naturally
        FAILED,    // unrecoverable — player offline, blocked, etc.
        BLOCKED    // temporary obstacle (GoalEngine retries for GOTO)
    }

    public final Type   type;
    public Status       status;               // mutable — GoalEngine updates this
    public final String targetPlayerName;     // FOLLOW / CONVERSE
    public final double targetX, targetY, targetZ; // GOTO / WANDER destination
    public final int    wanderRadius;         // WANDER (default 20)
    public String       failReason;           // set on FAILED / BLOCKED
    public final long   startedAt;            // System.currentTimeMillis() at creation
    public final long   timeoutMs;            // 0 = no timeout

    private Goal(Type type, String targetPlayerName,
                 double targetX, double targetY, double targetZ,
                 int wanderRadius, long timeoutMs) {
        this.type             = type;
        this.status           = Status.PENDING;
        this.targetPlayerName = targetPlayerName;
        this.targetX          = targetX;
        this.targetY          = targetY;
        this.targetZ          = targetZ;
        this.wanderRadius     = wanderRadius;
        this.startedAt        = System.currentTimeMillis();
        this.timeoutMs        = timeoutMs;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static Goal idle() {
        return new Goal(Type.IDLE, null, 0, 0, 0, 0, 0);
    }

    public static Goal wander(int radius) {
        int r = radius > 0 ? radius : 20;
        return new Goal(Type.WANDER, null, 0, 0, 0, r, 120_000L); // 2-minute timeout
    }

    public static Goal follow(String playerName) {
        return new Goal(Type.FOLLOW, playerName, 0, 0, 0, 0, 0); // no timeout — runs until interrupted
    }

    public static Goal goTo(double x, double y, double z) {
        return new Goal(Type.GOTO, null, x, y, z, 0, 60_000L); // 60-second timeout
    }

    public static Goal converse(String playerName) {
        return new Goal(Type.CONVERSE, playerName, 0, 0, 0, 0, 0); // no timeout
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Human-readable summary used in goal history and status command. */
    @Override
    public String toString() {
        String params = switch (type) {
            case FOLLOW, CONVERSE -> "player=" + targetPlayerName;
            case GOTO -> String.format("%.0f,%.0f,%.0f", targetX, targetY, targetZ);
            case WANDER -> "r=" + wanderRadius;
            case IDLE -> "";
        };
        return type + (params.isEmpty() ? "" : "(" + params + ")") + "[" + status + "]";
    }
}
