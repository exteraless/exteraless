package app.exteraless.plugins.menus;

import android.content.Context;
import android.widget.LinearLayout;

import android.view.View;

import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.exteraless.drawer.DrawerMenuItemView;
import app.exteraless.plugins.MenuItemRecord;
import app.exteraless.plugins.PluginsController;

/**
 * Рендерер пунктов меню плагинов в меню Telegram. Зовётся из патчей ядра:
 *  - {@link #fillMessageMenu} — конец {@code ChatActivity.fillMessageMenu} (MESSAGE_CONTEXT_MENU);
 *  - {@link #handleMessageMenuOption} — начало {@code ChatActivity.processSelectedOption};
 *  - {@link #appendDrawerItems} — конец {@code DrawerMenuView.rebuildMenu} (DRAWER_MENU);
 *  - {@link #appendMainMenuItems} — {@code DialogsActivity.showItemOptions} перед io.show() (MAIN_MENU).
 *
 * Видимость пункта — MVEL-выражение {@link MenuItemRecord#condition} над Map-контекстом
 * (null/ошибка = пункт виден). Клик уходит в
 * {@link PluginsController#dispatchMenuClick(String, String, Map)}.
 *
 * Всё зовётся на UI-потоке; при выключенном движке или пустом реестре работа не делается.
 */
public final class MenuInjector {

    /**
     * База id пунктов меню сообщения. Стоковые OPTION_* — 0..152, nkbtn_* — 2008..2101,
     * AyuConstants.OPTION_* — 133801+, поэтому 9_000_000 не пересекается ни с чем.
     */
    private static final int MESSAGE_OPTION_BASE = 9_000_000;

    /** Пункт последнего построенного меню сообщения: option id = BASE + индекс. Только UI-поток. */
    private static final class MessageMenuEntry {
        final MenuItemRecord record;
        final Map<String, Object> context;

        MessageMenuEntry(MenuItemRecord record, Map<String, Object> context) {
            this.record = record;
            this.context = context;
        }
    }

    private static final ArrayList<MessageMenuEntry> messageMenuEntries = new ArrayList<>();

    private static final ConcurrentHashMap<String, Serializable> COMPILED_CONDITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> ICON_IDS = new ConcurrentHashMap<>();

    private MenuInjector() {
    }

    public static void releaseMessageMenu(BaseFragment fragment) {
        if (messageMenuEntries.isEmpty()) {
            return;
        }
        Object owner = messageMenuEntries.get(0).context.get("fragment");
        if (owner == null || owner == fragment) {
            messageMenuEntries.clear();
        }
    }

    // ---------- общее ----------

    /** Видимость пункта по MVEL-condition; null/пустое = виден, ошибка вычисления = скрыт. */
    public static boolean isVisible(MenuItemRecord item, Map<String, Object> context) {
        if (item.condition == null || item.condition.isEmpty()) {
            return true;
        }
        try {
            Serializable compiled = COMPILED_CONDITIONS.get(item.condition);
            if (compiled == null) {
                compiled = MVEL.compileExpression(item.condition);
                if (COMPILED_CONDITIONS.size() < 512) {
                    COMPILED_CONDITIONS.put(item.condition, compiled);
                }
            }
            Boolean result = MVEL.executeExpression(compiled, context, Boolean.class);
            return result != null && result;
        } catch (Throwable t) {
            FileLog.e("MenuInjector: condition failed for " + item.pluginId + "/" + item.itemId, t);
            return false;
        }
    }

    /** icon — имя drawable-ресурса приложения; не резолвится → 0 (пункт без иконки). */
    public static int resolveIcon(Context context, String icon) {
        if (icon == null || icon.isEmpty()) {
            return 0;
        }
        Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return 0;
        }
        Integer cached = ICON_IDS.get(icon);
        if (cached != null) {
            return cached;
        }
        try {
            int id = ctx.getResources().getIdentifier(icon, "drawable", ctx.getPackageName());
            ICON_IDS.put(icon, id);
            return id;
        } catch (Throwable t) {
            FileLog.e("MenuInjector: bad icon " + icon, t);
            return 0;
        }
    }

    // ---------- MESSAGE_CONTEXT_MENU (ChatActivity) ----------

    /**
     * Дописать пункты плагинов в параллельные массивы меню сообщения.
     * Зовётся в конце {@code ChatActivity.fillMessageMenu}.
     */
    public static void fillMessageMenu(BaseFragment fragment, MessageObject message, TLRPC.Chat chat,
                                       TLRPC.User user, TLRPC.EncryptedChat encryptedChat,
                                       long dialogId, int account,
                                       ArrayList<Integer> icons, ArrayList<CharSequence> items,
                                       ArrayList<Integer> options) {
        messageMenuEntries.clear();
        if (!PluginsController.getInstance().isEngineEnabled()) {
            return;
        }
        List<MenuItemRecord> records =
                PluginsController.getInstance().getMenuItemsFor(MenuItemRecord.MenuType.MESSAGE_CONTEXT_MENU);
        if (records.isEmpty()) {
            return;
        }
        Context context = fragment != null ? fragment.getParentActivity() : null;
        Map<String, Object> menuContext = new HashMap<>();
        menuContext.put("message", message);
        if (chat != null) {
            menuContext.put("chat", chat);
            menuContext.put("chatId", chat.id);
        }
        if (user != null) {
            menuContext.put("user", user);
            menuContext.put("userId", user.id);
        }
        if (encryptedChat != null) {
            menuContext.put("encryptedChat", encryptedChat);
        }
        if (fragment != null) {
            menuContext.put("fragment", fragment);
        }
        if (context != null) {
            menuContext.put("context", context);
        }
        menuContext.put("dialog_id", dialogId);
        menuContext.put("account", account);
        for (MenuItemRecord record : records) {
            if (!isVisible(record, menuContext)) {
                continue;
            }
            items.add(record.text != null ? record.text : record.itemId);
            icons.add(resolveIcon(context, record.icon));
            options.add(MESSAGE_OPTION_BASE + messageMenuEntries.size());
            messageMenuEntries.add(new MessageMenuEntry(record, menuContext));
        }
    }

    /**
     * Перехват клика по пункту плагина. Зовётся в начале
     * {@code ChatActivity.processSelectedOption}.
     *
     * @return true, если option — пункт плагина и клик обработан.
     */
    public static boolean handleMessageMenuOption(int option) {
        int index = option - MESSAGE_OPTION_BASE;
        if (index < 0 || index >= messageMenuEntries.size()) {
            return false;
        }
        MessageMenuEntry entry = messageMenuEntries.get(index);
        PluginsController.getInstance()
                .dispatchMenuClick(entry.record.pluginId, entry.record.itemId, entry.context);
        return true;
    }

    // ---------- DRAWER_MENU (DrawerMenuView) ----------

    /**
     * Добавить пункты плагинов в конец боковой шторки. Зовётся в конце
     * {@code DrawerMenuView.rebuildMenu}.
     *
     * @param container   LinearLayout внутри DrawerMenuView, куда кладутся DrawerMenuItemView
     * @param onItemClick колбэк закрытия шторки из DrawerMenuView (может быть null)
     */
    public static void appendDrawerItems(LinearLayout container, int currentAccount, Runnable onItemClick) {
        if (container == null || !PluginsController.getInstance().isEngineEnabled()) {
            return;
        }
        List<MenuItemRecord> records =
                PluginsController.getInstance().getMenuItemsFor(MenuItemRecord.MenuType.DRAWER_MENU);
        if (records.isEmpty()) {
            return;
        }
        Map<String, Object> menuContext = new HashMap<>();
        menuContext.put("account", currentAccount);
        Context context = container.getContext();
        for (MenuItemRecord record : records) {
            if (!isVisible(record, menuContext)) {
                continue;
            }
            DrawerMenuItemView itemView = new DrawerMenuItemView(context);
            // layoutButtonId используется только для бейджа непрочитанных «Архива» — 0 его не даёт.
            itemView.setMenuItem(0, currentAccount, resolveIcon(context, record.icon),
                    record.text != null ? record.text : record.itemId);
            itemView.setOnClickListener(v -> {
                if (onItemClick != null) {
                    onItemClick.run();
                }
                Map<String, Object> clickContext = new HashMap<>();
                clickContext.put("account", currentAccount);
                PluginsController.getInstance()
                        .dispatchMenuClick(record.pluginId, record.itemId, clickContext);
            });
            container.addView(itemView);
        }
    }

    // ---------- CHAT_ACTION_MENU / PROFILE_ACTION_MENU (swipe-back подменю) ----------

    /**
     * Повесить подменю плагинов на всплывающее меню «⋮».
     *
     * Пунктов у плагинов бывает много (в публичном каталоге CHAT_ACTION_MENU —
     * самое популярное место, его занимают 70 плагинов из 361), поэтому они
     * уезжают в отдельный swipe-back-слой, а не в основное меню; так же в
     * exteraGram.
     *
     * @param previous результат прошлого вызова на том же экране (или null):
     *                 обёртка переиспользуется, пересоздаётся только пункт.
     * @return подменю и открывающий его пункт — владелец зовёт
     *         {@link SwipeBackMenu#refresh()} на
     *         {@code NotificationCenter.pluginMenuItemsUpdated}; null, если
     *         движок выключен или вешать некуда.
     */
    /** Пара «подменю + пункт, который его открывает»: владельцу нужны оба. */
    public static final class SwipeBackMenu {
        public final PluginsMenuWrapper wrapper;
        public final ActionBarMenuItem.Item item;
        final ActionBarMenuItem headerItem;

        SwipeBackMenu(PluginsMenuWrapper wrapper, ActionBarMenuItem.Item item, ActionBarMenuItem headerItem) {
            this.wrapper = wrapper;
            this.item = item;
            this.headerItem = headerItem;
        }

        /** Пересобрать и спрятать пункт, если плагины не дали ни одного. */
        public void refresh() {
            boolean hasItems = wrapper.hasItems();
            if (hasItems) {
                wrapper.rebuildMenu(null);
            }
            item.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        }
    }

    public static SwipeBackMenu attachSwipeBackMenu(ActionBarMenuItem headerItem,
                                                    MenuItemRecord.MenuType menuType,
                                                    Map<String, Object> contextData,
                                                    Theme.ResourcesProvider resourcesProvider,
                                                    SwipeBackMenu previous) {
        if (headerItem == null || !PluginsController.getInstance().isEngineEnabled()) {
            return previous;
        }
        ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout = headerItem.getPopupLayout();
        if (popupLayout == null || popupLayout.getSwipeBack() == null) {
            return previous;
        }
        try {
            // Экраны, которые пересобирают меню (ProfileActivity зовёт
            // removeAllSubItems на каждом createActionBarMenu), теряют пункт,
            // но не слой swipe-back: обёртку переиспользуем, иначе слои копятся.
            final boolean sameHost = previous != null && previous.headerItem == headerItem;
            PluginsMenuWrapper wrapper = sameHost ? previous.wrapper : new PluginsMenuWrapper(
                    popupLayout.getSwipeBack(), menuType, contextData, resourcesProvider) {
                @Override
                public void closeMenu() {
                    headerItem.toggleSubMenu();
                }
            };
            ActionBarMenuItem.Item item;
            if (sameHost) {
                wrapper.rebuildMenu(null);
                item = headerItem.lazilyReuseItem(previous.item);
            } else {
                item = headerItem.lazilyAddSwipeBackItem(
                        R.drawable.msg_settings_old, null,
                        LocaleController.getString(R.string.OpenExteraPlugins), wrapper.getSwipeBack());
                item.setOnClickListener(v -> item.openSwipeBack());
            }
            item.setVisibility(wrapper.hasItems() ? View.VISIBLE : View.GONE);
            return new SwipeBackMenu(wrapper, item, headerItem);
        } catch (Throwable t) {
            FileLog.e("MenuInjector: cannot attach " + menuType + " submenu", t);
            return previous;
        }
    }

    // ---------- MAIN_MENU (DialogsActivity «⋮») ----------

    /**
     * Добавить пункты плагинов в меню «⋮» главного экрана. Зовётся в
     * {@code DialogsActivity.showItemOptions} перед {@code io.show()}.
     */
    public static void appendMainMenuItems(ItemOptions io, int currentAccount) {
        if (io == null || !PluginsController.getInstance().isEngineEnabled()) {
            return;
        }
        List<MenuItemRecord> records =
                PluginsController.getInstance().getMenuItemsFor(MenuItemRecord.MenuType.MAIN_MENU);
        if (records.isEmpty()) {
            return;
        }
        Map<String, Object> menuContext = new HashMap<>();
        menuContext.put("account", currentAccount);
        boolean addedAny = false;
        for (MenuItemRecord record : records) {
            if (!isVisible(record, menuContext)) {
                continue;
            }
            if (!addedAny) {
                io.addGap();
                addedAny = true;
            }
            CharSequence text = record.text != null ? record.text : record.itemId;
            Runnable onClick = () -> {
                Map<String, Object> clickContext = new HashMap<>();
                clickContext.put("account", currentAccount);
                PluginsController.getInstance()
                        .dispatchMenuClick(record.pluginId, record.itemId, clickContext);
            };
            int iconRes = resolveIcon(null, record.icon);
            if (record.subtext != null) {
                io.add(text, record.subtext, onClick);
            } else if (iconRes != 0) {
                io.add(iconRes, text, onClick);
            } else {
                io.add(text, onClick);
            }
        }
    }
}
