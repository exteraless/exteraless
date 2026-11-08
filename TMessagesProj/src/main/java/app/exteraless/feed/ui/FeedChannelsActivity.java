package app.exteraless.feed.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.feed.FeedConfig;
import app.exteraless.feed.FeedController;

/**
 * Экран настроек ленты: общие переключатели и состав каналов.
 *
 * Порт {@code com/exteragram/messenger/feed/ui/FeedChannelsActivity.java}. Сверху секция
 * General — «показывать ленту в нижних вкладках» и «включать каналы из архива», ниже два
 * списка с чекбоксами: показанные каналы и скрытые. В шапке поиск по названию и меню
 * «выбрать все» / «снять все».
 *
 * У эталона экран наследует BasePreferencesActivity; у нас такого базового класса нет,
 * поэтому список на UniversalRecyclerView собирается здесь же, а публичные методы
 * (fillItems, onClick, getTitle) оставлены с эталонными сигнатурами.
 */
public class FeedChannelsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_SEARCH = 0;
    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_DESELECT_ALL = 2;
    private static final int MENU_OTHER = 3;

    private static final int ID_BOTTOM_TAB = Integer.MAX_VALUE - 1;
    private static final int ID_UNREAD_COUNTER = Integer.MAX_VALUE - 2;
    private static final int ID_INCLUDE_ARCHIVED = Integer.MAX_VALUE;
    private static final int ID_ADD_NEW_CHANNELS = Integer.MAX_VALUE - 3;
    private static final int ID_CREATE_CATEGORY = Integer.MAX_VALUE - 4;
    private static final int ID_CATEGORY_BASE = -1000;

    private static final Comparator<TLRPC.Chat> BY_TITLE =
            Comparator.comparing(FeedChannelsActivity::sortKey);

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    private UniversalRecyclerView listView;
    private ActionBarMenuItem otherItem;
    private String query;
    private boolean searching;

    private static String sortKey(TLRPC.Chat chat) {
        return chat.title == null ? "" : chat.title.toLowerCase(Locale.ROOT);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SELECT_ALL) {
                    setAllExcluded(false);
                } else if (id == MENU_DESELECT_ALL) {
                    setAllExcluded(true);
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SEARCH, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        if (otherItem != null) {
                            otherItem.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onSearchCollapse() {
                        searching = false;
                        query = null;
                        if (otherItem != null) {
                            otherItem.setVisibility(View.VISIBLE);
                        }
                        update();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        query = editText.getText().toString().trim().toLowerCase(Locale.ROOT);
                        update();
                    }
                })
                .setSearchFieldHint(getString(R.string.Search));

        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, getString(R.string.SelectAll));
        otherItem.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, getString(R.string.DeselectAll));

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick,
                (item, view, position, x, y) -> false);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.allowReorder(true);
        listView.listenReorder((section, items) -> {
            ArrayList<Integer> ids = new ArrayList<>();
            for (UItem item : items) {
                if (item.object instanceof FeedConfig.Category) {
                    ids.add(((FeedConfig.Category) item.object).getId());
                }
            }
            FeedConfig.getInstance(currentAccount).reorderCategories(ids);
        });
        contentView.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        fragmentView = contentView;
        reloadChannels();
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
        }
    }

    public String getTitle() {
        return getString(R.string.FeedSettings);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.feedNeedReload) {
            reloadChannels();
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!searching) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            actionBar.closeSearchField();
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    private void reloadChannels() {
        FeedController.getInstance(currentAccount).loadChannels((loaded, includedCount) -> {
            channels.clear();
            channels.addAll(loaded);
            channels.sort(BY_TITLE);
            update();
        });
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private ArrayList<Long> channelIds() {
        ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            dialogIds.add(-channels.get(i).id);
        }
        return dialogIds;
    }

    private void setAllExcluded(boolean excluded) {
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        if (excluded) {
            config.excludeAll(channelIds());
        } else {
            config.includeAll(channelIds());
        }
        update();
    }

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        boolean noQuery = TextUtils.isEmpty(query);
        if (noQuery) {
            items.add(UItem.asHeader(getString(R.string.General)));
            items.add(UItem.asCheck(ID_BOTTOM_TAB, getString(R.string.FeedBottomTab),
                    getString(R.string.FeedBottomTabInfo), true)
                    .setChecked(AppearanceConfig.showFeedTab()));
            items.add(UItem.asCheck(ID_UNREAD_COUNTER, getString(R.string.FeedUnreadCounter))
                    .setChecked(AppearanceConfig.showFeedUnreadCounter()));
            items.add(UItem.asCheck(ID_INCLUDE_ARCHIVED, getString(R.string.FeedIncludeArchived),
                    getString(R.string.FeedIncludeArchivedInfo), true)
                    .setChecked(config.getIncludeArchived()));
            items.add(UItem.asCheck(ID_ADD_NEW_CHANNELS, getString(R.string.FeedAddNewChannels),
                    getString(config.getAddNewChannels() ? R.string.FeedAddNewChannelsOnInfo : R.string.FeedAddNewChannelsOffInfo), true)
                    .setChecked(config.getAddNewChannels()));
            items.add(UItem.asShadow((CharSequence) null));
            addCategoriesSection(items, adapter);
        }

        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            if (!noQuery && (chat.title == null || !chat.title.toLowerCase(Locale.ROOT).contains(query))) {
                continue;
            }
            boolean excluded = config.isHiddenFromAll(-chat.id);
            UItem item = UItem.asUserCheckbox(i + 1, chat).setChecked(!excluded);
            (excluded ? hidden : shown).add(item);
        }

        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.FeedShownChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow((CharSequence) null));
            }
            items.add(UItem.asHeader(getString(R.string.FeedHiddenChannels)));
            items.addAll(hidden);
        }
        if (noQuery && !(shown.isEmpty() && hidden.isEmpty())) {
            items.add(UItem.asShadow(getString(R.string.FeedChannelsInfo)));
        }
    }

    private void addCategoriesSection(ArrayList<UItem> items, UniversalAdapter adapter) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(getString(R.string.FeedCategories)));
        adapter.reorderSectionStart();
        for (FeedConfig.Category category : FeedConfig.getInstance(currentAccount).getCategories()) {
            items.add(FeedCategoryCell.Factory.of(ID_CATEGORY_BASE - category.getId(), category,
                    v -> toggleCategoryHidden(category.getId())));
        }
        adapter.reorderSectionEnd();
        items.add(UItem.asButton(ID_CREATE_CATEGORY, R.drawable.msg_add, getString(R.string.FeedCategoryCreate)).accent());
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(getString(R.string.FeedCategoriesInfo)));
    }

    private void toggleCategoryHidden(int id) {
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        FeedConfig.Category category = config.getCategory(id);
        if (category != null && config.setCategoryHidden(id, !category.isHidden())) {
            update();
        }
    }

    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof FeedConfig.Category) {
            presentFragment(new FeedCategoryEditActivity(((FeedConfig.Category) item.object).getId(), false));
        } else if (item.id == ID_CREATE_CATEGORY) {
            presentFragment(new FeedCategoryEditActivity(0, false));
        } else if (item.id == ID_BOTTOM_TAB) {
            AppearanceConfig.showFeedTab.toggleConfigBool();
            update();
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.feedTabVisibleToggled);
        } else if (item.id == ID_UNREAD_COUNTER) {
            AppearanceConfig.showFeedUnreadCounter.toggleConfigBool();
            update();
            // Своего события для счётчика нет, а перестроение вкладок заодно
            // пересчитывает бейджи — берём его вместо новой константы в общем
            // NotificationCenter.
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.feedTabVisibleToggled);
        } else if (item.id == ID_ADD_NEW_CHANNELS) {
            FeedConfig config = FeedConfig.getInstance(currentAccount);
            config.setAddNewChannels(!config.getAddNewChannels(), channelIds());
            update();
        } else if (item.id == ID_INCLUDE_ARCHIVED) {
            FeedConfig config = FeedConfig.getInstance(currentAccount);
            config.setIncludeArchived(!config.getIncludeArchived());
            reloadChannels();
        } else if (item.object instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) item.object;
            boolean checked = !item.checked;
            FeedConfig.getInstance(currentAccount).setExcluded(-chat.id, !checked);
            setCheckedAndRefresh(item, checked);
        }
    }

    /**
     * Ставит галочку сразу на самой ячейке, а потом пересобирает список: без этого
     * канал переезжает между секциями «показанные» и «скрытые» без анимации чекбокса.
     */
    private void setCheckedAndRefresh(UItem item, boolean checked) {
        item.setChecked(checked);
        View cell = listView == null ? null : listView.findViewByItemId(item.id);
        if (cell instanceof CheckBoxCell) {
            ((CheckBoxCell) cell).setChecked(checked, true);
        }
        update();
    }
}
