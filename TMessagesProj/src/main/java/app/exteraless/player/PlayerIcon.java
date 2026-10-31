package app.exteraless.player;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.PathParser;

import org.telegram.messenger.AndroidUtilities;

import java.util.HashMap;

public class PlayerIcon extends Drawable {

    public static final String CHEVRON_DOWN = "M6 9l6 6 6-6";
    public static final String MORE = circle(12, 5, 1.8f) + circle(12, 12, 1.8f) + circle(12, 19, 1.8f);
    public static final String HEART = "M12 20.5s-7.5-4.6-7.5-10.3A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.6c0 5.7-7.5 10.3-7.5 10.3z";
    public static final String SHUFFLE = "M16 4h4v4M4 20L20 4M20 16v4h-4M14.5 14.5L20 20M4 4l5 5";
    public static final String REPEAT = "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4";
    public static final String REVERSE = "M7 4v16M3 16l4 4 4-4M13 6h8M13 11h6M13 16h4";
    public static final String ONCE = "M4 12h11M11 7l5 5-5 5M20 5v14";
    public static final String PREV = roundRect(5, 5.5f, 2.6f, 13, 1.2f) + "M19 6.6v10.8a1 1 0 0 1-1.55.84l-8.1-5.4a1 1 0 0 1 0-1.68l8.1-5.4A1 1 0 0 1 19 6.6z";
    public static final String NEXT = roundRect(16.4f, 5.5f, 2.6f, 13, 1.2f) + "M5 6.6v10.8a1 1 0 0 0 1.55.84l8.1-5.4a1 1 0 0 0 0-1.68l-8.1-5.4A1 1 0 0 0 5 6.6z";
    public static final String PLAY = "M8 5.8v12.4a1 1 0 0 0 1.54.84l9.6-6.2a1 1 0 0 0 0-1.68l-9.6-6.2A1 1 0 0 0 8 5.8z";
    public static final String PAUSE = roundRect(6, 5, 4.6f, 14, 1.6f) + roundRect(13.4f, 5, 4.6f, 14, 1.6f);
    public static final String SPEED = "M12 14l3.5-3.5M4.6 18a8.5 8.5 0 1 1 14.8 0";
    public static final String LYRICS = "M4 6h12M4 11h16M4 16h9";
    public static final String QUEUE = "M4 6h12M4 11h12M4 16h7M15 14v6l5-3z";
    public static final String NOTE = "M9 17V5l11-2v12" + circle(6.5f, 17, 2.5f) + circle(17.5f, 15, 2.5f);
    public static final String CLOSE = "M6 6l12 12M18 6L6 18";
    public static final String ARROW_UP = "M12 19V5M6 11l6-6 6 6";
    public static final String ARROW_DOWN = "M12 5v14M6 13l6 6 6-6";
    public static final String CHECK = "M5 12.5l4.5 4.5L19 7.5";
    public static final String CHEVRON_UP = "M6 15l6-6 6 6";
    public static final String DRAG = "M5 9h14M5 15h14";
    public static final String EYE = "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z" + circle(12, 12, 3);
    public static final String EYE_OFF = EYE + "M4 4l16 16";

    private static final HashMap<String, Path> cache = new HashMap<>();

    private final Path strokePath;
    private final Path fillPath;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private boolean fillStroke;

    public PlayerIcon(String stroke, String fill, int sizeDp) {
        strokePath = stroke == null ? null : path(stroke);
        fillPath = fill == null ? null : path(fill);
        size = AndroidUtilities.dp(sizeDp);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(2f);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public static PlayerIcon stroke(String d, int sizeDp) {
        return new PlayerIcon(d, null, sizeDp);
    }

    public static PlayerIcon fill(String d, int sizeDp) {
        return new PlayerIcon(null, d, sizeDp);
    }

    public static String circle(float cx, float cy, float r) {
        return "M" + (cx - r) + " " + cy + "a" + r + " " + r + " 0 1 0 " + (2 * r) + " 0a" + r + " " + r + " 0 1 0 " + (-2 * r) + " 0z";
    }

    public static String roundRect(float x, float y, float w, float h, float r) {
        return "M" + (x + r) + " " + y + "h" + (w - 2 * r) + "a" + r + " " + r + " 0 0 1 " + r + " " + r
                + "v" + (h - 2 * r) + "a" + r + " " + r + " 0 0 1 " + (-r) + " " + r
                + "h" + (2 * r - w) + "a" + r + " " + r + " 0 0 1 " + (-r) + " " + (-r)
                + "v" + (2 * r - h) + "a" + r + " " + r + " 0 0 1 " + r + " " + (-r) + "z";
    }

    private static Path path(String d) {
        Path p = cache.get(d);
        if (p == null) {
            p = PathParser.createPathFromPathData(d);
            cache.put(d, p);
        }
        return p;
    }

    public void setColor(int color) {
        if (strokePaint.getColor() != color || fillPaint.getColor() != color) {
            strokePaint.setColor(color);
            fillPaint.setColor(color);
            invalidateSelf();
        }
    }

    public void setFillStroke(boolean fill) {
        if (fillStroke != fill) {
            fillStroke = fill;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        canvas.save();
        canvas.translate(b.centerX() - size / 2f, b.centerY() - size / 2f);
        float k = size / 24f;
        canvas.scale(k, k);
        if (fillPath != null) {
            canvas.drawPath(fillPath, fillPaint);
        }
        if (strokePath != null) {
            if (fillStroke) {
                canvas.drawPath(strokePath, fillPaint);
            }
            canvas.drawPath(strokePath, strokePaint);
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        strokePaint.setAlpha(a);
        fillPaint.setAlpha(a);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        strokePaint.setColorFilter(colorFilter);
        fillPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
