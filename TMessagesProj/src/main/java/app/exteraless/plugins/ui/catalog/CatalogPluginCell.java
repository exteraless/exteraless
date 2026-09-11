package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.regex.Pattern;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.plugins.catalog.CatalogConfig;
import app.exteraless.plugins.catalog.CatalogPlugin;
import app.exteraless.plugins.catalog.CatalogUpdateMatch;

final class CatalogPluginCell extends FrameLayout implements Theme.Colorable {

    private static final Pattern MARKDOWN_PREFIX = Pattern.compile(
            "(?m)^\\s{0,3}(?:#{1,6}\\s+|>\\s?|[-+*]\\s+|\\d+[.)]\\s+)");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    static final class Model {
        final CatalogPlugin plugin;
        final String cover;
        final String authorAvatar;
        final CharSequence author;
        final CharSequence version;
        final CharSequence description;
        final CharSequence status;
        final CharSequence rating;
        final CharSequence downloads;
        final CharSequence category;
        final CharSequence state;
        final boolean installed;
        final boolean unsupported;

        Model(CatalogPlugin plugin, CatalogUpdateMatch match,
              boolean showUpdates, boolean developerMode,
              boolean allowRemoteMedia, CharSequence categoryName) {
            this.plugin = plugin;
            cover = allowRemoteMedia ? CatalogUi.firstTrustedScreenshot(plugin) : null;
            authorAvatar = allowRemoteMedia
                    && CatalogConfig.isTrustedOfficialMediaUrl(plugin.authorImage)
                    ? plugin.authorImage : null;
            author = TextUtils.isEmpty(plugin.author)
                    ? getString(R.string.PluginCatalogAuthorUnknown) : plugin.author;
            version = TextUtils.isEmpty(plugin.version) ? "" : "v" + plugin.version;
            description = plainSummary(TextUtils.isEmpty(plugin.shortDescription)
                    ? plugin.description : plugin.shortDescription, 180);

            StringBuilder statusBuilder = new StringBuilder();
            if (plugin.verified) append(statusBuilder,
                    getString(R.string.PluginCatalogVerifiedShort));
            if (plugin.featured) append(statusBuilder,
                    getString(R.string.PluginCatalogFeatured));
            if (Boolean.TRUE.equals(plugin.exteralessCompatible)) {
                append(statusBuilder, getString(R.string.PluginCatalogCompatible));
            } else if (Boolean.FALSE.equals(plugin.exteralessCompatible)) {
                append(statusBuilder, getString(R.string.PluginCatalogUnsupported));
            } else {
                append(statusBuilder,
                        getString(R.string.PluginCatalogCompatibilityUnknown));
            }
            status = statusBuilder;

            rating = plugin.ratingCount > 0
                    ? LocaleController.formatString(R.string.PluginCatalogRating,
                    String.format(LocaleController.getInstance().getCurrentLocale(),
                            "%.1f", plugin.rating), plugin.ratingCount)
                    : getString(R.string.PluginCatalogNoRatings);
            downloads = LocaleController.formatShortNumber(
                    (int) Math.min(Integer.MAX_VALUE, plugin.downloadCount), null);
            category = categoryName;

            boolean update = CatalogUi.isUpdate(match, showUpdates);
            installed = CatalogUi.isInstalled(match, showUpdates);
            unsupported = Boolean.FALSE.equals(plugin.exteralessCompatible) && !developerMode;
            state = unsupported ? getString(R.string.PluginCatalogUnsupported)
                    : update ? getString(R.string.PluginCatalogUpdate)
                    : installed ? getString(R.string.PluginCatalogInstalled) : "";
        }

        private static void append(StringBuilder builder, CharSequence value) {
            if (TextUtils.isEmpty(value)) return;
            if (builder.length() > 0) builder.append(" · ");
            builder.append(value);
        }

        boolean sameContent(Model other) {
            return other != null
                    && TextUtils.equals(plugin.name, other.plugin.name)
                    && plugin.verified == other.plugin.verified
                    && TextUtils.equals(cover, other.cover)
                    && TextUtils.equals(authorAvatar, other.authorAvatar)
                    && TextUtils.equals(author, other.author)
                    && TextUtils.equals(version, other.version)
                    && TextUtils.equals(description, other.description)
                    && TextUtils.equals(status, other.status)
                    && TextUtils.equals(rating, other.rating)
                    && TextUtils.equals(downloads, other.downloads)
                    && TextUtils.equals(category, other.category)
                    && TextUtils.equals(state, other.state)
                    && installed == other.installed
                    && unsupported == other.unsupported;
        }
    }

    static final class Factory extends UItem.UItemFactory<CatalogPluginCell> {
        static { setup(new Factory()); }

        @Override
        public CatalogPluginCell createView(Context context, RecyclerListView listView,
                                             int currentAccount, int classGuid,
                                             Theme.ResourcesProvider resourcesProvider) {
            return new CatalogPluginCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((CatalogPluginCell) view).setModel((Model) item.object, divider);
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

        static UItem asPlugin(Model model) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = model.plugin.slug.hashCode() | Integer.MIN_VALUE;
            item.object = model;
            return item;
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final BackupImageView coverView;
    private final AvatarDrawable pluginPlaceholder = new AvatarDrawable();
    private final FrameLayout authorBadge;
    private final BackupImageView authorAvatarView;
    private final AvatarDrawable authorPlaceholder = new AvatarDrawable();
    private final TextView nameView;
    private final TextView descriptionView;
    private final ImageView ratingIconView;
    private final TextView ratingView;
    private final TextView downloadsView;
    private final TextView categoryView;
    private final TextView stateView;
    private Model model;
    private boolean needDivider;
    private android.graphics.drawable.Drawable verifiedBadge;

    private CatalogPluginCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        coverView = new BackupImageView(context);
        coverView.setAspectFit(false);
        coverView.getImageReceiver().setCrossfadeWithOldImage(true);
        coverView.getImageReceiver().setCrossfadeDuration(180);
        addView(coverView, LayoutHelper.createFrameRelatively(
                56, 56, Gravity.START | Gravity.TOP, 12, 20, 0, 0));

        authorBadge = new FrameLayout(context);
        authorAvatarView = new BackupImageView(context);
        authorAvatarView.setRoundRadius(AndroidUtilities.dp(9));
        authorAvatarView.getImageReceiver().setCrossfadeWithOldImage(true);
        authorAvatarView.getImageReceiver().setCrossfadeDuration(180);
        authorBadge.addView(authorAvatarView, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
        addView(authorBadge, LayoutHelper.createFrameRelatively(
                22, 22, Gravity.START | Gravity.TOP, 50, 58, 0, 0));

        nameView = text(context, 16, Theme.key_windowBackgroundWhiteBlackText, true);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setCompoundDrawablePadding(AndroidUtilities.dp(4));
        addView(nameView, LayoutHelper.createFrameRelatively(
                LayoutHelper.WRAP_CONTENT, 22, Gravity.START | Gravity.TOP,
                80, 8, 12, 0));

        stateView = text(context, 12, Theme.key_featuredStickers_addButton, true);
        stateView.setSingleLine(true);
        stateView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        addView(stateView, LayoutHelper.createFrameRelatively(
                104, 22, Gravity.END | Gravity.TOP, 0, 8, 12, 0));

        descriptionView = text(context, 13, Theme.key_windowBackgroundWhiteGrayText, false);
        descriptionView.setMaxLines(2);
        descriptionView.setEllipsize(TextUtils.TruncateAt.END);
        addView(descriptionView, LayoutHelper.createFrameRelatively(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.START | Gravity.TOP, 80, 31, 12, 0));

        LinearLayout metrics = new LinearLayout(context);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        addView(metrics, LayoutHelper.createFrameRelatively(
                LayoutHelper.MATCH_PARENT, 22, Gravity.START | Gravity.TOP,
                80, 67, 12, 0));

        ratingIconView = metricIcon(context, R.drawable.ic_rating_star_filled);
        metrics.addView(ratingIconView, LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL));
        ratingView = metricText(context);
        ratingView.setMaxWidth(AndroidUtilities.dp(106));
        metrics.addView(ratingView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 3, 0, 9, 0));

        metrics.addView(metricIcon(context, R.drawable.msg_download),
                LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL));
        downloadsView = metricText(context);
        metrics.addView(downloadsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 3, 0, 9, 0));

        categoryView = metricText(context);
        categoryView.setSingleLine(true);
        categoryView.setEllipsize(TextUtils.TruncateAt.END);
        metrics.addView(categoryView, LayoutHelper.createLinear(0,
                LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

    }

    static CharSequence plainSummary(CharSequence source, int maxLength) {
        if (TextUtils.isEmpty(source) || maxLength <= 0) return "";
        String value = source.toString();
        if (value.length() > 2_000) value = value.substring(0, 2_000);
        value = MARKDOWN_PREFIX.matcher(value).replaceAll("");
        value = MARKDOWN_IMAGE.matcher(value).replaceAll("$1");
        value = MARKDOWN_LINK.matcher(value).replaceAll("$1");
        value = value
                .replace("`", "")
                .replace("*", "")
                .replace("_", "")
                .replace("~", "");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).trim() + "…";
    }

    private TextView text(Context context, int size, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(colorKey, resourcesProvider));
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private TextView metricText(Context context) {
        TextView view = text(context, 12, Theme.key_windowBackgroundWhiteGrayText, false);
        view.setSingleLine(true);
        return view;
    }

    private ImageView metricIcon(Context context, int icon) {
        ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setColorFilter(Theme.getColor(
                Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.SRC_IN);
        return view;
    }

    View getHeroCoverView() {
        return coverView;
    }

    View getHeroNameView() {
        return nameView;
    }

    private void setModel(Model model, boolean divider) {
        this.model = model;
        needDivider = divider;
        setWillNotDraw(!divider);

        int mediaRadius = AndroidUtilities.dp(Math.min(12, AppearanceConfig.sectionRadius()));
        pluginPlaceholder.setInfo(model.plugin.id, model.plugin.name, null);
        coverView.setRoundRadius(mediaRadius);
        if (TextUtils.isEmpty(model.cover)) {
            coverView.setImageDrawable(pluginPlaceholder);
        } else {
            coverView.setImage(ImageLocation.getForPath(model.cover), "160_160",
                    pluginPlaceholder, model.plugin);
        }

        authorPlaceholder.setInfo(model.plugin.id ^ 0x5f3759df,
                model.author == null ? "" : model.author.toString(), null);
        authorBadge.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(22),
                Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        if (TextUtils.isEmpty(model.authorAvatar)) {
            authorAvatarView.setImageDrawable(authorPlaceholder);
        } else {
            authorAvatarView.setImage(ImageLocation.getForPath(model.authorAvatar),
                    "48_48", authorPlaceholder, model.plugin);
        }

        nameView.setText(model.plugin.name);
        if (model.plugin.verified && verifiedBadge == null) {
            verifiedBadge = CatalogUi.verifiedBadge(getContext(), resourcesProvider);
        }
        nameView.setCompoundDrawablesRelative(null, null,
                model.plugin.verified ? verifiedBadge : null, null);
        stateView.setText(model.state);
        stateView.setVisibility(TextUtils.isEmpty(model.state) ? GONE : VISIBLE);
        stateView.setTextColor(Theme.getColor(model.unsupported
                ? Theme.key_text_RedRegular
                : model.installed ? Theme.key_windowBackgroundWhiteGreenText
                : Theme.key_featuredStickers_addButton, resourcesProvider));
        FrameLayout.LayoutParams nameParams = (FrameLayout.LayoutParams) nameView.getLayoutParams();
        int endMargin = AndroidUtilities.dp(TextUtils.isEmpty(model.state) ? 12 : 124);
        if (LocaleController.isRTL) {
            nameParams.leftMargin = endMargin;
            nameParams.rightMargin = AndroidUtilities.dp(80);
        } else {
            nameParams.leftMargin = AndroidUtilities.dp(80);
            nameParams.rightMargin = endMargin;
        }
        nameView.setLayoutParams(nameParams);
        descriptionView.setText(TextUtils.isEmpty(model.description)
                ? (TextUtils.isEmpty(model.version)
                        ? model.author : model.author + " · " + model.version)
                : model.description);
        CatalogUi.applyRating(ratingIconView, ratingView, model.plugin, model.rating,
                resourcesProvider);
        downloadsView.setText(model.downloads);
        categoryView.setText(model.category);
        categoryView.setVisibility(TextUtils.isEmpty(model.category) ? GONE : VISIBLE);

        setContentDescription(model.plugin.name + ". " + model.author + ". "
                + model.description + ". " + model.status + ". " + model.rating + ". "
                + LocaleController.formatPluralString("PluginCatalogDownloads",
                (int) Math.min(Integer.MAX_VALUE, model.plugin.downloadCount)) + ". "
                + model.category + ". " + model.state);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            float start = AndroidUtilities.dp(80);
            if (LocaleController.isRTL) {
                canvas.drawLine(0, getHeight() - 1, getWidth() - start,
                        getHeight() - 1, Theme.dividerPaint);
            } else {
                canvas.drawLine(start, getHeight() - 1, getWidth(),
                        getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(
                AndroidUtilities.dp(96) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    public void updateColors() {
        verifiedBadge = null;
        nameView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        int secondary = Theme.getColor(
                Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        descriptionView.setTextColor(secondary);
        ratingView.setTextColor(secondary);
        downloadsView.setTextColor(secondary);
        categoryView.setTextColor(secondary);
        if (model != null) {
            stateView.setTextColor(Theme.getColor(model.unsupported
                    ? Theme.key_text_RedRegular
                    : model.installed ? Theme.key_windowBackgroundWhiteGreenText
                    : Theme.key_featuredStickers_addButton, resourcesProvider));
        }
        ratingIconView.setColorFilter(Theme.getColor(
                Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider),
                PorterDuff.Mode.SRC_IN);
        if (model != null) setModel(model, needDivider);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
