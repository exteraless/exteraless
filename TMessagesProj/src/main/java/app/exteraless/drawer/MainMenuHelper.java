package app.exteraless.drawer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.media3.common.util.Consumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;

import app.exteraless.components.QRCodeSheet;
import app.exteraless.feed.ui.FeedActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.ActionIntroActivity;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import app.exteraless.plugins.ui.PluginsActivity;

import org.telegram.ui.WebAppDisclaimerAlert;
import org.telegram.ui.bots.BotWebViewSheet;
import org.telegram.ui.web.SearchEngine;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.settings.GhostModeActivity;

/**
 * Резолвер пунктов главного меню: id из {@link MainMenuLayout} → иконка, подпись и действие.
 *
 * exteraGram: {@code com/exteragram/messenger/utils/chats/MainMenuHelper.java} (12.9.0, 819 строк).
 * Из {@code MenuContext} убран {@code pluginContextData}.
 */
public final class MainMenuHelper {

    private MainMenuHelper() {
    }

    /**
     * Контекст резолва. exteraGram: {@code MainMenuHelper.MenuContext} — record из четырёх полей,
     * из них {@code pluginContextData} выброшен вместе с движком плагинов.
     *
     * @param archiveClick своё действие для «Архива»: в списке чатов архив открывается
     *                     не новым фрагментом, а прокруткой
     */
    public record MenuContext(int currentAccount, BaseFragment fragment, Runnable archiveClick) {
    }

    public record MenuItemInfo(int iconRes, CharSequence text, Runnable onClick, Runnable onLongClick) {
    }

    public record AttachMenuBotInfo(int iconRes, CharSequence text, TLRPC.TL_attachMenuBot bot,
                                    Runnable onClick, Runnable onLongClick) {
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment) {
        return new MenuContext(currentAccount, fragment, null);
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment, Runnable archiveClick) {
        return new MenuContext(currentAccount, fragment, archiveClick);
    }

    // ---- меню «⋮» ----

    public static void addConfiguredItemOptions(ItemOptions io, MenuContext ctx) {
        addConfiguredItemOptions(io, ctx, id -> false);
    }

    /**
     * Разделитель ставится «отложенно»: висящие в начале и в конце схлопываются.
     *
     * @param skip пункты, которые в этом конкретном меню дублируют что-то другое
     */
    public static void addConfiguredItemOptions(ItemOptions io, MenuContext ctx, IntPredicate skip) {
        boolean hasAnyItem = false;
        boolean dividerPending = false;
        final List<Integer> layout = MainMenuLayout.getLayout();
        for (int i = 0; i < layout.size(); i++) {
            final Integer id = layout.get(i);
            if (id == null) {
                continue;
            }
            if (id == MainMenuItem.DIVIDER.getId()) {
                if (hasAnyItem) {
                    dividerPending = true;
                }
                continue;
            }
            if (skip.test(id)) {
                continue;
            }
            if (dividerPending) {
                io.addGap();
                dividerPending = false;
            }
            if (addConfiguredItemOption(io, ctx, id)) {
                hasAnyItem = true;
            }
        }
    }

    private static boolean addConfiguredItemOption(ItemOptions io, MenuContext ctx, int id) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return false;
        }
        if (item == MainMenuItem.ARCHIVE && !hasArchivedChats(ctx.currentAccount())) {
            return false;
        }
        if (item == MainMenuItem.BOTS) {
            return addAttachMenuBotMenuItems(io, ctx);
        }
        final MenuItemInfo info = resolveMenuItem(id, ctx);
        if (info == null || info.onClick() == null) {
            return false;
        }
        io.add(info.iconRes(), info.text(), info.onClick());
        bindLongClick(io, info.onLongClick());
        return true;
    }

    private static boolean addAttachMenuBotMenuItems(ItemOptions io, MenuContext ctx) {
        final List<AttachMenuBotInfo> bots = getAttachMenuBotItems(ctx);
        if (bots.isEmpty()) {
            return false;
        }
        for (AttachMenuBotInfo bot : bots) {
            io.addBot(bot.bot(), bot.onClick(), bot.onLongClick());
        }
        return true;
    }

    /** Длинное нажатие закрывает меню. */
    private static void bindLongClick(ItemOptions io, Runnable onLongClick) {
        if (onLongClick == null) {
            return;
        }
        final ActionBarMenuSubItem last = io.getLast();
        if (last == null) {
            return;
        }
        last.setOnLongClickListener(v -> {
            io.dismiss();
            onLongClick.run();
            return true;
        });
    }

    // ---- шторка ----

    public static List<MenuItemInfo> resolveDrawerMenuItems(int id, MenuContext ctx) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return Collections.emptyList();
        }
        if (item == MainMenuItem.BOTS) {
            return resolveDrawerBotMenuItems(ctx);
        }
        if (item == MainMenuItem.ARCHIVE && !hasArchivedChats(ctx.currentAccount())) {
            // Пустой архив в шторке не показывается.
            return Collections.emptyList();
        }
        final MenuItemInfo info = resolveMenuItem(id, ctx);
        return info == null ? Collections.emptyList() : Collections.singletonList(info);
    }

    private static List<MenuItemInfo> resolveDrawerBotMenuItems(MenuContext ctx) {
        final List<AttachMenuBotInfo> bots = getAttachMenuBotItems(ctx);
        if (bots.isEmpty()) {
            return Collections.emptyList();
        }
        final ArrayList<MenuItemInfo> result = new ArrayList<>(bots.size());
        for (AttachMenuBotInfo bot : bots) {
            result.add(new MenuItemInfo(bot.iconRes(), bot.text(), bot.onClick(), bot.onLongClick()));
        }
        return result;
    }

    // ---- сам резолвер ----

    public static MenuItemInfo resolveMenuItem(int id, MenuContext ctx) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null || ctx.fragment() == null) {
            return null;
        }
        final int currentAccount = ctx.currentAccount();
        final BaseFragment fragment = ctx.fragment();
        switch (item) {
            case PROFILE:
                return new MenuItemInfo(R.drawable.left_status_profile, LocaleController.getString(R.string.MyProfile), () -> {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                    args.putBoolean("my_profile", true);
                    fragment.presentFragment(new ProfileActivity(args));
                }, null);
            case ARCHIVE:
                return new MenuItemInfo(R.drawable.msg_archive, LocaleController.getString(R.string.ArchivedChats),
                        ctx.archiveClick() != null ? ctx.archiveClick() : () -> {
                            final Bundle args = new Bundle();
                            args.putInt("folderId", 1);
                            fragment.presentFragment(new DialogsActivity(args));
                        }, null);
            case NEW_GROUP:
                return new MenuItemInfo(R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup),
                        () -> fragment.presentFragment(new GroupCreateActivity(new Bundle())), null);
            case CONTACTS:
                return new MenuItemInfo(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), () -> {
                    final Bundle args = new Bundle();
                    args.putBoolean("needPhonebook", true);
                    args.putBoolean("needFinishFragment", false);
                    fragment.presentFragment(new ContactsActivity(args));
                }, null);
            case CALLS:
                return new MenuItemInfo(R.drawable.msg_calls, LocaleController.getString(R.string.Calls),
                        () -> fragment.presentFragment(new CallLogActivity()), null);
            case NEW_CHANNEL:
                return new MenuItemInfo(R.drawable.msg_channel, LocaleController.getString(R.string.NewChannel),
                        () -> presentChannelCreate(fragment), null);
            case SAVED:
                return new MenuItemInfo(R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), () -> {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                    fragment.presentFragment(new ChatActivity(args));
                }, null);
            case FEED:
                return new MenuItemInfo(R.drawable.ic_feed, LocaleController.getString(R.string.Feed),
                        () -> FeedActivity.presentFeed(fragment), null);
            case SETTINGS:
                return new MenuItemInfo(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new SettingsActivity()), null);
            case BROWSER:
                return new MenuItemInfo(R.drawable.msg2_language, LocaleController.getString(R.string.BrowserSettingsTitle),
                        () -> openBrowserHomepage(fragment), null);
            case PLUGINS:
                return new MenuItemInfo(R.drawable.msg_plugins, LocaleController.getString(R.string.OpenExteraPlugins),
                        () -> fragment.presentFragment(new PluginsActivity()), null);
            case QR:
                return new MenuItemInfo(R.drawable.msg_qrcode, LocaleController.getString(R.string.AuthAnotherClient),
                        () -> openQrScanner(fragment), null);
            case GHOST_MODE:
                return new MenuItemInfo(R.drawable.ayu_ghost, ghostModeTitle(),
                        () -> toggleGhostMode(fragment, currentAccount),
                        () -> fragment.presentFragment(new GhostModeActivity()));
            case RECENT_CHATS:
                return new MenuItemInfo(R.drawable.menu_recent, LocaleController.getString(R.string.RecentChats),
                        () -> showRecentChats(fragment, currentAccount), null);
            default:
                return null;
        }
    }

    private static void showRecentChats(BaseFragment fragment, int currentAccount) {
        final org.telegram.ui.ActionBar.ActionBar actionBar = fragment.getActionBar();
        if (actionBar == null) {
            return;
        }
        if (!tw.nekomimi.nekogram.BackButtonMenuRecent.hasRecentDialogs(currentAccount)) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.NoRecent)).show();
            return;
        }
        final View anchor = actionBar.getBackButton() != null ? actionBar.getBackButton() : actionBar;
        tw.nekomimi.nekogram.BackButtonMenuRecent.show(currentAccount, fragment, anchor);
    }

    /** Заголовок зависит от состояния: пункт и показывает его, и переключает. */
    private static CharSequence ghostModeTitle() {
        return LocaleController.getString(NekoConfig.isGhostModeActive()
                ? R.string.DisableGhostMode
                : R.string.EnableGhostMode);
    }

    private static void toggleGhostMode(BaseFragment fragment, int currentAccount) {
        final boolean wasActive = NekoConfig.isGhostModeActive();
        NekoConfig.toggleGhostMode();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        BulletinFactory.of(fragment)
                .createSuccessBulletin(LocaleController.getString(
                        wasActive ? R.string.GhostModeDisabled : R.string.GhostModeEnabled))
                .show();
    }

    private static void presentChannelCreate(BaseFragment fragment) {
        final SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (BuildVars.DEBUG_VERSION || !prefs.getBoolean("channel_intro", false)) {
            fragment.presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
            prefs.edit().putBoolean("channel_intro", true).apply();
        } else {
            final Bundle args = new Bundle();
            args.putInt("step", 0);
            fragment.presentFragment(new ChannelCreateActivity(args));
        }
    }

    /**
     * exteraGram: {@code MainMenuHelper.$r8$lambda$saqA7TDqFSmi2ZXfA6fwir6uv4I} — открывает
     * домашнюю страницу поисковика во встроенном браузере. В нашем {@code SearchEngine}
     * нет {@code getHomepage()}, поэтому берётся {@code search_url}.
     */
    private static void openBrowserHomepage(BaseFragment fragment) {
        final SearchEngine engine = SearchEngine.getCurrent();
        final Activity activity = fragment.getParentActivity();
        if (engine == null || activity == null || TextUtils.isEmpty(engine.search_url)) {
            return;
        }
        Browser.openInTelegramBrowser(activity, engine.search_url, null);
    }

    /**
     * Сканер QR со своим листом результата — как у exteraGram
     * ({@code MainMenuHelper} → {@code CameraScanActivity.showAsSheet} →
     * {@code QRCodeSheet}).
     *
     * Раньше отсюда открывался стоковый экран привязки устройства: своего листа
     * не было, а сканер без него бесполезен — результат некуда деть. Делегат
     * безопасен: {@code getSubtitleText()} у интерфейса объявлен со значением по
     * умолчанию (CameraScanActivity:174), так что переопределять его не нужно.
     */
    private static void openQrScanner(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        CameraScanActivity.showAsSheet(fragment, false, CameraScanActivity.TYPE_QR,
                new CameraScanActivity.CameraScanActivityDelegate() {
                    @Override
                    public boolean processQr(String text, Runnable onLoadEnd) {
                        // Возвращаем true: экран закрывается сам, а лист
                        // показываем после закрытия, иначе он окажется под ним.
                        AndroidUtilities.runOnUIThread(() -> {
                            onLoadEnd.run();
                            AndroidUtilities.runOnUIThread(() -> {
                                BaseFragment last = LaunchActivity.getSafeLastFragment();
                                if (last != null && last.getParentActivity() != null) {
                                    new QRCodeSheet(last, text).show();
                                }
                            }, 150);
                        }, 600);
                        return true;
                    }
                });
    }

    /**
     * Иконка и подпись пункта без действия — для экрана-редактора раскладки,
     * где фрагмента-получателя ещё нет. {@link MainMenuItem#DIVIDER} возвращает
     * {@code null}: подпись разделителя — дело редактора (строки {@code MainMenuDivider}
     * в ресурсах форка пока нет).
     */
    public static MenuItemInfo describeItem(int id) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return null;
        }
        return switch (item) {
            case PROFILE -> new MenuItemInfo(R.drawable.left_status_profile, LocaleController.getString(R.string.MyProfile), null, null);
            case ARCHIVE -> new MenuItemInfo(R.drawable.msg_archive, LocaleController.getString(R.string.ArchivedChats), null, null);
            case BOTS -> new MenuItemInfo(R.drawable.msg_bot, LocaleController.getString(R.string.SearchApps), null, null);
            case NEW_GROUP -> new MenuItemInfo(R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup), null, null);
            case CONTACTS -> new MenuItemInfo(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), null, null);
            case NEW_CHANNEL -> new MenuItemInfo(R.drawable.msg_channel, LocaleController.getString(R.string.NewChannel), null, null);
            case CALLS -> new MenuItemInfo(R.drawable.msg_calls, LocaleController.getString(R.string.Calls), null, null);
            case SAVED -> new MenuItemInfo(R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), null, null);
            case FEED -> new MenuItemInfo(R.drawable.ic_feed, LocaleController.getString(R.string.Feed), null, null);
            case SETTINGS -> new MenuItemInfo(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), null, null);
            case BROWSER -> new MenuItemInfo(R.drawable.msg2_language, LocaleController.getString(R.string.BrowserSettingsTitle), null, null);
            case PLUGINS -> new MenuItemInfo(R.drawable.msg_plugins, LocaleController.getString(R.string.OpenExteraPlugins), null, null);
            case QR -> new MenuItemInfo(R.drawable.msg_qrcode, LocaleController.getString(R.string.AuthAnotherClient), null, null);
            case GHOST_MODE -> new MenuItemInfo(R.drawable.ayu_ghost, ghostModeTitle(), null, null);
            case RECENT_CHATS -> new MenuItemInfo(R.drawable.menu_recent, LocaleController.getString(R.string.RecentChats), null, null);
            default -> null;
        };
    }

    // ---- attach-menu-боты ----

    public static List<AttachMenuBotInfo> getAttachMenuBotItems(MenuContext ctx) {
        final BaseFragment fragment = ctx.fragment();
        final LaunchActivity launchActivity = findLaunchActivity(fragment);
        final TLRPC.TL_attachMenuBots attachMenuBots =
                MediaDataController.getInstance(ctx.currentAccount()).getAttachMenuBots();
        if (fragment == null || launchActivity == null || attachMenuBots == null
                || attachMenuBots.bots == null || attachMenuBots.bots.isEmpty()) {
            return Collections.emptyList();
        }
        final ArrayList<AttachMenuBotInfo> result = new ArrayList<>();
        for (TLRPC.TL_attachMenuBot bot : attachMenuBots.bots) {
            if (!bot.show_in_side_menu) {
                continue;
            }
            result.add(new AttachMenuBotInfo(
                    R.drawable.msg_bot,
                    bot.short_name,
                    bot,
                    createAttachMenuBotClickAction(ctx, bot, launchActivity),
                    () -> BotWebViewSheet.deleteBot(ctx.currentAccount(), bot.bot_id, null)));
        }
        return result;
    }

    private static Runnable createAttachMenuBotClickAction(MenuContext ctx, TLRPC.TL_attachMenuBot bot, LaunchActivity launchActivity) {
        return () -> {
            if (bot.inactive || bot.side_menu_disclaimer_needed) {
                final android.content.Context context = ctx.fragment() != null && ctx.fragment().getContext() != null
                        ? ctx.fragment().getContext() : launchActivity;
                WebAppDisclaimerAlert.show(context, (Consumer<Boolean>) allowSendMessage -> {
                    final TLRPC.TL_messages_toggleBotInAttachMenu req = new TLRPC.TL_messages_toggleBotInAttachMenu();
                    req.bot = MessagesController.getInstance(ctx.currentAccount()).getInputUser(bot.bot_id);
                    req.enabled = true;
                    req.write_allowed = true;
                    ConnectionsManager.getInstance(ctx.currentAccount()).sendRequest(req, (response, error) ->
                            AndroidUtilities.runOnUIThread(() -> {
                                bot.side_menu_disclaimer_needed = false;
                                bot.inactive = false;
                                LaunchActivity.showAttachMenuBot(launchActivity, ctx.currentAccount(), bot, null, true);
                                MediaDataController.getInstance(ctx.currentAccount()).updateAttachMenuBotsInCache();
                            }), ConnectionsManager.RequestFlagInvokeAfter | ConnectionsManager.RequestFlagFailOnServerErrors);
                }, null, null);
            } else {
                LaunchActivity.showAttachMenuBot(launchActivity, ctx.currentAccount(), bot, null, true);
            }
        };
    }

    private static LaunchActivity findLaunchActivity(BaseFragment fragment) {
        if (fragment == null) {
            return LaunchActivity.instance;
        }
        final Activity activity = AndroidUtilities.findActivity(
                fragment.getContext() != null ? fragment.getContext() : fragment.getParentActivity());
        return activity instanceof LaunchActivity ? (LaunchActivity) activity : LaunchActivity.instance;
    }

    /**
     * exteraGram: {@code ChatUtils.hasArchivedChats()} → {@code MessagesController.hasArchivedChatsActual()}.
     * У нас поле {@code hasArchivedChats} приватное, поэтому смотрим сам список папки 1.
     */
    public static boolean hasArchivedChats(int currentAccount) {
        try {
            final List<TLRPC.Dialog> archived = MessagesController.getInstance(currentAccount).getDialogs(1);
            return archived != null && !archived.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
