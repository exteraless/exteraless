package app.exteraless.privacy;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.bots.WebViewRequestProps;
import org.telegram.ui.bots.BotWebViewSheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.WeakHashMap;

import app.exteraless.general.GeneralConfig;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;

/** UI-thread routing. Reads history, never starts a bot or copies messages between identities. */
public final class BotAccountRouter {
    private static final WeakHashMap<BaseFragment, String> navigation = new WeakHashMap<>();
    private static final WeakHashMap<WebViewRequestProps, String> webviews = new WeakHashMap<>();
    private static final HashSet<String> approvedSends = new HashSet<>();
    private static final HashMap<String, Long> handoffs = new HashMap<>();
    private static boolean lastEnabled;
    private static boolean lastAlwaysAsk;
    private static long lastConfigured;

    private BotAccountRouter() {}

    public static boolean enabled() {
        GeneralConfig.init();
        boolean enabled = GeneralConfig.botPrivacyEnabled.Bool();
        long configured = GeneralConfig.botPrivacyUserId.Long();
        boolean alwaysAsk = GeneralConfig.botPrivacyAlwaysAsk.Bool();
        if (enabled != lastEnabled || configured != lastConfigured || alwaysAsk != lastAlwaysAsk) {
            approvedSends.clear();
            navigation.clear();
            webviews.clear();
            handoffs.clear();
            lastEnabled = enabled;
            lastAlwaysAsk = alwaysAsk;
            lastConfigured = configured;
        }
        return BotAccountPolicy.isEnabled(enabled, alwaysAsk);
    }

    public static int configuredAccount() {
        GeneralConfig.init();
        long id = GeneralConfig.botPrivacyUserId.Long();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.isValidAccount(i) && !PasscodeHelper.isAccountHidden(i) && UserConfig.getInstance(i).getClientUserId() == id) return i;
        }
        return -1;
    }

    public static String accountName(int account) {
        if (!UserConfig.isValidAccount(account)) return LocaleController.getString(R.string.OEBotPrivacySelect);
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        String username = UserObject.getPublicUsername(user);
        return UserObject.getUserName(user) + (TextUtils.isEmpty(username) ? "" : " (@" + username + ")");
    }

    public static void selectAccount(Activity activity, int[] accounts, Utilities.Callback<Integer> selected) {
        if (activity == null || activity.isFinishing()) return;
        ArrayList<Integer> visible = new ArrayList<>();
        ArrayList<CharSequence> names = new ArrayList<>();
        ArrayList<Long> identities = new ArrayList<>();
        for (int account : accounts) {
            if (!UserConfig.isValidAccount(account) || PasscodeHelper.isAccountHidden(account)) continue;
            visible.add(account);
            names.add(accountName(account));
            identities.add(UserConfig.getInstance(account).getClientUserId());
        }
        if (visible.isEmpty()) {
            error(activity, false);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.OEBotPrivacyAccount))
                .setMessage(LocaleController.getString(R.string.OEBotPrivacyChoose))
                .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                    int account = visible.get(which);
                    if (UserConfig.isValidAccount(account) && !PasscodeHelper.isAccountHidden(account) &&
                            UserConfig.getInstance(account).getClientUserId() == identities.get(which)) selected.run(account);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
    }

    public static int[] accounts() {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.isValidAccount(i)) accounts.add(i);
        }
        return accounts.stream().mapToInt(Integer::intValue).toArray();
    }

    public static void error(Activity activity, boolean unavailable) {
        if (activity == null || activity.isFinishing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.OEBotPrivacyAccount))
                .setMessage(LocaleController.getString(unavailable ? R.string.OEBotPrivacyUnavailable : R.string.OEBotPrivacyFailed))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (unavailable) {
            builder.setPositiveButton(LocaleController.getString(R.string.OEBotPrivacySelect), (d, w) ->
                    selectAccount(activity, accounts(), account -> {
                        GeneralConfig.botPrivacyUserId.setConfigLong(UserConfig.getInstance(account).getClientUserId());
                    }));
            builder.setNeutralButton(LocaleController.getString(R.string.Disable), (d, w) -> {
                GeneralConfig.botPrivacyEnabled.setConfigBool(false);
                GeneralConfig.botPrivacyAlwaysAsk.setConfigBool(false);
            });
        }
        builder.show();
    }

    private static String stamp(int account, long botId) {
        return UserConfig.getInstance(account).getClientUserId() + ":" + botId + ":" + GeneralConfig.botPrivacyUserId.Long() + ":" + GeneralConfig.botPrivacyAlwaysAsk.Bool();
    }

    /** One-use handoff from a verified generic link to its chat or Mini App navigation. */
    public static void allowNextOpen(int account, long botId) {
        long now = android.os.SystemClock.elapsedRealtime();
        handoffs.values().removeIf(deadline -> deadline < now);
        handoffs.put(stamp(account, botId), now + 15000);
    }

    private static boolean consumeHandoff(int account, long botId) {
        Long deadline = handoffs.remove(stamp(account, botId));
        return deadline != null && deadline >= android.os.SystemClock.elapsedRealtime();
    }

    /** Captures all account identities and preferences; any change cancels this decision. */
    private static final class Check {
        final Activity activity;
        final TLRPC.User bot;
        final int requested;
        final boolean explicit;
        final Utilities.Callback<Integer> callback;
        final long configured = GeneralConfig.botPrivacyUserId.Long();
        final boolean alwaysAsk = GeneralConfig.botPrivacyAlwaysAsk.Bool();
        final int active = UserConfig.selectedAccount;
        final long[] identities = new long[UserConfig.MAX_ACCOUNT_COUNT];
        final ArrayList<Integer> existing = new ArrayList<>();
        int pending;
        boolean failed;
        boolean finished;

        Check(Activity activity, TLRPC.User bot, int requested, boolean explicit, Utilities.Callback<Integer> callback) {
            this.activity = activity;
            this.bot = bot;
            this.requested = requested;
            this.explicit = explicit;
            this.callback = callback;
            for (int i : accounts()) {
                identities[i] = UserConfig.getInstance(i).getClientUserId();
                pending++;
            }
        }

        boolean valid() {
            long[] current = new long[identities.length];
            for (int i = 0; i < identities.length; i++) {
                current[i] = UserConfig.isValidAccount(i) ? UserConfig.getInstance(i).getClientUserId() : 0;
            }
            return BotAccountPolicy.isCurrent(identities, current, configured, GeneralConfig.botPrivacyUserId.Long(),
                    active, UserConfig.selectedAccount, enabled(), alwaysAsk, GeneralConfig.botPrivacyAlwaysAsk.Bool()) && (activity == null || !activity.isFinishing());
        }

        void complete(int account, Boolean established) {
            if (finished) return;
            if (explicit && account == requested && Boolean.TRUE.equals(established)) {
                finished = true;
                if (valid()) callback.run(account);
                return;
            }
            if (established == null) failed = true;
            else if (established) existing.add(account);
            if (--pending != 0) return;
            finish();
        }

        void finish() {
            if (finished) return;
            finished = true;
            if (!valid()) return;
            int[] established = existing.stream().mapToInt(Integer::intValue).toArray();
            int result = BotAccountPolicy.choose(GeneralConfig.botPrivacyEnabled.Bool(), alwaysAsk, requested, configuredAccount(), explicit, established, !failed);
            if (result == BotAccountPolicy.AMBIGUOUS || result == BotAccountPolicy.ASK) {
                selectAccount(activity, result == BotAccountPolicy.ASK ? accounts() : established, account -> {
                    if (valid()) callback.run(account);
                });
            } else if (result < 0) {
                error(activity, result == BotAccountPolicy.UNAVAILABLE || !alwaysAsk && configuredAccount() < 0);
            } else if (PasscodeHelper.isAccountHidden(result)) {
                error(activity, false);
            } else {
                callback.run(result);
            }
        }

        void inspect(int account) {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.hasBotConversation(bot.id, local -> {
                if (finished) return;
                if (explicit && account == requested && Boolean.TRUE.equals(local)) {
                    complete(account, true);
                    return;
                }
                // Obtain the access hash from this account, never from another account's user.
                storage.getStorageQueue().postRunnable(() -> {
                    TLRPC.User stored = storage.getUser(bot.id);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (finished) return;
                        MessagesController controller = MessagesController.getInstance(account);
                        TLRPC.User user = controller.getUser(bot.id);
                        if (user == null || user.min) user = stored;
                        if (user != null && !user.min && user.access_hash != 0) {
                            controller.putUser(user, true);
                            inspectPeer(account, local);
                        } else {
                            String username = UserObject.getPublicUsername(bot);
                            if (TextUtils.isEmpty(username)) {
                                complete(account, null);
                                return;
                            }
                            controller.getUserNameResolver().resolve(username, peer -> {
                                if (peer == null || peer != bot.id) complete(account, null);
                                else inspectPeer(account, local);
                            });
                        }
                    });
                });
            });
        }

        void inspectPeer(int account, Boolean local) {
            if (finished || !valid()) return;
            if (Boolean.TRUE.equals(local)) {
                complete(account, true);
                return;
            }
            TLRPC.TL_messages_getPeerDialogs req = new TLRPC.TL_messages_getPeerDialogs();
            TLRPC.TL_inputDialogPeer peer = new TLRPC.TL_inputDialogPeer();
            peer.peer = MessagesController.getInstance(account).getInputPeer(bot.id);
            req.peers.add(peer);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.TL_messages_peerDialogs) || error != null) {
                    complete(account, null);
                    return;
                }
                TLRPC.TL_messages_peerDialogs dialogs = (TLRPC.TL_messages_peerDialogs) response;
                // shortcut: fully erased histories are unobservable; revisit if Telegram exposes prior-contact metadata.
                boolean found = false;
                for (TLRPC.Dialog dialog : dialogs.dialogs) {
                    if (dialog.peer != null && dialog.peer.user_id == bot.id &&
                            (dialog.top_message != 0 || dialog.read_inbox_max_id > 0 || dialog.read_outbox_max_id > 0)) found = true;
                }
                complete(account, local == null && !found ? null : found);
            }));
        }
    }

    public static void resolve(Activity activity, TLRPC.User bot, int requested, boolean explicit, Utilities.Callback<Integer> callback) {
        if (!enabled() || bot == null || !bot.bot) {
            callback.run(requested);
            return;
        }
        Check check = new Check(activity, bot, requested, explicit, callback);
        AndroidUtilities.runOnUIThread(() -> {
            if (!check.finished) {
                check.finished = true;
                if (check.valid()) error(activity, false);
            }
        }, 15000);
        for (int account : accounts()) check.inspect(account);
    }

    /** Called by LaunchActivity before onFragmentCreate (which itself can send /start). */
    public static boolean interceptChat(LaunchActivity activity, INavigationLayout layout, INavigationLayout.NavigationParams params) {
        if (!enabled() || !(params.fragment instanceof ChatActivity)) return false;
        BaseFragment fragment = params.fragment;
        android.os.Bundle args = fragment.getArguments();
        if (args == null || args.getLong("user_id", 0) <= 0 || args.getLong("chat_id", 0) != 0 ||
                args.getInt("enc_id", 0) != 0 || args.getInt("chatMode", 0) != 0 || args.containsKey("inline_query") || args.containsKey("attach_bot")) return false;
        int requested = fragment.getCurrentAccount();
        long botId = args.getLong("user_id");
        if (stamp(requested, botId).equals(navigation.remove(fragment))) return false;
        if (consumeHandoff(requested, botId)) {
            approvedSends.add(stamp(requested, botId));
            showAccount(fragment, requested);
            return false;
        }
        TLRPC.User cached = MessagesController.getInstance(requested).getUser(botId);
        if (cached != null && !cached.bot) return false;
        BaseFragment anchor = layout.getLastFragment();
        int active = UserConfig.selectedAccount;
        long identity = UserConfig.getInstance(requested).getClientUserId();
        MessagesStorage storage = MessagesStorage.getInstance(requested);
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.User stored = storage.getUser(botId);
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing() || active != UserConfig.selectedAccount || layout.getLastFragment() != anchor ||
                        identity != UserConfig.getInstance(requested).getClientUserId()) return;
                TLRPC.User user = MessagesController.getInstance(requested).getUser(botId);
                if (user == null) user = stored;
                if (user == null) {
                    error(activity, false);
                    return;
                }
                final boolean isBot = user.bot;
                resolve(activity, user, requested, true, account -> {
                    if (layout.getLastFragment() != anchor) return;
                    boolean switched = account != UserConfig.selectedAccount;
                    if (switched) activity.switchToAccount(account, true);
                    fragment.setCurrentAccount(account);
                    if (enabled() && isBot) approvedSends.add(stamp(account, botId));
                    navigation.put(fragment, stamp(account, botId));
                    (switched ? activity.getActionBarLayout() : layout).presentFragment(params);
                    if (switched) showAccount(fragment, account);
                });
            });
        });
        return true;
    }

    public static boolean guardComposer(BaseFragment fragment, int account, long peer) {
        return guardSend(account, peer, () -> {
            if (fragment != null && fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                        LocaleController.getString(R.string.OEBotPrivacyReady)).show();
            }
        });
    }

    private static void showAccount(BaseFragment fragment, int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment != null && fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                        LocaleController.formatString(R.string.OEBotPrivacyOpened, accountName(account))).show();
            }
        }, 300);
    }

    /** Only private Mini Apps; inline, attachment, group and authentication flows keep their identity. */
    public static boolean interceptWebView(BaseFragment fragment, WebViewRequestProps props, java.util.function.BooleanSupplier alive, Utilities.Callback<BaseFragment> retry) {
        if (!enabled() || (props.flags & BotWebViewSheet.FLAG_FROM_INLINE_SWITCH) != 0 || props.type == BotWebViewSheet.TYPE_FAKE_WEB_VIEW || props.type == BotWebViewSheet.TYPE_WEB_VIEW_GUARD ||
                (props.peerId != 0 && props.peerId != props.botId)) return false;
        if (stamp(props.currentAccount, props.botId).equals(webviews.remove(props))) return false;
        if (consumeHandoff(props.currentAccount, props.botId)) {
            showAccount(fragment, props.currentAccount);
            return false;
        }
        TLRPC.User bot = MessagesController.getInstance(props.currentAccount).getUser(props.botId);
        if (bot == null || !bot.bot) return false;
        resolve(fragment.getParentActivity(), bot, props.currentAccount, fragment instanceof ChatActivity, account -> {
            if (!alive.getAsBoolean()) return;
            int previous = props.currentAccount;
            props.currentAccount = account;
            props.botUser = MessagesController.getInstance(account).getUser(props.botId);
            Runnable proceed = () -> {
                if (!alive.getAsBoolean()) return;
                BaseFragment target = fragment;
                if (account != UserConfig.selectedAccount && LaunchActivity.instance != null) {
                    LaunchActivity.instance.switchToAccount(account, true);
                }
                if (fragment.getCurrentAccount() != account) target = LaunchActivity.getLastFragment();
                if (target == null || target.getCurrentAccount() != account) return;
                if (previous != account) props.response = null;
                webviews.put(props, stamp(account, props.botId));
                retry.run(target);
                if (previous != account) showAccount(target, account);
            };
            if (previous != account && props.app != null) {
                TLRPC.TL_messages_getBotApp req = new TLRPC.TL_messages_getBotApp();
                TLRPC.TL_inputBotAppShortName app = new TLRPC.TL_inputBotAppShortName();
                app.bot_id = MessagesController.getInstance(account).getInputUser(props.botId);
                app.short_name = props.app.short_name;
                req.app = app;
                long identity = UserConfig.getInstance(account).getClientUserId();
                long setting = GeneralConfig.botPrivacyUserId.Long();
                boolean alwaysAsk = GeneralConfig.botPrivacyAlwaysAsk.Bool();
                int active = UserConfig.selectedAccount;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!enabled() || identity != UserConfig.getInstance(account).getClientUserId() ||
                            setting != GeneralConfig.botPrivacyUserId.Long() || alwaysAsk != GeneralConfig.botPrivacyAlwaysAsk.Bool() || active != UserConfig.selectedAccount) return;
                    if (error != null || !(response instanceof TLRPC.TL_messages_botApp)) {
                        error(fragment.getParentActivity(), false);
                        return;
                    }
                    props.app = ((TLRPC.TL_messages_botApp) response).app;
                    proceed.run();
                }));
            } else {
                proceed.run();
            }
        });
        return true;
    }

    /** Revalidate stale open chats after enabling/changing the setting, before sending anything. */
    public static boolean guardSend(int account, long peer, Runnable retry) {
        if (!enabled() || peer <= 0 || approvedSends.contains(stamp(account, peer))) return false;
        if (consumeHandoff(account, peer)) {
            approvedSends.add(stamp(account, peer));
            return false;
        }
        TLRPC.User bot = MessagesController.getInstance(account).getUser(peer);
        if (bot == null) {
            long identity = UserConfig.getInstance(account).getClientUserId();
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                TLRPC.User stored = storage.getUser(peer);
                AndroidUtilities.runOnUIThread(() -> {
                    if (identity != UserConfig.getInstance(account).getClientUserId()) return;
                    if (stored == null) {
                        error(LaunchActivity.instance, false);
                    } else {
                        MessagesController.getInstance(account).putUser(stored, true);
                        if (!guardSend(account, peer, retry)) retry.run();
                    }
                });
            });
            return true;
        }
        if (!bot.bot) return false;
        resolve(LaunchActivity.instance, bot, account, true, chosen -> {
            if (chosen == account) {
                approvedSends.add(stamp(account, peer));
                retry.run();
            } else {
                error(LaunchActivity.instance, false);
            }
        });
        return true;
    }
}
