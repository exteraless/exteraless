/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.android.material.loadingindicator.LoadingIndicator;

import app.exteraless.appearance.M3CircularProgress;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class RadialProgressView extends View implements Drawable.Callback {

    private long lastUpdateTime;
    private float radOffset;
    private float currentCircleLength;
    private boolean risingCircleLength;
    private float currentProgressTime;
    private RectF cicleRect = new RectF();
    private boolean useSelfAlpha;
    private float drawingCircleLenght;

    private int progressColor;
    private LoadingIndicator m3IndicatorView;
    private Drawable m3Drawable;

    private DecelerateInterpolator decelerateInterpolator;
    private AccelerateInterpolator accelerateInterpolator;
    private Paint progressPaint;
    private static final float rotationTime = 2000;
    private static final float risingTime = 500;
    private int size;

    private float currentProgress;
    private float progressAnimationStart;
    private int progressTime;
    private float animatedProgress;
    private boolean toCircle;
    private float toCircleProgress;

    private boolean noProgress = true;
    private final Theme.ResourcesProvider resourcesProvider;

    // Стиль 1 рисует LoadingIndicator из com.google.android.material, как у exteraGram;
    // дуга с дорожкой и зазором (стили 2 и 3) считается вручную в M3CircularProgress.
    private int currentStyle = M3CircularProgress.STYLE_LEGACY;
    private int trackColor;
    private boolean trackColorCustom;
    private M3CircularProgress m3;
    // Автовыбор стиля по теме: на Monet спиннеры — LoadingIndicator (морф-фигура),
    // детерминированный прогресс — CircularProgressIndicator (дуга с дорожкой).
    private int themeStyle = -1;
    private boolean manualStyle;

    public RadialProgressView(Context context) {
        this(context, null);
    }

    public RadialProgressView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        size = AndroidUtilities.dp(40);

        progressColor = getThemedColor(Theme.key_progressCircle);
        decelerateInterpolator = new DecelerateInterpolator();
        accelerateInterpolator = new AccelerateInterpolator();
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(AndroidUtilities.dp(3));
        progressPaint.setColor(progressColor);
    }

    public void setUseSelfAlpha(boolean value) {
        useSelfAlpha = value;
    }

    @Keep
    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (useSelfAlpha) {
            Drawable background = getBackground();
            int a = (int) (alpha * 255);
            if (background != null) {
                background.setAlpha(a);
            }
            progressPaint.setAlpha(a);
        }
    }

    public void setNoProgress(boolean value) {
        noProgress = value;
    }

    public void setProgress(float value) {
        currentProgress = value;
        if (animatedProgress > value) {
            animatedProgress = value;
        }
        progressAnimationStart = animatedProgress;
        progressTime = 0;
    }

    public void sync(RadialProgressView from) {
        lastUpdateTime = from.lastUpdateTime;
        radOffset = from.radOffset;
        toCircle = from.toCircle;
        toCircleProgress = from.toCircleProgress;
        noProgress = from.noProgress;
        currentCircleLength = from.currentCircleLength;
        drawingCircleLenght = from.drawingCircleLenght;
        currentProgressTime = from.currentProgressTime;
        currentProgress = from.currentProgress;
        progressTime = from.progressTime;
        animatedProgress = from.animatedProgress;
        risingCircleLength = from.risingCircleLength;
        progressAnimationStart = from.progressAnimationStart;
        updateAnimation(17 * 5);
    }

    private void updateAnimation() {
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        if (dt > 17) {
            dt = 17;
        }
        lastUpdateTime = newTime;
        updateAnimation(dt);
    }

    private void updateAnimation(long dt) {
        radOffset += 360 * dt / rotationTime;
        int count = (int) (radOffset / 360);
        radOffset -= count * 360;

        if (toCircle && toCircleProgress != 1f) {
            toCircleProgress += 16 / 220f;
            if (toCircleProgress > 1f) {
                toCircleProgress = 1f;
            }
        } else if (!toCircle && toCircleProgress != 0f) {
            toCircleProgress -= 16 / 400f;
            if (toCircleProgress < 0) {
                toCircleProgress = 0f;
            }
        }

        if (noProgress) {
            if (toCircleProgress == 0) {
                currentProgressTime += dt;
                if (currentProgressTime >= risingTime) {
                    currentProgressTime = risingTime;
                }
                if (risingCircleLength) {
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                } else {
                    currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                }

                if (currentProgressTime == risingTime) {
                    if (risingCircleLength) {
                        radOffset += 270;
                        currentCircleLength = -266;
                    }
                    risingCircleLength = !risingCircleLength;
                    currentProgressTime = 0;
                }
            } else {
                if (risingCircleLength) {
                    float old = currentCircleLength;
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                    currentCircleLength += 360 * toCircleProgress;
                    float dx = old - currentCircleLength;
                    if (dx > 0) {
                        radOffset += old - currentCircleLength;
                    }
                } else {
                    float old = currentCircleLength;
                    currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                    currentCircleLength -= 364 * toCircleProgress;
                    float dx = old - currentCircleLength;
                    if (dx > 0) {
                        radOffset += old - currentCircleLength;
                    }
                }
            }
        } else {
            float progressDiff = currentProgress - progressAnimationStart;
            if (progressDiff > 0) {
                progressTime += dt;
                if (progressTime >= 200.0f) {
                    animatedProgress = progressAnimationStart = currentProgress;
                    progressTime = 0;
                } else {
                    animatedProgress = progressAnimationStart + progressDiff * AndroidUtilities.decelerateInterpolator.getInterpolation(progressTime / 200.0f);
                }
            }
            currentCircleLength = Math.max(4, 360 * animatedProgress);
        }
        invalidate();
    }

    public void setSize(int value) {
        size = value;
        if (m3IndicatorView != null) {
            m3IndicatorView.setIndicatorSize(value);
        }
        invalidate();
    }

    public void setStrokeWidth(float value) {
        progressPaint.setStrokeWidth(AndroidUtilities.dp(value));
    }

    public void setProgressColor(int color) {
        progressColor = color;
        progressPaint.setColor(progressColor);
        if (m3IndicatorView != null) {
            m3IndicatorView.setIndicatorColor(color);
        }
        if (!trackColorCustom) {
            trackColor = Theme.multAlpha(progressColor, 0.2f);
            if (m3 != null) {
                m3.setTrackColor(trackColor);
            }
        }
    }

    /**
     * Стиль 0 — сток, 1 — LoadingIndicator из M3 Expressive,
     * 2 — CircularProgressIndicator, 3 — он же с волной.
     */
    public void setStyle(int style) {
        manualStyle = true;
        setStyleInternal(style);
    }

    private void setStyleInternal(int style) {
        style = M3CircularProgress.degradeStyle(style);
        if (currentStyle == style
                && (style != M3CircularProgress.STYLE_LOADING_INDICATOR || m3Drawable != null)) {
            return;
        }
        currentStyle = style;
        final Drawable previous = m3Drawable;
        if (style == M3CircularProgress.STYLE_LOADING_INDICATOR) {
            if (m3IndicatorView == null) {
                m3IndicatorView = new LoadingIndicator(getContext());
            }
            m3IndicatorView.setIndicatorSize(size);
            m3IndicatorView.setIndicatorColor(progressColor);
            m3Drawable = m3IndicatorView.getDrawable();
        } else {
            m3Drawable = null;
        }
        if (previous != null && previous != m3Drawable) {
            previous.setVisible(false, false);
            previous.setCallback(null);
        }
        if (m3Drawable != null) {
            m3Drawable.setCallback(this);
            m3Drawable.setVisible(isAttachedToWindow(), true);
        }
        if (M3CircularProgress.isCircular(style)) {
            if (m3 == null) {
                m3 = new M3CircularProgress();
            }
            // IndicatorTrackGapSize = dp(2)
            m3.setGap(AndroidUtilities.dp(2));
            if (!trackColorCustom) {
                trackColor = Theme.multAlpha(progressColor, 0.2f);
            }
            m3.setTrackColor(trackColor);
            setWavy(currentStyle == M3CircularProgress.STYLE_WAVY);
        }
        invalidate();
    }

    public boolean isMaterial3ProgressStyle() {
        return M3CircularProgress.isCircular(currentStyle);
    }

    public void setTrackColor(int color) {
        trackColor = color;
        trackColorCustom = true;
        if (m3 != null) {
            m3.setTrackColor(color);
        }
    }

    private void applyThemeStyle() {
        if (manualStyle) {
            return;
        }
        int desired = Theme.isCurrentThemeMonet()
                ? (noProgress ? M3CircularProgress.STYLE_LOADING_INDICATOR : M3CircularProgress.STYLE_CIRCULAR)
                : M3CircularProgress.STYLE_LEGACY;
        if (desired == themeStyle) {
            return;
        }
        themeStyle = desired;
        if (!trackColorCustom) {
            trackColor = Theme.multAlpha(progressColor, 0.2f);
        }
        setStyleInternal(desired);
    }

    /** SetWavyValues(dp(15), dp(1.6f), dp(5), 0.05f). */
    @Keep
    public void setWavy(boolean wavy) {
        if (m3 == null) {
            return;
        }
        if (wavy) {
            m3.setWavyValues(AndroidUtilities.dp(15), AndroidUtilities.dp(1.6f), AndroidUtilities.dp(5));
        } else {
            m3.setWavy(false);
        }
    }

    public void setWavyValues(int wavelength, int amplitude, int speed, float amplitudeRampProgressMin) {
        if (currentStyle != M3CircularProgress.STYLE_WAVY || m3 == null) {
            return;
        }
        // amplitudeRampProgressMin у exteraGram относится к MDC-спеку; в ручной отрисовке не участвует
        m3.setWavyValues(wavelength, amplitude, speed);
    }

    public void toCircle(boolean toCircle, boolean animated) {
        this.toCircle = toCircle;
        if (!animated) {
            toCircleProgress = toCircle ? 1f : 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int x = (getMeasuredWidth() - size) / 2;
        int y = (getMeasuredHeight() - size) / 2;
        cicleRect.set(x, y, x + size, y + size);
        drawArc(canvas);
        updateAnimation();
    }

    public void draw(Canvas canvas, float cx, float cy) {
        cicleRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy +  size / 2f);
        drawArc(canvas);
        updateAnimation();
    }

    private void drawArc(Canvas canvas) {
        applyThemeStyle();
        drawingCircleLenght = currentCircleLength;
        if (currentStyle == M3CircularProgress.STYLE_LOADING_INDICATOR && m3Drawable != null) {
            m3Drawable.setBounds((int) cicleRect.left, (int) cicleRect.top,
                    (int) cicleRect.right, (int) cicleRect.bottom);
            m3Drawable.draw(canvas);
            return;
        }
        if (m3 != null && M3CircularProgress.isCircular(currentStyle)) {
            m3.draw(canvas, cicleRect, radOffset, drawingCircleLenght, progressPaint);
            return;
        }
        canvas.drawArc(cicleRect, radOffset, drawingCircleLenght, false, progressPaint);
    }

    public boolean isCircle() {
        return Math.abs(drawingCircleLenght) >= 360;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (m3Drawable != null) {
            m3Drawable.setVisible(true, true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (m3Drawable != null) {
            m3Drawable.setVisible(false, false);
        }
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidate();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        postDelayed(what, when - android.os.SystemClock.uptimeMillis());
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        removeCallbacks(what);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}
