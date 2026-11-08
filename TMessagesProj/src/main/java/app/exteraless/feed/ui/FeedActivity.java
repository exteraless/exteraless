package app.exteraless.feed.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import app.exteraless.appearance.ChatHeaderUiHelper;
import app.exteraless.appearance.MainTabsUiHelper;
import app.exteraless.feed.FeedConfig;
import app.exteraless.feed.FeedController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatActivityContainer;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.ChatSearchTabs;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

/**
 * Экран ленты: синтетический чат из постов каналов.
 * Собственного списка не рисует — держит {@link ChatActivityContainer} со встроенным
 * {@link ChatActivity} в режиме ленты и управляет его заголовком, меню и жизненным циклом.
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final String ARG_HAS_MAIN_TABS = "hasMainTabs";
    private static final int FEED_SEARCH_TYPE = 4;
    private static final int MENU_FEED_SETTINGS = 75;
    private static final int MENU_MARK_ALL_READ = 76;
    private static final long LOAD_NEW_POSTS_DELAY = 1000L;
    private static final int HEADER_LEFT_MARGIN_M3_DP = 12;

    private FeedCategoryTabsView categoryTabs;
    private int lastCategoriesVersion;
    private int lastSelectedCategoryId;
    private ChatActivityContainer chatContainer;
    private boolean embeddedChatCreated;
    private boolean hasMainTabs;
    private int lastConfigGeneration;
    private WindowInsetsCompat lastWindowInsets;
    private final Runnable loadNewPosts;
    private Runnable parentTabsGlassInvalidationCallback;
    private boolean resumedOnce;
    private boolean uiActiveHeld;
    private boolean uiResumedHeld;
    private boolean viewportFullyVisible;

    public FeedActivity() {
        this(null);
    }

    public FeedActivity(Bundle args) {
        super(args);
        loadNewPosts = () -> {
            if (chatContainer == null || chatContainer.chatActivity == null || !uiResumedHeld) {
                return;
            }
            chatContainer.chatActivity.loadNewerFeed(true);
        };
    }

    /**
     * Открывает ленту: на планшете — в правой колонке поверх очищенного стека,
     * на телефоне — обычным переходом из {@code fragment}.
     */
    public static void presentFeed(BaseFragment fragment) {
        final LaunchActivity launchActivity = LaunchActivity.instance;
        if (!AndroidUtilities.isTablet() || launchActivity == null || launchActivity.getRightActionBarLayout() == null) {
            if (fragment != null) {
                fragment.presentFragment(new FeedActivity());
            }
            return;
        }
        final INavigationLayout rightLayout = launchActivity.getRightActionBarLayout();
        if (rightLayout.getLastFragment() instanceof FeedActivity) {
            return;
        }
        if (!rightLayout.getFragmentStack().isEmpty()) {
            while (rightLayout.getFragmentStack().size() - 1 > 0) {
                rightLayout.removeFragmentFromStack(rightLayout.getFragmentStack().get(0));
            }
            rightLayout.closeLastFragment(false);
        }
        rightLayout.presentFragment(new INavigationLayout.NavigationParams(new FeedActivity())
                .setNoAnimation(true)
                .forceRightLayout());
    }

    @Override
    public boolean onFragmentCreate() {
        hasMainTabs = arguments != null && arguments.getBoolean(ARG_HAS_MAIN_TABS, false);
        viewportFullyVisible = !hasMainTabs;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        lastConfigGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        lastSelectedCategoryId = FeedConfig.getInstance(currentAccount).getSelectedCategoryId();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(loadNewPosts);
        destroyEmbeddedChat();
        if (uiResumedHeld) {
            uiResumedHeld = false;
            FeedController.getInstance(currentAccount).setUiResumed(false);
        }
        if (uiActiveHeld) {
            uiActiveHeld = false;
            FeedController.getInstance(currentAccount).setUiActive(false);
        }
        Bulletin.removeDelegate(this);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        destroyEmbeddedChat();
        lastWindowInsets = null;
        actionBar.setAddToContainer(false);
        actionBar.setVisibility(View.GONE);

        final FrameLayout rootLayout = new FrameLayout(context);
        fragmentView = rootLayout;
        rootLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (view, insets) -> extendInsetsByTabsHeight(insets));
        }
        rootLayout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (lastWindowInsets != null) {
                    ViewCompat.dispatchApplyWindowInsets(view, lastWindowInsets);
                } else {
                    view.requestApplyInsets();
                }
                invalidateEmbeddedActionBar();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
            }
        });

        final FrameLayout chatLayout = new FrameLayout(context);
        rootLayout.addView(chatLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        final Bundle chatArgs = new Bundle();
        chatArgs.putInt("chatMode", ChatActivity.MODE_SEARCH);
        chatArgs.putInt("searchType", FEED_SEARCH_TYPE);
        chatArgs.putBoolean(ARG_HAS_MAIN_TABS, hasMainTabs);
        chatContainer = new ChatActivityContainer(context, getParentLayout(), chatArgs) {
            private boolean activityCreated;

            @Override
            protected void initChatActivity() {
                if (activityCreated) {
                    return;
                }
                activityCreated = true;
                FeedActivity.this.embeddedChatCreated = true;
                super.initChatActivity();
                applyFloatingWindowLayout();
                setupChatActionBar();
                setupChatTitle();
                setupCategoryTabs();
                if (FeedActivity.this.lastWindowInsets != null && FeedActivity.this.fragmentView != null) {
                    ViewCompat.dispatchApplyWindowInsets(FeedActivity.this.fragmentView, FeedActivity.this.lastWindowInsets);
                }
                invalidateParentTabsGlass();
            }
        };
        chatContainer.chatActivity.isInsideContainer = false;
        chatContainer.chatActivity.setFeedChannelsChangedCallback(this::updateFeedSubtitle);
        chatContainer.chatActivity.setGlassSourceInvalidationCallback(this::invalidateParentTabsGlass);
        updateFeedViewportActive(viewportFullyVisible);
        if (!uiResumedHeld) {
            chatContainer.onPause();
        }
        chatLayout.addView(chatContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        if (!uiActiveHeld) {
            uiActiveHeld = true;
            FeedController.getInstance(currentAccount).setUiActive(true);
        }

        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                if (chatContainer == null || chatContainer.chatActivity == null) {
                    return 0;
                }
                return chatContainer.chatActivity.getBulletinBottomOffset();
            }

            @Override
            public int getTopOffset(int tag) {
                if (chatContainer == null || chatContainer.chatActivity == null) {
                    return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
                }
                return chatContainer.chatActivity.getBulletinTopOffset();
            }
        });
        return fragmentView;
    }

    private WindowInsetsCompat extendInsetsByTabsHeight(WindowInsetsCompat windowInsets) {
        lastWindowInsets = windowInsets;
        final int tabsHeight = AndroidUtilities.dp(MainTabsUiHelper.getTabsViewHeightDp()) + miniPlayerPadding;
        if (tabsHeight == 0) {
            return windowInsets;
        }
        final Insets systemBars = extendBottom(
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()), tabsHeight);
        final Insets navigationBars = extendBottom(
                windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()), tabsHeight);
        // setInsets мало: WindowInsetsStateHolder — а через него и нижний отступ
        // списка сообщений — читает getInsetsIgnoringVisibility(), и там на API 30+
        // лежит отдельный массив. Builder копирует его из исходных инсетов, поэтому
        // без второй пары вызовов чат считал, что снизу только системная полоса,
        // и последнее сообщение уезжало под панель вкладок.
        return new WindowInsetsCompat.Builder(windowInsets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), systemBars)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBars)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), extendBottom(
                        windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()), tabsHeight))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), extendBottom(
                        windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()), tabsHeight))
                .build();
    }

    private static Insets extendBottom(Insets insets, int extra) {
        return Insets.of(insets.left, insets.top, insets.right, insets.bottom + extra);
    }

    private int miniPlayerPadding;

    @Override
    public void setMiniPlayerInset(float inset, int padding) {
        if (miniPlayerPadding == padding) {
            return;
        }
        miniPlayerPadding = padding;
        if (fragmentView != null && lastWindowInsets != null) {
            ViewCompat.dispatchApplyWindowInsets(fragmentView, lastWindowInsets);
        }
    }

    private void applyFloatingWindowLayout() {
        if (getParentLayout() == null || !getParentLayout().isLayersLayout() || chatContainer == null || chatContainer.chatActivity == null) {
            return;
        }
        final ChatActivity chatActivity = chatContainer.chatActivity;
        if (chatActivity.getActionBar() != null) {
            chatActivity.getActionBar().setOccupyStatusBar(false);
        }
        if (chatActivity.avatarContainer != null) {
            chatActivity.avatarContainer.setOccupyStatusBar(false);
        }
        if (chatActivity.contentView != null) {
            chatActivity.contentView.setOccupyStatusBar(false);
        }
    }

    private void applyMainTabsHeaderLayout() {
        if (chatContainer == null || chatContainer.chatActivity == null || chatContainer.chatActivity.avatarContainer == null) {
            return;
        }
        final ChatAvatarContainer avatarContainer = chatContainer.chatActivity.avatarContainer;
        final ViewGroup.LayoutParams layoutParams = avatarContainer.getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
        final int leftMargin = AndroidUtilities.dp(ChatHeaderUiHelper.isMaterial3ChatHeaderStyle() ? HEADER_LEFT_MARGIN_M3_DP : 0);
        if (marginLayoutParams.leftMargin != leftMargin) {
            marginLayoutParams.leftMargin = leftMargin;
            avatarContainer.setLayoutParams(marginLayoutParams);
        }
    }

    private void setupChatActionBar() {
        if (chatContainer == null || chatContainer.chatActivity == null || chatContainer.chatActivity.getActionBar() == null) {
            return;
        }
        final ActionBar chatActionBar = chatContainer.chatActivity.getActionBar();
        final ActionBarMenu menu = chatActionBar.createMenu();
        if (menu.getItem(MENU_MARK_ALL_READ) == null) {
            menu.addItem(MENU_MARK_ALL_READ, R.drawable.msg_markread, chatContainer.chatActivity.themeDelegate)
                    .setContentDescription(LocaleController.getString(R.string.FeedMarkAllRead));
        }
        if (menu.getItem(MENU_FEED_SETTINGS) == null) {
            menu.addItem(MENU_FEED_SETTINGS, R.drawable.msg_settings, chatContainer.chatActivity.themeDelegate)
                    .setContentDescription(LocaleController.getString(R.string.FeedSettings));
        }
        if (hasMainTabs) {
            applyMainTabsHeaderLayout();
        }
        final ActionBar.ActionBarMenuOnItemClick chatItemClick = chatActionBar.getActionBarMenuOnItemClick();
        chatActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public boolean canOpenMenu() {
                return chatItemClick == null || chatItemClick.canOpenMenu();
            }

            @Override
            public void onItemClick(int id) {
                if (id == -1 && hasMainTabs && !chatActionBar.isActionModeShowed()) {
                    return;
                }
                if (id == MENU_MARK_ALL_READ) {
                    showMarkAllReadDialog();
                    return;
                }
                if (id == MENU_FEED_SETTINGS) {
                    presentFragment(new FeedChannelsActivity());
                    return;
                }
                if (chatItemClick != null) {
                    chatItemClick.onItemClick(id);
                }
            }
        });
    }

    private void setupChatTitle() {
        if (chatContainer == null || chatContainer.chatActivity == null || chatContainer.chatActivity.avatarContainer == null) {
            return;
        }
        final ChatAvatarContainer avatarContainer = chatContainer.chatActivity.avatarContainer;
        avatarContainer.setTitle(LocaleController.getString(R.string.Feed));
        avatarContainer.setFeedAvatar();
        updateFeedSubtitle();
    }

    private ChatSearchTabs getCategoryTabsHost() {
        return chatContainer == null || chatContainer.chatActivity == null ? null : chatContainer.chatActivity.hashtagSearchTabs;
    }

    private void setupCategoryTabs() {
        final ChatSearchTabs host = getCategoryTabsHost();
        if (host == null) {
            return;
        }
        categoryTabs = new FeedCategoryTabsView(chatContainer.getContext(), this, currentAccount,
                chatContainer.chatActivity.getResourceProvider(), new FeedCategoryTabsView.Delegate() {
            @Override
            public void onCategorySelected(int id) {
                switchCategory(id);
            }

            @Override
            public void onCreateCategory() {
                presentFragment(new FeedCategoryEditActivity(0, true));
            }

            @Override
            public void onEditCategory(FeedConfig.Category category) {
                presentFragment(new FeedCategoryEditActivity(category.getId(), false));
            }

            @Override
            public void onHideCategory(FeedConfig.Category category) {
                hideCategory(category);
            }

            @Override
            public void onDeleteCategory(FeedConfig.Category category) {
                FeedCategoryEditActivity.showDeleteDialog(FeedActivity.this, category, FeedActivity.this::applyCategoryChanges);
            }
        });
        categoryTabs.attachTo(host);
        lastCategoriesVersion = FeedConfig.getInstance(currentAccount).getCategoriesVersion();
        host.show(categoryTabs.hasCategories(), false);
    }

    private void switchCategory(int id) {
        if (FeedConfig.getInstance(currentAccount).selectCategory(id)) {
            applyCategoryChanges();
        }
    }

    private boolean applyCategoryChanges() {
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        if (categoryTabs != null && lastCategoriesVersion != config.getCategoriesVersion()) {
            lastCategoriesVersion = config.getCategoriesVersion();
            categoryTabs.update();
            final ChatSearchTabs host = getCategoryTabsHost();
            if (host != null && host.isShown() != categoryTabs.hasCategories()) {
                host.show(categoryTabs.hasCategories());
            }
        }
        if (config.getSelectedCategoryId() == lastSelectedCategoryId) {
            return false;
        }
        lastSelectedCategoryId = config.getSelectedCategoryId();
        lastConfigGeneration = config.getGeneration();
        final FeedController controller = FeedController.getInstance(currentAccount);
        controller.requestBadgeRefresh();
        final ChatActivity chatActivity = chatContainer == null ? null : chatContainer.chatActivity;
        if (chatActivity == null) {
            controller.clear();
        } else {
            if (chatActivity.getActionBar() != null && chatActivity.getActionBar().isActionModeShowed()) {
                chatActivity.clearSelectionMode();
            }
            chatActivity.reloadFeed();
        }
        updateFeedSubtitle();
        return true;
    }

    private void hideCategory(FeedConfig.Category category) {
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        final boolean wasSelected = config.getSelectedCategoryId() == category.getId();
        if (!config.setCategoryHidden(category.getId(), true)) {
            return;
        }
        applyCategoryChanges();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.caption_hide,
                AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedCategoryHiddenInfo, category.getTitle())),
                LocaleController.getString(R.string.Undo), () -> {
                    if (!config.setCategoryHidden(category.getId(), false)) {
                        return;
                    }
                    if (wasSelected) {
                        config.selectCategory(category.getId());
                    }
                    applyCategoryChanges();
                }).show();
    }

    private void updateFeedSubtitle() {
        final FeedController controller = FeedController.getInstance(currentAccount);
        setFeedSubtitle(controller.getIncludedChannelCount());
        controller.loadChannels((channels, includedCount) -> setFeedSubtitle(includedCount));
    }

    private void setFeedSubtitle(int channelCount) {
        if (chatContainer == null || chatContainer.chatActivity == null || chatContainer.chatActivity.avatarContainer == null) {
            return;
        }
        final ChatAvatarContainer avatarContainer = chatContainer.chatActivity.avatarContainer;
        avatarContainer.setSubtitle(LocaleController.formatPluralString("Channels", channelCount));
        final View subtitleTextView = avatarContainer.getSubtitleTextView();
        if (subtitleTextView != null) {
            subtitleTextView.setVisibility(View.VISIBLE);
        }
    }

    private void showMarkAllReadDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.FeedMarkAllRead));
        builder.setMessage(LocaleController.getString(R.string.FeedMarkAllReadConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.MarkAsRead), (dialog, which) -> {
            markAllRead();
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.FeedMarkAllReadDone))
                    .show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    public void markAllRead() {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            FeedController.getInstance(currentAccount).markAllRead();
        } else {
            chatContainer.chatActivity.markFeedAsRead();
        }
    }

    private void destroyEmbeddedChat() {
        if (chatContainer != null && chatContainer.chatActivity != null) {
            if (!hasMainTabs && embeddedChatCreated) {
                chatContainer.chatActivity.saveFeedScrollPosition();
            }
            chatContainer.chatActivity.setFeedChannelsChangedCallback(null);
            chatContainer.chatActivity.setGlassSourceInvalidationCallback(null);
            if (embeddedChatCreated) {
                chatContainer.chatActivity.onFragmentDestroy();
            }
        }
        embeddedChatCreated = false;
        chatContainer = null;
        categoryTabs = null;
    }

    private void updateFeedViewportActive(boolean active) {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            return;
        }
        chatContainer.chatActivity.setFeedViewportActive(active);
    }

    private void reattachCurrentFeedVideoTexture() {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            return;
        }
        chatContainer.chatActivity.reattachCurrentFeedVideoTexture();
    }

    /**
     * Пересобрать запись RenderNode у шапки встроенного чата.
     *
     * Пока лента вынута из окна, инвалидация детей экшн-бара до него самого не доходит:
     * дети остаются грязными, а он — чистым, и при повторном подключении переиспользует
     * старую запись, в которой детей ещё не было. Видно как пустые стеклянные пилюли
     * без аватарки, заголовка и кнопок.
     */
    private void invalidateEmbeddedActionBar() {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            return;
        }
        final ActionBar chatActionBar = chatContainer.chatActivity.getActionBar();
        if (chatActionBar == null) {
            return;
        }
        chatActionBar.invalidate();
        for (int i = 0; i < chatActionBar.getChildCount(); i++) {
            chatActionBar.getChildAt(i).invalidate();
        }
    }

    private void invalidateParentTabsGlass() {
        if (parentTabsGlassInvalidationCallback != null) {
            parentTabsGlassInvalidationCallback.run();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            final boolean scheduled = (Boolean) args[2];
            if (scheduled || chatContainer == null
                    || !FeedController.getInstance(currentAccount).isIncludedChannelPost((Long) args[0])) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(loadNewPosts);
            AndroidUtilities.runOnUIThread(loadNewPosts, LOAD_NEW_POSTS_DELAY);
        } else if (id == NotificationCenter.feedNeedReload) {
            if (chatContainer != null && chatContainer.chatActivity != null) {
                final boolean truncated = args.length > 0 && Boolean.TRUE.equals(args[0]);
                chatContainer.chatActivity.onFeedChannelsChanged(truncated);
            }
            updateFeedSubtitle();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (chatContainer != null) {
            chatContainer.onResume();
            updateFeedViewportActive(viewportFullyVisible);
        }
        if (!uiResumedHeld) {
            uiResumedHeld = true;
            FeedController.getInstance(currentAccount).setUiResumed(true);
        }
        if (fragmentView != null) {
            if (lastWindowInsets != null) {
                ViewCompat.dispatchApplyWindowInsets(fragmentView, lastWindowInsets);
            } else {
                fragmentView.requestApplyInsets();
            }
        }
        reattachCurrentFeedVideoTexture();
        invalidateEmbeddedActionBar();

        final boolean categorySwitched = applyCategoryChanges();
        final int generation = FeedConfig.getInstance(currentAccount).getGeneration();
        if (categorySwitched) {
            lastConfigGeneration = generation;
        } else if (generation != lastConfigGeneration) {
            lastConfigGeneration = generation;
            if (chatContainer != null && chatContainer.chatActivity != null) {
                chatContainer.chatActivity.applyFeedConfigChange();
            }
        } else if (resumedOnce && chatContainer != null && chatContainer.chatActivity != null) {
            chatContainer.chatActivity.reconcileFeedList();
            chatContainer.chatActivity.refreshFeedUnreadDivider();
            if (!FeedController.getInstance(currentAccount).getMessages().isEmpty()) {
                chatContainer.chatActivity.loadNewerFeed(true);
            }
        }
        resumedOnce = true;
        updateFeedSubtitle();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (chatContainer != null) {
            updateFeedViewportActive(false);
            chatContainer.onPause();
        }
        if (uiResumedHeld) {
            uiResumedHeld = false;
            FeedController.getInstance(currentAccount).setUiResumed(false);
        }
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        viewportFullyVisible = true;
        updateFeedViewportActive(true);
        reattachCurrentFeedVideoTexture();
        invalidateEmbeddedActionBar();
    }

    @Override
    public void onBecomeFullyHidden() {
        viewportFullyVisible = false;
        updateFeedViewportActive(false);
        super.onBecomeFullyHidden();
    }

    @Override
    public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        invalidateEmbeddedActionBar();
        if (hasMainTabs) {
            viewportFullyVisible = false;
            updateFeedViewportActive(false);
        }
        super.onTransitionAnimationStart(isOpen, backward);
    }

    @Override
    public void onTransitionAnimationProgress(boolean isOpen, float progress) {
        super.onTransitionAnimationProgress(isOpen, progress);
        invalidateEmbeddedActionBar();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        invalidateEmbeddedActionBar();
        if (hasMainTabs) {
            viewportFullyVisible = isOpen;
            updateFeedViewportActive(isOpen);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (chatContainer == null || chatContainer.chatActivity == null
                || chatContainer.chatActivity.getActionBar() == null
                || !chatContainer.chatActivity.getActionBar().isActionModeShowed()) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            chatContainer.chatActivity.clearSelectionMode();
        }
        return false;
    }

    @Override
    public boolean isLightStatusBar() {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            return !Theme.isCurrentThemeDark();
        }
        return chatContainer.chatActivity.isLightStatusBar();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (categoryTabs != null && categoryTabs.isTouchInside(ev)) {
            return false;
        }
        return chatContainer == null || chatContainer.chatActivity == null
                || chatContainer.chatActivity.getActionBar() == null
                || !chatContainer.chatActivity.getActionBar().isActionModeShowed();
    }

    @Override
    public void onParentScrollToTop() {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            return;
        }
        chatContainer.chatActivity.onPageDownClicked();
    }

    @Override
    public GlassSourceRenderNode getGlassSource() {
        if (chatContainer == null || chatContainer.chatActivity == null) {
            return null;
        }
        return chatContainer.chatActivity.getGlassSource();
    }

    /** Вызывается MainTabsActivity, когда вкладка стала полностью видимой. */
    public void onParentBecomeFullyVisible() {
        reattachCurrentFeedVideoTexture();
    }

    /** Колбэк перерисовки стекла нижних вкладок; вызывается MainTabsActivity. */
    public void setParentTabsGlassInvalidationCallback(Runnable callback) {
        parentTabsGlassInvalidationCallback = callback;
    }
}
