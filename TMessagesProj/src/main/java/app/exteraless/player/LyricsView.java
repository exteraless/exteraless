package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class LyricsView extends FrameLayout {

    public static final int STATE_LYRICS = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_OFFER = 2;
    public static final int STATE_NOT_FOUND = 3;
    public static final int STATE_ERROR = 4;
    public static final int STATE_INSTRUMENTAL = 5;

    public interface Delegate {
        void onAction(int state);

        void onSeek(long ms);
    }

    private final RecyclerListView list;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private final LinearLayout empty;
    private final FrameLayout tile;
    private final PlayerIcon tileIcon = PlayerIcon.stroke(PlayerIcon.NOTE, 28);
    private final TextView emptyTitle;
    private final TextView emptyText;
    private final TextView actionButton;
    private final RadialProgressView progress;
    private final GradientDrawable tileBg = new GradientDrawable();
    private final GradientDrawable buttonBg = new GradientDrawable();
    private final Paint fadePaint = new Paint();
    private LinearGradient topFade;
    private LinearGradient bottomFade;
    private Delegate delegate;
    private Lyrics lyrics;
    private int state = -1;
    private int active = -1;
    private long userScrollAt;
    private boolean userDragging;
    private int colorActive;
    private long positionMs;
    private long positionAt;
    private final Runnable resumeFollow = () -> {
        userScrollAt = 0;
        updateBlur();
        scrollToActive(true);
    };

    private static final long FOLLOW_RESUME_MS = 750;
    private static final int MAX_BLUR_DISTANCE = 4;
    private static final float BLUR_PER_LINE_DP = 1.25f;

    public LyricsView(Context context) {
        super(context);

        list = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context);
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setSelectorDrawableColor(0);
        list.setOnItemClickListener((view, position) -> {
            if (lyrics != null && lyrics.synced && position >= 0 && position < lyrics.lines.size() && delegate != null) {
                delegate.onSeek(lyrics.lines.get(position).time);
                userScrollAt = 0;
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                userDragging = newState == RecyclerView.SCROLL_STATE_DRAGGING;
                if (newState != RecyclerView.SCROLL_STATE_IDLE && userDragging) {
                    userScrollAt = SystemClock.elapsedRealtime();
                    removeCallbacks(resumeFollow);
                    updateBlur();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && userScrollAt != 0) {
                    userScrollAt = SystemClock.elapsedRealtime();
                    removeCallbacks(resumeFollow);
                    postDelayed(resumeFollow, FOLLOW_RESUME_MS);
                }
            }
        });
        addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        tile = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                super.dispatchDraw(canvas);
                tileIcon.setBounds(0, 0, getWidth(), getHeight());
                tileIcon.draw(canvas);
            }
        };
        tile.setWillNotDraw(false);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        tileBg.setCornerRadius(dp(22));
        tile.setBackground(tileBg);
        empty.addView(tile, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL));
        progress = new RadialProgressView(context);
        progress.setSize(dp(36));
        empty.addView(progress, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
        emptyTitle = new TextView(context);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        emptyTitle.setTypeface(AndroidUtilities.bold());
        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
        emptyText = new TextView(context);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyText.setLineSpacing(0, 1.2f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setMaxWidth(dp(260));
        empty.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
        actionButton = new TextView(context);
        actionButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        actionButton.setTypeface(AndroidUtilities.bold());
        actionButton.setGravity(Gravity.CENTER);
        actionButton.setPadding(dp(20), 0, dp(20), 0);
        buttonBg.setCornerRadius(dp(22));
        actionButton.setBackground(buttonBg);
        actionButton.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onAction(state);
            }
        });
        empty.addView(actionButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 16, 0, 16, 0));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setColors(PlayerColors c) {
        colorActive = c.onSurface;
        tileBg.setColor(c.secondaryContainer);
        tileIcon.setColor(c.onSecondaryContainer);
        tile.invalidate();
        emptyTitle.setTextColor(c.onSurface);
        emptyText.setTextColor(c.onSurfaceVariant);
        actionButton.setTextColor(c.primary);
        buttonBg.setStroke(dp(1), c.outlineVariant);
        buttonBg.setColor(0);
        progress.setProgressColor(c.primary);
        for (int i = 0; i < list.getChildCount(); i++) {
            list.getChildAt(i).invalidate();
        }
    }

    public boolean canScrollUp() {
        return list.getVisibility() == VISIBLE && list.canScrollVertically(-1);
    }

    public int getState() {
        return state;
    }

    public Lyrics getLyrics() {
        return lyrics;
    }

    public void showState(int newState) {
        state = newState;
        if (newState == STATE_LYRICS) {
            list.setVisibility(VISIBLE);
            empty.setVisibility(GONE);
            return;
        }
        lyrics = null;
        active = -1;
        adapter.notifyDataSetChanged();
        list.setVisibility(GONE);
        empty.setVisibility(VISIBLE);
        boolean loading = newState == STATE_LOADING;
        progress.setVisibility(loading ? VISIBLE : GONE);
        tile.setVisibility(loading ? GONE : VISIBLE);
        String title;
        String text;
        String action = null;
        if (loading) {
            title = null;
            text = LocaleController.getString(R.string.OEPlayerLyricsSearching);
        } else if (newState == STATE_OFFER) {
            title = LocaleController.getString(R.string.OEPlayerNoLyricsTitle);
            text = LocaleController.getString(R.string.OEPlayerNoLyricsText);
            action = LocaleController.getString(R.string.OEPlayerFindLyrics);
        } else if (newState == STATE_NOT_FOUND) {
            title = LocaleController.getString(R.string.OEPlayerLyricsNotFound);
            text = LocaleController.getString(R.string.OEPlayerLyricsNotFoundText);
        } else if (newState == STATE_ERROR) {
            title = LocaleController.getString(R.string.OEPlayerLyricsError);
            text = LocaleController.getString(R.string.OEPlayerLyricsErrorText);
            action = LocaleController.getString(R.string.OEPlayerRetry);
        } else {
            title = LocaleController.getString(R.string.OEPlayerInstrumental);
            text = LocaleController.getString(R.string.OEPlayerInstrumentalText);
        }
        emptyTitle.setText(title);
        emptyTitle.setVisibility(title == null ? GONE : VISIBLE);
        emptyText.setText(text);
        actionButton.setText(action);
        actionButton.setVisibility(action == null ? GONE : VISIBLE);
    }

    public void setLyrics(Lyrics value, long positionMs) {
        lyrics = value;
        state = STATE_LYRICS;
        list.setVisibility(VISIBLE);
        empty.setVisibility(GONE);
        active = value != null ? value.indexAt(positionMs) : -1;
        this.positionMs = positionMs;
        positionAt = SystemClock.elapsedRealtime();
        userScrollAt = 0;
        applyPadding();
        adapter.notifyDataSetChanged();
        list.post(() -> scrollToActive(false));
    }

    public void setPosition(long ms) {
        if (lyrics == null || !lyrics.synced) {
            return;
        }
        positionMs = ms;
        positionAt = SystemClock.elapsedRealtime();
        int index = lyrics.indexAt(ms);
        if (index == active) {
            return;
        }
        int old = active;
        active = index;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof LineView) {
                int pos = list.getChildAdapterPosition(child);
                ((LineView) child).setRole(roleFor(pos), true);
            }
        }
        boolean following = !userDragging && (userScrollAt == 0 || SystemClock.elapsedRealtime() - userScrollAt > FOLLOW_RESUME_MS);
        if (following) {
            userScrollAt = 0;
            scrollToActive(old >= 0 && Math.abs(index - old) <= 3);
        }
        updateBlur();
    }

    private long currentMs() {
        if (MediaController.getInstance().isMessagePaused()) {
            return positionMs;
        }
        return positionMs + Math.min(2000, SystemClock.elapsedRealtime() - positionAt);
    }

    private boolean isPlaying() {
        return !MediaController.getInstance().isMessagePaused();
    }

    private float blurFor(int position) {
        if (lyrics == null || !lyrics.synced || active < 0 || userDragging || userScrollAt != 0) {
            return 0f;
        }
        int distance = Math.min(Math.abs(position - active), MAX_BLUR_DISTANCE);
        return dp(BLUR_PER_LINE_DP) * distance;
    }

    private void updateBlur() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof LineView) {
                ((LineView) child).setBlur(blurFor(list.getChildAdapterPosition(child)));
            }
        }
    }

    private int roleFor(int position) {
        if (lyrics == null || !lyrics.synced) {
            return LineView.ROLE_PLAIN;
        }
        if (position == active) {
            return LineView.ROLE_ACTIVE;
        }
        return position < active ? LineView.ROLE_PAST : LineView.ROLE_FUTURE;
    }

    private void scrollToActive(boolean smooth) {
        if (lyrics == null || list.getHeight() == 0) {
            return;
        }
        if (!lyrics.synced) {
            return;
        }
        int target = Math.max(active, 0);
        if (target >= adapter.getItemCount()) {
            return;
        }
        if (smooth && layoutManager.findViewByPosition(target) != null) {
            LinearSmoothScroller scroller = new LinearSmoothScroller(getContext()) {
                @Override
                public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                    return boxStart - viewStart;
                }

                @Override
                protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                    return 400f / displayMetrics.densityDpi;
                }

                @Override
                protected int calculateTimeForDeceleration(int dx) {
                    return Math.max(380, Math.min(700, super.calculateTimeForDeceleration(dx)));
                }
            };
            scroller.setTargetPosition(target);
            layoutManager.startSmoothScroll(scroller);
        } else {
            list.stopScroll();
            layoutManager.scrollToPositionWithOffset(target, 0);
        }
    }

    private void applyPadding() {
        int h = getHeight();
        if (lyrics != null && !lyrics.synced) {
            list.setPadding(0, dp(8), 0, dp(24));
        } else if (h > 0) {
            int offset = Math.min(dp(108), (int) (h * 0.3f));
            list.setPadding(0, offset, 0, Math.max(0, h - offset - dp(72)));
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (list.getVisibility() != VISIBLE || getWidth() == 0 || getHeight() == 0 || topFade == null) {
            super.dispatchDraw(canvas);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        int save = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        fadePaint.setShader(topFade);
        canvas.drawRect(0, 0, w, dp(32), fadePaint);
        fadePaint.setShader(bottomFade);
        canvas.drawRect(0, h - dp(40), w, h, fadePaint);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        topFade = new LinearGradient(0, 0, 0, dp(32), 0xff000000, 0x00000000, Shader.TileMode.CLAMP);
        bottomFade = new LinearGradient(0, h - dp(40), 0, h, 0x00000000, 0xff000000, Shader.TileMode.CLAMP);
        if (h != oldh) {
            applyPadding();
            list.post(() -> scrollToActive(false));
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return lyrics != null && lyrics.synced;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LineView view = new LineView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LineView view = (LineView) holder.itemView;
            String text = lyrics.lines.get(position).text;
            view.index = position;
            view.setText(TextUtils.isEmpty(text) ? (lyrics.synced ? "" : "♪") : text, !lyrics.synced);
            view.setRole(roleFor(position), false);
            view.setBlur(blurFor(position));
        }

        @Override
        public int getItemCount() {
            return lyrics == null ? 0 : lyrics.lines.size();
        }
    }

    private class LineView extends View {

        static final int ROLE_PAST = 0;
        static final int ROLE_ACTIVE = 1;
        static final int ROLE_FUTURE = 2;
        static final int ROLE_PLAIN = 3;

        private static final float OPACITY_FUTURE = 0.51f;
        private static final float OPACITY_PAST = 0.5f;
        private static final float UNFILLED_ALPHA = 0.45f;
        private static final float ACTIVE_SCALE = 1.05f;
        private static final float WORD_FEATHER_DP = 14f;

        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix fillMatrix = new Matrix();
        private final Spring opacity = new Spring(OPACITY_FUTURE, 260f, 1f, 0.004f);
        private final Spring scale = new Spring(1f, 320f, 0.75f, 0.0005f);
        private int index = -1;
        private String text;
        private boolean plain;
        private StaticLayout layout;
        private int layoutWidth;
        private int role = ROLE_FUTURE;
        private long lastFrame;
        private float blur = -1f;

        LineView(Context context) {
            super(context);
            paint.setTypeface(tw.nekomimi.nekogram.helpers.TypefaceHelper.lyricsTypeface());
        }

        void setText(String value, boolean isPlain) {
            if (!TextUtils.equals(text, value) || plain != isPlain) {
                text = value;
                plain = isPlain;
                layout = null;
                requestLayout();
            }
        }

        void setBlur(float value) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || value == blur) {
                return;
            }
            blur = value;
            setRenderEffect(value > 0f ? RenderEffect.createBlurEffect(value, value, Shader.TileMode.DECAL) : null);
        }

        void setRole(int value, boolean animated) {
            role = value;
            float targetOpacity = value == ROLE_PAST ? OPACITY_PAST : value == ROLE_FUTURE ? OPACITY_FUTURE : 1f;
            float targetScale = value == ROLE_ACTIVE ? ACTIVE_SCALE : 1f;
            if (animated) {
                opacity.target = targetOpacity;
                scale.target = targetScale;
                lastFrame = 0;
            } else {
                opacity.snap(targetOpacity);
                scale.snap(targetScale);
            }
            invalidate();
        }

        private boolean isInterlude() {
            return !plain && TextUtils.isEmpty(text);
        }

        private float textSize() {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, plain ? 20 : 28, getResources().getDisplayMetrics());
        }

        private void ensureLayout(int width) {
            if (layout != null && layoutWidth == width) {
                return;
            }
            layoutWidth = width;
            paint.setTextSize(textSize());
            int textWidth = Math.max(1, (int) (width / ACTIVE_SCALE));
            layout = StaticLayout.Builder.obtain(text == null ? "" : text, 0, text == null ? 0 : text.length(), paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0, 1.18f)
                    .setIncludePad(false)
                    .build();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            ensureLayout(width);
            int pad = plain ? dp(6) : dp(10);
            int content = isInterlude() ? (int) (textSize() * 0.9f) : layout.getHeight();
            setMeasuredDimension(width, content + pad * 2);
        }

        private float lineProgress() {
            if (lyrics == null || index < 0 || index >= lyrics.lines.size()) {
                return 0f;
            }
            ArrayList<Lyrics.Word> words = lyrics.lines.get(index).words;
            long start = words != null ? words.get(0).start : lyrics.lines.get(index).time;
            long end = words != null ? words.get(words.size() - 1).end : index + 1 < lyrics.lines.size() ? lyrics.lines.get(index + 1).time : start + 4000;
            if (end <= start) {
                return 1f;
            }
            return Math.max(0f, Math.min(1f, (currentMs() - start) / (float) (end - start)));
        }

        private float glow(float t) {
            if (t < 0.15f) {
                return t / 0.15f;
            }
            if (t < 0.6f) {
                return 1f;
            }
            return Math.max(0f, 1f - (t - 0.6f) / 0.4f);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            long now = SystemClock.elapsedRealtime();
            float dt = lastFrame == 0 ? 0.016f : (now - lastFrame) / 1000f;
            lastFrame = now;
            boolean animating = opacity.step(dt);
            animating |= scale.step(dt);
            ensureLayout(getWidth());
            float alpha = Math.max(0f, Math.min(1f, opacity.value));
            int baseAlpha = colorActive >>> 24;
            boolean live = role == ROLE_ACTIVE && !plain;
            float progress = live ? lineProgress() : 0f;
            int pad = plain ? dp(6) : dp(10);
            canvas.save();
            canvas.translate(0, pad);
            float pivotY = (getHeight() - pad * 2) / 2f;
            canvas.scale(scale.value, scale.value, 0, pivotY);
            if (isInterlude()) {
                drawInterlude(canvas, live, progress, alpha, now);
            } else if (live) {
                int filled = ColorUtils.setAlphaComponent(colorActive, (int) (alpha * baseAlpha));
                int unfilled = ColorUtils.setAlphaComponent(colorActive, (int) (alpha * baseAlpha * UNFILLED_ALPHA));
                ArrayList<Lyrics.Word> words = index >= 0 && lyrics != null && index < lyrics.lines.size() ? lyrics.lines.get(index).words : null;
                float height = layout.getHeight();
                LinearGradient fill = words != null
                        ? new LinearGradient(0, 0, 1, 0, filled, unfilled, Shader.TileMode.CLAMP)
                        : new LinearGradient(0, 0, 0, 1, filled, unfilled, Shader.TileMode.CLAMP);
                if (words == null) {
                    fillMatrix.setScale(1f, Math.max(1f, height * 0.2f));
                    fillMatrix.postTranslate(0, (progress * 1.2f - 0.2f) * height);
                    fill.setLocalMatrix(fillMatrix);
                }
                paint.setShader(fill);
                paint.setColor(0xffffffff);
                float g = glow(progress);
                if (g > 0.01f) {
                    paint.setShadowLayer(dp(4 + 2 * g), 0, 0, ColorUtils.setAlphaComponent(colorActive, (int) (g * 0.35f * baseAlpha)));
                } else {
                    paint.clearShadowLayer();
                }
                if (words != null) {
                    drawWords(canvas, fill, cursorAt(words, currentMs()));
                } else {
                    layout.draw(canvas);
                }
                paint.setShader(null);
                paint.clearShadowLayer();
            } else {
                paint.setShader(null);
                paint.clearShadowLayer();
                paint.setColor(ColorUtils.setAlphaComponent(colorActive, (int) (alpha * baseAlpha)));
                layout.draw(canvas);
            }
            canvas.restore();
            if (animating || (live && isPlaying())) {
                postInvalidateOnAnimation();
            } else {
                lastFrame = 0;
            }
        }

        private float cursorAt(ArrayList<Lyrics.Word> words, long ms) {
            float cursor = words.get(0).from;
            for (Lyrics.Word word : words) {
                if (ms < word.start) {
                    break;
                }
                if (ms >= word.end) {
                    cursor = word.to;
                } else {
                    cursor = word.from + (word.to - word.from) * (ms - word.start) / (float) (word.end - word.start);
                    break;
                }
            }
            return cursor;
        }

        private float cursorX(int line, float cursor) {
            int visibleEnd = layout.getLineVisibleEnd(line);
            int low = Math.max(layout.getLineStart(line), Math.min((int) cursor, visibleEnd - 1));
            float from = layout.getPrimaryHorizontal(low);
            float to = low + 1 >= visibleEnd ? (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT ? layout.getLineLeft(line) : layout.getLineRight(line)) : layout.getPrimaryHorizontal(low + 1);
            return from + (to - from) * Math.max(0f, Math.min(1f, cursor - low));
        }

        private void drawWords(Canvas canvas, LinearGradient fill, float cursor) {
            float feather = dp(WORD_FEATHER_DP);
            int margin = dp(24);
            for (int line = 0; line < layout.getLineCount(); line++) {
                boolean rtl = layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
                float left = layout.getLineLeft(line);
                float right = layout.getLineRight(line);
                float edge;
                if (cursor <= layout.getLineStart(line)) {
                    edge = rtl ? right + feather : left - feather;
                } else if (cursor >= layout.getLineVisibleEnd(line)) {
                    edge = rtl ? left - feather : right + feather;
                } else {
                    float x = cursorX(line, cursor);
                    edge = rtl ? x + feather / 2f : x - feather / 2f;
                }
                fillMatrix.setScale(rtl ? -feather : feather, 1f);
                fillMatrix.postTranslate(edge, 0);
                fill.setLocalMatrix(fillMatrix);
                canvas.save();
                canvas.clipRect(-margin, layout.getLineTop(line), layout.getWidth() + margin, layout.getLineBottom(line));
                layout.draw(canvas);
                canvas.restore();
            }
        }

        private void drawInterlude(Canvas canvas, boolean live, float progress, float alpha, long now) {
            float size = textSize() * 0.32f;
            float gap = size * 0.55f;
            float cy = textSize() * 0.45f;
            float breathe = live ? 1f + 0.06f * (float) Math.sin(now / 1600.0 * Math.PI * 2) : 1f;
            canvas.save();
            canvas.scale(breathe, breathe, size * 1.5f + gap, cy);
            int baseAlpha = colorActive >>> 24;
            for (int i = 0; i < 3; i++) {
                float lit = live ? Math.max(0f, Math.min(1f, progress * 3f - i)) : 0f;
                float dotAlpha = alpha * (0.35f + 0.65f * lit);
                dotPaint.setColor(ColorUtils.setAlphaComponent(colorActive, (int) (dotAlpha * baseAlpha)));
                if (lit > 0f) {
                    dotPaint.setShadowLayer(dp(4 + 2 * lit), 0, 0, ColorUtils.setAlphaComponent(colorActive, (int) (lit * 0.35f * baseAlpha)));
                } else {
                    dotPaint.clearShadowLayer();
                }
                float cx = size / 2f + i * (size + gap);
                canvas.drawCircle(cx, cy, size / 2f * (0.9f + 0.1f * lit), dotPaint);
            }
            canvas.restore();
        }
    }
}
