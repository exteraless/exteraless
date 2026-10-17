package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CacheControlActivity;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.exteraless.pillstack.PillStackEvents;
import app.exteraless.pillstack.PillStackSettingsActivity;
import app.exteraless.pillstack.PillType;

/** Размер кэша Telegram и заполненность накопителя. */
@SuppressLint("ViewConstructor")
public class CachePill extends BasePill implements PillStackEvents.Listener {

    private static final AtomicLong lastKnownCacheSize = new AtomicLong(-1);
    private static float lastKnownProgress = -1f;

    private final AtomicBoolean calculating = new AtomicBoolean(false);
    private final LinearLayout layout;
    private final ImageView iconView;
    private final PillAnimatedTextView textView;
    private final StorageProgressDrawable progressDrawable;

    /** Кольцо вокруг иконки: доля занятого места на устройстве. */
    public static class StorageProgressDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        private final AnimatedFloat animatedProgress;
        private float progress;
        private int color;

        public StorageProgressDrawable(View view) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            animatedProgress = new AnimatedFloat(view, 650, CubicBezierInterpolator.EASE_OUT_QUINT);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int width = getBounds().width();
            int height = getBounds().height();
            float size = Math.min(width, height) - AndroidUtilities.dp(2);
            float left = (width - size) / 2f;
            float top = (height - size) / 2f;
            rectF.set(left, top, left + size, top + size);
            float value = animatedProgress.set(progress);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setColor(color);
            paint.setAlpha(50);
            canvas.drawCircle(width / 2f, height / 2f, size / 2f, paint);
            paint.setAlpha(255);
            canvas.drawArc(rectF, -90, value * 360, false, paint);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        public void setColor(int color) {
            this.color = color;
            invalidateSelf();
        }

        public void setProgress(float progress, boolean animated) {
            this.progress = Math.max(0.05f, Math.min(progress, 1f));
            if (!animated) {
                animatedProgress.force(this.progress);
            }
            invalidateSelf();
        }
    }

    public CachePill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48));
        layout.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(8), 0);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        progressDrawable = new StorageProgressDrawable(iconView);
        iconView.setImageDrawable(progressDrawable);

        textView = new PillAnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        if (lastKnownCacheSize.get() != -1 && !isRefreshDue()) {
            setData(lastKnownCacheSize.get(), lastKnownProgress, false);
        } else {
            iconView.setVisibility(GONE);
            textView.setVisibility(GONE);
        }
    }

    @Override
    public int getPillId() {
        return PillType.CACHE.id;
    }

    @Override
    public long getRefreshInterval() {
        return 3 * 60 * 1000L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onUpdateData(PillStackEvents.checkAndClearPendingUpdate(getPillId()));
        PillStackEvents.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PillStackEvents.removeListener(this);
    }

    @Override
    public void onPillStackSettingsChanged(int[] pillIds) {
        if (PillStackEvents.shouldUpdatePill(pillIds, getPillId())) {
            PillStackEvents.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        boolean never = lastKnownCacheSize.get() == -1;
        if (!(force || never || isRefreshDue()) || !calculating.compareAndSet(false, true)) {
            return;
        }
        if (force || never) {
            CacheControlActivity.resetCalculatedTotalSIze();
        }
        startLoading();
        ImageLoader.getInstance().checkMediaPaths(() ->
                CacheControlActivity.calculateTotalSize(cacheSize -> {
                    lastKnownCacheSize.set(cacheSize);
                    CacheControlActivity.getDeviceTotalSize((totalSize, freeSize) -> {
                        float progress = totalSize > 0 ? (totalSize - freeSize) / (float) totalSize : 0f;
                        lastKnownProgress = progress;
                        calculating.set(false);
                        setData(cacheSize, progress, true);
                    });
                }));
    }

    private void setData(long cacheSize, float progress, boolean animated) {
        stopLoading();
        String size = AndroidUtilities.formatFileSize(cacheSize);
        if (animated && (textView.getText() == null || !TextUtils.equals(textView.getText(), size) || textView.getVisibility() == GONE)) {
            animateSizeChange();
        }
        textView.setText(size, animated);
        progressDrawable.setProgress(progress, animated);
        iconView.setVisibility(VISIBLE);
        textView.setVisibility(VISIBLE);
        markDataUpdated();
    }

    @Override
    public void onPillClicked() {
        openCacheSettings();
    }

    private void openCacheSettings() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new CacheControlActivity());
        }
    }

    @Override
    public boolean onPillLongClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg2_data, LocaleController.getString(R.string.StorageUsage), this::openCacheSettings)
                .addGap()
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new PillStackSettingsActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();
        return true;
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (loading) {
            return;
        }
        super.drawableHotspotChanged(x, y);
        layout.drawableHotspotChanged(x - layout.getLeft(), y - layout.getTop());
    }

    @Override
    public void setPressed(boolean pressed) {
        if (loading) {
            pressed = false;
        }
        super.setPressed(pressed);
        layout.setPressed(pressed);
    }

    @Override
    public void updateColors() {
        int color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f),
                Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        progressDrawable.setColor(color);
        updateLoadingColors();
    }
}
