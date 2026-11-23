package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessageObject;

public class RingPlayButton extends View {

    private static final float TAU = (float) (Math.PI * 2);
    private static final long WAVE_FRAME_MS = 33;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Runnable waveFrame = this::invalidate;
    private final PlayerIcon playIcon;
    private final PlayerIcon pauseIcon;
    private final Spring alt = new Spring(0, 520f, 0.6f, 0.002f);
    private final float radius;
    private final float maxAmplitude;
    private final int waves;
    private int ringColor;
    private int iconColor;
    private MessageObject message;
    private boolean playing;
    private float amplitude;
    private float phase;
    private long lastFrame;
    private float drawnProgress = -1f;
    private boolean ringVisible = true;

    public RingPlayButton(Context context, int radiusDp, float strokeDp, int iconDp, float amplitudeDp, int waves) {
        super(context);
        radius = dp(radiusDp);
        maxAmplitude = dpf2(amplitudeDp);
        this.waves = waves;
        playIcon = PlayerIcon.fill(PlayerIcon.PLAY, iconDp);
        pauseIcon = PlayerIcon.fill(PlayerIcon.PAUSE, iconDp);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dpf2(strokeDp));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dpf2(strokeDp));
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setColors(int ring, int track, int icon) {
        ringColor = ring;
        trackPaint.setColor(track);
        iconColor = icon;
        invalidate();
    }

    public void setMessage(MessageObject messageObject) {
        message = messageObject;
        invalidate();
    }

    public void setRingVisible(boolean visible) {
        if (ringVisible == visible) {
            return;
        }
        ringVisible = visible;
        lastFrame = 0;
        invalidate();
    }

    public void setPlaying(boolean value, boolean animated) {
        if (playing == value && alt.target == (value ? 1f : 0f)) {
            return;
        }
        playing = value;
        if (animated) {
            alt.target = value ? 1f : 0f;
        } else {
            alt.snap(value ? 1f : 0f);
        }
        lastFrame = 0;
        invalidate();
    }

    public void progressChanged() {
        if (!ringVisible || amplitude > 0f) {
            return;
        }
        MessageObject mo = message;
        float progress = mo != null ? Math.max(0f, Math.min(1f, mo.audioProgress)) : 0f;
        if (Math.abs(progress - drawnProgress) * TAU * radius >= 1f) {
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(waveFrame);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = alt.step(dt);
        float targetAmp = ringVisible && playing && !LiteMode.isPowerSaverApplied() ? maxAmplitude : 0f;
        amplitude += (targetAmp - amplitude) * (1f - (float) Math.pow(0.85, dt / 0.04f));
        if (Math.abs(amplitude - targetAmp) < dpf2(0.03f)) {
            amplitude = targetAmp;
        }
        if (amplitude > 0f) {
            phase = (phase + 6f * dt) % TAU;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (ringVisible) {
            canvas.drawCircle(cx, cy, radius, trackPaint);
        }
        MessageObject mo = message;
        float progress = mo != null ? Math.max(0f, Math.min(1f, mo.audioProgress)) : 0f;
        drawnProgress = progress;
        float sweep = ringVisible ? 360f * progress : 0f;
        if (sweep > 0.5f) {
            path.rewind();
            boolean first = true;
            for (float deg = 0; ; deg += 3f) {
                boolean last = deg >= sweep;
                float d = last ? sweep : deg;
                double th = Math.toRadians(d - 90);
                float r = radius + amplitude * (float) Math.sin(waves * Math.toRadians(d) + phase);
                float x = cx + r * (float) Math.cos(th);
                float y = cy + r * (float) Math.sin(th);
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
                if (last) {
                    break;
                }
            }
            ringPaint.setColor(ringColor);
            canvas.drawPath(path, ringPaint);
        }
        float t = Math.max(0f, Math.min(1f, alt.value));
        drawIcon(canvas, playIcon, 1f - t, 1f - 0.4f * alt.value);
        drawIcon(canvas, pauseIcon, t, 0.6f + 0.4f * alt.value);
        removeCallbacks(waveFrame);
        if (animating || targetAmp != amplitude) {
            postInvalidateOnAnimation();
        } else if (amplitude > 0f) {
            postDelayed(waveFrame, WAVE_FRAME_MS);
        } else {
            lastFrame = 0;
        }
    }

    private void drawIcon(Canvas canvas, PlayerIcon icon, float alpha, float scale) {
        if (alpha <= 0f) {
            return;
        }
        int size = icon.getIntrinsicWidth();
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        icon.setColor(iconColor);
        icon.setAlpha((int) (255 * Math.min(1f, alpha)));
        icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        icon.draw(canvas);
        canvas.restore();
    }
}
