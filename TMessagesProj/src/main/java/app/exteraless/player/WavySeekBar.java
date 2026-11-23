package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

public class WavySeekBar extends View {

    public interface Delegate {
        void onSeekStart();

        void onSeekMove(float progress);

        void onSeekEnd(float progress, boolean commit);
    }

    private static final float TAU = (float) (Math.PI * 2);

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Delegate delegate;
    private float progress;
    private float dragProgress;
    private boolean dragging;
    private boolean playing;
    private float amplitude;
    private float phase;
    private long lastFrame;
    private boolean frozen;
    private float maxAmplitude = dp(4);

    public WavySeekBar(Context context) {
        super(context);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(dp(4));
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(4));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setColors(int active, int inactive) {
        wavePaint.setColor(active);
        fillPaint.setColor(active);
        trackPaint.setColor(inactive);
        invalidate();
    }

    public void setFrozen(boolean value) {
        if (frozen == value) {
            return;
        }
        frozen = value;
        lastFrame = 0;
        if (!value) {
            invalidate();
        }
    }

    public void setMaxAmplitude(float px) {
        maxAmplitude = px;
        invalidate();
    }

    public void setProgress(float value) {
        if (!dragging && progress != value) {
            progress = value;
            invalidate();
        }
    }

    public float getProgress() {
        return dragging ? dragProgress : progress;
    }

    public void setPlaying(boolean value) {
        if (playing != value) {
            playing = value;
            lastFrame = 0;
            invalidate();
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    private float left() {
        return dp(3);
    }

    private float right() {
        return getWidth() - dp(3);
    }

    private float progressAt(float x) {
        float w = right() - left();
        if (w <= 0) {
            return 0;
        }
        return Math.max(0f, Math.min(1f, (x - left()) / w));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                dragProgress = progressAt(event.getX());
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (delegate != null) {
                    delegate.onSeekStart();
                    delegate.onSeekMove(dragProgress);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    dragProgress = progressAt(event.getX());
                    if (delegate != null) {
                        delegate.onSeekMove(dragProgress);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    progress = dragProgress;
                    if (delegate != null) {
                        delegate.onSeekEnd(progress, true);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (delegate != null) {
                        delegate.onSeekEnd(progress, false);
                    }
                    invalidate();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        float dt = frozen ? 0f : lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = frozen ? 0 : now;
        float targetAmp = playing ? maxAmplitude : 0f;
        amplitude += (targetAmp - amplitude) * (1f - (float) Math.pow(0.85, dt / 0.04f));
        if (Math.abs(amplitude - targetAmp) < dpf2(0.05f)) {
            amplitude = targetAmp;
        }
        if (amplitude > 0f) {
            phase = (phase + 6f * dt) % TAU;
        }

        float cy = getHeight() / 2f;
        float left = left();
        float right = right();
        float p = dragging ? dragProgress : progress;
        float thumbW = dragging ? dp(3) : dp(5);
        float x = left + p * (right - left);
        float gap = dp(6);
        float waveEnd = x - gap - thumbW / 2f;
        float lambda = dp(34);
        float step = dp(2);
        if (waveEnd > left) {
            path.rewind();
            boolean first = true;
            for (float px = left; ; px += step) {
                boolean last = px >= waveEnd;
                float cx = last ? waveEnd : px;
                float y = cy + amplitude * (float) Math.sin(TAU * cx / lambda + phase);
                if (first) {
                    path.moveTo(cx, y);
                    first = false;
                } else {
                    path.lineTo(cx, y);
                }
                if (last) {
                    break;
                }
            }
            canvas.drawPath(path, wavePaint);
        }
        float stopX = right - dp(1);
        float restX = Math.min(stopX, x + thumbW / 2f + gap);
        if (restX < stopX) {
            canvas.drawLine(restX, cy, stopX, cy, trackPaint);
        }
        canvas.drawCircle(stopX, cy, dp(2), fillPaint);
        rect.set(x - thumbW / 2f, cy - dp(14), x + thumbW / 2f, cy + dp(14));
        canvas.drawRoundRect(rect, dpf2(1.5f), dpf2(1.5f), fillPaint);

        if (!frozen && (amplitude > 0f || targetAmp != amplitude)) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0, 100, getProgress() * 100f));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            float value = Math.max(0f, Math.min(1f, progress + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 0.05f : -0.05f)));
            progress = value;
            if (delegate != null) {
                delegate.onSeekEnd(value, true);
            }
            invalidate();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
