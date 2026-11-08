package app.exteraless.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.BackupImageView;

public class CoverImage extends BackupImageView {

    public interface Listener {
        void onSeed(MessageObject messageObject, int seed);
    }

    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean disc;
    private final RectF rect = new RectF();
    private final PlayerIcon note;
    private int radius;
    private String currentKey;
    private MessageObject message;
    private int fallbackSeed;
    private Listener listener;

    public CoverImage(Context context, int noteSizeDp) {
        super(context);
        note = PlayerIcon.stroke(PlayerIcon.NOTE, noteSizeDp);
        getImageReceiver().setDelegate((imageReceiver, set, thumb, memCache) -> {
            if (set) {
                onBitmap(imageReceiver.getBitmap());
            }
        });
    }

    public void setListener(int fallbackSeed, Listener listener) {
        this.fallbackSeed = fallbackSeed;
        this.listener = listener;
    }

    public void setRadius(int r) {
        radius = r;
        setRoundRadius(r);
        invalidate();
    }

    public void setColors(int background, int foreground) {
        placeholderPaint.setColor(background);
        note.setColor(foreground);
        invalidate();
    }

    public void setDisc(boolean disc, int holeColor) {
        holePaint.setColor(holeColor);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0x33000000);
        this.disc = disc;
        invalidate();
    }

    public int getRadius() {
        return radius;
    }

    public MessageObject getMessage() {
        return message;
    }

    public void setMessage(MessageObject messageObject) {
        if (messageObject == null) {
            currentKey = null;
            message = null;
            setImageDrawable(null);
            return;
        }
        Bitmap file = PlayerArt.fileCover(messageObject);
        boolean needsArtwork = PlayerArt.needsArtwork(messageObject);
        String artwork = needsArtwork ? PlayerArtwork.cached(messageObject) : null;
        if (needsArtwork && artwork == null && PlayerArt.isPlaying(messageObject)) {
            PlayerArtwork.fetch(messageObject, url -> {
                if (message == messageObject) {
                    currentKey = null;
                    setMessage(messageObject);
                }
            });
        }
        String key = PlayerArt.key(messageObject) + (file != null ? ":f" : "") + (artwork != null ? ":a" : "");
        if (key.equals(currentKey)) {
            return;
        }
        currentKey = key;
        message = messageObject;
        if (artwork != null) {
            ImageLocation location = ImageLocation.getForPath(artwork);
            if (file != null) {
                setImage(location, null, new BitmapDrawable(getResources(), file), messageObject);
            } else {
                setImage(location, null, PlayerArt.thumbLocation(messageObject), null, null, 0, 1, messageObject);
            }
            return;
        }
        if (file != null) {
            setImageBitmap(file);
            onBitmap(file);
            return;
        }
        ImageLocation full = PlayerArt.fullLocation(messageObject);
        ImageLocation thumb = PlayerArt.thumbLocation(messageObject);
        if (full != null) {
            setImage(full, null, thumb, null, null, 0, 1, messageObject);
        } else if (thumb != null) {
            setImage(null, null, thumb, null, null, 0, 1, messageObject);
        } else {
            setImageDrawable(null);
        }
    }

    private void onBitmap(Bitmap bitmap) {
        final MessageObject target = message;
        if (bitmap == null || target == null || listener == null) {
            return;
        }
        PlayerArt.requestSeed(target, bitmap, fallbackSeed, seed -> {
            if (target == message && listener != null) {
                listener.onSeed(target, seed);
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!getImageReceiver().hasBitmapImage()) {
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, radius, radius, placeholderPaint);
            note.setBounds(0, 0, getWidth(), getHeight());
            note.draw(canvas);
        }
        super.onDraw(canvas);
        if (disc && getImageReceiver().hasBitmapImage()) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float size = Math.min(getWidth(), getHeight());
            ringPaint.setStrokeWidth(Math.max(1f, size * 0.012f));
            canvas.drawCircle(cx, cy, size * 0.16f, ringPaint);
            canvas.drawCircle(cx, cy, size * 0.05f, holePaint);
        }
    }
}
