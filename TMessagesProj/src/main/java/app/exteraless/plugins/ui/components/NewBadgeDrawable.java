package app.exteraless.plugins.ui.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public final class NewBadgeDrawable extends Drawable {

    private static final int TEXT_SIZE_DP = 11;
    private static final int HORIZONTAL_PADDING_DP = 6;
    private static final int HEIGHT_DP = 17;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF backgroundRect = new RectF();
    private final String text;
    private final int width;

    public NewBadgeDrawable(String text) {
        this.text = text;
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(dp(TEXT_SIZE_DP));
        width = (int) Math.ceil(textPaint.measureText(text)) + dp(HORIZONTAL_PADDING_DP) * 2;
        updateColors();
    }

    public void updateColors() {
        backgroundPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        textPaint.setColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        backgroundRect.set(getBounds());
        final float radius = backgroundRect.height() / 2f;
        canvas.drawRoundRect(backgroundRect, radius, radius, backgroundPaint);
        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        final float baseline = backgroundRect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, backgroundRect.centerX(), baseline, textPaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(HEIGHT_DP);
    }

    @Override
    public void setAlpha(int alpha) {
        backgroundPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        backgroundPaint.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
