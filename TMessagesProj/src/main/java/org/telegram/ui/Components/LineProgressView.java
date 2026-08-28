/*
 * This is the source code of Telegram for Android v. 2.0.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of that license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.FrameLayout;

import app.exteraless.appearance.AppearanceConfig;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.voip.CellFlickerDrawable;

public class LineProgressView extends FrameLayout {

    private long lastUpdateTime;
    private float currentProgress;
    private float animationProgressStart;
    private long currentProgressTime;
    private float animatedProgressValue;
    private float animatedAlphaValue = 1.0f;

    private int backColor;
    private int progressColor;

    private static android.view.animation.DecelerateInterpolator decelerateInterpolator;
    private static Paint progressPaint;

    private RectF rect = new RectF();

    CellFlickerDrawable cellFlickerDrawable;

    // M3-полоса загрузки — официальный LinearProgressIndicator (material 1.14).
    // Волна включается метриками M3 Expressive: wavelength dp(40), waveAmplitude dp(3),
    // waveSpeed dp(15). Контекст оборачивается в Material3-тему только для конструктора,
    // цвета всегда задаются из темы Telegram через setProgressColor/setBackColor.
    public int type;
    // Пока setProgressType никто не звал, тип не задан явно — тогда по умолчанию волна.
    private boolean typeSet;
    private LinearProgressIndicator m3Indicator;
    private boolean m3Failed;

    public LineProgressView(Context context) {
        super(context);
        setWillNotDraw(false);

        if (decelerateInterpolator == null) {
            decelerateInterpolator = new android.view.animation.DecelerateInterpolator();
            progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(AndroidUtilities.dp(2));
        }
    }

    private boolean useM3() {
        return AppearanceConfig.newLoadingStyle() && !m3Failed;
    }

    private void ensureM3Indicator() {
        if (m3Indicator != null || m3Failed) {
            return;
        }
        try {
            Context themed = new ContextThemeWrapper(getContext(),
                    com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
            m3Indicator = new LinearProgressIndicator(themed);
            m3Indicator.setIndeterminate(false);
            m3Indicator.setMax(10000);
            addView(m3Indicator, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            applyM3Config();
        } catch (Throwable t) {
            m3Failed = true;
            m3Indicator = null;
        }
    }

    private void applyM3Config() {
        if (m3Indicator == null) {
            return;
        }
        final int h = getHeight();
        final int thickness = Math.min(h > 0 ? h : AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        m3Indicator.setTrackThickness(thickness);
        m3Indicator.setTrackCornerRadius(thickness / 2);
        // Волна только если тип не переопределён в плоский и высоты хватает на амплитуду
        final boolean wavy = effectiveType() == 1;
        final int amplitude = wavy ? Math.min(AndroidUtilities.dp(3), Math.max(0, (h - thickness) / 2)) : 0;
        m3Indicator.setWaveAmplitude(amplitude);
        m3Indicator.setWavelength(wavy && amplitude > 0 ? AndroidUtilities.dp(40) : 0);
        m3Indicator.setWaveSpeed(wavy && amplitude > 0 ? AndroidUtilities.dp(15) : 0);
        if (progressColor != 0) {
            m3Indicator.setIndicatorColor(progressColor);
        }
        // Без заданного фона трек прозрачный, как в старой отрисовке
        m3Indicator.setTrackColor(backColor != 0 ? backColor : Color.TRANSPARENT);
        m3Indicator.setProgressCompat((int) (currentProgress * 10000), false);
    }

    /** M3 Expressive: без явного setProgressType полоса по умолчанию волнистая. */
    private int effectiveType() {
        return typeSet ? type : 1;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyM3Config();
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
        if (m3Indicator != null) {
            m3Indicator.setIndicatorColor(color);
        }
    }

    public void setBackColor(int color) {
        backColor = color;
        if (m3Indicator != null) {
            m3Indicator.setTrackColor(color != 0 ? color : Color.TRANSPARENT);
        }
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
        if (m3Indicator != null) {
            m3Indicator.setProgressCompat((int) (value * 10000), animated);
        }
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
        typeSet = true;
        if (!AppearanceConfig.newLoadingStyle()) {
            this.type = 0;
            return;
        }
        if (this.type == type) {
            return;
        }
        this.type = type;
        applyM3Config();
        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (useM3()) {
            ensureM3Indicator();
        }
        if (m3Indicator != null && useM3()) {
            m3Indicator.setVisibility(VISIBLE);
            return;
        }
        if (m3Indicator != null) {
            m3Indicator.setVisibility(GONE);
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
}
