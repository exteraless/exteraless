package app.exteraless.plugins.ui.catalog;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.plugins.catalog.CatalogPlugin;

final class CatalogScreenshotsCell extends HorizontalScrollView implements Theme.Colorable {

    interface Delegate {
        void open(int index, CatalogScreenshotsCell source);
    }

    static final class Model {
        final CatalogPlugin plugin;
        final ArrayList<String> screenshots;

        Model(CatalogPlugin plugin, List<String> screenshots) {
            this.plugin = plugin;
            this.screenshots = new ArrayList<>(screenshots);
        }
    }

    static final class Factory extends UItem.UItemFactory<CatalogScreenshotsCell> {
        static { setup(new Factory()); }

        @Override
        public CatalogScreenshotsCell createView(Context context, RecyclerListView listView,
                                                  int currentAccount, int classGuid,
                                                  Theme.ResourcesProvider resourcesProvider) {
            return new CatalogScreenshotsCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((CatalogScreenshotsCell) view).setModel((Model) item.object,
                    (Delegate) item.object2);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && b != null && TextUtils.equals(a.plugin.slug, b.plugin.slug);
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && b != null && a.screenshots.equals(b.screenshots);
        }

        static UItem asGallery(Model model, Delegate delegate) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = model.plugin.slug.hashCode() ^ 0x6a11e9;
            item.object = model;
            item.object2 = delegate;
            return item;
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout content;
    private final ArrayList<BackupImageView> imageViews = new ArrayList<>();
    private Model model;
    private Delegate delegate;
    private final ArrayList<Runnable> pendingTimeouts = new ArrayList<>();

    private CatalogScreenshotsCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        addView(content, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    private void setModel(Model model, Delegate delegate) {
        this.model = model;
        this.delegate = delegate;
        for (Runnable pending : pendingTimeouts) {
            removeCallbacks(pending);
        }
        pendingTimeouts.clear();
        content.removeAllViews();
        imageViews.clear();
        int radius = Math.min(12, AppearanceConfig.sectionRadius());
        int total = model.screenshots.size();
        for (int i = 0; i < total; i++) {
            final int index = i;
            final String url = model.screenshots.get(i);
            FrameLayout frame = new FrameLayout(getContext());
            BackupImageView image = new BackupImageView(getContext());
            image.setAspectFit(false);
            image.setRoundRadius(AndroidUtilities.dp(radius));
            image.setFocusable(true);
            image.setContentDescription(LocaleController.formatString(
                    R.string.PluginCatalogScreenshotA11y, i + 1, total));
            image.setOnClickListener(v -> delegate.open(index, this));
            image.getImageReceiver().setCrossfadeWithOldImage(true);
            image.getImageReceiver().setCrossfadeDuration(180);
            if (SharedConfig.animationsEnabled()) {
                ScaleStateListAnimator.apply(image, 0.03f, 1.5f);
            }
            frame.addView(image, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            ImageView retry = new ImageView(getContext());
            retry.setImageResource(R.drawable.msg_retry);
            retry.setColorFilter(Theme.getColor(
                    Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider),
                    PorterDuff.Mode.SRC_IN);
            retry.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, resourcesProvider), 1));
            retry.setContentDescription(LocaleController.getString(R.string.Retry));
            retry.setVisibility(GONE);
            frame.addView(retry, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

            final LoadingDrawable loading;
            final Drawable placeholder;
            if (SharedConfig.animationsEnabled()) {
                loading = new LoadingDrawable();
                int base = Theme.getColor(
                        Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
                loading.setColors(
                        Theme.multAlpha(base, 0.05f),
                        Theme.multAlpha(base, 0.15f),
                        Theme.multAlpha(base, 0.1f),
                        Theme.multAlpha(base, 0.3f));
                loading.setRadiiDp(radius);
                placeholder = loading;
            } else {
                loading = null;
                placeholder = Theme.createRoundRectDrawable(AndroidUtilities.dp(radius),
                        Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
            }
            final boolean[] loaded = {false};
            Runnable[] load = new Runnable[1];
            load[0] = () -> image.setImage(ImageLocation.getForPath(url), "480_720",
                    placeholder, model.plugin);
            image.getImageReceiver().setDelegate((receiver, set, thumb, memCache) -> {
                if (set && !thumb) {
                    loaded[0] = true;
                    retry.setVisibility(GONE);
                    int bitmapWidth = receiver.getBitmapWidth();
                    int bitmapHeight = receiver.getBitmapHeight();
                    if (bitmapWidth > 0 && bitmapHeight > 0) {
                        int target = Math.max(AndroidUtilities.dp(96),
                                Math.min(AndroidUtilities.dp(324),
                                        (int) (AndroidUtilities.dp(216)
                                                * (float) bitmapWidth / bitmapHeight)));
                        android.view.ViewGroup.LayoutParams params = frame.getLayoutParams();
                        if (params != null && params.width != target) {
                            params.width = target;
                            frame.setLayoutParams(params);
                        }
                    }
                }
            });
            retry.setOnClickListener(v -> {
                loaded[0] = false;
                retry.setVisibility(GONE);
                if (loading != null) {
                    loading.resetDisappear();
                    loading.reset();
                }
                load[0].run();
            });
            load[0].run();
            Runnable timeout = () -> {
                if (!loaded[0] && frame.getParent() != null) {
                    if (loading != null) loading.disappear();
                    retry.setVisibility(VISIBLE);
                }
            };
            pendingTimeouts.add(timeout);
            postDelayed(timeout, 12_000);
            LinearLayout.LayoutParams params = LayoutHelper.createLinear(144, 216,
                    Gravity.CENTER_VERTICAL, i == 0 ? 16 : 8, 10,
                    i == total - 1 ? 16 : 0, 10);
            content.addView(frame, params);
            imageViews.add(image);
        }
    }

    BackupImageView getImageAt(int index) {
        if (index < 0 || index >= imageViews.size()) return null;
        BackupImageView image = imageViews.get(index);
        Rect visible = new Rect();
        return image.isShown() && image.getGlobalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0 ? image : null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                AndroidUtilities.dp(236), MeasureSpec.EXACTLY));
    }

    @Override
    public void updateColors() {
        if (model != null) setModel(model, delegate);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
