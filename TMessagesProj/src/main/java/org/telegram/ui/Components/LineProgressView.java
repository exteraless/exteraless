/*
 * This is the source code of Telegram for Android v. 2.0.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import app.exteraless.appearance.AppearanceConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.voip.CellFlickerDrawable;

public class LineProgressView extends View {

    private long lastUpdateTime;
    private float currentProgress;
    private float animationProgressStart;
    private long currentProgressTime;
    private float animatedProgressValue;
    private float animatedAlphaValue = 1.0f;

    private int backColor;
    private int progressColor;

    private static DecelerateInterpolator decelerateInterpolator;
    private static Paint progressPaint;

    private RectF rect = new RectF();

    CellFlickerDrawable cellFlickerDrawable;

    // M3-полоса загрузки. exteraGram: org/telegram/ui/Components/LineProgressView.java —
    // класс наследует com.google.android.material.progressindicator.LinearProgressIndicator
    // с init() (:53-62): trackThickness dp(2), trackCornerRadiusFraction 0.5,
    // indicatorTrackGapSize dp(2), trackStopIndicatorSize dp(2).
    // Зависимости com.google.android.material в дереве нет — рисуем теми же метриками сами.
    public int type;
    private Paint m3Paint;
    private Path m3Path;

    public LineProgressView(Context context) {
        super(context);

        if (decelerateInterpolator == null) {
            decelerateInterpolator = new DecelerateInterpolator();
            progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(AndroidUtilities.dp(2));
        }
    }

    private void updateAnimation() {
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;

        if (animatedProgressValue != 1 && animatedProgressValue != currentProgress) {
            float progressDiff = currentProgress - animationProgressStart;
            if (progressDiff > 0) {
                currentProgressTime += dt;
                if (currentProgressTime >= 300) {
                    animatedProgressValue = currentProgress;
                    animationProgressStart = currentProgress;
                    currentProgressTime = 0;
                } else {
                    animatedProgressValue = animationProgressStart + progressDiff * decelerateInterpolator.getInterpolation(currentProgressTime / 300.0f);
                }
            }
            invalidate();
        }
        if (animatedProgressValue >= 1 && animatedProgressValue == 1 && animatedAlphaValue != 0) {
            animatedAlphaValue -= dt / 200.0f;
            if (animatedAlphaValue <= 0) {
                animatedAlphaValue = 0.0f;
            }
            invalidate();
        }
    }

    public void setProgressColor(int color) {
        progressColor = color;
    }

    public void setBackColor(int color) {
        backColor = color;
    }

    public void setProgress(float value, boolean animated) {
        if (!animated) {
            animatedProgressValue = value;
            animationProgressStart = value;
        } else {
            animationProgressStart = animatedProgressValue;
        }
        if (value != 1) {
            animatedAlphaValue = 1;
        }
        currentProgress = value;
        currentProgressTime = 0;

        lastUpdateTime = System.currentTimeMillis();
        invalidate();
    }

    public float getCurrentProgress() {
        return currentProgress;
    }

    /**
     * Тип 0 обычная полоса, тип 1 волнистая
     * (wavelengthDeterminate dp(40), waveAmplitude dp(3), waveSpeed dp(15)).
     * При выключенном newLoadingStyle тип принудительно 0, как в exteraGram.
     */
    public void setProgressType(int type) {
        if (!AppearanceConfig.newLoadingStyle()) {
            this.type = 0;
            return;
        }
        if (this.type == type) {
            return;
        }
        this.type = type;
        invalidate();
    }

    public void onDraw(Canvas canvas) {
        // При новом стиле рисует BaseProgressIndicator
        if (AppearanceConfig.newLoadingStyle()) {
            drawMaterial3(canvas);
            updateAnimation();
            return;
        }
        if (backColor != 0 && animatedProgressValue != 1) {
            progressPaint.setColor(backColor);
            progressPaint.setAlpha((int) (255 * animatedAlphaValue));
            int start = (int) (getWidth() * animatedProgressValue);
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, getHeight() / 2f, getHeight() / 2f, progressPaint);
        }

        progressPaint.setColor(progressColor);
        progressPaint.setAlpha((int) (255 * animatedAlphaValue));
        rect.set(0, 0, getWidth() * animatedProgressValue, getHeight());
        canvas.drawRoundRect(rect, getHeight() / 2f, getHeight() / 2f, progressPaint);

        if (animatedAlphaValue > 0) {
            if (cellFlickerDrawable == null) {
                cellFlickerDrawable = new CellFlickerDrawable(160, 0);
                cellFlickerDrawable.drawFrame = false;
                cellFlickerDrawable.animationSpeedScale = 0.8f;
                cellFlickerDrawable.repeatProgress = 1.2f;
            }
            cellFlickerDrawable.setParentWidth(getMeasuredWidth());
            cellFlickerDrawable.draw(canvas, rect, getHeight() / 2f, null);
            invalidate();
        }

        updateAnimation();
    }

    private void drawMaterial3(Canvas canvas) {
        if (m3Paint == null) {
            m3Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            m3Paint.setStrokeCap(Paint.Cap.ROUND);
            m3Paint.setStrokeJoin(Paint.Join.ROUND);
        }
        final float width = getWidth();
        final float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        final float thickness = Math.min(height, AndroidUtilities.dp(2));
        final float cy = height / 2f;
        final float top = cy - thickness / 2f;
        final float bottom = cy + thickness / 2f;
        final float radius = thickness / 2f;      // trackCornerRadiusFraction = 0.5f
        final float gap = AndroidUtilities.dp(2); // indicatorTrackGapSize
        final float stop = AndroidUtilities.dp(2);// trackStopIndicatorSize
        final int alpha = (int) (255 * animatedAlphaValue);
        final float progress = Math.max(0, Math.min(1, animatedProgressValue));
        final float indicatorRight = width * progress;

        if (backColor != 0) {
            final float trackLeft = Math.min(width, indicatorRight > 0 ? indicatorRight + gap : 0);
            if (trackLeft < width) {
                m3Paint.setStyle(Paint.Style.FILL);
                m3Paint.setColor(backColor);
                m3Paint.setAlpha(alpha);
                rect.set(trackLeft, top, width, bottom);
                canvas.drawRoundRect(rect, radius, radius, m3Paint);
            }
        }

        if (indicatorRight > 0) {
            m3Paint.setColor(progressColor);
            m3Paint.setAlpha(alpha);
            final float amplitude = getWaveAmplitude(progress, height, thickness);
            if (amplitude > 0) {
                if (m3Path == null) {
                    m3Path = new Path();
                }
                buildWavePath(indicatorRight, cy, amplitude);
                m3Paint.setStyle(Paint.Style.STROKE);
                m3Paint.setStrokeWidth(thickness);
                canvas.drawPath(m3Path, m3Paint);
            } else {
                m3Paint.setStyle(Paint.Style.FILL);
                rect.set(0, top, indicatorRight, bottom);
                canvas.drawRoundRect(rect, radius, radius, m3Paint);
            }
        }

        // точка-ограничитель в конце дорожки (trackStopIndicatorSize)
        if (width - indicatorRight > gap + stop) {
            m3Paint.setStyle(Paint.Style.FILL);
            m3Paint.setColor(progressColor);
            m3Paint.setAlpha(alpha);
            canvas.drawCircle(width - stop / 2f, cy, Math.min(stop, thickness) / 2f, m3Paint);
        }

        if (animatedProgressValue < 1 && type == 1) {
            invalidate();
        }
    }

    /**
     * WaveAmplitude dp(3),
     * waveAmplitudeRampProgressMin 0.05f. Разгон амплитуды на первых 5% прогресса
     * воспроизведён приближённо: в MDC он зашит внутрь DeterminateDrawable.
     */
    private float getWaveAmplitude(float progress, float height, float thickness) {
        if (type != 1) {
            return 0;
        }
        final float ramp = Math.max(0, Math.min(1, (progress - 0.05f) / 0.05f));
        return Math.min(AndroidUtilities.dp(3) * ramp, Math.max(0, (height - thickness) / 2f));
    }

    private void buildWavePath(float right, float cy, float amplitude) {
        m3Path.reset();
        final float wavelength = AndroidUtilities.dp(40);
        final float speed = AndroidUtilities.dp(15);
        final float phase = (SystemClock.elapsedRealtime() % 100000L) / 1000f * speed;
        final float step = AndroidUtilities.dpf2(2);
        boolean first = true;
        for (float x = 0; x <= right; x += step) {
            final float y = cy + (float) (amplitude * Math.sin(2 * Math.PI * (x + phase) / wavelength));
            if (first) {
                m3Path.moveTo(x, y);
                first = false;
            } else {
                m3Path.lineTo(x, y);
            }
        }
        final float y = cy + (float) (amplitude * Math.sin(2 * Math.PI * (right + phase) / wavelength));
        m3Path.lineTo(right, y);
    }
}
