package app.exteraless.chats;

import android.os.SystemClock;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

public final class ScheduledGuard {

    private static final long KEEP_MS = 60_000;

    private static final HashMap<String, HashMap<Integer, Long>> RECENT = new HashMap<>();

    private ScheduledGuard() {
    }

    public static void sent(int account, Object response) {
        if (!(response instanceof TLRPC.Updates)) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (RECENT) {
            for (TLRPC.Update update : ((TLRPC.Updates) response).updates) {
                if (update instanceof TL_update.TL_updateNewScheduledMessage) {
                    final TLRPC.Message message = ((TL_update.TL_updateNewScheduledMessage) update).message;
                    if (message == null || message.id <= 0) {
                        continue;
                    }
                    final String key = account + "_" + MessageObject.getDialogId(message);
                    HashMap<Integer, Long> ids = RECENT.get(key);
                    if (ids == null) {
                        RECENT.put(key, ids = new HashMap<>());
                    }
                    ids.put(message.id, now);
                }
            }
        }
    }

    public static boolean stale(int account, long dialogId, TLRPC.messages_Messages res) {
        final long now = SystemClock.elapsedRealtime();
        final HashSet<Integer> missing = new HashSet<>();
        synchronized (RECENT) {
            final String key = account + "_" + dialogId;
            final HashMap<Integer, Long> ids = RECENT.get(key);
            if (ids == null) {
                return false;
            }
            for (Iterator<Map.Entry<Integer, Long>> it = ids.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<Integer, Long> entry = it.next();
                if (now - entry.getValue() > KEEP_MS) {
                    it.remove();
                } else {
                    missing.add(entry.getKey());
                }
            }
            if (ids.isEmpty()) {
                RECENT.remove(key);
            }
        }
        if (missing.isEmpty() || res == null || res.messages == null) {
            return false;
        }
        for (TLRPC.Message message : res.messages) {
            if (message != null) {
                missing.remove(message.id);
            }
        }
        return !missing.isEmpty();
    }
}
