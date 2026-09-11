package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import app.exteraless.plugins.PluginsConstants;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.catalog.CatalogCall;
import app.exteraless.plugins.catalog.CatalogCategory;
import app.exteraless.plugins.catalog.CatalogData;
import app.exteraless.plugins.catalog.CatalogException;
import app.exteraless.plugins.catalog.CatalogPage;
import app.exteraless.plugins.catalog.CatalogPlugin;
import app.exteraless.plugins.catalog.CatalogQuery;
import app.exteraless.plugins.catalog.CatalogRepository;
import app.exteraless.plugins.catalog.CatalogUpdateMatch;
import tw.nekomimi.nekogram.NekoConfig;

public class PluginCatalogActivity extends BaseFragment {

    static final String PREF_AUTO_REFRESH = "catalog_auto_refresh";
    static final String PREF_AUTO_UPDATE_CHECK = "catalog_auto_update_check";

    private static final int MENU_SEARCH = 1;
    private static final int MENU_OTHER = 6;
    private static final int ID_RETRY_MORE = 101;
    private static final int ID_RETRY = 102;
    private static final int ID_CLEAR_FILTERS = 106;
    private static final int ID_ENABLE_ENGINE = 107;
    private static final int ID_CACHE_INFO = 108;
    private static final long RESUME_REFRESH_INTERVAL_MS = 5 * 60 * 1000L;

    private final ArrayList<CatalogPlugin> plugins = new ArrayList<>();
    private final ArrayList<CatalogCategory> categories = new ArrayList<>();
    private UniversalRecyclerView listView;
    private CatalogRepository repository;
    private CatalogQuery query;
    private CatalogQuery displayedQuery;
    private CatalogCall pageCall;
    private CatalogCall categoriesCall;
    private Runnable searchRunnable;
    private boolean initialLoading;
    private boolean loadingMore;
    private boolean hasNextPage;
    private boolean loadMoreFailed;
    private CatalogException error;
    private boolean fromCache;
    private boolean stale;
    private long cachedAt;
    private long totalCount;
    private long pageGeneration;
    private long categoriesGeneration;
    private boolean firstResume = true;
    private String lastSource;
    private org.telegram.ui.ActionBar.ActionBarMenuItem searchItem;
    private org.telegram.ui.ActionBar.ActionBarMenuItem otherItem;
    private FilterTabsView categoryTabs;
    private FrameLayout tabsContainer;
    private org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton toTopButton;
    private boolean toTopVisible;
    private final SparseArray<String> categoryIds = new SparseArray<>();
    private String pendingAnchorSlug;
    private int pendingAnchorOffset;
    private long lastRefreshElapsed;
    private boolean searchExpanded;
    private boolean lastSystemEmoji;
    private String lastLocale;
    private int bottomInset;
    private final java.util.HashMap<String, CharSequence> categoryLabels = new java.util.HashMap<>();

    @Override
    public View createView(Context context) {
        repository = new CatalogRepository(context);
        query = repository.getDefaultQuery();
        lastSource = repository.getConfig().getBaseUrl();
        lastSystemEmoji = NekoConfig.useSystemEmoji.Bool();
        lastLocale = CatalogRepository.appLocale();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PluginCatalogTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        org.telegram.ui.ActionBar.ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu
                .addItem(MENU_SEARCH, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(
                        new org.telegram.ui.ActionBar.ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                            @Override
                            public void onSearchCollapse() {
                                searchExpanded = false;
                                setCategoryBarVisible(true, true);
                                scheduleSearch(null);
                            }

                            @Override
                            public void onSearchExpand() {
                                searchExpanded = true;
                                setCategoryBarVisible(false, true);
                                scheduleSearch("");
                            }

                            @Override
                            public void onTextChanged(android.widget.EditText editText) {
                                scheduleSearch(editText.getText().toString());
                            }
                        });
        searchItem.setSearchFieldHint(getString(R.string.PluginCatalogSearchHint));
        searchItem.setContentDescription(getString(R.string.Search));
        otherItem = menu.addItem(
                MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        otherItem.setOnClickListener(v -> showCatalogMenu());

        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        tabsContainer = new FrameLayout(context);
        categoryTabs = new FilterTabsView(context, getResourceProvider());
        categoryTabs.setForceTextTitles();
        categoryTabs.setColors(Theme.key_actionBarTabLine,
                Theme.key_actionBarTabActiveText, Theme.key_actionBarTabUnactiveText,
                Theme.key_actionBarTabSelector, Theme.key_windowBackgroundWhite);
        BlurredBackgroundSourceColor tabsBackgroundSource = new BlurredBackgroundSourceColor();
        tabsBackgroundSource.setColor(Theme.getColor(
                Theme.key_windowBackgroundWhite, getResourceProvider()));
        BlurredBackgroundDrawable tabsBackground = new BlurredBackgroundDrawableViewFactory(
                tabsBackgroundSource).create(categoryTabs,
                BlurredBackgroundProviderImpl.topPanel(getResourceProvider()));
        tabsBackground.setRadius(AndroidUtilities.dp(18));
        tabsBackground.setPadding(AndroidUtilities.dp(6.666f));
        categoryTabs.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        categoryTabs.setBlurredBackground(tabsBackground);
        categoryTabs.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
            @Override
            public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
                onCategorySelected(categoryIds.get(tab.id));
            }

            @Override public void onPageScrolled(float progress) { }
            @Override public void onSamePageSelected() {
                if (listView != null) listView.smoothScrollToPosition(0);
            }
            @Override public int getTabCounter(int tabId) { return categoryCount(tabId); }
            @Override public boolean didSelectTab(FilterTabsView.TabView tabView,
                                                   boolean selected) { return false; }
            @Override public boolean isTabMenuVisible() { return false; }
            @Override public void onDeletePressed(int id) { }
            @Override public void onPageReorder(int fromId, int toId) { }
            @Override public boolean canPerformActions() { return true; }
        });
        tabsContainer.addView(categoryTabs, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50, Gravity.TOP | Gravity.FILL_HORIZONTAL,
                4, 0, 4, 0));
        content.addView(tabsContainer, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50, Gravity.TOP | Gravity.FILL_HORIZONTAL));
        rebuildCategoryTabs();

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections(true);
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        applyListPadding();
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && searchExpanded) {
                    AndroidUtilities.hideKeyboard(searchItem.getSearchField());
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateToTopButton();
                if (dy <= 0 || !hasNextPage || loadingMore || initialLoading) {
                    return;
                }
                RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    int last = ((LinearLayoutManager) manager).findLastVisibleItemPosition();
                    if (last >= listView.adapter.getItemCount() - 4) {
                        loadNextPage();
                    }
                }
            }
        });
        GestureDetector swipeDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent start, MotionEvent end,
                                   float velocityX, float velocityY) {
                if (start == null || end == null) return false;
                float dx = end.getX() - start.getX();
                if (Math.abs(dx) < AndroidUtilities.dp(80)
                        || Math.abs(velocityX) < Math.abs(velocityY) * 1.5f
                        || Math.abs(velocityX) < 600) {
                    return false;
                }
                swipeCategory(LocaleController.isRTL == (dx > 0));
                return true;
            }
        });
        listView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView recyclerView, MotionEvent event) {
                swipeDetector.onTouchEvent(event);
                return false;
            }
        });
        content.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        content.bringChildToFront(tabsContainer);
        actionBar.setAdaptiveBackground(listView);

        toTopButton = org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton
                .create(context, new BlurredBackgroundDrawableViewFactory(tabsBackgroundSource),
                        BlurredBackgroundProviderImpl.topPanel(getResourceProvider()),
                        getResourceProvider(), R.drawable.pagedown, 48);
        toTopButton.reverseIconByY();
        toTopButton.setContentDescription(getString(R.string.AccDescrPageDown));
        toTopButton.setOnClickListener(v -> scrollCatalogToTop());
        toTopButton.setVisibility(View.GONE);
        toTopButton.setAlpha(0f);
        toTopButton.setScaleX(0.6f);
        toTopButton.setScaleY(0.6f);
        content.addView(toTopButton, LayoutHelper.createFrame(56, 56,
                Gravity.END | Gravity.BOTTOM, 0, 0, 10, 10));
        fragmentView = content;

        if (PluginsController.getInstance().isEngineEnabled()) {
            loadCategories();
            loadFirstPage(false);
        } else {
            updateRows(false);
        }
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (repository == null) {
            return;
        }
        String currentSource = repository.getConfig().getBaseUrl();
        boolean currentCompatibleOnly = repository.getConfig().isCompatibleOnly();
        boolean sourceChanged = !TextUtils.equals(lastSource, currentSource);
        boolean compatibilityChanged =
                Boolean.TRUE.equals(query.exteralessOnly) != currentCompatibleOnly;
        boolean systemEmojiChanged = lastSystemEmoji != NekoConfig.useSystemEmoji.Bool();
        boolean localeChanged = !TextUtils.equals(lastLocale, CatalogRepository.appLocale());
        lastSystemEmoji = NekoConfig.useSystemEmoji.Bool();
        lastLocale = CatalogRepository.appLocale();
        lastSource = currentSource;
        query = query.withExteralessOnly(currentCompatibleOnly ? Boolean.TRUE : null);
        if (sourceChanged || localeChanged) {
            if (sourceChanged) {
                query = query.withCategory(null);
            }
            categories.clear();
            rebuildCategoryTabs();
            displayedQuery = null;
        }
        if (systemEmojiChanged) {
            rebuildCategoryTabs();
        }
        if (firstResume) {
            firstResume = false;
            return;
        }
        if (sourceChanged || compatibilityChanged) {
            displayedQuery = null;
        }
        boolean refreshDue = autoRefreshEnabled() && lastRefreshElapsed > 0
                && SystemClock.elapsedRealtime() - lastRefreshElapsed
                >= RESUME_REFRESH_INTERVAL_MS;
        if (PluginsController.getInstance().isEngineEnabled()
                && (sourceChanged || compatibilityChanged
                || displayedQuery == null || refreshDue)) {
            loadCategories();
            loadFirstPage(false);
        } else {
            updateRows(false);
        }
    }

    @Override
    public void onFragmentDestroy() {
        cancelCalls();
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        }
        super.onFragmentDestroy();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean engineOn = PluginsController.getInstance().isEngineEnabled();
        if (!engineOn) {
            items.add(UItem.asTopView(getString(R.string.PluginCatalogEngineOffTitle),
                    getString(R.string.PluginCatalogEngineOffText), R.raw.error));
            items.add(UItem.asButton(ID_ENABLE_ENGINE, R.drawable.msg_settings,
                    getString(R.string.PluginCatalogEnableEngine)).accent());
            items.add(UItem.asShadow(null));
            return;
        }

        if (fromCache) {
            items.add(UItem.asHeader(getString(R.string.PluginCatalogSource)));
            String time = cachedAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT,
                    DateFormat.SHORT).format(new Date(cachedAt)) : "";
            items.add(UItem.asSettingsCell(ID_CACHE_INFO, R.drawable.baseline_cloud_download_24,
                    LocaleController.formatString(stale ? R.string.PluginCatalogCachedStaleAt
                            : R.string.PluginCatalogCachedAt, time)));
            items.add(UItem.asShadow(null));
        }

        String catalogHeader = getString(R.string.PluginCatalogTitle);
        if (totalCount > 0) {
            catalogHeader += " · " + LocaleController.formatNumber(totalCount, ' ');
        }
        items.add(UItem.asHeader(catalogHeader));
        if (initialLoading && plugins.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                items.add(UItem.asFlicker(5000 + i,
                        FlickerLoadingView.PLUGIN_CATALOG_TYPE));
            }
            items.add(UItem.asShadow(null));
            return;
        }
        if (error != null && plugins.isEmpty()) {
            items.add(UItem.asTopView(getString(R.string.PluginCatalogLoadError),
                    CatalogUi.errorText(error), R.raw.utyan_empty2));
            items.add(UItem.asButton(ID_RETRY, R.drawable.msg_retry,
                    getString(R.string.Retry)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        if (error != null) {
            items.add(UItem.asButton(ID_RETRY, R.drawable.msg_retry,
                    getString(R.string.PluginCatalogLoadError)).accent());
        }
        if (plugins.isEmpty()) {
            boolean filtered = !TextUtils.isEmpty(query.search) || query.category != null
                    || Boolean.TRUE.equals(query.exteralessOnly);
            items.add(UItem.asTopView(
                    getString(filtered ? R.string.PluginCatalogNoResults
                            : R.string.PluginCatalogEmpty),
                    getString(filtered ? R.string.PluginCatalogNoResultsText
                            : R.string.PluginCatalogEmptyText), R.raw.utyan_empty));
            if (filtered) {
                items.add(UItem.asButton(ID_CLEAR_FILTERS, R.drawable.msg_reset,
                        getString(R.string.PluginCatalogClearFilters)).accent());
                items.add(UItem.asShadow(null));
            }
            return;
        }
        Map<String, String> installed = CatalogUi.installedVersions();
        boolean showUpdates = autoUpdateCheckEnabled(getContext());
        boolean developerMode = PluginsController.getInstance().isDeveloperMode();
        boolean officialSource = app.exteraless.plugins.catalog.CatalogConfig
                .DEFAULT_BASE_URL.equals(repository.getConfig().getBaseUrl());
        for (CatalogPlugin plugin : plugins) {
            CatalogUpdateMatch match = repository.matchInstalled(plugin, installed);
            CatalogPluginCell.Model model = new CatalogPluginCell.Model(plugin,
                    match, showUpdates, developerMode, officialSource,
                    categoryName(plugin.category));
            items.add(CatalogPluginCell.Factory.asPlugin(model));
        }
        if (loadingMore) {
            items.add(UItem.asFlicker(6000 + query.page,
                    FlickerLoadingView.PLUGIN_CATALOG_TYPE));
        } else if (loadMoreFailed) {
            items.add(UItem.asButton(ID_RETRY_MORE, R.drawable.msg_retry,
                    getString(R.string.PluginCatalogRetryMore)).accent());
        }
        items.add(UItem.asShadow(null));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof CatalogPlugin) {
            openDetails((CatalogPlugin) item.object, view);
        } else if (item.object instanceof CatalogPluginCell.Model) {
            openDetails(((CatalogPluginCell.Model) item.object).plugin, view);
        } else if (item.id == ID_CLEAR_FILTERS) {
            clearFilters();
        } else if (item.id == ID_ENABLE_ENGINE) {
            PluginsController.getInstance().setEngineEnabled(true);
            loadCategories();
            loadFirstPage(false);
        } else if (item.id == ID_RETRY_MORE) {
            loadNextPage();
        } else if (item.id == ID_RETRY) {
            loadFirstPage(true);
        }
    }

    private void openDetails(CatalogPlugin plugin, View card) {
        PluginCatalogDetailsActivity details = new PluginCatalogDetailsActivity(
                plugin.slug, categoryName(plugin.category));
        if (card instanceof CatalogPluginCell && card.isAttachedToWindow()) {
            CatalogPluginCell cell = (CatalogPluginCell) card;
            details.setHeroSource(plugin, windowBounds(cell.getHeroCoverView()),
                    windowBounds(cell.getHeroNameView()), coverBitmap(cell));
        }
        presentFragment(details);
    }

    private android.graphics.Bitmap coverBitmap(CatalogPluginCell cell) {
        View cover = cell.getHeroCoverView();
        if (!(cover instanceof org.telegram.ui.Components.BackupImageView)) return null;
        try {
            android.graphics.Bitmap bitmap = ((org.telegram.ui.Components.BackupImageView)
                    cover).getImageReceiver().getBitmap();
            if (bitmap == null || bitmap.isRecycled()) return null;
            return bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    private android.graphics.RectF windowBounds(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return new android.graphics.RectF(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private CharSequence categoryName(String slug) {
        if (TextUtils.isEmpty(slug)) return "";
        CharSequence cached = categoryLabels.get(slug);
        if (cached != null) return cached;
        CharSequence label = null;
        for (CatalogCategory category : categories) {
            if (TextUtils.equals(slug, category.slug)) {
                label = categoryLabel(category);
                break;
            }
        }
        if (label == null) {
            String readable = CatalogUi.humanizeSlug(slug);
            label = readable.isEmpty() ? ""
                    : CatalogUi.renderEmoji(CatalogUi.categoryEmoji(null, slug) + " " + readable);
        }
        categoryLabels.put(slug, label);
        return label;
    }

    private void rebuildCategoryTabs() {
        rebuildCategoryTabs(false);
    }

    private void rebuildCategoryTabs(boolean animated) {
        if (categoryTabs == null) return;
        categoryLabels.clear();
        categoryTabs.removeTabs();
        categoryTabs.resetTabId();
        categoryIds.clear();
        categoryIds.put(0, null);
        String allTitle = getString(R.string.PluginCatalogCategoryAll);
        categoryTabs.addTab(0, 0, allTitle, null, true, false, false);
        int selectedId = 0;
        for (int i = 0; i < categories.size(); i++) {
            int tabId = i + 1;
            CatalogCategory category = categories.get(i);
            categoryIds.put(tabId, category.slug);
            String emoji = CatalogUi.categoryEmoji(category.icon, category.slug);
            categoryTabs.addTab(tabId, tabId,
                    categoryTabTitle(emoji, CatalogUi.categoryTitle(category, allTitle)),
                    emoji, true, false, false);
            if (TextUtils.equals(query.category, category.slug)) selectedId = tabId;
        }
        categoryTabs.selectTabWithStableId(selectedId);
        categoryTabs.finishAddingTabs(animated
                && org.telegram.messenger.SharedConfig.animationsEnabled());
    }

    private int categoryCount(int tabId) {
        if (tabId == 0) {
            long total = 0;
            for (CatalogCategory category : categories) {
                total += category.pluginCount;
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
        int index = tabId - 1;
        if (index < 0 || index >= categories.size()) return 0;
        return (int) Math.min(Integer.MAX_VALUE, categories.get(index).pluginCount);
    }

    private CharSequence categoryTabTitle(String emoji, String title) {
        return CatalogUi.renderEmoji(emoji + " " + title);
    }

    private CharSequence categoryLabel(CatalogCategory category) {
        String emoji = CatalogUi.categoryEmoji(category.icon, category.slug);
        String name = CatalogUi.categoryTitle(category,
                getString(R.string.PluginCatalogCategoryAll));
        String value = name.startsWith(emoji) ? name : emoji + " " + name;
        return CatalogUi.renderEmoji(value);
    }

    private void loadFirstPage(boolean explicitRetry) {
        if (!PluginsController.getInstance().isEngineEnabled()) {
            updateRows(false);
            return;
        }
        if (pageCall != null) {
            pageCall.cancel();
        }
        final long generation = ++pageGeneration;
        CatalogQuery requested = query.withPage(1);
        boolean criteriaChanged = !sameCriteria(displayedQuery, requested);
        boolean backgroundRefresh = !criteriaChanged && !plugins.isEmpty();
        query = requested;
        if (criteriaChanged) {
            plugins.clear();
            hasNextPage = false;
            totalCount = 0;
            fromCache = false;
            stale = false;
            cachedAt = 0;
        }
        initialLoading = true;
        loadingMore = false;
        loadMoreFailed = false;
        error = null;
        if (explicitRetry) {
            fromCache = false;
        }
        if (backgroundRefresh) {
            captureScrollAnchor();
        } else {
            updateRows(false);
        }
        if (criteriaChanged && listView != null) {
            listView.scrollToPosition(0);
        }
        pageCall = repository.getAll(query,
                new CatalogCall.Callback<CatalogData<CatalogPage>>() {
                    @Override
                    public void onSuccess(CatalogData<CatalogPage> data) {
                        if (generation != pageGeneration) {
                            return;
                        }
                        pageCall = null;
                        initialLoading = false;
                        boolean wasAtTop = listView != null
                                && !listView.canScrollVertically(-1);
                        ArrayList<CatalogPlugin> previous = backgroundRefresh
                                ? new ArrayList<>(plugins) : null;
                        plugins.clear();
                        appendUnique(data.value.plugins);
                        if (previous != null) {
                            appendUnique(previous);
                        }
                        displayedQuery = requested;
                        hasNextPage = data.value.hasNextPage();
                        totalCount = data.value.totalCount;
                        fromCache = data.fromCache;
                        stale = data.stale;
                        cachedAt = data.cachedAtMs;
                        lastRefreshElapsed = SystemClock.elapsedRealtime();
                        updateRows(true);
                        if (backgroundRefresh) {
                            restoreScrollAnchor();
                        } else if (wasAtTop && listView != null
                                && listView.getLayoutManager() instanceof LinearLayoutManager) {
                            ((LinearLayoutManager) listView.getLayoutManager())
                                    .scrollToPositionWithOffset(0, 0);
                        }
                    }

                    @Override
                    public void onError(CatalogException failure) {
                        if (generation != pageGeneration) {
                            return;
                        }
                        pageCall = null;
                        initialLoading = false;
                        error = failure;
                        updateRows(true);
                        if (backgroundRefresh) restoreScrollAnchor();
                    }
                });
    }

    private boolean sameCriteria(CatalogQuery first, CatalogQuery second) {
        return first != null && second != null
                && first.limit == second.limit
                && TextUtils.equals(first.category, second.category)
                && TextUtils.equals(first.search, second.search)
                && first.sort == second.sort
                && java.util.Objects.equals(first.exteralessOnly, second.exteralessOnly);
    }

    private void loadNextPage() {
        if (!hasNextPage || loadingMore || pageCall != null) {
            return;
        }
        loadingMore = true;
        loadMoreFailed = false;
        AndroidUtilities.runOnUIThread(() -> updateRows(false));
        final long generation = pageGeneration;
        CatalogQuery next = query.withPage(query.page + 1);
        pageCall = repository.getAll(next,
                new CatalogCall.Callback<CatalogData<CatalogPage>>() {
                    @Override
                    public void onSuccess(CatalogData<CatalogPage> data) {
                        if (generation != pageGeneration) {
                            return;
                        }
                        pageCall = null;
                        loadingMore = false;
                        query = next;
                        appendUnique(data.value.plugins);
                        hasNextPage = data.value.hasNextPage();
                        totalCount = data.value.totalCount;
                        fromCache |= data.fromCache;
                        stale |= data.stale;
                        if (data.cachedAtMs > 0) {
                            cachedAt = data.cachedAtMs;
                        }
                        updateRows(true);
                    }

                    @Override
                    public void onError(CatalogException failure) {
                        if (generation != pageGeneration) {
                            return;
                        }
                        pageCall = null;
                        loadingMore = false;
                        loadMoreFailed = true;
                        updateRows(true);
                    }
                });
    }

    private void loadCategories() {
        if (!PluginsController.getInstance().isEngineEnabled()) {
            return;
        }
        if (categoriesCall != null) {
            categoriesCall.cancel();
        }
        final long generation = ++categoriesGeneration;
        categoriesCall = repository.getCategories(
                new CatalogCall.Callback<CatalogData<List<CatalogCategory>>>() {
                    @Override
                    public void onSuccess(CatalogData<List<CatalogCategory>> data) {
                        if (generation != categoriesGeneration) {
                            return;
                        }
                        categoriesCall = null;
                        categories.clear();
                        categories.addAll(data.value);
                        rebuildCategoryTabs(true);
                        updateRows(true);
                    }

                    @Override
                    public void onError(CatalogException ignored) {
                        if (generation != categoriesGeneration) {
                            return;
                        }
                        categoriesCall = null;
                    }
                });
    }

    private void appendUnique(List<CatalogPlugin> incoming) {
        for (CatalogPlugin candidate : incoming) {
            boolean duplicate = false;
            for (CatalogPlugin existing : plugins) {
                if (existing.id == candidate.id
                        || TextUtils.equals(existing.slug, candidate.slug)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) plugins.add(candidate);
        }
    }

    private void scheduleSearch(String value) {
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
            searchRunnable = null;
        }
        String normalized = value == null ? null : value.trim();
        if (normalized != null && normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }
        if (TextUtils.isEmpty(normalized)) {
            normalized = null;
        }
        if (TextUtils.equals(normalized, query.search)) {
            return;
        }
        final String searchValue = normalized;
        searchRunnable = () -> {
            searchRunnable = null;
            query = query.withSearch(searchValue);
            loadFirstPage(false);
        };
        AndroidUtilities.runOnUIThread(searchRunnable, 350);
    }

    private CharSequence sortLabel(CatalogQuery.Sort sort) {
        switch (sort) {
            case POPULAR: return getString(R.string.PluginCatalogSortPopular);
            case RATING: return getString(R.string.PluginCatalogSortRating);
            case DOWNLOADS: return getString(R.string.PluginCatalogSortDownloads);
            default: return getString(R.string.PluginCatalogSortNewest);
        }
    }

    private void swipeCategory(boolean forward) {
        if (categoryTabs == null || categoryTabs.getTabsCount() <= 1 || searchExpanded
                || initialLoading) {
            return;
        }
        int current = 0;
        for (int i = 0; i < categoryTabs.getTabsCount(); i++) {
            FilterTabsView.Tab tab = categoryTabs.getTab(i);
            if (tab != null && tab.id == categoryTabs.getCurrentTabId()) {
                current = i;
                break;
            }
        }
        int next = current + (forward ? 1 : -1);
        if (next < 0 || next >= categoryTabs.getTabsCount()) return;
        FilterTabsView.Tab tab = categoryTabs.getTab(next);
        if (tab != null) categoryTabs.scrollToTab(tab, next);
    }

    public void onCategorySelected(String id) {
        if (TextUtils.equals(query.category, id)) {
            return;
        }
        query = query.withCategory(id);
        loadFirstPage(false);
    }

    public void onSortClicked() {
        CatalogQuery.Sort[] values = CatalogQuery.Sort.values();
        CharSequence[] labels = new CharSequence[values.length];
        int[] icons = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            CharSequence label = sortLabel(values[i]);
            labels[i] = label;
            icons[i] = query.sort == values[i] ? R.drawable.msg_check : 0;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginCatalogSortTitle))
                .setItems(labels, icons, (dialog, which) -> {
                    dialog.dismiss();
                    query = query.withSort(values[which]);
                    loadFirstPage(false);
                })
                .show();
    }

    private void showCatalogMenu() {
        if (otherItem == null || query == null || getContext() == null) return;
        ItemOptions options = ItemOptions.makeOptions(this, otherItem)
                .addText(getString(R.string.PluginCatalogTitle), 13)
                .add(R.drawable.menu_sort_date, sortLabel(query.sort), this::onSortClicked)
                .addChecked(Boolean.TRUE.equals(query.exteralessOnly),
                        R.drawable.msg_policy, getString(R.string.PluginCatalogCompatibleOnly),
                        () -> onCompatibleOnlyChanged(!Boolean.TRUE.equals(query.exteralessOnly)))
                .addGap()
                .add(R.drawable.msg_retry, getString(R.string.PluginCatalogRefresh), () -> {
                    loadCategories();
                    loadFirstPage(true);
                })
                .add(R.drawable.msg_settings, getString(R.string.PluginCatalogSettings),
                        () -> presentFragment(new PluginCatalogSettingsActivity()))
                .setGravity(Gravity.RIGHT)
                .translate(AndroidUtilities.dp(-8), AndroidUtilities.dp(-4));
        options.show();
    }

    private void setCategoryBarVisible(boolean visible, boolean animated) {
        if (tabsContainer == null || listView == null) return;
        tabsContainer.animate().cancel();
        boolean wasAtTop = !listView.canScrollVertically(-1);
        applyListPadding();
        if (wasAtTop && listView.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) listView.getLayoutManager())
                    .scrollToPositionWithOffset(0, 0);
        }
        boolean animate = animated && org.telegram.messenger.SharedConfig.animationsEnabled();
        if (visible) {
            tabsContainer.setVisibility(View.VISIBLE);
            if (animate) {
                tabsContainer.animate().alpha(1f).translationY(0f)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .setDuration(220).start();
            } else {
                tabsContainer.setAlpha(1f);
                tabsContainer.setTranslationY(0f);
            }
        } else if (animate) {
            tabsContainer.animate().alpha(0f).translationY(-AndroidUtilities.dp(10))
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setDuration(180).withEndAction(() -> {
                        if (searchExpanded) tabsContainer.setVisibility(View.GONE);
                    }).start();
        } else {
            tabsContainer.setVisibility(View.GONE);
            tabsContainer.setAlpha(0f);
            tabsContainer.setTranslationY(-AndroidUtilities.dp(10));
        }
    }

    private void applyListPadding() {
        if (listView == null) return;
        listView.setPadding(0, AndroidUtilities.dp(searchExpanded ? 0 : 50),
                0, bottomInset);
        if (toTopButton != null
                && toTopButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) toTopButton.getLayoutParams();
            int margin = AndroidUtilities.dp(10) + bottomInset;
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin;
                toTopButton.setLayoutParams(params);
            }
        }
    }

    private void updateToTopButton() {
        if (toTopButton == null || listView == null) {
            return;
        }
        boolean show = listView.computeVerticalScrollOffset()
                > AndroidUtilities.displaySize.y / 2;
        if (show == toTopVisible) return;
        toTopVisible = show;
        toTopButton.animate().cancel();
        if (!org.telegram.messenger.SharedConfig.animationsEnabled()) {
            toTopButton.setVisibility(show ? View.VISIBLE : View.GONE);
            toTopButton.setAlpha(show ? 1f : 0f);
            toTopButton.setScaleX(show ? 1f : 0.6f);
            toTopButton.setScaleY(show ? 1f : 0.6f);
            return;
        }
        if (show) {
            toTopButton.setVisibility(View.VISIBLE);
            toTopButton.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                    .setDuration(220).withEndAction(null).start();
        } else {
            toTopButton.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setDuration(180).withEndAction(() -> {
                        if (!toTopVisible) toTopButton.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void scrollCatalogToTop() {
        if (listView == null
                || !(listView.getLayoutManager() instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager manager = (LinearLayoutManager) listView.getLayoutManager();
        if (manager.findFirstVisibleItemPosition() > 40) {
            manager.scrollToPositionWithOffset(40, 0);
        }
        listView.smoothScrollToPosition(0);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        bottomInset = bottom;
        applyListPadding();
    }

    public void onCompatibleOnlyChanged(boolean value) {
        repository.getConfig().setCompatibleOnly(value);
        query = query.withExteralessOnly(value ? Boolean.TRUE : null);
        loadFirstPage(false);
    }

    private void clearFilters() {
        if (searchItem != null && !TextUtils.isEmpty(searchItem.getSearchField().getText())) {
            searchItem.getSearchField().setText("");
            if (searchRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                searchRunnable = null;
            }
        }
        query = repository.getDefaultQuery().withExteralessOnly(null);
        repository.getConfig().setCompatibleOnly(false);
        rebuildCategoryTabs();
        if (searchExpanded) actionBar.closeSearchField();
        loadFirstPage(false);
    }

    static boolean autoUpdateCheckEnabled(Context context) {
        return context != null && context.getSharedPreferences(
                PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_UPDATE_CHECK, true);
    }

    private boolean autoRefreshEnabled() {
        SharedPreferences preferences = getContext().getSharedPreferences(
                PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getBoolean(PREF_AUTO_REFRESH, true);
    }

    private void cancelCalls() {
        pageGeneration++;
        categoriesGeneration++;
        if (pageCall != null) {
            pageCall.cancel();
            pageCall = null;
        }
        if (categoriesCall != null) {
            categoriesCall.cancel();
            categoriesCall = null;
        }
    }

    private void updateRows(boolean animated) {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(animated && org.telegram.messenger.SharedConfig.animationsEnabled());
        }
    }

    private void captureScrollAnchor() {
        pendingAnchorSlug = null;
        pendingAnchorOffset = 0;
        if (listView == null || listView.adapter == null
                || !(listView.getLayoutManager() instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager manager = (LinearLayoutManager) listView.getLayoutManager();
        int first = manager.findFirstVisibleItemPosition();
        int count = listView.adapter.getItemCount();
        for (int position = Math.max(0, first); position < count; position++) {
            UItem item = listView.adapter.getItem(position);
            if (item != null && item.object instanceof CatalogPluginCell.Model) {
                pendingAnchorSlug = ((CatalogPluginCell.Model) item.object).plugin.slug;
                View child = manager.findViewByPosition(position);
                pendingAnchorOffset = child == null ? 0 : manager.getDecoratedTop(child);
                return;
            }
        }
    }

    private void restoreScrollAnchor() {
        if (listView == null || listView.adapter == null
                || TextUtils.isEmpty(pendingAnchorSlug)) {
            pendingAnchorSlug = null;
            return;
        }
        final String slug = pendingAnchorSlug;
        final int offset = pendingAnchorOffset;
        pendingAnchorSlug = null;
        listView.post(() -> {
            if (!(listView.getLayoutManager() instanceof LinearLayoutManager)) return;
            for (int position = 0; position < listView.adapter.getItemCount(); position++) {
                UItem item = listView.adapter.getItem(position);
                if (item != null && item.object instanceof CatalogPluginCell.Model
                        && TextUtils.equals(slug,
                        ((CatalogPluginCell.Model) item.object).plugin.slug)) {
                    ((LinearLayoutManager) listView.getLayoutManager())
                            .scrollToPositionWithOffset(position, offset);
                    return;
                }
            }
        });
    }

}
