package app.exteraless.player;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;

public final class AudioFileCover {

    private static final LruCache<String, Optional<Bitmap>> covers = new LruCache<>(64);
    private static final HashMap<String, ArrayList<Consumer<Bitmap>>> pending = new HashMap<>();

    private AudioFileCover() {
    }

    public static void loadSmallCover(MessageObject messageObject, Consumer<Bitmap> callback) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getDocument() == null) {
            return;
        }
        File file = null;
        final String attachPath = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            final File attach = new File(attachPath);
            if (attach.exists()) {
                file = attach;
            }
        }
        if (file == null) {
            file = FileLoader.getInstance(messageObject.currentAccount).getPathToAttach(messageObject.getDocument());
        }
        if (file == null || file.length() == 0) {
            return;
        }
        final String key = file.getAbsolutePath();
        final Optional<Bitmap> cached = covers.get(key);
        if (cached != null) {
            cached.ifPresent(callback);
            return;
        }
        final ArrayList<Consumer<Bitmap>> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        final ArrayList<Consumer<Bitmap>> callbacks = new ArrayList<>();
        callbacks.add(callback);
        pending.put(key, callbacks);
        final File source = file;
        Utilities.globalQueue.postRunnable(() -> {
            final AudioInfo info = AudioInfo.getAudioInfo(source);
            final Bitmap cover = info != null ? info.getSmallCover() : null;
            AndroidUtilities.runOnUIThread(() -> {
                covers.put(key, Optional.ofNullable(cover));
                final ArrayList<Consumer<Bitmap>> done = pending.remove(key);
                if (done == null || cover == null) {
                    return;
                }
                for (Consumer<Bitmap> c : done) {
                    c.accept(cover);
                }
            });
        });
    }
}
