package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.PhotoViewer;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.catalog.CatalogCall;
import app.exteraless.plugins.catalog.CatalogConfig;
import app.exteraless.plugins.catalog.CatalogException;
import app.exteraless.plugins.catalog.CatalogInstallPlanItem;
import app.exteraless.plugins.catalog.CatalogPlugin;
import app.exteraless.plugins.catalog.CatalogRepository;
import app.exteraless.plugins.catalog.CatalogUpdateMatch;
import app.exteraless.plugins.catalog.CatalogVersion;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

public class PluginCatalogDetailsActivity extends BasePreferencesActivity
        implements CatalogInstallCoordinator.Delegate {

    private enum Compatibility { COMPATIBLE, UNSUPPORTED, UNKNOWN }

    private static final int ID_AUTHOR = 2;
    private static final int ID_SOURCE = 3;
    private static final int ID_GITHUB = 4;
    private static final int ID_DOCUMENTATION = 5;
    private static final int ID_RETRY = 6;
    private static final int ID_ENABLE_ENGINE = 7;
    private static final int ID_RETRY_VERSIONS = 8;
    private static final int ID_RETRY_PLAN = 9;
    private static final int MENU_SHARE = 10;
    private static final int ID_SECURITY = 11;

    private final String slug;
    private final CharSequence categoryName;
    private CatalogRepository repository;
    private CatalogInstallCoordinator installer;
    private CatalogCall detailCall;
    private CatalogCall versionsCall;
    private CatalogCall planCall;
    private CatalogPlugin plugin;
    private List<CatalogVersion> versions = Collections.emptyList();
    private List<CatalogInstallPlanItem> installPlan = Collections.emptyList();
    private CatalogException loadError;
    private CatalogException versionsError;
    private CatalogException planError;
    private boolean busy;
    private boolean loading;
    private long loadGeneration;
    private long supplementalGeneration;
    private int supplementalPending;
    private boolean destroyed;
    private ActionBarMenuItem shareItem;
    private CatalogPlugin heroPlugin;
    private RectF heroCoverFrom;
    private RectF heroNameFrom;
    private android.graphics.Bitmap heroCoverBitmap;
    private Runnable heroSwap;
    private String authorImage;

    public PluginCatalogDetailsActivity(String slug) {
        this(slug, null);
    }

    public PluginCatalogDetailsActivity(String slug, CharSequence categoryName) {
        this.slug = slug;
        this.categoryName = categoryName;
    }

    public PluginCatalogDetailsActivity setHeroSource(CatalogPlugin listPlugin,
                                                      RectF coverBounds, RectF nameBounds,
                                                      android.graphics.Bitmap coverBitmap) {
        heroPlugin = listPlugin;
        heroCoverFrom = coverBounds;
        heroNameFrom = nameBounds;
        heroCoverBitmap = coverBitmap;
        return this;
    }

    @Override
    public AnimatorSet onCustomTransitionAnimation(boolean isOpen, Runnable callback) {
        if (!isOpen || plugin == null || heroCoverFrom == null || heroNameFrom == null
                || !(fragmentView instanceof FrameLayout)
                || !(fragmentView.getParent() instanceof View)
                || !SharedConfig.animationsEnabled()) {
            return null;
        }
        final FrameLayout root = (FrameLayout) fragmentView;
        ((View) root.getParent()).setTranslationX(0);

        final Context context = root.getContext();
        final int backgroundColor = Theme.getColor(
                Theme.key_windowBackgroundGray, getResourceProvider());
        final android.graphics.drawable.Drawable originalBackground = root.getBackground();

        final AvatarDrawable placeholder = new AvatarDrawable();
        placeholder.setInfo(plugin.id, plugin.name, null);
        placeholder.setRoundRadius(AndroidUtilities.dp(1));
        final int coverRadiusFrom = AndroidUtilities.dp(Math.min(12,
                AppearanceConfig.sectionRadius()));
        final int coverRadiusTo = AndroidUtilities.dp(AppearanceConfig.sectionRadius());
        final float[] heroRadius = { coverRadiusFrom };
        final View heroCover;
        if (heroCoverBitmap != null && !heroCoverBitmap.isRecycled()) {
            BackupImageView image = new BackupImageView(context);
            image.setImageBitmap(heroCoverBitmap);
            heroCover = image;
        } else {
            heroCover = new View(context) {
                @Override
                protected void onDraw(android.graphics.Canvas canvas) {
                    int width = getWidth(), height = getHeight();
                    if (width <= 0) return;
                    placeholder.setBounds(0, (height - width) / 2, width,
                            (height - width) / 2 + width);
                    placeholder.draw(canvas);
                }
            };
        }
        heroCover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Math.max(1f, heroRadius[0]));
            }
        });
        heroCover.setClipToOutline(true);
        final TextView heroName = new TextView(context);
        heroName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        heroName.setTypeface(AndroidUtilities.bold());
        heroName.setSingleLine(true);
        heroName.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        heroName.setText(plugin.name);
        heroName.setPivotX(0f);
        heroName.setPivotY(0f);

        final FrameLayout overlay = new FrameLayout(context);
        final FrameLayout.LayoutParams coverParams = new FrameLayout.LayoutParams(
                (int) heroCoverFrom.width(), (int) heroCoverFrom.height());
        overlay.addView(heroCover, coverParams);
        overlay.addView(heroName, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        final RectF coverStart = new RectF();
        final RectF coverTo = new RectF();
        final RectF nameStart = new RectF();
        final RectF nameTo = new RectF();
        final View[] realTargets = new View[2];
        final float nameTargetScale = 23f / 16f;

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float flight = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(t);
            float content = Math.max(0f, Math.min(1f, (t - 0.1f) / 0.55f));
            float contentEase = CubicBezierInterpolator.EASE_OUT_QUINT
                    .getInterpolation(content);

            root.setBackgroundColor(Theme.multAlpha(backgroundColor, contentEase));
            if (listView != null) {
                listView.setAlpha(contentEase);
                listView.setTranslationY(AndroidUtilities.dp(24) * (1f - contentEase));
            }
            if (actionBar != null) {
                actionBar.setAlpha(Math.max(0f, Math.min(1f, (t - 0.35f) / 0.45f)));
            }

            int coverWidth = (int) AndroidUtilities.lerp(
                    coverStart.width(), coverTo.width(), flight);
            int coverHeight = (int) AndroidUtilities.lerp(
                    coverStart.height(), coverTo.height(), flight);
            if (coverParams.width != coverWidth || coverParams.height != coverHeight) {
                coverParams.width = Math.max(1, coverWidth);
                coverParams.height = Math.max(1, coverHeight);
                heroCover.setLayoutParams(coverParams);
            }
            heroCover.setTranslationX(AndroidUtilities.lerp(
                    coverStart.left, coverTo.left, flight));
            heroCover.setTranslationY(AndroidUtilities.lerp(
                    coverStart.top, coverTo.top, flight));
            heroRadius[0] = AndroidUtilities.lerp(coverRadiusFrom, coverRadiusTo, flight);
            heroCover.invalidateOutline();

            heroName.setTranslationX(AndroidUtilities.lerp(
                    nameStart.left, nameTo.left, flight));
            heroName.setTranslationY(AndroidUtilities.lerp(
                    nameStart.top, nameTo.top, flight));
            float nameScale = AndroidUtilities.lerp(1f, nameTargetScale, flight);
            heroName.setScaleX(nameScale);
            heroName.setScaleY(nameScale);
        });
        final boolean[] preStateApplied = { false };
        final Runnable restore = () -> {
            if (!preStateApplied[0]) return;
            preStateApplied[0] = false;
            root.setBackground(originalBackground);
            if (listView != null) {
                listView.setAlpha(1f);
                listView.setTranslationY(0f);
            }
            if (actionBar != null) actionBar.setAlpha(1f);
        };
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                restore.run();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                restore.run();
                Runnable[] timeout = new Runnable[1];
                heroSwap = () -> {
                    if (heroSwap == null) return;
                    heroSwap = null;
                    AndroidUtilities.cancelRunOnUIThread(timeout[0]);
                    heroCoverBitmap = null;
                    for (View target : realTargets) {
                        if (target != null) target.setAlpha(1f);
                    }
                    root.removeView(overlay);
                };
                timeout[0] = () -> {
                    if (heroSwap != null) heroSwap.run();
                };
                CatalogDetailsHeaderCell header = findHeaderCell();
                if (header != null && !destroyed) {
                    header.runWhenCoverShown(() -> {
                        if (heroSwap != null) heroSwap.run();
                    });
                    AndroidUtilities.runOnUIThread(timeout[0], 700);
                } else {
                    heroSwap.run();
                }
                callback.run();
            }
        });

        final AnimatorSet set = new AnimatorSet();
        set.playTogether(animator);
        set.setDuration(360);

        final int[] attempts = { 0 };
        root.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (set.isStarted() || destroyed) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
                CatalogDetailsHeaderCell header = findHeaderCell();
                if ((header == null || header.getWidth() == 0 || root.getWidth() == 0)
                        && attempts[0]++ < 8) {
                    root.invalidate();
                    return true;
                }
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] location = new int[2];
                root.getLocationInWindow(location);
                float rootX = location[0], rootY = location[1];
                coverStart.set(heroCoverFrom.left - rootX, heroCoverFrom.top - rootY,
                        heroCoverFrom.right - rootX, heroCoverFrom.bottom - rootY);
                nameStart.set(heroNameFrom.left - rootX, heroNameFrom.top - rootY,
                        heroNameFrom.right - rootX, heroNameFrom.bottom - rootY);
                if (header != null && header.getWidth() > 0) {
                    View coverTarget = header.getHeroCoverView();
                    View nameTarget = header.getHeroNameView();
                    coverTo.set(viewBounds(coverTarget, rootX, rootY));
                    nameTo.set(viewBounds(nameTarget, rootX, rootY));
                    realTargets[0] = coverTarget;
                    realTargets[1] = nameTarget;
                    coverTarget.setAlpha(0f);
                    nameTarget.setAlpha(0f);
                } else {
                    coverTo.set(coverStart);
                    nameTo.set(nameStart);
                }
                heroCover.setTranslationX(coverStart.left);
                heroCover.setTranslationY(coverStart.top);
                heroName.setTranslationX(nameStart.left);
                heroName.setTranslationY(nameStart.top);
                preStateApplied[0] = true;
                root.setBackground(null);
                if (listView != null) listView.setAlpha(0f);
                if (actionBar != null) actionBar.setAlpha(0f);
                root.addView(overlay, LayoutHelper.createFrame(
                        LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                set.start();
                return true;
            }
        });
        return set;
    }

    private CatalogDetailsHeaderCell findHeaderCell() {
        if (listView == null) return null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof CatalogDetailsHeaderCell) {
                return (CatalogDetailsHeaderCell) child;
            }
        }
        return null;
    }

    private RectF viewBounds(View view, float rootX, float rootY) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return new RectF(location[0] - rootX, location[1] - rootY,
                location[0] - rootX + view.getWidth(),
                location[1] - rootY + view.getHeight());
    }

    @Override
    public String getTitle() {
        return plugin == null ? getString(R.string.PluginCatalogDetailsTitle) : plugin.name;
    }

    @Override
    public View createView(Context context) {
        repository = new CatalogRepository(context);
        installer = new CatalogInstallCoordinator(getParentActivity(), repository,
                installLabels(), this);
        if (plugin == null && heroPlugin != null) {
            plugin = heroPlugin;
        }
        if (plugin != null && authorImage == null) {
            authorImage = plugin.authorImage;
        }
        View view = super.createView(context);
        shareItem = actionBar.createMenu().addItem(MENU_SHARE, R.drawable.msg_share);
        shareItem.setContentDescription(getString(R.string.LinkActionShare));
        shareItem.setVisibility(plugin != null ? View.VISIBLE : View.GONE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SHARE) {
                    sharePlugin();
                }
            }
        });
        if (listView != null) {
            listView.setSections(true);
            scrollDetailsToTop();
            listView.post(this::scrollDetailsToTop);
        }
        if (PluginsController.getInstance().isEngineEnabled()) {
            load();
        } else {
            updateRows(false);
        }
        return view;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!PluginsController.getInstance().isEngineEnabled()) {
            items.add(UItem.asTopView(getString(R.string.PluginCatalogEngineOffTitle),
                    getString(R.string.PluginCatalogEngineOffText), R.raw.error));
            items.add(UItem.asButton(ID_ENABLE_ENGINE, R.drawable.msg_settings,
                    getString(R.string.PluginCatalogEnableEngine)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        if (loading && plugin == null) {
            items.add(UItem.asFlicker(9000,
                    FlickerLoadingView.PLUGIN_CATALOG_DETAILS_TYPE));
            items.add(UItem.asShadow(null));
            return;
        }
        if (loadError != null && plugin == null) {
            items.add(UItem.asTopView(getString(R.string.PluginCatalogLoadError),
                    errorText(loadError), R.raw.utyan_empty2));
            items.add(UItem.asButton(ID_RETRY, R.drawable.msg_retry,
                    getString(R.string.Retry)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        if (plugin == null) {
            return;
        }

        Compatibility compatibility = compatibility(plugin);
        CatalogUpdateMatch match = repository.matchInstalled(plugin,
                CatalogUi.installedVersions());
        boolean showUpdates = PluginCatalogActivity.autoUpdateCheckEnabled(getContext());
        boolean installed = CatalogUi.isInstalled(match, showUpdates);
        boolean update = CatalogUi.isUpdate(match, showUpdates);
        String action = busy ? getString(R.string.PluginCatalogInstalling)
                : installed ? getString(R.string.PluginCatalogInstalled)
                : update ? getString(R.string.PluginCatalogUpdate)
                : getString(R.string.PluginCatalogInstall);
        boolean actionEnabled = !busy && !installed
                && (compatibility != Compatibility.UNSUPPORTED
                || PluginsController.getInstance().isDeveloperMode());

        String author = TextUtils.isEmpty(plugin.author)
                ? getString(R.string.PluginCatalogAuthorUnknown) : plugin.author;
        CharSequence description = CatalogPluginCell.plainSummary(
                TextUtils.isEmpty(plugin.shortDescription)
                        ? plugin.description : plugin.shortDescription, 420);
        StringBuilder status = new StringBuilder(compatibilityText(compatibility));
        if (plugin.verified) status.append(" · ")
                .append(getString(R.string.PluginCatalogVerifiedShort));
        if (plugin.featured) status.append(" · ")
                .append(getString(R.string.PluginCatalogFeatured));
        CatalogDetailsHeaderCell.Model header = new CatalogDetailsHeaderCell.Model(
                plugin, isOfficialSource(), author, description, status,
                ratingText(plugin), downloadsText(plugin.downloadCount),
                TextUtils.isEmpty(categoryName)
                        ? CatalogUi.humanizeSlug(plugin.category) : categoryName,
                action, actionEnabled, busy, installed,
                compatibility == Compatibility.UNSUPPORTED
                        && !PluginsController.getInstance().isDeveloperMode());
        items.add(CatalogDetailsHeaderCell.Factory.asHeader(header,
                selected -> installer.install(selected)));
        items.add(UItem.asShadow(installed ? null
                : getString(R.string.PluginCatalogDownloadRiskHint)));

        boolean authorClickable = !TextUtils.isEmpty(plugin.authorId);
        String authorAvatar = isOfficialSource()
                && CatalogConfig.isTrustedOfficialMediaUrl(authorImage)
                ? authorImage : null;
        items.add(CatalogAuthorCell.Factory.asAuthor(ID_AUTHOR,
                new CatalogAuthorCell.Model(plugin, author,
                        authorClickable ? getString(R.string.PluginCatalogOpenAuthor) : null,
                        authorAvatar)).setEnabled(authorClickable));

        items.add(UItem.asHeader(getString(R.string.PluginCatalogDetailsTitle)));
        items.add(CatalogDetailCell.Factory.asDetail(100, R.drawable.msg_plugins,
                IconBackgroundColors.BLUE_DEEP,
                getString(R.string.PluginCatalogCurrentVersion), plugin.version));
        items.add(CatalogDetailCell.Factory.asDetail(101, R.drawable.msg_check,
                compatibility == Compatibility.COMPATIBLE
                        ? IconBackgroundColors.GREEN
                        : compatibility == Compatibility.UNSUPPORTED
                        ? IconBackgroundColors.RED
                        : IconBackgroundColors.GRAY,
                getString(R.string.PluginCatalogCompatibilityTitle),
                compatibilityText(compatibility)));
        if (!TextUtils.isEmpty(plugin.minExteralessVersion)) {
            items.add(CatalogDetailCell.Factory.asDetail(103, R.drawable.msg_info,
                    IconBackgroundColors.CYAN,
                    getString(R.string.PluginCatalogMinimumVersion),
                    plugin.minExteralessVersion));
        }
        boolean hasCheckDetails = plugin.securityCheck != null
                || plugin.performanceCheck != null;
        if (hasCheckDetails) {
            items.add(CatalogChecksCell.Factory.asChecks(ID_SECURITY, plugin));
        } else {
            items.add(CatalogDetailCell.Factory.asDetail(102, R.drawable.msg2_policy,
                    IconBackgroundColors.GRAY,
                    getString(R.string.PluginCatalogSecurity), securityText(plugin)));
        }
        items.add(UItem.asShadow(null));

        List<String> screenshots = trustedScreenshots();
        if (!screenshots.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogScreenshots)));
            items.add(CatalogScreenshotsCell.Factory.asGallery(
                    new CatalogScreenshotsCell.Model(plugin, screenshots), this::openGallery));
            items.add(UItem.asShadow(null));
        }

        CharSequence translatedNote = null;
        if (!TextUtils.isEmpty(plugin.localizedLocale)
                && !TextUtils.isEmpty(plugin.contentLocale)
                && !plugin.localizedLocale.equalsIgnoreCase(plugin.contentLocale)) {
            String language = new Locale(plugin.contentLocale).getDisplayLanguage(
                    LocaleController.getInstance().getCurrentLocale());
            translatedNote = LocaleController.formatString(
                    R.string.PluginCatalogTranslatedFrom,
                    TextUtils.isEmpty(language) ? plugin.contentLocale : language);
        }
        addLongSection(items, R.string.PluginCatalogDescription, plugin.description,
                translatedNote);
        addLongSection(items, R.string.PluginCatalogChangelog, plugin.changelog);
        addLongSection(items, R.string.PluginCatalogRequirements, plugin.requirements);
        if (!plugin.tags.isEmpty()) {
            addLongSection(items, R.string.PluginCatalogTags, tagsText(plugin.tags));
        }

        if (versionsCall != null) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogVersions)));
            items.add(UItem.asSettingsCell(500, R.drawable.msg_download,
                    getString(R.string.PluginCatalogLoading)));
            items.add(UItem.asShadow(null));
        } else if (versionsError != null) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogVersions)));
            items.add(UItem.asSettingsCell(501, R.drawable.msg_warning,
                    getString(R.string.PluginCatalogLoadError), errorText(versionsError)));
            items.add(UItem.asButton(ID_RETRY_VERSIONS, R.drawable.msg_retry,
                    getString(R.string.Retry)).accent());
            items.add(UItem.asShadow(null));
        } else if (!versions.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogVersions)));
            int id = 1000;
            for (CatalogVersion version : versions) {
                String value = AndroidUtilities.formatFileSize(version.fileSize);
                if (version.downloadCount > 0) value += " · " + downloadsText(version.downloadCount);
                if (version.stable) value += " · " + getString(R.string.PluginCatalogStableVersion);
                boolean versionInstalling = busy
                        && TextUtils.equals(installer.getActiveSlug(), plugin.slug)
                        && installer.getActiveVersionId() == version.id;
                if (versionInstalling) {
                    value += " · " + getString(R.string.PluginCatalogInstalling);
                }
                String fileName = plugin.slug + "-v" + version.version + ".plugin";
                String accessibility = fileName + ". " + value + ". "
                        + getString(R.string.PluginCatalogInstall);
                items.add(CatalogVersionCell.Factory.asVersion(id++,
                        new CatalogVersionCell.Model(plugin, version, fileName, value,
                                accessibility)).setEnabled(!busy));
            }
            items.add(UItem.asShadow(null));
        }

        if (planCall != null) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogDependencies)));
            items.add(UItem.asSettingsCell(600, R.drawable.msg_plugins,
                    getString(R.string.PluginCatalogLoading)));
            items.add(UItem.asShadow(getString(R.string.PluginCatalogInstallPlanHint)));
        } else if (planError != null) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogDependencies)));
            items.add(UItem.asSettingsCell(601, R.drawable.msg_warning,
                    getString(R.string.PluginCatalogLoadError), errorText(planError)));
            items.add(UItem.asButton(ID_RETRY_PLAN, R.drawable.msg_retry,
                    getString(R.string.Retry)).accent());
            items.add(UItem.asShadow(getString(R.string.PluginCatalogInstallPlanHint)));
        } else if (!installPlan.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogDependencies)));
            int id = 2000;
            for (CatalogInstallPlanItem dependency : installPlan) {
                String value = dependency.version;
                if (dependency.requestedPlugin) {
                    value += " · " + getString(R.string.PluginCatalogRequestedPlugin);
                }
                items.add(UItem.asSettingsCell(id++, dependency.requestedPlugin
                                ? R.drawable.msg_check : R.drawable.msg_plugins,
                        dependency.name, value));
            }
            items.add(UItem.asShadow(getString(R.string.PluginCatalogInstallPlanHint)));
        }

        items.add(UItem.asHeader(getString(R.string.PluginCatalogSource)));
        items.add(UItem.asButton(ID_SOURCE, R.drawable.msg_link2,
                getString(R.string.PluginCatalogSource)));
        if (!TextUtils.isEmpty(plugin.githubUrl)) {
            items.add(UItem.asButton(ID_GITHUB, R.drawable.menu_intro,
                    getString(R.string.PluginCatalogGithub)));
        }
        if (!TextUtils.isEmpty(plugin.documentationUrl)) {
            items.add(UItem.asButton(ID_DOCUMENTATION, R.drawable.msg2_policy,
                    getString(R.string.PluginCatalogDocumentation)));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        if (item.object instanceof CatalogVersionCell.Model) {
            CatalogVersionCell.Model model = (CatalogVersionCell.Model) item.object;
            installer.install(model.plugin, model.version);
            return;
        }
        switch (item.id) {
            case ID_AUTHOR:
                openAuthor(plugin);
                break;
            case ID_SECURITY:
                showSecuritySheet();
                break;
            case ID_SOURCE: openLink(repository.getConfig().getBaseUrl()); break;
            case ID_GITHUB: openLink(plugin == null ? null : plugin.githubUrl); break;
            case ID_DOCUMENTATION:
                openLink(plugin == null ? null : plugin.documentationUrl);
                break;
            case ID_RETRY: load(); break;
            case ID_RETRY_VERSIONS:
            case ID_RETRY_PLAN:
                if (plugin != null) loadSupplementalData(plugin, loadGeneration);
                break;
            case ID_ENABLE_ENGINE:
                PluginsController.getInstance().setEngineEnabled(true);
                load();
                break;
        }
    }

    private void addLongSection(ArrayList<UItem> items, int titleRes, CharSequence value) {
        addLongSection(items, titleRes, value, null);
    }

    private void addLongSection(ArrayList<UItem> items, int titleRes, CharSequence value,
                                CharSequence footer) {
        if (TextUtils.isEmpty(value)) return;
        CharSequence displayValue = value.length() > 20_000
                ? value.subSequence(0, 20_000) + "…" : value;
        items.add(UItem.asHeader(getString(titleRes)));
        items.add(CatalogTextCell.Factory.asText(4000 + titleRes, displayValue,
                repository.getConfig().getBaseUrl()));
        items.add(UItem.asShadow(footer));
    }

    private void load() {
        cancelLoadCalls();
        final long generation = ++loadGeneration;
        final boolean silent = plugin != null;
        loading = true;
        loadError = null;
        if (!silent) {
            versions = Collections.emptyList();
            installPlan = Collections.emptyList();
            versionsError = null;
            planError = null;
            supplementalPending = 0;
            if (shareItem != null) shareItem.setVisibility(View.GONE);
            updateRows(false);
            scrollDetailsToTop();
        } else {
            loadSupplementalData(plugin, generation);
        }
        detailCall = repository.getDetail(slug, new CatalogCall.Callback<CatalogPlugin>() {
            @Override
            public void onSuccess(CatalogPlugin value) {
                if (destroyed || generation != loadGeneration) return;
                detailCall = null;
                loading = false;
                plugin = value;
                if (!TextUtils.isEmpty(value.authorImage)) {
                    authorImage = value.authorImage;
                }
                actionBar.setTitle(value.name);
                if (shareItem != null) shareItem.setVisibility(View.VISIBLE);
                if (silent) {
                    updateRows(false);
                } else {
                    loadSupplementalData(value, generation);
                    scrollDetailsToTop();
                }
            }

            @Override
            public void onError(CatalogException error) {
                if (destroyed || generation != loadGeneration) return;
                detailCall = null;
                loading = false;
                if (!silent) {
                    loadError = error;
                    if (shareItem != null) shareItem.setVisibility(View.GONE);
                    updateRows(false);
                } else if (error.kind != CatalogException.Kind.CANCELLED
                        && getContext() != null) {
                    BulletinFactory.of(PluginCatalogDetailsActivity.this)
                            .createSimpleBulletin(R.raw.error, errorText(error)).show();
                }
            }
        });
    }

    private void loadSupplementalData(CatalogPlugin loadedPlugin, long generation) {
        if (versionsCall != null) versionsCall.cancel();
        if (planCall != null) planCall.cancel();
        final long supplemental = ++supplementalGeneration;
        versionsError = null;
        planError = null;
        supplementalPending = 2;
        versionsCall = repository.getVersions(loadedPlugin.slug,
                new CatalogCall.Callback<List<CatalogVersion>>() {
                    @Override public void onSuccess(List<CatalogVersion> value) {
                        if (destroyed || generation != loadGeneration
                                || supplemental != supplementalGeneration) {
                            return;
                        }
                        versionsCall = null;
                        versionsError = null;
                        if (plugin != null && TextUtils.equals(plugin.slug, loadedPlugin.slug)) {
                            versions = value == null ? Collections.emptyList() : value;
                        }
                        supplementalFinished(generation);
                    }
                    @Override public void onError(CatalogException error) {
                        if (destroyed || generation != loadGeneration
                                || supplemental != supplementalGeneration
                                || error.kind == CatalogException.Kind.CANCELLED) {
                            return;
                        }
                        versionsCall = null;
                        versionsError = error;
                        supplementalFinished(generation);
                    }
                });
        planCall = repository.getInstallPlan(loadedPlugin.id,
                new CatalogCall.Callback<List<CatalogInstallPlanItem>>() {
                    @Override public void onSuccess(List<CatalogInstallPlanItem> value) {
                        if (destroyed || generation != loadGeneration
                                || supplemental != supplementalGeneration) {
                            return;
                        }
                        planCall = null;
                        planError = null;
                        if (plugin != null && plugin.id == loadedPlugin.id) {
                            installPlan = value == null ? Collections.emptyList() : value;
                        }
                        supplementalFinished(generation);
                    }
                    @Override public void onError(CatalogException error) {
                        if (destroyed || generation != loadGeneration
                                || supplemental != supplementalGeneration
                                || error.kind == CatalogException.Kind.CANCELLED) {
                            return;
                        }
                        planCall = null;
                        planError = error;
                        supplementalFinished(generation);
                    }
                });
        updateRows(false);
    }

    private void supplementalFinished(long generation) {
        if (destroyed || generation != loadGeneration) return;
        if (supplementalPending > 0) supplementalPending--;
        if (supplementalPending == 0) updateRows(false);
    }

    private void openGallery(int selected, CatalogScreenshotsCell source) {
        List<String> screenshots = trustedScreenshots();
        if (selected < 0 || selected >= screenshots.size()) return;
        ArrayList<Object> entries = new ArrayList<>(screenshots.size());
        for (int i = 0; i < screenshots.size(); i++) {
            MediaController.SearchImage entry = new MediaController.SearchImage();
            entry.id = plugin.slug + ":" + i;
            entry.imageUrl = screenshots.get(i);
            entry.thumbUrl = screenshots.get(i);
            entry.type = 0;
            entries.add(entry);
        }
        PhotoViewer viewer = PhotoViewer.getInstance();
        viewer.setParentActivity(this);
        viewer.openPhotoForSelect(entries, selected, PhotoViewer.SELECT_TYPE_NO_SELECT,
                false, new PhotoViewer.EmptyPhotoViewerProvider() {
                    @Override
                    public boolean allowCaption() {
                        return false;
                    }

                    @Override
                    public boolean canCaptureMorePhotos() {
                        return false;
                    }

                    @Override
                    public boolean allowSendingSubmenu() {
                        return false;
                    }

                    @Override
                    public CharSequence getTitleFor(int index) {
                        return LocaleController.formatString(
                                R.string.Of, index + 1, screenshots.size());
                    }

                    @Override
                    public PhotoViewer.PlaceProviderObject getPlaceForPhoto(
                            MessageObject messageObject, TLRPC.FileLocation fileLocation,
                            int index, boolean needPreview, boolean closing) {
                        BackupImageView thumbnail = source == null ? null
                                : source.getImageAt(index);
                        if (thumbnail == null) return null;
                        int[] coordinates = new int[2];
                        thumbnail.getLocationInWindow(coordinates);
                        ImageReceiver receiver = thumbnail.getImageReceiver();
                        PhotoViewer.PlaceProviderObject place =
                                new PhotoViewer.PlaceProviderObject();
                        place.viewX = coordinates[0];
                        place.viewY = coordinates[1] - (Build.VERSION.SDK_INT >= 21
                                ? 0 : AndroidUtilities.statusBarHeight);
                        place.parentView = source;
                        place.imageReceiver = receiver;
                        place.thumb = receiver.getBitmapSafe();
                        place.radius = receiver.getRoundRadius(true);
                        place.scale = thumbnail.getScaleX();
                        return place;
                    }

                    @Override
                    public ImageReceiver.BitmapHolder getThumbForPhoto(
                            MessageObject messageObject, TLRPC.FileLocation fileLocation,
                            int index) {
                        BackupImageView thumbnail = source == null ? null
                                : source.getImageAt(index);
                        return thumbnail == null ? null
                                : thumbnail.getImageReceiver().getBitmapSafe();
                    }
                }, null);
    }

    private List<String> trustedScreenshots() {
        ArrayList<String> result = new ArrayList<>();
        if (plugin == null || !isOfficialSource()) return result;
        for (String screenshot : plugin.screenshots) {
            if (CatalogConfig.isTrustedOfficialMediaUrl(screenshot)) result.add(screenshot);
        }
        return result;
    }

    private boolean isOfficialSource() {
        return repository != null && CatalogConfig.DEFAULT_BASE_URL.equals(
                repository.getConfig().getBaseUrl());
    }

    private void openLink(String url) {
        if (isAllowedWebUrl(url)) Browser.openUrl(getParentActivity(), url);
    }

    private void showSecuritySheet() {
        if (plugin == null || getContext() == null) return;
        showDialog(new CatalogSecuritySheet(getContext(), getResourceProvider(), plugin,
                storeDisplayName()));
    }

    private String storeDisplayName() {
        if (isOfficialSource()) return "ExteraStore";
        String source = repository.getConfig().getBaseUrl();
        try {
            String host = new URI(source).getHost();
            return TextUtils.isEmpty(host) ? source : host;
        } catch (Exception ignored) {
            return source;
        }
    }

    private void openAuthor(CatalogPlugin value) {
        if (value == null || TextUtils.isEmpty(value.authorId)) return;
        Browser.openUrl(getParentActivity(), "tg://user?id=" + value.authorId);
    }

    private void sharePlugin() {
        if (plugin == null || repository == null || getParentActivity() == null) return;
        String source = repository.getConfig().getBaseUrl();
        boolean official = CatalogConfig.DEFAULT_BASE_URL.equals(source);
        String url = official ? source + "/plugins/" + Uri.encode(plugin.slug) : source;
        String text = official ? plugin.name + "\n" + url
                : plugin.name + "\n" + plugin.slug + "\n" + source;
        showDialog(new ShareAlert(getParentActivity(), null, text, false, url,
                false, getResourceProvider()));
    }

    private boolean isAllowedWebUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            URI uri = new URI(url);
            boolean allowedScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && repository.getConfig().isInsecureAllowed());
            return allowedScheme && !TextUtils.isEmpty(uri.getHost()) && uri.getRawUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override public void onBusyChanged(boolean value) { busy = value; updateRows(true); }

    @Override public void onFailure(CatalogException error) {
        if (getContext() != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, errorText(error)).show();
        }
    }

    @Override public void onInstalled() { updateRows(true); }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        heroSwap = null;
        heroCoverBitmap = null;
        loadGeneration++;
        cancelLoadCalls();
        if (installer != null) installer.cancel();
        super.onFragmentDestroy();
    }

    private void cancelLoadCalls() {
        if (detailCall != null) { detailCall.cancel(); detailCall = null; }
        if (versionsCall != null) { versionsCall.cancel(); versionsCall = null; }
        if (planCall != null) { planCall.cancel(); planCall = null; }
    }

    private void updateRows(boolean animated) {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(animated && SharedConfig.animationsEnabled());
        }
    }

    private void scrollDetailsToTop() {
        if (listView == null || layoutManager == null) return;
        listView.stopScroll();
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    private Compatibility compatibility(CatalogPlugin value) {
        return Boolean.TRUE.equals(value.exteralessCompatible)
                ? Compatibility.COMPATIBLE
                : Boolean.FALSE.equals(value.exteralessCompatible)
                ? Compatibility.UNSUPPORTED
                : Compatibility.UNKNOWN;
    }

    private String compatibilityText(Compatibility value) {
        switch (value) {
            case COMPATIBLE: return getString(R.string.PluginCatalogCompatible);
            case UNSUPPORTED: return getString(R.string.PluginCatalogUnsupported);
            default: return getString(R.string.PluginCatalogCompatibilityUnknown);
        }
    }

    private String securityText(CatalogPlugin value) {
        return CatalogUi.checkVerdict(value.securityCheck, false).toString();
    }

    private CharSequence tagsText(List<String> tags) {
        StringBuilder result = new StringBuilder();
        for (String tag : tags) {
            if (TextUtils.isEmpty(tag)) continue;
            if (result.length() > 0) result.append("  ");
            if (!tag.startsWith("#")) result.append('#');
            result.append(tag.trim());
        }
        return result;
    }

    private String ratingText(CatalogPlugin value) {
        if (value.ratingCount <= 0) return getString(R.string.PluginCatalogNoRatings);
        return LocaleController.formatString(R.string.PluginCatalogRating,
                String.format(LocaleController.getInstance().getCurrentLocale(),
                        "%.1f", value.rating), value.ratingCount);
    }

    private String downloadsText(long count) {
        return LocaleController.formatPluralString("PluginCatalogDownloads",
                (int) Math.min(Integer.MAX_VALUE, count));
    }

    private CharSequence errorText(CatalogException error) {
        return CatalogUi.errorText(error);
    }

    private CatalogInstallCoordinator.Labels installLabels() {
        return new CatalogInstallCoordinator.Labels(
                getString(R.string.PluginCatalogCompatibilityUnknownTitle),
                getString(R.string.PluginCatalogCompatibilityUnknownWarning),
                getString(R.string.PluginCatalogUnsupportedTitle),
                getString(R.string.PluginCatalogUnsupportedWarning),
                getString(R.string.PluginCatalogDownloadRiskTitle),
                getString(R.string.PluginCatalogDownloadRiskMessage),
                getString(R.string.Continue), getString(R.string.PluginCatalogTelegramTitle),
                getString(R.string.PluginCatalogTelegramMessage),
                getString(R.string.PluginCatalogOpenTelegram),
                getString(R.string.PluginCatalogNoVersion));
    }
}
