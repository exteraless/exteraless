package app.exteraless.plugins.ui.catalog;

import android.content.Context;
import android.graphics.PorterDuff;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProgressButton;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.plugins.catalog.CatalogConfig;
import app.exteraless.plugins.catalog.CatalogPlugin;

final class CatalogDetailsHeaderCell extends FrameLayout implements Theme.Colorable {

    interface Delegate {
        void install(CatalogPlugin plugin);
    }

    static final class Model {
        final CatalogPlugin plugin;
        final String cover;
        final CharSequence author;
        final CharSequence description;
        final CharSequence status;
        final CharSequence rating;
        final CharSequence downloads;
        final CharSequence category;
        final CharSequence action;
        final boolean actionEnabled;
        final boolean busy;
        final boolean installed;
        final boolean unsupported;

        Model(CatalogPlugin plugin, boolean allowRemoteMedia, CharSequence author,
              CharSequence description, CharSequence status, CharSequence rating,
              CharSequence downloads, CharSequence category, CharSequence action,
              boolean actionEnabled, boolean busy, boolean installed, boolean unsupported) {
            this.plugin = plugin;
            this.author = author;
            this.description = description;
            this.status = status;
            this.rating = rating;
            this.downloads = downloads;
            this.category = category;
            this.action = action;
            this.actionEnabled = actionEnabled;
            this.busy = busy;
            this.installed = installed;
            this.unsupported = unsupported;
            cover = allowRemoteMedia ? CatalogUi.firstTrustedScreenshot(plugin) : null;
        }

        boolean sameContent(Model other) {
            return other != null
                    && TextUtils.equals(plugin.name, other.plugin.name)
                    && TextUtils.equals(cover, other.cover)
                    && TextUtils.equals(author, other.author)
                    && TextUtils.equals(description, other.description)
                    && TextUtils.equals(status, other.status)
                    && TextUtils.equals(rating, other.rating)
                    && TextUtils.equals(downloads, other.downloads)
                    && TextUtils.equals(category, other.category)
                    && TextUtils.equals(action, other.action)
                    && actionEnabled == other.actionEnabled
                    && busy == other.busy
                    && installed == other.installed
                    && unsupported == other.unsupported;
        }
    }

    static final class Factory extends UItem.UItemFactory<CatalogDetailsHeaderCell> {
        static { setup(new Factory()); }

        @Override
        public CatalogDetailsHeaderCell createView(Context context, RecyclerListView listView,
                                                    int currentAccount, int classGuid,
                                                    Theme.ResourcesProvider resourcesProvider) {
            return new CatalogDetailsHeaderCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((CatalogDetailsHeaderCell) view).setModel((Model) item.object,
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
            return a != null && a.sameContent(b);
        }

        static UItem asHeader(Model model, Delegate delegate) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = model.plugin.slug.hashCode();
            item.object = model;
            item.object2 = delegate;
            return item;
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout coverContainer;
    private final BackupImageView coverView;
    private final LinearLayout fallbackView;
    private final View fallbackBanner;
    private final AvatarDrawable pluginPlaceholder = new AvatarDrawable();
    private Runnable pendingCoverShown;
    private final TextView nameView;
    private final TextView descriptionView;
    private final TextView statusView;
    private final ImageView ratingIconView;
    private final TextView ratingView;
    private final TextView downloadsView;
    private final TextView categoryView;
    private final ProgressButton actionView;
    private Model model;
    private Delegate delegate;
    private String boundCover;
    private android.graphics.drawable.Drawable verifiedBadge;

    private CatalogDetailsHeaderCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        coverContainer = new FrameLayout(context);
        coverContainer.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(AppearanceConfig.sectionRadius()),
                fallbackBackground()));
        coverContainer.setClipToOutline(true);
        root.addView(coverContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 232));

        coverView = new BackupImageView(context);
        coverView.setAspectFit(false);
        coverView.getImageReceiver().setCrossfadeWithOldImage(true);
        coverView.getImageReceiver().setCrossfadeDuration(200);
        coverView.setRoundRadius(AndroidUtilities.dp(AppearanceConfig.sectionRadius()));
        coverContainer.addView(coverView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fallbackView = new LinearLayout(context);
        fallbackView.setGravity(Gravity.CENTER);
        fallbackView.setOrientation(LinearLayout.VERTICAL);
        coverContainer.addView(fallbackView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        coverView.getImageReceiver().setDelegate((receiver, set, thumb, memCache) -> {
            boolean loaded = model != null && !TextUtils.isEmpty(model.cover)
                    && set && !thumb;
            coverView.setVisibility(loaded ? VISIBLE : GONE);
            fallbackView.setVisibility(loaded ? GONE : VISIBLE);
            if (loaded && pendingCoverShown != null) {
                Runnable pending = pendingCoverShown;
                pendingCoverShown = null;
                pending.run();
            }
        });

        fallbackBanner = new View(context) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                int width = getWidth(), height = getHeight();
                if (width <= 0) return;
                pluginPlaceholder.setRoundRadius(AndroidUtilities.dp(1));
                pluginPlaceholder.setBounds(0, (height - width) / 2, width,
                        (height - width) / 2 + width);
                pluginPlaceholder.draw(canvas);
            }
        };
        fallbackView.addView(fallbackBanner, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        nameView = text(context, 23, Theme.key_windowBackgroundWhiteBlackText, true);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        content.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT));

        descriptionView = text(context, 15, Theme.key_windowBackgroundWhiteBlackText, false);
        descriptionView.setMaxLines(4);
        descriptionView.setEllipsize(TextUtils.TruncateAt.END);
        descriptionView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        content.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        statusView = text(context, 13, Theme.key_featuredStickers_addButton, true);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        LinearLayout metrics = new LinearLayout(context);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(metrics, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                32, 0, 9, 0, 0));
        ratingIconView = metricIcon(context, R.drawable.ic_rating_star_filled);
        metrics.addView(ratingIconView,
                LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL));
        ratingView = metricText(context);
        metrics.addView(ratingView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 12, 0));
        metrics.addView(metricIcon(context, R.drawable.msg_download),
                LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL));
        downloadsView = metricText(context);
        metrics.addView(downloadsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 12, 0));
        categoryView = metricText(context);
        categoryView.setSingleLine(true);
        categoryView.setEllipsize(TextUtils.TruncateAt.END);
        metrics.addView(categoryView, LayoutHelper.createLinear(0,
                LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        actionView = new ProgressButton(context);
        actionView.setGravity(Gravity.CENTER);
        actionView.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        actionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        actionView.setTypeface(AndroidUtilities.bold());
        actionView.setMinHeight(AndroidUtilities.dp(48));
        actionView.setTextColor(Theme.getColor(
                Theme.key_featuredStickers_buttonText, resourcesProvider));
        content.addView(actionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                48, 0, 12, 0, 0));
        actionView.setOnClickListener(v -> {
            if (model != null && model.actionEnabled && delegate != null) {
                delegate.install(model.plugin);
            }
        });
        if (SharedConfig.animationsEnabled()) {
            ScaleStateListAnimator.apply(actionView, 0.02f, 1.2f);
        }
    }

    private TextView text(Context context, int size, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(colorKey, resourcesProvider));
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private TextView metricText(Context context) {
        TextView view = text(context, 13, Theme.key_windowBackgroundWhiteGrayText, false);
        view.setSingleLine(true);
        return view;
    }

    private ImageView metricIcon(Context context, int icon) {
        ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setColorFilter(Theme.getColor(
                Theme.key_windowBackgroundWhiteGrayText, resourcesProvider), PorterDuff.Mode.SRC_IN);
        return view;
    }

    View getHeroCoverView() {
        return coverContainer;
    }

    void runWhenCoverShown(Runnable runnable) {
        if (model == null || TextUtils.isEmpty(model.cover)
                || coverView.getVisibility() == VISIBLE) {
            runnable.run();
            return;
        }
        pendingCoverShown = runnable;
    }

    View getHeroNameView() {
        return nameView;
    }

    private void setModel(Model model, Delegate delegate) {
        this.model = model;
        this.delegate = delegate;
        int sectionRadius = AndroidUtilities.dp(AppearanceConfig.sectionRadius());
        coverContainer.setBackground(Theme.createRoundRectDrawable(sectionRadius,
                TextUtils.isEmpty(model.cover)
                        ? Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)
                        : fallbackBackground()));
        coverView.setRoundRadius(sectionRadius);
        pluginPlaceholder.setInfo(model.plugin.id, model.plugin.name, null);
        fallbackBanner.invalidate();
        boolean hasCover = !TextUtils.isEmpty(model.cover);
        if (!TextUtils.equals(boundCover, model.cover)) {
            boundCover = model.cover;
            coverView.setVisibility(GONE);
            fallbackView.setVisibility(VISIBLE);
            if (hasCover) {
                coverView.setImage(ImageLocation.getForPath(model.cover), "1280_720",
                        ImageLocation.getForPath(model.cover), "160_160",
                        null, model.plugin);
            } else {
                coverView.setImageDrawable(null);
            }
        }

        nameView.setText(model.plugin.name);
        if (model.plugin.verified && verifiedBadge == null) {
            verifiedBadge = CatalogUi.verifiedBadge(getContext(), resourcesProvider);
        }
        nameView.setCompoundDrawablesRelative(null, null,
                model.plugin.verified ? verifiedBadge : null, null);
        descriptionView.setText(model.description);
        descriptionView.setVisibility(TextUtils.isEmpty(model.description) ? GONE : VISIBLE);
        statusView.setText(model.status);
        statusView.setVisibility(TextUtils.isEmpty(model.status) ? GONE : VISIBLE);
        statusView.setTextColor(Theme.getColor(model.unsupported
                ? Theme.key_text_RedRegular : Theme.key_featuredStickers_addButton,
                resourcesProvider));
        CatalogUi.applyRating(ratingIconView, ratingView, model.plugin, model.rating,
                resourcesProvider);
        downloadsView.setText(model.downloads);
        categoryView.setText(model.category);
        categoryView.setVisibility(TextUtils.isEmpty(model.category) ? GONE : VISIBLE);

        SpannableStringBuilder actionText = new SpannableStringBuilder();
        actionText.append(".  ").append(model.action);
        int actionIcon = model.installed ? R.drawable.msg_check : R.drawable.msg_download;
        actionText.setSpan(new ColoredImageSpan(ContextCompat.getDrawable(
                getContext(), actionIcon)), 0, 1, 0);
        actionView.setText(actionText);
        actionView.setEnabled(model.actionEnabled);
        actionView.setAlpha(model.actionEnabled ? 1f : 0.65f);
        actionView.setDrawProgress(model.busy, SharedConfig.animationsEnabled());
        int actionColor = model.installed
                ? Theme.getColor(Theme.key_windowBackgroundWhiteGreenText, resourcesProvider)
                : model.unsupported
                ? Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)
                : Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        actionView.setTextColor(model.installed || model.unsupported ? actionColor
                : Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        int background = model.installed || model.unsupported
                ? Theme.multAlpha(actionColor, 0.12f) : actionColor;
        int pressed = model.actionEnabled
                ? Theme.blendOver(background, Theme.multAlpha(0xff000000, 0.10f))
                : background;
        actionView.setProgressColor(Theme.getColor(
                Theme.key_featuredStickers_buttonText, resourcesProvider));
        actionView.setBackgroundRoundRect(background, pressed, 24);
        setContentDescription(model.plugin.name + ". " + model.author + ". "
                + model.description + ". " + model.status + ". " + model.rating + ". "
                + model.downloads + ". " + model.category + ". " + model.action);
    }

    private int fallbackBackground() {
        return Theme.blendOver(
                Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                Theme.multAlpha(Theme.getColor(
                        Theme.key_featuredStickers_addButton, resourcesProvider), 0.08f));
    }

    @Override
    public void updateColors() {
        verifiedBadge = null;
        nameView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        descriptionView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        int secondary = Theme.getColor(
                Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        ratingView.setTextColor(secondary);
        downloadsView.setTextColor(secondary);
        categoryView.setTextColor(secondary);
        ratingIconView.setColorFilter(secondary, PorterDuff.Mode.SRC_IN);
        if (model != null) setModel(model, delegate);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
