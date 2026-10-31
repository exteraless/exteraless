package app.exteraless.privacy;

import java.util.HashSet;

/** Suppress duplicate payload-link starts in this process; failed requests remain retryable. */
public final class BotStartDeduplicator {
    // shortcut: process-local deduplication; persist only if suppression across restarts is required.
    private final HashSet<String> starts = new HashSet<>();

    private String key(long identity, long botId, String payload) {
        return identity + ":" + botId + ":" + payload;
    }

    public synchronized boolean begin(long identity, long botId, String payload) {
        return starts.add(key(identity, botId, payload));
    }

    public synchronized void failed(long identity, long botId, String payload) {
        starts.remove(key(identity, botId, payload));
    }
}
