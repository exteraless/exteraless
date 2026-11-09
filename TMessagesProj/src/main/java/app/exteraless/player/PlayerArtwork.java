package app.exteraless.player;

import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

final class PlayerArtwork {

    interface Callback {
        void onResult(String url);
    }

    private static final long NOT_FOUND_TTL_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_URL_BYTES = 2048;

    private static final LruCache<String, String> memory = new LruCache<>(128);
    private static final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();
    private static final DispatchQueue queue = new DispatchQueue("playerArtwork");

    private PlayerArtwork() {
    }

    private static OnlineLyrics.Query query(MessageObject messageObject) {
        if (messageObject == null || messageObject.isVoice() || messageObject.isRoundVideo()) {
            return null;
        }
        OnlineLyrics.Query query = new OnlineLyrics.Query(messageObject.getMusicAuthor(false), messageObject.getMusicTitle(false),
                null, (int) Math.round(messageObject.getDuration()));
        return query.valid() ? query : null;
    }

    static String cached(MessageObject messageObject) {
        OnlineLyrics.Query query = query(messageObject);
        String url = query == null ? null : memory.get(query.key());
        return TextUtils.isEmpty(url) ? null : url;
    }

    static void fetch(MessageObject messageObject, Callback callback) {
        OnlineLyrics.Query query = query(messageObject);
        if (query == null) {
            return;
        }
        String key = query.key();
        if (memory.get(key) != null) {
            return;
        }
        ArrayList<Callback> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        pending.put(key, waiting);
        queue.postRunnable(() -> {
            String url = null;
            boolean resolved = false;
            try {
                url = load(query, key);
                resolved = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
            String result = url;
            boolean remember = resolved;
            AndroidUtilities.runOnUIThread(() -> finish(key, result, remember));
        });
    }

    private static void finish(String key, String url, boolean remember) {
        if (remember) {
            memory.put(key, url != null ? url : "");
        }
        ArrayList<Callback> waiting = pending.remove(key);
        if (waiting == null || url == null) {
            return;
        }
        for (Callback callback : waiting) {
            callback.onResult(url);
        }
    }

    private static File directory() {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "player_artwork");
        return dir.exists() || dir.mkdirs() ? dir : null;
    }

    private static String load(OnlineLyrics.Query query, String key) throws Exception {
        File dir = directory();
        String name = Utilities.MD5(key);
        File found = dir == null ? null : new File(dir, name + ".url");
        File missing = dir == null ? null : new File(dir, name + ".none");
        if (found != null && found.length() > 0) {
            String saved = read(found);
            if (saved != null) {
                return saved;
            }
        }
        if (missing != null && missing.exists() && System.currentTimeMillis() - missing.lastModified() < NOT_FOUND_TTL_MS) {
            return null;
        }
        String url = OnlineLyrics.findArtwork(query);
        if (url != null && found != null) {
            try (FileOutputStream out = new FileOutputStream(found)) {
                out.write(url.getBytes(StandardCharsets.UTF_8));
            }
        } else if (url == null && missing != null) {
            new FileOutputStream(missing).close();
        }
        return url;
    }

    private static String read(File file) throws Exception {
        byte[] bytes = new byte[(int) Math.min(file.length(), MAX_URL_BYTES)];
        int total = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            int count;
            while (total < bytes.length && (count = in.read(bytes, total, bytes.length - total)) > 0) {
                total += count;
            }
        }
        String value = new String(bytes, 0, total, StandardCharsets.UTF_8).trim();
        return value.startsWith("https://") ? value : null;
    }
}
