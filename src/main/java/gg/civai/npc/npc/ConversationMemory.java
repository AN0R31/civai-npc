package gg.civai.npc.npc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Circular buffer of the last N player↔NPC interactions.
 * Thread-safe: written from the main thread after an AI response,
 * read from the main thread when building the next Ollama prompt.
 */
public class ConversationMemory {

    /** One recorded exchange. */
    public record Entry(String playerName, String playerMessage, String steveResponse, long timestamp) {}

    private final int maxEntries;
    private final ArrayDeque<Entry> entries;

    public ConversationMemory(int maxEntries) {
        this.maxEntries = maxEntries;
        this.entries    = new ArrayDeque<>(maxEntries);
    }

    /** Add a new entry (trims oldest if over capacity). */
    public synchronized void add(String playerName, String playerMessage, String steveResponse) {
        entries.addLast(new Entry(playerName, playerMessage, steveResponse, System.currentTimeMillis()));
        while (entries.size() > maxEntries) entries.pollFirst();
    }

    /** Returns a snapshot of all entries in chronological order. */
    public synchronized List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    public synchronized int size() { return entries.size(); }

    public int capacity() { return maxEntries; }
}
