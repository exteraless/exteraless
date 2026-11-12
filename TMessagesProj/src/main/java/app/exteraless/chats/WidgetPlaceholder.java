package app.exteraless.chats;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

public final class WidgetPlaceholder {

    private final Runnable invalidate;
    private final AnimatedFloat pressedT;
    private final AnimatedFloat loadingT;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spinnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path play = new Path();
    private final RectF rect = new RectF();
    private boolean pressed;
    private boolean loading;

    public WidgetPlaceholder(Runnable invalidate) {
        this.invalidate = invalidate;
        pressedT = new AnimatedFloat(invalidate, 160, CubicBezierInterpolator.EASE_OUT_QUINT);
        loadingT = new AnimatedFloat(invalidate, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
        spinnerPaint.setStyle(Paint.Style.STROKE);
        spinnerPaint.setStrokeCap(Paint.Cap.ROUND);
        titlePaint.setTypeface(AndroidUtilities.bold());
    }

    public boolean draw(Canvas canvas, int width, int height, boolean out, Theme.ResourcesProvider resourcesProvider, boolean loading, boolean interactive) {
        this.loading = loading;
        final int textColor = Theme.getColor(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn, resourcesProvider);
        paint.setColor(Theme.multAlpha(textColor, 0.06f));
        canvas.drawRect(0, 0, width, height, paint);

        final float pressedValue = pressedT.set(pressed);
        final float loadingValue = loadingT.set(loading);
        final boolean vertical = height >= dp(108);
        final float radius = dp(vertical ? 24 : 18);

        titlePaint.setTextSize(dp(15));
        titlePaint.setColor(textColor);
        subtitlePaint.setTextSize(dp(13));
        subtitlePaint.setColor(Theme.getColor(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText, resourcesProvider));
        final String title = LocaleController.getString(R.string.OEChatsWidgetPlaceholderTitle);
        final String subtitle = LocaleController.getString(!interactive ? R.string.OEChatsWidgetPlaceholderPreview
                : loading ? R.string.OEChatsWidgetPlaceholderLoading : R.string.OEChatsWidgetPlaceholderTap);

        final float cx;
        final float cy;
        if (vertical) {
            final float total = radius * 2 + dp(12) + dp(18) + dp(18);
            cx = width / 2f;
            cy = (height - total) / 2f + radius;
            final float available = width - dp(32);
            drawCentered(canvas, title, titlePaint, available, cx, cy + radius + dp(12) + dp(14));
            drawCentered(canvas, subtitle, subtitlePaint, available, cx, cy + radius + dp(12) + dp(32));
        } else {
            final float textWidth = Math.max(titlePaint.measureText(title), subtitlePaint.measureText(subtitle));
            final float left = Math.max(dp(16), (width - radius * 2 - dp(12) - textWidth) / 2f);
            cx = left + radius;
            cy = height / 2f;
            final float textLeft = left + radius * 2 + dp(12);
            final float available = width - textLeft - dp(16);
            canvas.drawText(ellipsize(title, titlePaint, available), textLeft, cy - dp(3), titlePaint);
            canvas.drawText(ellipsize(subtitle, subtitlePaint, available), textLeft, cy + dp(15), subtitlePaint);
        }

        final float scale = 1f - 0.08f * pressedValue;
        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        paint.setColor(Theme.getColor(out ? Theme.key_chat_outLoader : Theme.key_chat_inLoader, resourcesProvider));
        canvas.drawCircle(cx, cy, radius, paint);

        final int iconColor = Theme.getColor(out ? Theme.key_chat_outMediaIcon : Theme.key_chat_inMediaIcon, resourcesProvider);
        if (loadingValue < 1f) {
            final float size = radius * 0.42f;
            play.rewind();
            play.moveTo(cx - size * 0.7f, cy - size);
            play.lineTo(cx + size, cy);
            play.lineTo(cx - size * 0.7f, cy + size);
            play.close();
            paint.setColor(Theme.multAlpha(iconColor, 1f - loadingValue));
            canvas.drawPath(play, paint);
        }
        if (loadingValue > 0f) {
            final float inset = radius * 0.45f;
            rect.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset);
            spinnerPaint.setStrokeWidth(dp(2.5f));
            spinnerPaint.setColor(Theme.multAlpha(iconColor, loadingValue));
            final float rotation = SystemClock.uptimeMillis() % 1000 / 1000f * 360f;
            canvas.drawArc(rect, rotation, 100f, false, spinnerPaint);
        }
        canvas.restore();
        return loading || loadingValue > 0f && loadingValue < 1f;
    }

    public boolean onTouchEvent(MotionEvent event, int width, int height, Runnable onTap) {
        final boolean inside = event.getX() >= 0 && event.getX() < width && event.getY() >= 0 && event.getY() < height;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inside || loading) {
                    return false;
                }
                pressed = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!inside) {
                    pressed = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (pressed) {
                    onTap.run();
                }
                pressed = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                break;
        }
        invalidate.run();
        return true;
    }

    private static void drawCentered(Canvas canvas, String text, TextPaint paint, float available, float cx, float baseline) {
        final String shown = ellipsize(text, paint, available);
        canvas.drawText(shown, cx - paint.measureText(shown) / 2f, baseline, paint);
    }

    private static String ellipsize(String text, TextPaint paint, float available) {
        return TextUtils.ellipsize(text, paint, Math.max(0f, available), TextUtils.TruncateAt.END).toString();
    }
}
