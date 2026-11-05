package com.exteragram.messenger.preferences.appearance.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("ViewConstructor")
public class FilterTabsPreviewCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int[] SAMPLE_IDS = {100, 101, 102, 103};
    private static final String[] SAMPLE_EMOTICONS = {"👤", "👥", "📢", "🤖"};
    private static final int[] SAMPLE_TITLES = {R.string.FilterContacts, R.string.FilterGroups, R.string.FilterChannels, R.string.FilterBots};

    private final FilterTabsView filterTabsView;
    private final BlurredBackgroundSourceColor backgroundSource;
    private final Map<Integer, Integer> idsWithCounters = new HashMap<>();
    private final int counterSeed = Utilities.random.nextInt(40) + 20;

    public FilterTabsPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);

        backgroundSource = new BlurredBackgroundSourceColor();
        backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        filterTabsView = new FilterTabsView(context, null);
        filterTabsView.setPadding(0, dp(7), 0, dp(7));
        BlurredBackgroundDrawable background = new BlurredBackgroundDrawableViewFactory(backgroundSource)
                .create(filterTabsView, BlurredBackgroundProviderImpl.topPanel(null));
        background.setRadius(dp(18));
        background.setPadding(dp(6.666f));
        filterTabsView.setBlurredBackground(background);
        filterTabsView.setColors(Theme.key_actionBarTabLine, Theme.key_actionBarTabActiveText,
                Theme.key_actionBarTabUnactiveText, Theme.key_actionBarTabSelector, Theme.key_actionBarDefault);
        filterTabsView.setDelegate(new PreviewDelegate());
        addView(filterTabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER, 12, 0, 12, 0));
        updateTabs(false);
    }

    private class PreviewDelegate implements FilterTabsView.FilterTabsViewDelegate {

        @Override
        public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
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
            Integer counter = idsWithCounters.get(tabId);
            return counter != null ? counter : 0;
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
    }

    public void updateTabs(boolean animated) {
        filterTabsView.resetTabId();
        filterTabsView.removeTabs();
        idsWithCounters.clear();
        boolean hideAllChats = NekoConfig.hideAllTab.Bool();
        filterTabsView.showAllChatsTab = !hideAllChats;
        ArrayList<MessagesController.DialogFilter> filters = MessagesController.getInstance(UserConfig.selectedAccount).getDialogFilters();
        int added = 0;
        int selectedId = -1;
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter filter = filters.get(i);
            if (filter.isDefault() && hideAllChats) {
                continue;
            }
            if (i == 0 || i == 1 || i == 3) {
                idsWithCounters.put(filter.id, counterSeed / (i + 1));
            }
            if (selectedId == -1) {
                selectedId = filter.id;
            }
            if (filter.isDefault()) {
                filterTabsView.addTab(filter.id, 0, LocaleController.getString(R.string.FilterAllChats),
                        "💬", null, false, true, filter.locked);
            } else {
                filterTabsView.addFolderTab(filter.id, filter);
            }
            added++;
        }
        for (int i = 0; i < SAMPLE_IDS.length && added < 6; i++, added++) {
            int position = filters.size() + i;
            if (position == 0 || position == 1 || position == 3) {
                idsWithCounters.put(SAMPLE_IDS[i], counterSeed / (position + 1));
            }
            if (selectedId == -1) {
                selectedId = SAMPLE_IDS[i];
            }
            filterTabsView.addTab(SAMPLE_IDS[i], SAMPLE_IDS[i], LocaleController.getString(SAMPLE_TITLES[i]),
                    SAMPLE_EMOTICONS[i], null, false, false, false);
        }
        filterTabsView.finishAddingTabs(animated);
        if (selectedId != -1) {
            filterTabsView.selectTabWithId(selectedId, 1f);
        } else {
            filterTabsView.selectFirstTab();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated) {
            updateTabs(true);
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (backgroundSource != null) {
            backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        if (filterTabsView != null) {
            filterTabsView.updateColors();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(74), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
    }
}
