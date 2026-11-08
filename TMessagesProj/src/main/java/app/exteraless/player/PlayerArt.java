package app.exteraless.player;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class PlayerArt {

    public interface SeedCallback {
        void onSeed(int seed);
    }

    private static final int SMALL_THUMB_SIZE = 360;
    private static final int FULL_THUMB_SIZE = 1280;
    private static final int MIN_ARTWORK_SIZE = 640;

    private static final LruCache<String, Integer> seeds = new LruCache<>(64);
    private static final ArrayList<Pending> pending = new ArrayList<>();

    private static final class Pending {
        final String key;
        final SeedCallback callback;

        Pending(String key, SeedCallback callback) {
            this.key = key;
            this.callback = callback;
        }
    }

    private PlayerArt() {
    }

    public static String key(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        TLRPC.Document document = messageObject.getDocument();
        if (document != null && document.id != 0) {
            return "d" + document.id;
        }
        return "m" + messageObject.getDialogId() + "_" + messageObject.getId();
    }

    public static Integer cachedSeed(MessageObject messageObject) {
        String key = key(messageObject);
        return key == null ? null : seeds.get(key);
    }

    public static boolean isPlaying(MessageObject messageObject) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return playing != null && messageObject != null && TextUtils.equals(key(playing), key(messageObject));
    }

    public static Bitmap fileCover(MessageObject messageObject) {
        if (messageObject != null && messageObject.audioCover != null) {
            return messageObject.audioCover;
        }
        if (!isPlaying(messageObject)) {
            return null;
        }
        AudioInfo info = MediaController.getInstance().getAudioInfo();
        return info != null ? info.getCover() : null;
    }

    private static TLRPC.PhotoSize documentThumb(MessageObject messageObject, int size) {
        TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, size) : null;
        return thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive ? thumb : null;
    }

    static boolean needsArtwork(MessageObject messageObject) {
        if (messageObject == null || messageObject.isVoice() || messageObject.isRoundVideo()) {
            return false;
        }
        Bitmap cover = fileCover(messageObject);
        if (cover != null) {
            return Math.max(cover.getWidth(), cover.getHeight()) < MIN_ARTWORK_SIZE;
        }
        TLRPC.PhotoSize thumb = documentThumb(messageObject, FULL_THUMB_SIZE);
        return thumb == null || Math.max(thumb.w, thumb.h) < MIN_ARTWORK_SIZE;
    }

    public static ImageLocation thumbLocation(MessageObject messageObject) {
        TLRPC.PhotoSize thumb = documentThumb(messageObject, SMALL_THUMB_SIZE);
        if (thumb != null) {
            return ImageLocation.getForDocument(thumb, messageObject.getDocument());
        }
        String small = messageObject.getArtworkUrl(true);
        if (small != null) {
            return ImageLocation.getForPath(small);
        }
        return null;
    }

    public static ImageLocation fullLocation(MessageObject messageObject) {
        TLRPC.PhotoSize thumb = documentThumb(messageObject, FULL_THUMB_SIZE);
        if (thumb != null) {
            return ImageLocation.getForDocument(thumb, messageObject.getDocument());
        }
        String url = messageObject.getArtworkUrl(false);
        return TextUtils.isEmpty(url) ? null : ImageLocation.getForPath(url);
    }

    public static void requestSeed(MessageObject messageObject, Bitmap bitmap, int fallback, SeedCallback callback) {
        String key = key(messageObject);
        if (key == null) {
            return;
        }
        Integer cached = seeds.get(key);
        if (cached != null) {
            callback.onSeed(cached);
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).key.equals(key)) {
                pending.add(new Pending(key, callback));
                return;
            }
        }
        Bitmap sample = PlayerColors.sample(bitmap);
        if (sample == null) {
            return;
        }
        pending.add(new Pending(key, callback));
        Utilities.globalQueue.postRunnable(() -> {
            int seed = PlayerColors.seedFromSample(sample, fallback);
            AndroidUtilities.runOnUIThread(() -> {
                seeds.put(key, seed);
                for (int i = pending.size() - 1; i >= 0; i--) {
                    Pending p = pending.get(i);
                    if (p.key.equals(key)) {
                        pending.remove(i);
                        p.callback.onSeed(seed);
                    }
                }
            });
        });
    }
}
