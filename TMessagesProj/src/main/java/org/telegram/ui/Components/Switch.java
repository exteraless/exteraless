/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.StateSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Keep;
import androidx.core.content.ContextCompat;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.BaseCell;

import me.vkryl.android.animator.BoolAnimator;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.MonetHelper;
import xyz.nextalone.nagram.NaConfig;

public class Switch extends View implements Theme.Colorable {

    @Override
    public void updateColors() {
        colorSet = 0; // the ripple color is re-read from the theme on the next press
    }
    private final BoolAnimator animatorIconVisibility = new BoolAnimator(this, CubicBezierInterpolator.EASE_OUT_QUINT, 380L, true);

    public static final int SWITCH_STYLE_DEFAULT = 0;
    public static final int SWITCH_STYLE_MODERN = 1;
    public static final int SWITCH_STYLE_MD3 = 2;
    public static final int SWITCH_STYLE_M3 = 3;
    private final BoolAnimator animatorPressed = new BoolAnimator(this, new CubicBezierInterpolator(0.2, 0, 0, 1), 100L);
    private final float[] iconLines = new float[8];
    private Paint outlinePaint;
    private int separateTrackColorKey = -1;
    private int lastCheckColor = Integer.MIN_VALUE;
    private final Paint googleBorderPaint;
    private final Drawable checkDrawable;

    private RectF rectF;

    private float progress;
    private static final FloatPropertyCompat<Switch> PROGRESS_PROPERTY = new FloatPropertyCompat<Switch>("progress") {
        @Override
        public float getValue(Switch object) {
            return object.getProgress();
        }

        @Override
        public void setValue(Switch object, float value) {
            object.setProgress(value);
        }
    };

    private ObjectAnimator checkAnimator;
    private SpringAnimation checkSpringAnimator;
    /** Альфа при новом стиле пишется сюда, а не во View (иначе ломаются overlay-битмапы). */
    private float overrideAlpha = 1.0f;
    private ObjectAnimator iconAnimator;

    private boolean attachedToWindow;
    private boolean isChecked;
    private Paint paint;
    private Paint paint2;

    private int drawIconType;
    private float iconProgress = 1.0f;

    private OnCheckedChangeListener onCheckedChangeListener;

    private int trackColorKey = Theme.key_fill_RedNormal;
    private int trackCheckedColorKey = Theme.key_switch2TrackChecked;
    private int thumbColorKey = Theme.key_windowBackgroundWhite;
    private int thumbCheckedColorKey = Theme.key_windowBackgroundWhite;

    private Drawable iconDrawable;
    private int lastIconColor;

    private boolean drawRipple;
    private RippleDrawable rippleDrawable;
    private Paint ripplePaint;
    private int[] pressedState = new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed};
    private int colorSet;

    private boolean bitmapsCreated;
    private Bitmap[] overlayBitmap;
    private Canvas[] overlayCanvas;
    private Bitmap overlayMaskBitmap;
    private Canvas overlayMaskCanvas;
    private float overlayCx;
    private float overlayCy;
    private float overlayRad;
    private Paint overlayEraserPaint;
    private Paint overlayMaskPaint;

    private Theme.ResourcesProvider resourcesProvider;

    private int overrideColorProgress;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(Switch view, boolean isChecked);
    }

    public Switch(Context context) {
        this(context, null);
    }

    public Switch(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rectF = new RectF();

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeCap(Paint.Cap.ROUND);
        paint2.setStrokeWidth(AndroidUtilities.dp(2));

        googleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        googleBorderPaint.setStyle(Paint.Style.STROKE);
        googleBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        googleBorderPaint.setStrokeWidth(AndroidUtilities.dp(1));
        checkDrawable = ContextCompat.getDrawable(context, R.drawable.floating_check).mutate();
        checkDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(trackCheckedColorKey, resourcesProvider), PorterDuff.Mode.MULTIPLY));

        setHapticFeedbackEnabled(!NekoConfig.disableVibration.Bool());
    }

    @Keep
    public void setProgress(float value) {
        if (progress == value) {
            return;
        }
        progress = value;
        invalidate();
    }

    @Keep
    public float getProgress() {
        return progress;
    }

    @Keep
    public void setIconProgress(float value) {
        if (iconProgress == value) {
            return;
        }
        iconProgress = value;
        invalidate();
    }

    @Keep
    public float getIconProgress() {
        return iconProgress;
    }

    private void cancelCheckAnimator() {
        if (checkAnimator != null) {
            checkAnimator.cancel();
            checkAnimator = null;
        }
        if (checkSpringAnimator != null) {
            checkSpringAnimator.cancel();
            checkSpringAnimator = null;
        }
    }

    /** Новый (exteraGram) стиль тумблера — тот же слот, что рисует {@code drawExteraSwitch}. */
    private static boolean isExteraSwitchStyle() {
        int style = NaConfig.INSTANCE.getSwitchStyle().Int();
        return style == SWITCH_STYLE_MD3 || style == SWITCH_STYLE_M3;
    }

    private static boolean isMaterial3SwitchStyle() {
        return NaConfig.INSTANCE.getSwitchStyle().Int() == SWITCH_STYLE_M3;
    }

    /**
     * Запас вокруг тумблера для overlay-битмапов: капсула нового стиля вылезает
     * за measuredWidth/Height (exteraGram: {@code Switch.getOverlayPadding()} = dp(5)).
     */
    private int getOverlayPadding() {
        return isExteraSwitchStyle() ? dp(5) : 0;
    }

    private void cancelIconAnimator() {
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
    }

    public void setDrawIconType(int type) {
        drawIconType = type;
    }

    public void setDrawRipple(boolean value) {
        if (Build.VERSION.SDK_INT < 21 || value == drawRipple) {
            return;
        }
        drawRipple = value;

        if (rippleDrawable == null) {
            ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ripplePaint.setColor(0xffffffff);
            Drawable maskDrawable;
            if (Build.VERSION.SDK_INT >= 23) {
                maskDrawable = null;
            } else {
                maskDrawable = new Drawable() {
                    @Override
                    public void draw(Canvas canvas) {
                        android.graphics.Rect bounds = getBounds();
                        canvas.drawCircle(bounds.centerX(), bounds.centerY(), AndroidUtilities.dp(18), ripplePaint);
                    }

                    @Override
                    public void setAlpha(int alpha) {

                    }

                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {

                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.UNKNOWN;
                    }
                };
            }
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{0}
            );
            rippleDrawable = new BaseCell.RippleDrawableSafe(colorStateList, null, maskDrawable);
            rippleDrawable.setCallback(this);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            rippleDrawable.setRadius(isMaterial3SwitchStyle() ? app.exteraless.appearance.Material3SwitchHelper.stateLayerRadius() : AndroidUtilities.dp(18));
        }
        if (isChecked && colorSet != 2 || !isChecked && colorSet != 1) {
            int color = Theme.getColor(isChecked ? Theme.key_switchTrackBlueSelectorChecked : Theme.key_switchTrackBlueSelector, resourcesProvider);
            color = processColor(color);
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{color}
            );
            rippleDrawable.setColor(colorStateList);
            colorSet = isChecked ? 2 : 1;
        }
        if (Build.VERSION.SDK_INT >= 28 && value) {
            rippleDrawable.setHotspot(isChecked ? 0 : AndroidUtilities.dp(100), AndroidUtilities.dp(18));
        }
        rippleDrawable.setState(value ? pressedState : StateSet.NOTHING);
        animatorPressed.setValue(value && isMaterial3SwitchStyle(), attachedToWindow);
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || rippleDrawable != null && who == rippleDrawable;
    }

    protected int processColor(int color) {
        return color;
    }

    public void setColors(int track, int trackChecked, int thumb, int thumbChecked) {
        trackColorKey = track;
        trackCheckedColorKey = trackChecked;
        thumbColorKey = thumb;
        thumbCheckedColorKey = thumbChecked;
    }

    private void animateToCheckedState(boolean newCheckedState) {
        cancelCheckAnimator();
        if (isMaterial3SwitchStyle()) {
            checkSpringAnimator = new SpringAnimation(this, PROGRESS_PROPERTY)
                    .setSpring(new SpringForce(newCheckedState ? 1f : 0f)
                            .setDampingRatio(0.65f)
                            .setStiffness(510f))
                    .setMinimumVisibleChange(0.001f)
                    .setStartValue(progress);
            checkSpringAnimator.start();
            return;
        }
        if (isExteraSwitchStyle()) {
            // exteraGram: SpringForce(target).setDampingRatio(0.9f).setStiffness(1400f), minimumVisibleChange 0.001f
            checkSpringAnimator = new SpringAnimation(this, PROGRESS_PROPERTY)
                    .setSpring(new SpringForce(newCheckedState ? 1f : 0f)
                            .setDampingRatio(0.9f)
                            .setStiffness(1400f))
                    .setMinimumVisibleChange(0.001f)
                    .setStartValue(progress);
            checkSpringAnimator.start();
            return;
        }
        checkAnimator = ObjectAnimator.ofFloat(this, "progress", newCheckedState ? 1 : 0);
        checkAnimator.setDuration(200);
        checkAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        checkAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                checkAnimator = null;
            }
        });
        checkAnimator.start();
    }

    private void animateIcon(boolean newCheckedState) {
        iconAnimator = ObjectAnimator.ofFloat(this, "iconProgress", newCheckedState ? 1 : 0);
        iconAnimator.setDuration(200);
        iconAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                iconAnimator = null;
            }
        });
        iconAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedToWindow = true;
        // Стиль exteraGram рисует дорожку шире собственных границ вьюхи
        // (-2dp слева, +3dp справа, высота 26dp против 20-22dp у ячейки), и
        // клип по границам ребёнка срезает капсуле бока. TextCheckCell снимает
        // клип у себя, но переключатель живёт ещё в восьми ячейках — проще
        // попросить об этом родителя отсюда, чем помнить про каждую новую.
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).setClipChildren(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attachedToWindow = false;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        onCheckedChangeListener = listener;
    }

    @Override
    public void setAlpha(float alpha) {
        // exteraGram: при новом стиле альфа не уходит во View, иначе гаснут overlay-битмапы
        if (!isExteraSwitchStyle()) {
            overrideAlpha = 1.0f;
            super.setAlpha(alpha);
        } else {
            overrideAlpha = alpha;
            super.setAlpha(1.0f);
            invalidate();
        }
    }

    public void setChecked(boolean checked, boolean animated) {
        setChecked(checked, drawIconType, animated);
    }

    public void setChecked(boolean checked, int iconType, boolean animated) {
        if (checked != isChecked) {
            isChecked = checked;
            if (attachedToWindow && animated) {
                animateToCheckedState(checked);
            } else {
                cancelCheckAnimator();
                setProgress(checked ? 1.0f : 0.0f);
            }
            if (onCheckedChangeListener != null) {
                onCheckedChangeListener.onCheckedChanged(this, checked);
            }
        }
        setDrawIconType(iconType, animated);
    }

    public void setIcon(int icon) {
        if (icon != 0) {
            iconDrawable = getResources().getDrawable(icon).mutate();
            if (iconDrawable != null) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = Theme.getColor(isChecked ? trackCheckedColorKey : trackColorKey, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            }
        } else {
            iconDrawable = null;
        }
        invalidate();
    }

    public void setIconVisible(boolean visible, boolean animated) {
        animatorIconVisibility.setValue(visible, animated);
    }

    public void setDrawIconType(int iconType, boolean animated) {
        if (drawIconType != iconType) {
            drawIconType = iconType;
            if (attachedToWindow && animated) {
                animateIcon(iconType == 0);
            } else {
                cancelIconAnimator();
                setIconProgress(iconType == 0 ? 1.0f : 0.0f);
            }
        }
    }

    public boolean hasIcon() {
        return iconDrawable != null;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setOverrideColor(int override) {
        if (overrideColorProgress == override) {
            return;
        }
        if (overlayBitmap == null) {
            try {
                overlayBitmap = new Bitmap[2];
                overlayCanvas = new Canvas[2];
                final int overlayW = getMeasuredWidth() + getOverlayPadding() * 4;
                final int overlayH = getMeasuredHeight() + getOverlayPadding() * 4;
                for (int a = 0; a < 2; a++) {
                    overlayBitmap[a] = Bitmap.createBitmap(overlayW, overlayH, Bitmap.Config.ARGB_8888);
                    overlayCanvas[a] = new Canvas(overlayBitmap[a]);
                }
                overlayMaskBitmap = Bitmap.createBitmap(overlayW, overlayH, Bitmap.Config.ARGB_8888);
                overlayMaskCanvas = new Canvas(overlayMaskBitmap);

                overlayEraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayEraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

                overlayMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                bitmapsCreated = true;
            } catch (Throwable e) {
                return;
            }
        }
        if (!bitmapsCreated) {
            return;
        }
        overrideColorProgress = override;
        overlayCx = 0;
        overlayCy = 0;
        overlayRad = 0;
        invalidate();
    }

    public void setOverrideColorProgress(float cx, float cy, float rad) {
        overlayCx = cx;
        overlayCy = cy;
        overlayRad = rad;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            return;
        }

        int switchStyle = NaConfig.INSTANCE.getSwitchStyle().Int();
        if (switchStyle != SWITCH_STYLE_DEFAULT) {
            drawCustomSwitch(canvas, switchStyle);
            return;
        }

        int width = AndroidUtilities.dp(31);
        int thumb = AndroidUtilities.dp(20);
        int x = (getMeasuredWidth() - width) / 2;
        float y = (getMeasuredHeight() - AndroidUtilities.dpf2(14)) / 2;
        int tx = x + AndroidUtilities.dp(7) + (int) (AndroidUtilities.dp(17) * progress);
        int ty = getMeasuredHeight() / 2;


        int color1;
        int color2;
        float colorProgress;
        int r1;
        int r2;
        int g1;
        int g2;
        int b1;
        int b2;
        int a1;
        int a2;
        int red;
        int green;
        int blue;
        int alpha;
        int color;

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[0];

            if (a == 1) {
                overlayBitmap[0].eraseColor(0);
                paint.setColor(0xff000000);
                overlayMaskCanvas.drawRect(0, 0, overlayMaskBitmap.getWidth(), overlayMaskBitmap.getHeight(), paint);
                overlayMaskCanvas.drawCircle(overlayCx - getX(), overlayCy - getY(), overlayRad, overlayEraserPaint);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = processColor(Theme.getColor(trackColorKey, resourcesProvider));
            color2 = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
            if (a == 0 && iconDrawable != null && lastIconColor != (isChecked ? color2 : color1)) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? color2 : color1), PorterDuff.Mode.MULTIPLY));
            }

            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            color = ((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
            paint.setColor(color);
            paint2.setColor(color);

            rectF.set(x, y, x + width, y + AndroidUtilities.dpf2(14));
            canvasToDraw.drawRoundRect(rectF, AndroidUtilities.dpf2(7), AndroidUtilities.dpf2(7), paint);
            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dpf2(10), paint);

            if (a == 0 && rippleDrawable != null) {
                rippleDrawable.setBounds(tx - AndroidUtilities.dp(18), ty - AndroidUtilities.dp(18), tx + AndroidUtilities.dp(18), ty + AndroidUtilities.dp(18));
                rippleDrawable.draw(canvasToDraw);
            } else if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[0], 0, 0, null);
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[1];

            if (a == 1) {
                overlayBitmap[1].eraseColor(0);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = Theme.getColor(thumbColorKey, resourcesProvider);
            color2 = processColor(Theme.getColor(thumbCheckedColorKey, resourcesProvider));
            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            paint.setColor(((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff));

            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dp(8), paint);

            if (a == 0) {
                if (iconDrawable != null) {
                    final float factor = animatorIconVisibility.getFloatValue();
                    if (factor > 0) {
                        final boolean needScale = factor < 1;
                        if (needScale) {
                            canvas.save();
                            canvas.scale(factor, factor, tx, ty);
                        }
                        iconDrawable.setBounds(tx - iconDrawable.getIntrinsicWidth() / 2, ty - iconDrawable.getIntrinsicHeight() / 2, tx + iconDrawable.getIntrinsicWidth() / 2, ty + iconDrawable.getIntrinsicHeight() / 2);
                        iconDrawable.draw(canvasToDraw);
                        if (needScale) {
                            canvas.restore();
                        }
                    }
                } else if (drawIconType == 1) {
                    tx -= AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * progress;
                    ty -= AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * progress;
                    int startX2 = (int) AndroidUtilities.dpf2(4.6f) + tx;
                    int startY2 = (int) (AndroidUtilities.dpf2(9.5f) + ty);
                    int endX2 = startX2 + AndroidUtilities.dp(2);
                    int endY2 = startY2 + AndroidUtilities.dp(2);

                    int startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
                    int startY = (int) AndroidUtilities.dpf2(5.4f) + ty;
                    int endX = startX + AndroidUtilities.dp(7);
                    int endY = startY + AndroidUtilities.dp(7);

                    startX = (int) (startX + (startX2 - startX) * progress);
                    startY = (int) (startY + (startY2 - startY) * progress);
                    endX = (int) (endX + (endX2 - endX) * progress);
                    endY = (int) (endY + (endY2 - endY) * progress);
                    canvasToDraw.drawLine(startX, startY, endX, endY, paint2);

                    startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
                    startY = (int) AndroidUtilities.dpf2(12.5f) + ty;
                    endX = startX + AndroidUtilities.dp(7);
                    endY = startY - AndroidUtilities.dp(7);
                    canvasToDraw.drawLine(startX, startY, endX, endY, paint2);
                } else if (drawIconType == 2 || iconAnimator != null) {
                    paint2.setAlpha((int) (255 * (1.0f - iconProgress)));
                    canvasToDraw.drawLine(tx, ty, tx, ty - AndroidUtilities.dp(5), paint2);
                    canvasToDraw.save();
                    canvasToDraw.rotate(-90 * iconProgress, tx, ty);
                    canvasToDraw.drawLine(tx, ty, tx + AndroidUtilities.dp(4), ty, paint2);
                    canvasToDraw.restore();
                }
            }
            if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[1], 0, 0, null);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(isChecked);
        //info.setContentDescription(isChecked ? LocaleController.getString(R.string.NotificationsOn) : LocaleController.getString(R.string.NotificationsOff));
    }

    public void setSeparateTrackColorKey(int separateTrackColorKey) {
        if (this.separateTrackColorKey == separateTrackColorKey) {
            return;
        }
        this.separateTrackColorKey = separateTrackColorKey;
        invalidate();
    }

    private void drawCustomSwitch(Canvas canvas, int switchStyle) {
        // exteraGram "new switch" look reversed from exteraGram 12.9.0 onDraw (exteraSwitch.smali).
        // We reuse the MD3 slot (SWITCH_STYLE_MD3 == 2, the current default) to render it 1:1:
        // a fully-filled capsule track (no border, no icon) with a solid round thumb.
        if (switchStyle == SWITCH_STYLE_MD3) {
            drawExteraSwitch(canvas);
            return;
        }
        if (switchStyle == SWITCH_STYLE_M3) {
            drawMaterial3Switch(canvas);
            return;
        }
        int width = dp(36);
        int x = (getMeasuredWidth() - width) / 2;
        float y = (getMeasuredHeight() - dpf2(14)) / 2;
        int tx = x + dp(7) + (int) (dp(18) * progress);
        int thumbTx = Utilities.clamp(tx, x + width + dp(2), x + dp(10));
        int ty = getMeasuredHeight() / 2;

        int color1;
        int color2;
        int color;

        int trackCheckedFillKey = trackCheckedColorKey;
        int thumbCheckedKey = thumbCheckedColorKey;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MonetHelper.useMonetMd3Colors()) {
            trackCheckedFillKey = Theme.key_dialogRoundCheckBox;
            thumbCheckedKey = Theme.getActiveTheme().isMonetNight()
                    ? Theme.key_statisticChartRipple // a1_800
                    : Theme.key_chat_outInstant; // a1_10
        }

        boolean isMd3 = switchStyle == SWITCH_STYLE_MD3;
        boolean isModern = switchStyle == SWITCH_STYLE_MODERN;
        float iconVisibilityFactor = animatorIconVisibility.getFloatValue();
        boolean hasVisibleIcon = iconDrawable != null && iconVisibilityFactor > 0;
        boolean isMd3PermissionStyle = isMd3 && trackColorKey == Theme.key_fill_RedNormal && (drawIconType == 1 || hasVisibleIcon);
        boolean isModernPermissionStyle = isModern && trackColorKey == Theme.key_fill_RedNormal && drawIconType == 1;
        boolean shouldDrawModernOffIcon = isModern && (hasVisibleIcon || isModernPermissionStyle);

        int trackColor = processColor(Theme.getColor(trackColorKey, resourcesProvider));
        int md3PermissionTrackColor = trackColor;
        if (isMd3PermissionStyle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MonetHelper.useMonetMd3Colors()) {
            md3PermissionTrackColor = processColor(MonetHelper.harmonizeColor(md3PermissionTrackColor));
        }
        int md3OffTrackFillColor = 0;
        if (isMd3) {
            md3OffTrackFillColor = processColor(Theme.blendOver(
                    Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                    Theme.multAlpha(Theme.getColor(trackColorKey, resourcesProvider), Theme.isCurrentThemeDay() ? 0.2f : 0.1f)
            ));
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[0];

            if (a == 1) {
                overlayBitmap[0].eraseColor(0);
                paint.setColor(0xff000000);
                overlayMaskCanvas.drawRect(0, 0, overlayMaskBitmap.getWidth(), overlayMaskBitmap.getHeight(), paint);
                overlayMaskCanvas.drawCircle(overlayCx - getX(), overlayCy - getY(), overlayRad, overlayEraserPaint);
            }
            float colorProgress = getLayerColorProgress(a);

            int originalColor1;
            color1 = originalColor1 = isMd3 && !isMd3PermissionStyle ? md3OffTrackFillColor : isMd3PermissionStyle ? md3PermissionTrackColor : trackColor;
            color2 = processColor(Theme.getColor(trackCheckedFillKey, resourcesProvider));

            if (!isMd3) {
                color1 = separateTrackColorKey >= 0 ? processColor(Theme.getColor(separateTrackColorKey, resourcesProvider)) : Color.TRANSPARENT;
            }

            if (a == 0 && iconDrawable != null && !isMd3 && !shouldDrawModernOffIcon && lastIconColor != (isChecked ? color2 : color1)) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? color2 : color1), PorterDuff.Mode.MULTIPLY));
            }

            color = lerpColor(color1, color2, colorProgress);
            paint.setColor(color);
            paint2.setColor(color);

            rectF.set(x, y - dpf2(3), x + width, y + dpf2(17));
            canvasToDraw.drawRoundRect(rectF, dpf2(15), dpf2(15), paint);

            color1 = isMd3 ? (isMd3PermissionStyle ? md3PermissionTrackColor : processColor(Theme.getColor(trackColorKey, resourcesProvider))) : originalColor1;
            googleBorderPaint.setColor(lerpColor(color1, color2, colorProgress));

            canvasToDraw.drawRoundRect(rectF, dpf2(15), dpf2(15), googleBorderPaint);

            if (a == 0 && rippleDrawable != null) {
                rippleDrawable.setBounds(thumbTx - dp(18), ty - dp(18), thumbTx + dp(18), ty + dp(18));
                rippleDrawable.draw(canvasToDraw);
            } else if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[0], 0, 0, null);
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[1];

            if (a == 1) {
                overlayBitmap[1].eraseColor(0);
            }
            float colorProgress = getLayerColorProgress(a);

            int thumbUncheckedKey = isMd3 ? (isMd3PermissionStyle ? thumbCheckedKey : trackColorKey) : trackColorKey;
            color1 = Theme.getColor(thumbUncheckedKey, resourcesProvider);
            color2 = processColor(Theme.getColor(thumbCheckedKey, resourcesProvider));
            paint.setColor(lerpColor(color1, color2, colorProgress));

            canvasToDraw.drawCircle(thumbTx, ty, dp(isMd3 ? 8 : shouldDrawModernOffIcon ? 7 + progress : 6 + 2 * progress), paint);

            if (isMd3 || shouldDrawModernOffIcon) {
                if (isMd3) {
                    int iconColor = isMd3PermissionStyle ? md3PermissionTrackColor : md3OffTrackFillColor;
                    if (hasVisibleIcon) {
                        if (lastIconColor != iconColor) {
                            iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = iconColor, PorterDuff.Mode.MULTIPLY));
                        }
                        final boolean needScale = iconVisibilityFactor < 1;
                        if (needScale) {
                            canvasToDraw.save();
                            canvasToDraw.scale(iconVisibilityFactor, iconVisibilityFactor, thumbTx, ty);
                        }
                        drawCenteredDrawable(canvasToDraw, iconDrawable, thumbTx, ty, 0.8f, (int) (255 * (1.0f - progress)));
                        if (needScale) {
                            canvasToDraw.restore();
                        }
                    } else {
                        drawCross(canvasToDraw, thumbTx, ty, iconColor, 1.0f - progress, 3);
                    }
                    int checkColor = Theme.getColor(trackCheckedFillKey, resourcesProvider);
                    if (lastCheckColor != checkColor) {
                        checkDrawable.setColorFilter(new PorterDuffColorFilter(checkColor, PorterDuff.Mode.MULTIPLY));
                        lastCheckColor = checkColor;
                    }
                    int iconWidth = checkDrawable.getIntrinsicWidth() / 2;
                    int iconHeight = checkDrawable.getIntrinsicHeight() / 2;
                    checkDrawable.setBounds(thumbTx - iconWidth / 2, ty - iconHeight / 2, thumbTx + iconWidth / 2, ty + iconHeight / 2);
                    checkDrawable.setAlpha((int) (255 * progress));
                    checkDrawable.draw(canvasToDraw);
                } else {
                    int iconColor = Theme.getColor(thumbColorKey, resourcesProvider);
                    if (hasVisibleIcon) {
                        if (lastIconColor != iconColor) {
                            iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = iconColor, PorterDuff.Mode.MULTIPLY));
                        }
                        final boolean needScale = iconVisibilityFactor < 1;
                        if (needScale) {
                            canvasToDraw.save();
                            canvasToDraw.scale(iconVisibilityFactor, iconVisibilityFactor, thumbTx, ty);
                        }
                        drawCenteredDrawable(canvasToDraw, iconDrawable, thumbTx, ty, 0.7f, (int) (255 * (1.0f - progress)));
                        if (needScale) {
                            canvasToDraw.restore();
                        }
                    } else {
                        drawCross(canvasToDraw, thumbTx, ty, iconColor, 1.0f - progress, 2.5f);
                    }
                }
            }
            if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[1], 0, 0, null);
        }
    }

    /**
     * exteraGram "new switch" style, reversed from exteraGram 12.9.0 Switch.onDraw
     * (tools/exteraSwitch.smali, method onDraw, ExteraConfig.getNewSwitchStyle()==true branch).
     *
     * All const/high16 float values below were decoded as Float.intBitsToFloat(N << 16):
     *   16888->31  16848->26  16736->14  16640->8  16768->16  16608->7  16448->3
     *   16656->9   16672->10  16784->18
     *
     * Geometry (smali line refs):
     *   trackW  dp(31)      [591]  used only to center the thumb travel
     *   trackH  dp(26)      [595]  full capsule height
     *   trackTop = H/2 - trackH/2  [611-613]
     *   radius  dpf2(14)    [602/665 -> 959]  capsule corner radius
     *   track rect: left = -dp(2) [903], right = W + dp(3) [909], top = trackTop, bottom = H/2 + trackH/2 [913-919]
     *   thumb cx = (W-dp31)/2 + dp(8) + dp(16)*progress  [641-655]
     *   thumb cy = H/2  [657-659]
     *   thumb radius: dp(7) off -> dp(9) on  [1252 v17=7, 1255 v4=9, interp 1280-1285]
     *   thumb horizontal stretch during transition: dpf2(3) * (1 - |2p-1|)  [1307-1316]
     *   ripple radius dp(18)  [1013]
     * Colors: track lerp(trackColorKey -> trackCheckedColorKey), thumb lerp(thumbColorKey -> thumbCheckedColorKey).
     * No googleBorderPaint stroke and no drawIconType lines are emitted here (screenshot shows a plain toggle).
     */
    private void drawExteraSwitch(Canvas canvas) {
        final int measuredWidth = getMeasuredWidth();
        final int measuredHeight = getMeasuredHeight();

        // overrideAlpha: при новом стиле View-альфа не используется (см. setAlpha)
        final int alphaSave;
        if (overrideAlpha < 1f) {
            final int pad = dp(8);
            alphaSave = canvas.saveLayerAlpha(-pad, -pad, measuredWidth + pad, measuredHeight + pad,
                    (int) (255 * Utilities.clamp01(overrideAlpha)));
        } else {
            alphaSave = -1;
        }

        // smali 633-638: position uses clamp01(progress); color uses raw progress.
        final float pp = Utilities.clamp01(progress);
        final float colorProgress = progress;

        // Track capsule (smali 903-964).
        final int trackH = dp(26);                                   // smali 595 (v4)
        final float trackTop = measuredHeight / 2f - trackH / 2f;    // smali 611-613 (v9)
        final float trackBottom = measuredHeight / 2f + trackH / 2f; // smali 913-919
        final float trackLeft = -dp(2);                              // smali 903-906 (v5=0, minus dp(2))
        final float trackRight = measuredWidth + dp(3);              // smali 907-912
        final float trackRadius = dpf2(14);                          // smali 959 (dpf2 of v19=14)

        int trackOff = processColor(Theme.getColor(trackColorKey, resourcesProvider));         // smali 778-783
        int trackOn = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));   // smali 785-790
        paint.setColor(lerpColor(trackOff, trackOn, colorProgress));                           // smali 819-891
        rectF.set(trackLeft, trackTop, trackRight, trackBottom);
        canvas.drawRoundRect(rectF, trackRadius, trackRadius, paint);                          // smali 964

        // Thumb centre (smali 641-659).
        final int cx = (measuredWidth - dp(31)) / 2 + dp(8) + (int) (dp(16) * pp);
        final int cy = measuredHeight / 2;

        // Ripple (touch feedback) drawn under/around the thumb (smali 1009-1030).
        if (rippleDrawable != null) {
            rippleDrawable.setBounds(cx - dp(18), cy - dp(18), cx + dp(18), cy + dp(18));
            rippleDrawable.draw(canvas);
        }

        // Thumb colour (smali 1130-1246): lerp(thumbColorKey -> thumbCheckedColorKey).
        int thumbOff = processColor(Theme.getColor(thumbColorKey, resourcesProvider));
        int thumbOn = processColor(Theme.getColor(thumbCheckedColorKey, resourcesProvider));
        paint.setColor(lerpColor(thumbOff, thumbOn, colorProgress));

        // Thumb radius dp(7)->dp(9) (smali 1252/1255, interp 1280-1285).
        final float baseR = dp(7) + (dp(9) - dp(7)) * pp;
        final float thumbR = baseR + (dp(9) - baseR) * pp;
        // Squash/stretch: horizontal-only bulge, peaks at the mid-point of the animation (smali 1307-1316).
        final float stretch = dpf2(3) * (1f - Math.abs(pp * 2f - 1f));

        rectF.set(cx - thumbR - stretch, cy - thumbR, cx + thumbR + stretch, cy + thumbR); // smali 1318-1325
        canvas.drawRoundRect(rectF, thumbR, thumbR, paint);                                // smali 1329

        if (alphaSave != -1) {
            canvas.restoreToCount(alphaSave);
        }
    }

    private void drawMaterial3Switch(Canvas canvas) {
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        final int pad = getOverlayPadding();
        final int stateLayer = app.exteraless.appearance.Material3SwitchHelper.stateLayerRadius();
        final int alphaSave = overrideAlpha < 1f
                ? canvas.saveLayerAlpha(-stateLayer, -stateLayer, width + stateLayer, height + stateLayer, (int) (255 * Utilities.clamp01(overrideAlpha)))
                : -1;
        if (outlinePaint == null) {
            outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outlinePaint.setStyle(Paint.Style.STROKE);
        }
        final float cx = app.exteraless.appearance.Material3SwitchHelper.thumbCenterX(width, progress);
        final float cy = height / 2f;
        final int trackOff = processColor(app.exteraless.appearance.Material3SwitchHelper.unselectedTrackColor(trackColorKey, resourcesProvider));
        final int trackOn = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
        final int thumbOff = processColor(app.exteraless.appearance.Material3SwitchHelper.unselectedThumbColor(trackColorKey, resourcesProvider));
        final int thumbOn = processColor(app.exteraless.appearance.Material3SwitchHelper.selectedThumbColor(thumbCheckedColorKey, resourcesProvider));

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            final Canvas target = a == 0 ? canvas : overlayCanvas[0];
            final int offset = a == 0 ? 0 : pad;
            if (a == 1) {
                overlayBitmap[0].eraseColor(0);
                paint.setColor(0xff000000);
                overlayMaskCanvas.drawRect(pad, pad, overlayMaskBitmap.getWidth() - pad, overlayMaskBitmap.getHeight() - pad, paint);
                overlayMaskCanvas.drawCircle(overlayCx - getX() + pad, overlayCy - getY() + pad, overlayRad, overlayEraserPaint);
            }
            final float colorProgress = Utilities.clamp01(getLayerColorProgress(a));
            if (a == 0 && iconDrawable != null && lastIconColor != (isChecked ? trackOn : trackOff)) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? trackOn : trackOff), PorterDuff.Mode.MULTIPLY));
            }
            final int trackColor = lerpColor(trackOff, trackOn, colorProgress);
            paint.setColor(trackColor);
            paint2.setColor(trackColor);
            app.exteraless.appearance.Material3SwitchHelper.setTrackBounds(rectF, width, height);
            rectF.offset(offset, offset);
            final float trackRadius = rectF.height() / 2f;
            target.drawRoundRect(rectF, trackRadius, trackRadius, paint);
            final float outlineAlpha = 1f - colorProgress;
            if (outlineAlpha > 0f) {
                final float stroke = app.exteraless.appearance.Material3SwitchHelper.trackOutlineWidth();
                outlinePaint.setColor(Theme.multAlpha(thumbOff, outlineAlpha));
                outlinePaint.setStrokeWidth(stroke);
                rectF.inset(stroke / 2f, stroke / 2f);
                target.drawRoundRect(rectF, trackRadius - stroke / 2f, trackRadius - stroke / 2f, outlinePaint);
            }
            if (a == 0 && rippleDrawable != null) {
                rippleDrawable.setBounds((int) cx - stateLayer, (int) cy - stateLayer, (int) cx + stateLayer, (int) cy + stateLayer);
                rippleDrawable.draw(target);
            } else if (a == 1) {
                target.drawBitmap(overlayMaskBitmap, -pad, -pad, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[0], -pad, -pad, null);
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            final Canvas target = a == 0 ? canvas : overlayCanvas[1];
            final int offset = a == 0 ? 0 : pad;
            if (a == 1) {
                overlayBitmap[1].eraseColor(0);
            }
            final float colorProgress = Utilities.clamp01(getLayerColorProgress(a));
            paint.setColor(lerpColor(thumbOff, thumbOn, colorProgress));
            final float iconFactor = drawIconType == 1 || drawIconType == 2 || iconAnimator != null
                    ? 1f
                    : (iconDrawable != null ? animatorIconVisibility.getFloatValue() : 0f);
            app.exteraless.appearance.Material3SwitchHelper.setThumbBounds(rectF, cx + offset, cy + offset, progress, isChecked, iconFactor, animatorPressed.getFloatValue());
            final float thumbRadius = rectF.height() / 2f;
            target.drawRoundRect(rectF, thumbRadius, thumbRadius, paint);
            if (a == 0) {
                if (iconDrawable != null) {
                    final float visibility = animatorIconVisibility.getFloatValue();
                    if (visibility > 0f) {
                        final int iconWidth = iconDrawable.getIntrinsicWidth();
                        final int iconHeight = iconDrawable.getIntrinsicHeight();
                        final float scale = visibility * app.exteraless.appearance.Material3SwitchHelper.thumbIconScale(iconWidth, iconHeight);
                        canvas.save();
                        canvas.translate(cx - iconWidth / 2f, cy - iconHeight / 2f);
                        canvas.scale(scale, scale, iconWidth / 2f, iconHeight / 2f);
                        iconDrawable.setBounds(0, 0, iconWidth, iconHeight);
                        iconDrawable.draw(canvas);
                        canvas.restore();
                    }
                } else if (drawIconType == 1) {
                    paint2.setStrokeWidth(app.exteraless.appearance.Material3SwitchHelper.iconStrokeWidth());
                    app.exteraless.appearance.Material3SwitchHelper.setCheckIconLines(iconLines, cx, cy, progress);
                    target.drawLines(iconLines, paint2);
                } else if (drawIconType == 2 || iconAnimator != null) {
                    paint2.setAlpha((int) (255 * (1f - iconProgress)));
                    paint2.setStrokeWidth(app.exteraless.appearance.Material3SwitchHelper.iconStrokeWidth());
                    target.drawLine(cx, cy, cx, cy - app.exteraless.appearance.Material3SwitchHelper.clockHandLength(true), paint2);
                    target.save();
                    target.rotate(-90f * iconProgress, cx, cy);
                    target.drawLine(cx, cy, cx + app.exteraless.appearance.Material3SwitchHelper.clockHandLength(false), cy, paint2);
                    target.restore();
                }
            }
            if (a == 1) {
                target.drawBitmap(overlayMaskBitmap, -pad, -pad, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[1], -pad, -pad, null);
        }
        if (alphaSave != -1) {
            canvas.restoreToCount(alphaSave);
        }
    }

    private float getLayerColorProgress(int layer) {
        if (overrideColorProgress == 1) {
            return layer == 0 ? 0 : 1;
        } else if (overrideColorProgress == 2) {
            return layer == 0 ? 1 : 0;
        }
        return progress;
    }

    private int lerpColor(int color1, int color2, float progress) {
        int red = (int) (Color.red(color1) + (Color.red(color2) - Color.red(color1)) * progress);
        int green = (int) (Color.green(color1) + (Color.green(color2) - Color.green(color1)) * progress);
        int blue = (int) (Color.blue(color1) + (Color.blue(color2) - Color.blue(color1)) * progress);
        int alpha = (int) (Color.alpha(color1) + (Color.alpha(color2) - Color.alpha(color1)) * progress);
        return ((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
    }

    private void drawCenteredDrawable(Canvas canvas, Drawable drawable, int cx, int cy, float scale, int alpha) {
        int iconWidth = (int) (drawable.getIntrinsicWidth() * scale);
        int iconHeight = (int) (drawable.getIntrinsicHeight() * scale);
        drawable.setBounds(cx - iconWidth / 2, cy - iconHeight / 2, cx + iconWidth / 2, cy + iconHeight / 2);
        drawable.setAlpha(alpha);
        drawable.draw(canvas);
        drawable.setAlpha(255);
    }

    private void drawCross(Canvas canvas, int cx, int cy, int color, float alpha, float sizeDp) {
        int oldAlpha = paint2.getAlpha();
        float oldStrokeWidth = paint2.getStrokeWidth();
        paint2.setColor(color);
        paint2.setAlpha((int) (255 * alpha));
        paint2.setStrokeWidth(dpf2(1.5f));
        int crossSize = dp(sizeDp);
        canvas.drawLine(cx - crossSize, cy - crossSize, cx + crossSize, cy + crossSize, paint2);
        canvas.drawLine(cx + crossSize, cy - crossSize, cx - crossSize, cy + crossSize, paint2);
        paint2.setStrokeWidth(oldStrokeWidth);
        paint2.setAlpha(oldAlpha);
    }
}
