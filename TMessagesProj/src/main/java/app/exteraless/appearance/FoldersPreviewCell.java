package app.exteraless.appearance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Live preview of chat folders. It deliberately reuses the same {@link FilterTabsView}
 * as the chat list, so changes to the real component's shape, spacing and drawing are
 * reflected here without maintaining a second approximation.
 */
@SuppressLint("ViewConstructor")
public class FoldersPreviewCell extends FrameLayout {

    private static final int TAB_ALL = 0;
    private static final int TAB_GROUPS = 1;
    private static final int TAB_BOTS = 2;
    private static final int TAB_CHANNELS = 3;

    private final Theme.ResourcesProvider resourcesProvider;
    private final FilterTabsView tabsView;
    private final GlassSourceColor backgroundSource;

    private int renderedTitleType = Integer.MIN_VALUE;

    public FoldersPreviewCell(Context context) {
        this(context, null);
    }

    public FoldersPreviewCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setClipChildren(false);

        tabsView = new FilterTabsView(context, resourcesProvider);
        tabsView.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
            @Override
            public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
                // The preview has no pages, but the real selector animation is useful here.
            }

            @Override
            public void onPageScrolled(float progress) {
            }

            @Override
            public void onSamePageSelected() {
            }

            @Override
            public int getTabCounter(int tabId) {
                if (NaConfig.INSTANCE.getIgnoreUnreadCount().Int() == NekoConfig.DIALOG_FILTER_EXCLUDE_ALL) {
                    return 0;
                }
                return tabId == (NekoConfig.hideAllTab.Bool() ? TAB_GROUPS : TAB_ALL) ? 3 : 0;
            }

            @Override
            public boolean didSelectTab(FilterTabsView.TabView tabView, boolean selected) {
                return false;
            }

            @Override
            public boolean isTabMenuVisible() {
                return false;
            }

            @Override
            public void onDeletePressed(int id) {
            }

            @Override
            public void onPageReorder(int fromId, int toId) {
            }

            @Override
            public boolean canPerformActions() {
                return false;
            }
        });
        tabsView.setHorizontalScrollingEnabled(false);

        // Match the component setup in DialogsActivity. Only the obsolete preview
        // frame is omitted: the cell itself remains transparent and blends into its row.
        backgroundSource = new GlassSourceColor();
        backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        BlurredBackgroundDrawable background = new BlurredBackgroundDrawableViewFactory(backgroundSource)
                .create(tabsView, BlurredBackgroundProviderImpl.topPanel(resourcesProvider));
        background.setRadius(dp(18));
        background.setPadding(dp(6.666f));
        tabsView.setPadding(0, dp(7), 0, dp(7));
        tabsView.setBlurredBackground(background);

        addView(tabsView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER, 21, 0, 21, 0));
        rebuildTabs(false);
    }

    private void rebuildTabs(boolean animated) {
        renderedTitleType = NekoConfig.tabsTitleType.Int();
        tabsView.showAllChatsTab = !NekoConfig.hideAllTab.Bool();
        tabsView.removeTabs();
        tabsView.resetTabId();

        if (tabsView.showAllChatsTab) {
            tabsView.addTab(TAB_ALL, TAB_ALL, LocaleController.getString(R.string.FilterAllChats),
                    "\uD83D\uDCAC", null, false, true, false);
        }
        tabsView.addTab(TAB_GROUPS, TAB_GROUPS, LocaleController.getString(R.string.FilterGroups),
                "\uD83D\uDC65", null, false, false, false);
        tabsView.addTab(TAB_BOTS, TAB_BOTS, LocaleController.getString(R.string.FilterBots),
                "\uD83E\uDD16", null, false, false, false);
        tabsView.addTab(TAB_CHANNELS, TAB_CHANNELS, LocaleController.getString(R.string.FilterChannels),
                "\uD83D\uDCE2", null, false, false, false);
        tabsView.finishAddingTabs(animated);
        tabsView.requestLayout();
    }

    public void updateAllChatsTabName(boolean animate) {
        rebuildTabs(animate);
    }

    public void updateTabTitle(boolean animate) {
        if (renderedTitleType != NekoConfig.tabsTitleType.Int()) {
            rebuildTabs(animate);
        }
    }

    public void updateTabIcons(boolean animate) {
        if (renderedTitleType != NekoConfig.tabsTitleType.Int()) {
            rebuildTabs(animate);
        }
    }

    public void updateTabCounter(boolean animate) {
        tabsView.checkTabsCounter();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (backgroundSource != null) {
            backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        }
        if (tabsView != null) {
            tabsView.updateColors();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(LocaleController.isRTL ? 0 : dp(21), getMeasuredHeight() - 1,
                getMeasuredWidth() - (LocaleController.isRTL ? dp(21) : 0), getMeasuredHeight() - 1,
                Theme.dividerPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(86), MeasureSpec.EXACTLY));
    }
}
