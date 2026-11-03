package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.loadingindicator.LoadingIndicator;

import app.exteraless.appearance.M3CircularProgress;

import org.telegram.messenger.AndroidUtilities;

public class CircularProgressDrawable extends Drawable implements Drawable.Callback {

    public float size = AndroidUtilities.dp(18);
    public float thickness = AndroidUtilities.dp(2.25f);

    // Стиль 1 рисует LoadingIndicator из com.google.android.material, как у exteraGram;
    // дуга с дорожкой (стили 2 и 3) считается вручную в M3CircularProgress.
    private int currentStyle = M3CircularProgress.STYLE_LEGACY;
    private int trackColor;
    private int currentColor;
    private M3CircularProgress m3;
    private LoadingIndicator m3IndicatorView;
    private Drawable m3Drawable;

    public CircularProgressDrawable() {
        this(0xffffffff);
    }
    public CircularProgressDrawable(int color) {
        setColor(color);
        setStyle(M3CircularProgress.STYLE_LOADING_INDICATOR, AndroidUtilities.getActivity());
    }
    public CircularProgressDrawable(float size, float thickness, int color) {
        this.size = size;
        this.thickness = thickness;
        setColor(color);
        setStyle(M3CircularProgress.STYLE_LOADING_INDICATOR, AndroidUtilities.getActivity());
    }
    // Конструктор с цветом дорожки включает стиль 2
    public CircularProgressDrawable(float size, float thickness, int trackColor, int color) {
        this.size = size;
        this.thickness = thickness;
        this.trackColor = trackColor;
        setColor(color);
        setStyle(M3CircularProgress.STYLE_CIRCULAR, null);
        setTrackColor(trackColor);
    }

    /** Context нужен стилю 1: LoadingIndicator читает атрибуты темы. */
    public void setStyle(int style, Context context) {
        style = M3CircularProgress.degradeStyle(style);
        if (currentStyle == style
                && (style != M3CircularProgress.STYLE_LOADING_INDICATOR || m3Drawable != null)) {
            return;
        }
        currentStyle = style;
        final Drawable previous = m3Drawable;
        if (style == M3CircularProgress.STYLE_LOADING_INDICATOR && context != null) {
            if (m3IndicatorView == null) {
                m3IndicatorView = new LoadingIndicator(new ContextThemeWrapper(context,
                        com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar));
            }
            m3IndicatorView.setIndicatorSize((int) size);
            m3IndicatorView.setIndicatorColor(currentColor);
            m3Drawable = m3IndicatorView.getDrawable();
            if (m3Drawable != null) {
                m3Drawable.setCallback(this);
                m3Drawable.setVisible(isVisible(), false);
                updateIndicatorBounds();
            }
        } else {
            m3Drawable = null;
            m3IndicatorView = null;
        }
        if (previous != null && previous != m3Drawable) {
            previous.setVisible(false, false);
            previous.setCallback(null);
        }
        if (M3CircularProgress.isCircular(style)) {
            if (m3 == null) {
                m3 = new M3CircularProgress();
            }
            m3.setGap(AndroidUtilities.dp(2));
            m3.setTrackColor(trackColor);
            if (style == M3CircularProgress.STYLE_WAVY) {
                m3.setWavyValues(AndroidUtilities.dp(7), AndroidUtilities.dp(0.75f), AndroidUtilities.dp(6));
            } else {
                m3.setWavy(false);
            }
        }
        invalidateSelf();
    }

    public void setTrackColor(int color) {
        trackColor = color;
        if (m3 != null) {
            m3.setTrackColor(color);
        }
    }

    /** (амплитуда, длина волны, скорость) в dp. */
    public void setWavyValues(float amplitude, float wavelength, float speed) {
        if (m3 == null) {
            return;
        }
        m3.setWavyValues(AndroidUtilities.dp(wavelength), AndroidUtilities.dp(amplitude), AndroidUtilities.dp(speed));
    }

    private long start = -1;
    public static final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
    private float[] segment = new float[2];
    private void updateSegment() {
        final long now = SystemClock.elapsedRealtime();
        final long t = (now - start) % 5400;
        getSegments(t, segment);
    }

    public static void getSegments(float t, float[] segments) {
        segments[0] = Math.max(0, 1520 * t / 5400f - 20);
        segments[1] = 1520 * t / 5400f;
        for (int i = 0; i < 4; ++i) {
            segments[1] += interpolator.getInterpolation((t - i * 1350) / 667f) * 250;
            segments[0] += interpolator.getInterpolation((t - (667 + i * 1350)) / 667f) * 250;
        }
    }

    private final Paint paint = new Paint(); {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private float angleOffset;
    private final RectF bounds = new RectF();

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (start < 0) {
            start = SystemClock.elapsedRealtime();
        }
        if (currentStyle == M3CircularProgress.STYLE_LOADING_INDICATOR && m3Drawable != null) {
            if (getCallback() == null && !isVisible()) {
                setVisible(true, false);
            }
            m3Drawable.setVisible(isVisible(), false);
            m3Drawable.draw(canvas);
            return;
        }
        updateSegment();
        if (m3 != null && M3CircularProgress.isCircular(currentStyle)) {
            m3.draw(canvas, bounds, angleOffset + segment[0], segment[1] - segment[0], paint);
            invalidateSelf();
            return;
        }
        canvas.drawArc(
            bounds,
            angleOffset + segment[0],
            segment[1] - segment[0],
            false,
            paint
        );
        invalidateSelf();
    }

    public void reset() {
        start = -1;
    }

    public void setAngleOffset(float angleOffset) {
        this.angleOffset = angleOffset;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        bounds.set(
            left + (width - thickness / 2f - size) / 2f,
            top + (height - thickness / 2f - size) / 2f,
            left + (width + thickness / 2f + size) / 2f,
            top + (height + thickness / 2f + size) / 2f
        );
        super.setBounds(left, top, right, bottom);
        paint.setStrokeWidth(thickness);
        updateIndicatorBounds();
    }

    private void updateIndicatorBounds() {
        if (m3Drawable == null) {
            return;
        }
        final android.graphics.Rect rect = getBounds();
        if (rect.isEmpty()) {
            return;
        }
        final int side = (int) size;
        final int x = rect.left + (rect.width() - side) / 2;
        final int y = rect.top + (rect.height() - side) / 2;
        m3Drawable.setBounds(x, y, x + side, y + side);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        if (m3Drawable != null) {
            m3Drawable.setVisible(visible, restart);
        }
        return changed;
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }

    public void setColor(int color) {
        currentColor = color;
        paint.setColor(color);
        if (m3IndicatorView != null) {
            m3IndicatorView.setIndicatorColor(color);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        if (m3Drawable != null) {
            m3Drawable.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {}

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) (size + thickness);
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) (size + thickness);
    }
}
