package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

public class MorphButton extends android.view.View {

    public interface PressListener {
        void onPressChanged(MorphButton button, boolean pressed);
    }

    final Spring width = new Spring(0, 700f, 1f, 0.5f);
    private final Spring radiusLeft = new Spring(0, 520f, 0.55f, 0.3f);
    private final Spring radiusRight = new Spring(0, 520f, 0.55f, 0.3f);
    private final Spring alt = new Spring(0, 520f, 0.6f, 0.002f);
    private final Spring active = new Spring(0, 600f, 1f, 0.002f);
    private final Spring badge = new Spring(0, 600f, 1f, 0.002f);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float[] radii = new float[8];
    private PlayerIcon icon;
    private PlayerIcon altIcon;
    private String text;
    private String badgeText;
    private int bgColor;
    private int fgColor;
    private int activeBgColor;
    private int activeFgColor;
    private int badgeBg;
    private int badgeFg;
    private long lastFrame;
    private PressListener pressListener;
    float baseHeight;
    float scale = 1f;

    public MorphButton(Context context, PlayerIcon icon) {
        super(context);
        this.icon = icon;
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, getResources().getDisplayMetrics()));
        textPaint.setFontFeatureSettings("tnum");
        badgeTextPaint.setTypeface(AndroidUtilities.bold());
        badgeTextPaint.setTextSize(dp(10));
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setFocusable(true);
    }

    public void setPressListener(PressListener listener) {
        pressListener = listener;
    }

    public void setIcon(PlayerIcon icon) {
        this.icon = icon;
        invalidate();
    }

    public void setAltIcon(PlayerIcon altIcon) {
        this.altIcon = altIcon;
        invalidate();
    }

    public void setText(String value) {
        if (!TextUtils.equals(text, value)) {
            text = value;
            invalidate();
        }
    }

    public void setColors(int bg, int fg, int activeBg, int activeFg) {
        bgColor = bg;
        fgColor = fg;
        activeBgColor = activeBg;
        activeFgColor = activeFg;
        invalidate();
    }

    public void setBadgeColors(int bg, int fg) {
        badgeBg = bg;
        badgeFg = fg;
        invalidate();
    }

    public void setRadius(float left, float right, boolean animated) {
        if (animated) {
            radiusLeft.target = left;
            radiusRight.target = right;
        } else {
            radiusLeft.snap(left);
            radiusRight.snap(right);
        }
        invalidate();
    }

    public void setRadius(float r, boolean animated) {
        setRadius(r, r, animated);
    }

    public void setAlt(boolean value, boolean animated) {
        if (animated) {
            alt.target = value ? 1f : 0f;
        } else {
            alt.snap(value ? 1f : 0f);
        }
        invalidate();
    }

    public void setActive(boolean value, boolean animated) {
        if (animated) {
            active.target = value ? 1f : 0f;
        } else {
            active.snap(value ? 1f : 0f);
        }
        invalidate();
    }

    public void setBadge(String value, boolean animated) {
        if (value != null) {
            badgeText = value;
        }
        if (animated) {
            badge.target = value != null ? 1f : 0f;
        } else {
            badge.snap(value != null ? 1f : 0f);
        }
        invalidate();
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (changed && pressListener != null) {
            pressListener.onPressChanged(this, pressed);
        }
    }

    @Override
    public boolean performClick() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        playSoundEffect(SoundEffectConstants.CLICK);
        return super.performClick();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? 0.016f : (now - lastFrame) / 1000f;
        lastFrame = now;
        boolean animating = radiusLeft.step(dt);
        animating |= radiusRight.step(dt);
        animating |= alt.step(dt);
        animating |= active.step(dt);
        animating |= badge.step(dt);

        float w = getWidth();
        float h = getHeight();
        float a = Math.max(0f, Math.min(1f, active.value));
        int bg = ColorUtils.blendARGB(bgColor, activeBgColor, a);
        int fg = ColorUtils.blendARGB(fgColor, activeFgColor, a);
        float half = Math.min(w, h) / 2f;
        float rl = Math.max(0f, Math.min(half, radiusLeft.value * scale));
        float rr = Math.max(0f, Math.min(half, radiusRight.value * scale));
        if ((bg >>> 24) != 0) {
            bgPaint.setColor(bg);
            rect.set(0, 0, w, h);
            radii[0] = radii[1] = radii[6] = radii[7] = rl;
            radii[2] = radii[3] = radii[4] = radii[5] = rr;
            path.rewind();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.drawPath(path, bgPaint);
        }

        float contentWidth = 0;
        int iconSize = icon != null ? icon.getIntrinsicWidth() : 0;
        if (text != null) {
            contentWidth = iconSize + dp(8) + textPaint.measureText(text);
        }
        float iconCx = text != null ? (w - contentWidth) / 2f + iconSize / 2f : w / 2f;
        float cy = h / 2f;
        if (altIcon == null) {
            drawIcon(canvas, icon, iconCx, cy, fg, 1f, 1f);
        } else {
            float t = alt.value;
            drawIcon(canvas, icon, iconCx, cy, fg, 1f - t, 1f - 0.4f * t);
            drawIcon(canvas, altIcon, iconCx, cy, fg, t, 0.6f + 0.4f * t);
        }
        if (text != null) {
            textPaint.setColor(fg);
            float tx = iconCx + iconSize / 2f + dp(8);
            float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(text, tx, ty, textPaint);
        }
        float b = Math.max(0f, Math.min(1f, badge.value));
        if (b > 0f && badgeText != null) {
            float k = 0.6f + 0.4f * b;
            float badgeHalf = Math.max(dp(7.5f), badgeTextPaint.measureText(badgeText) / 2f + dp(4));
            float bx = w - dp(3) - badgeHalf;
            float by = dp(3) + dp(7.5f);
            badgePaint.setColor(ColorUtils.setAlphaComponent(badgeBg, (int) (255 * b)));
            rect.set(bx - badgeHalf * k, by - dp(7.5f) * k, bx + badgeHalf * k, by + dp(7.5f) * k);
            canvas.drawRoundRect(rect, dp(7.5f) * k, dp(7.5f) * k, badgePaint);
            badgeTextPaint.setColor(ColorUtils.setAlphaComponent(badgeFg, (int) (255 * b)));
            canvas.drawText(badgeText, bx, by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint);
        }

        if (animating) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    private void drawIcon(Canvas canvas, PlayerIcon drawable, float cx, float cy, int color, float alpha, float s) {
        if (drawable == null) {
            return;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));
        if (alpha <= 0f) {
            return;
        }
        int size = drawable.getIntrinsicWidth();
        drawable.setColor(color);
        drawable.setAlpha((int) (255 * alpha));
        drawable.setBounds((int) (cx - size / 2f), (int) (cy - size / 2f), (int) (cx + size / 2f), (int) (cy + size / 2f));
        canvas.save();
        canvas.scale(s * scale, s * scale, cx, cy);
        drawable.draw(canvas);
        canvas.restore();
    }
}
