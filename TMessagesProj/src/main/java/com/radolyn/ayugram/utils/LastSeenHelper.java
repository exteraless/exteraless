package com.radolyn.ayugram.utils;

import android.text.TextUtils;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.LastSeenDao;
import com.radolyn.ayugram.database.entities.LastSeenEntity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

public class LastSeenHelper {
    private static final int FLUSH_DELAY_MS = 5000;
    private static final int CLEANUP_DAYS = 7;
    private static final int PROBE_INTERVAL_MS = 60000;

    private static final LongSparseIntArray cache = new LongSparseIntArray();
    private static final LongSparseIntArray pending = new LongSparseIntArray();
    private static final LongSparseIntArray probeTimes = new LongSparseIntArray();
    private static volatile boolean flushScheduled;

    public static void preload() {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return;
        }
        AyuQueues.lastSeenQueue.postRunnable(() -> {
            int cutoff = (int) (System.currentTimeMillis() / 1000) - CLEANUP_DAYS * 24 * 60 * 60;
            LastSeenDao dao = AyuData.getLastSeenDao();
            if (dao == null) {
                return;
            }
            dao.deleteOlderThan(cutoff);
            List<LastSeenEntity> all = dao.getAll();
            synchronized (cache) {
                for (LastSeenEntity e : all) {
                    int cached = cache.get(e.userId, 0);
                    if (cached < e.lastSeen) {
                        cache.put(e.userId, e.lastSeen);
                    }
                }
                for (int i = cache.size() - 1; i >= 0; i--) {
                    if (cache.valueAt(i) < cutoff) {
                        cache.removeAt(i);
                    }
                }
            }
        });
    }

    public static void saveLastSeen(long userId, int timestamp) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool() || timestamp <= 0) {
            return;
        }
        synchronized (cache) {
            int cached = cache.get(userId, 0);
            if (cached >= timestamp) return;
            cache.put(userId, timestamp);
        }
        synchronized (pending) {
            pending.put(userId, timestamp);
        }
        scheduleFlush();
    }

    private static void scheduleFlush() {
        synchronized (pending) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        AyuQueues.lastSeenQueue.postRunnable(LastSeenHelper::flushPending, FLUSH_DELAY_MS);
    }

    private static void flushPending() {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            synchronized (pending) {
                pending.clear();
                flushScheduled = false;
            }
            return;
        }

        List<LastSeenEntity> toWrite;
        synchronized (pending) {
            if (pending.size() == 0) {
                flushScheduled = false;
                return;
            }
            toWrite = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                LastSeenEntity e = new LastSeenEntity();
                e.userId = pending.keyAt(i);
                e.lastSeen = pending.valueAt(i);
                toWrite.add(e);
            }
            pending.clear();
        }

        LastSeenDao dao = AyuData.getLastSeenDao();
        boolean ok = false;
        try {
            if (dao != null) {
                dao.upsertAll(toWrite);
                ok = true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!ok) {
            requeuePending(toWrite);
        }

        synchronized (pending) {
            if (pending.size() > 0) {
                AyuQueues.lastSeenQueue.postRunnable(LastSeenHelper::flushPending, FLUSH_DELAY_MS);
            } else {
                flushScheduled = false;
            }
        }
    }

    private static void requeuePending(List<LastSeenEntity> toRequeue) {
        synchronized (pending) {
            for (int i = 0; i < toRequeue.size(); i++) {
                LastSeenEntity e = toRequeue.get(i);
                int existing = pending.get(e.userId, 0);
                if (existing < e.lastSeen) {
                    pending.put(e.userId, e.lastSeen);
                }
            }
        }
    }

    public static int getLastSeen(long userId) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return 0;
        }
        synchronized (cache) {
            return cache.get(userId, 0);
        }
    }

    public static void probeLastSeen(int currentAccount, TLRPC.User user) {
        if (!NaConfig.INSTANCE.getProbeLastSeenUsingOtherAccounts().Bool()
                || user == null || user.id <= 0
                || !(user.status instanceof TLRPC.TL_userStatusRecently)
                && !(user.status instanceof TLRPC.TL_userStatusLastWeek)
                && !(user.status instanceof TLRPC.TL_userStatusLastMonth)
                || getLastSeen(user.id) > 0) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        synchronized (probeTimes) {
            if (now - probeTimes.get(user.id, 0) < PROBE_INTERVAL_MS) {
                return;
            }
            probeTimes.put(user.id, (int) now);
        }
        AyuQueues.lastSeenQueue.postRunnable(() -> probeWithAccount(
                currentAccount,
                user.id,
                UserObject.getPublicUsername(user),
                success -> {
                    if (success) {
                        return;
                    }
                    for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                        if (account != currentAccount && UserConfig.getInstance(account).isClientActivated()) {
                            probeWithAccount(account, user.id, UserObject.getPublicUsername(user), ignored -> {
                            });
                        }
                    }
                }
        ));
    }

    private static void probeWithAccount(int account, long userId, String username, Utilities.Callback<Boolean> callback) {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            callback.run(false);
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.User accountUser = messagesController.getUser(userId);
        if (accountUser == null) {
            accountUser = MessagesStorage.getInstance(account).getUserSync(userId);
        }
        if (accountUser == null) {
            if (TextUtils.isEmpty(username)) {
                callback.run(false);
                return;
            }
            messagesController.getUserNameResolver().resolve(username, resolvedId -> {
                if (resolvedId == null || resolvedId != userId) {
                    callback.run(false);
                    return;
                }
                TLRPC.User resolvedUser = messagesController.getUser(resolvedId);
                if (resolvedUser == null) {
                    callback.run(false);
                    return;
                }
                probeAccountUser(account, resolvedUser, callback);
            });
            return;
        }
        probeAccountUser(account, accountUser, callback);
    }

    private static void probeAccountUser(int account, TLRPC.User user, Utilities.Callback<Boolean> callback) {
        if (isKnownOnline(user)) {
            if (user.status instanceof TLRPC.TL_userStatusOffline && user.status.expires > 0) {
                saveLastSeen(user.id, user.status.expires);
            }
            callback.run(true);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            ContactsController.getInstance(account).loadPrivacySettings(false);
            ArrayList<TLRPC.PrivacyRule> rules = ContactsController.getInstance(account).getPrivacyRules(0);
            if (rules != null && !rules.isEmpty()) {
                probeWithPrivacyRules(account, user, rules, callback);
                return;
            }
            fetchPrivacyRules(account, fetchedRules -> {
                if (fetchedRules == null) {
                    callback.run(false);
                    return;
                }
                probeWithPrivacyRules(account, user, fetchedRules, callback);
            });
        });
    }

    private static void fetchPrivacyRules(int account, Utilities.Callback<ArrayList<TLRPC.PrivacyRule>> callback) {
        TL_account.getPrivacy request = new TL_account.getPrivacy();
        request.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<TLRPC.PrivacyRule> rules = null;
                if (error == null && response instanceof TL_account.privacyRules) {
                    TL_account.privacyRules privacyRules = (TL_account.privacyRules) response;
                    MessagesController.getInstance(account).putUsers(privacyRules.users, false);
                    MessagesController.getInstance(account).putChats(privacyRules.chats, false);
                    ContactsController.getInstance(account).setPrivacyRules(privacyRules.rules, 0);
                    rules = privacyRules.rules;
                }
                callback.run(rules);
            });
        });
    }

    private static void probeWithPrivacyRules(int account, TLRPC.User user, ArrayList<TLRPC.PrivacyRule> rules, Utilities.Callback<Boolean> callback) {
        ArrayList<Long> allow = new ArrayList<>();
        ArrayList<Long> disallow = new ArrayList<>();
        int mode = 1;
        int defaultRule = -1;
        for (int i = 0; i < rules.size(); i++) {
            TLRPC.PrivacyRule rule = rules.get(i);
            if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                ArrayList<Long> chats = ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats;
                for (int j = 0; j < chats.size(); j++) {
                    allow.add(-chats.get(j));
                }
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                ArrayList<Long> chats = ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats;
                for (int j = 0; j < chats.size(); j++) {
                    disallow.add(-chats.get(j));
                }
            } else if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                allow.addAll(((TLRPC.TL_privacyValueAllowUsers) rule).users);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                disallow.addAll(((TLRPC.TL_privacyValueDisallowUsers) rule).users);
            } else if (defaultRule == -1) {
                if (rule instanceof TLRPC.TL_privacyValueAllowAll) {
                    defaultRule = 0;
                } else if (rule instanceof TLRPC.TL_privacyValueDisallowAll) {
                    defaultRule = 1;
                } else {
                    defaultRule = 2;
                }
            }
        }
        if (defaultRule == 0 || (defaultRule == -1 && !disallow.isEmpty())) {
            mode = 0;
        } else if (defaultRule == 2 || (defaultRule == -1 && !disallow.isEmpty() && !allow.isEmpty())) {
            mode = 2;
        } else if (defaultRule == 1 || (defaultRule == -1 && !allow.isEmpty())) {
            mode = 1;
        } else {
            mode = 1;
        }
        boolean allowed = mode == 0 && !disallow.contains(user.id)
                || mode == 2 && (user.contact && !disallow.contains(user.id) || allow.contains(user.id))
                || mode == 1 && allow.contains(user.id);
        if (allowed) {
            fetchFullUser(account, user, callback);
            return;
        }
        performPrivacyHack(account, mode, user, allow, disallow, callback);
    }

    private static void performPrivacyHack(int account, int mode, TLRPC.User user, ArrayList<Long> allow, ArrayList<Long> disallow, Utilities.Callback<Boolean> callback) {
        TL_account.setPrivacy request = buildPrivacyRequest(account, mode, true, user, allow, disallow);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!applyPrivacyResponse(account, response, error)) {
                callback.run(false);
                return;
            }
            fetchFullUser(account, user, success -> restorePrivacy(account, mode, user, allow, disallow, ignored -> callback.run(success)));
        }), 2);
    }

    private static void restorePrivacy(int account, int mode, TLRPC.User user, ArrayList<Long> allow, ArrayList<Long> disallow, Utilities.Callback<Boolean> callback) {
        TL_account.setPrivacy request = buildPrivacyRequest(account, mode, false, user, allow, disallow);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            applyPrivacyResponse(account, response, error);
            callback.run(error == null);
        }), 2);
    }

    private static boolean applyPrivacyResponse(int account, TLObject response, TLRPC.TL_error error) {
        if (error != null || !(response instanceof TL_account.privacyRules)) {
            return false;
        }
        TL_account.privacyRules privacyRules = (TL_account.privacyRules) response;
        MessagesController.getInstance(account).putUsers(privacyRules.users, false);
        MessagesController.getInstance(account).putChats(privacyRules.chats, false);
        ContactsController.getInstance(account).setPrivacyRules(privacyRules.rules, 0);
        return true;
    }

    private static TL_account.setPrivacy buildPrivacyRequest(int account, int mode, boolean grantTarget, TLRPC.User user, ArrayList<Long> allow, ArrayList<Long> disallow) {
        TL_account.setPrivacy request = new TL_account.setPrivacy();
        request.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        MessagesController messagesController = MessagesController.getInstance(account);
        if (mode == 0) {
            fillDisallowRule(disallow, grantTarget ? user.id : null, messagesController, request);
        } else if (mode == 1) {
            fillAllowRule(allow, grantTarget ? null : user.id, messagesController, request);
            if (grantTarget) {
                TLRPC.InputUser inputUser = messagesController.getInputUser(user);
                if (inputUser != null) {
                    for (int i = 0; i < request.rules.size(); i++) {
                        if (request.rules.get(i) instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                            ((TLRPC.TL_inputPrivacyValueAllowUsers) request.rules.get(i)).users.add(inputUser);
                            break;
                        }
                    }
                }
            }
        } else {
            fillDisallowRule(disallow, grantTarget ? user.id : null, messagesController, request);
            fillAllowRule(allow, grantTarget ? null : user.id, messagesController, request);
            if (grantTarget) {
                TLRPC.InputUser inputUser = messagesController.getInputUser(user);
                if (inputUser != null) {
                    for (int i = 0; i < request.rules.size(); i++) {
                        if (request.rules.get(i) instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                            ((TLRPC.TL_inputPrivacyValueAllowUsers) request.rules.get(i)).users.add(inputUser);
                            break;
                        }
                    }
                }
            }
        }
        if (mode == 0) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
        } else if (mode == 1) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        } else {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
        }
        return request;
    }

    private static void fillAllowRule(ArrayList<Long> dialogIds, Long skipUserId, MessagesController messagesController, TL_account.setPrivacy request) {
        if (dialogIds.isEmpty() && skipUserId != null) {
            return;
        }
        TLRPC.TL_inputPrivacyValueAllowUsers usersRule = new TLRPC.TL_inputPrivacyValueAllowUsers();
        TLRPC.TL_inputPrivacyValueAllowChatParticipants chatsRule = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
        fillPrivacyLists(dialogIds, skipUserId, messagesController, usersRule.users, chatsRule.chats);
        request.rules.add(usersRule);
        request.rules.add(chatsRule);
    }

    private static void fillDisallowRule(ArrayList<Long> dialogIds, Long skipUserId, MessagesController messagesController, TL_account.setPrivacy request) {
        if (dialogIds.isEmpty()) {
            return;
        }
        TLRPC.TL_inputPrivacyValueDisallowUsers usersRule = new TLRPC.TL_inputPrivacyValueDisallowUsers();
        TLRPC.TL_inputPrivacyValueDisallowChatParticipants chatsRule = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
        fillPrivacyLists(dialogIds, skipUserId, messagesController, usersRule.users, chatsRule.chats);
        request.rules.add(usersRule);
        request.rules.add(chatsRule);
    }

    private static void fillPrivacyLists(ArrayList<Long> dialogIds, Long skipUserId, MessagesController messagesController, ArrayList<TLRPC.InputUser> targetUsers, ArrayList<Long> targetChats) {
        for (int i = 0; i < dialogIds.size(); i++) {
            long dialogId = dialogIds.get(i);
            if (skipUserId != null && dialogId == skipUserId) {
                continue;
            }
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User ruleUser = messagesController.getUser(dialogId);
                TLRPC.InputUser inputUser = ruleUser == null ? null : messagesController.getInputUser(ruleUser);
                if (inputUser != null) {
                    targetUsers.add(inputUser);
                }
            } else {
                targetChats.add(-dialogId);
            }
        }
    }

    private static void fetchFullUser(int account, TLRPC.User user, Utilities.Callback<Boolean> callback) {
        TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = user.id;
        inputUser.access_hash = user.access_hash;
        request.id = inputUser;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                callback.run(false);
                return;
            }
            TLRPC.User probedUser = getFullUser(response, user.id);
            if (!isKnownOnline(probedUser)) {
                callback.run(false);
                return;
            }
            if (probedUser.status instanceof TLRPC.TL_userStatusOffline && probedUser.status.expires > 0) {
                saveLastSeen(probedUser.id, probedUser.status.expires);
            }
            ArrayList<TLRPC.User> users = new ArrayList<>();
            users.add(probedUser);
            MessagesController.getInstance(account).putUsers(users, false);
            callback.run(true);
        }));
    }

    private static TLRPC.User getFullUser(TLObject response, long userId) {
        if (response instanceof TLRPC.TL_userFull) {
            return ((TLRPC.TL_userFull) response).user;
        }
        if (!(response instanceof TLRPC.TL_users_userFull)) {
            return null;
        }
        TLRPC.TL_users_userFull userFull = (TLRPC.TL_users_userFull) response;
        if (userFull.full_user != null && userFull.full_user.user != null) {
            return userFull.full_user.user;
        }
        ArrayList<TLRPC.User> users = userFull.users;
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).id == userId) {
                return users.get(i);
            }
        }
        return null;
    }

    private static boolean isKnownOnline(TLRPC.User user) {
        if (user == null || user.status == null || user.status.expires == 0
                || UserObject.isDeleted(user) || user instanceof TLRPC.TL_userEmpty) {
            return false;
        }
        int expires = user.status.expires;
        if (expires == -1 || expires == -100 || expires == -101 || expires == -102) {
            return false;
        }
        return user.status instanceof TLRPC.TL_userStatusOffline || user.status instanceof TLRPC.TL_userStatusOnline;
    }

    public static String getFormattedLastSeenOrDefault(TLRPC.User user, boolean[] madeShorter, String defaultValue, boolean relative) {
        int savedLastSeen = getLastSeen(user.id);
        if (savedLastSeen > 0) {
            return LocaleController.formatDateOnline(savedLastSeen, madeShorter, true, relative);
        }
        return defaultValue;
    }

    private static long getPeerId(TLRPC.Peer peer) {
        if (peer == null) {
            return 0;
        }
        if (peer.user_id != 0) {
            return peer.user_id;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        return 0;
    }

    private static int getLastMessageDate(long userId, ArrayList<MessageObject> messageObjects) {
        int lastMessageDate = 0;
        for (int i = 0, size = messageObjects.size(); i < size; i++) {
            MessageObject messageObject = messageObjects.get(i);
            if (messageObject == null || messageObject.messageOwner == null) {
                continue;
            }
            if (messageObject.getFromChatId() != userId) {
                continue;
            }
            int date = messageObject.messageOwner.date;
            if (date > lastMessageDate) {
                lastMessageDate = date;
            }
        }
        return lastMessageDate;
    }

    private static int getLastReactionDate(long userId, ArrayList<MessageObject> messageObjects) {
        int lastReactionDate = 0;
        for (int i = 0, size = messageObjects.size(); i < size; i++) {
            MessageObject messageObject = messageObjects.get(i);
            if (messageObject == null || messageObject.messageOwner == null) {
                continue;
            }
            if (messageObject.messageOwner.reactions == null || messageObject.messageOwner.reactions.recent_reactions == null) {
                continue;
            }
            for (int j = 0, rSize = messageObject.messageOwner.reactions.recent_reactions.size(); j < rSize; j++) {
                TLRPC.MessagePeerReaction reaction = messageObject.messageOwner.reactions.recent_reactions.get(j);
                if (reaction == null || reaction.peer_id == null || reaction.date <= 0) {
                    continue;
                }
                if (MessageObject.getPeerId(reaction.peer_id) != userId) {
                    continue;
                }
                if (reaction.date > lastReactionDate) {
                    lastReactionDate = reaction.date;
                }
            }
        }
        return lastReactionDate;
    }

    public static void saveLastSeenFromLoadedMessages(long userId, long selfUserId, ArrayList<MessageObject> messages, ChatActivity.ChatActivityAdapter chatAdapter) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return;
        }
        if (userId <= 0 || userId == selfUserId) {
            return;
        }
        ArrayList<MessageObject> messageObjects = chatAdapter != null ? chatAdapter.getMessages() : messages;
        if (messageObjects == null) {
            return;
        }
        int lastMessageDate = getLastMessageDate(userId, messageObjects);
        if (lastMessageDate > 0) {
            LastSeenHelper.saveLastSeen(userId, lastMessageDate);
        }
        int lastReactionDate = getLastReactionDate(userId, messageObjects);
        if (lastReactionDate > 0) {
            LastSeenHelper.saveLastSeen(userId, lastReactionDate);
        }
    }

    public static void saveLastSeenFromMessageReactions(TLRPC.TL_messageReactions reactions, long selfUserId) {
        if (reactions == null || reactions.recent_reactions == null || reactions.recent_reactions.isEmpty()) {
            return;
        }
        saveLastSeenFromPeerReactions(reactions.recent_reactions, selfUserId);
    }

    public static void saveLastSeenFromPeerReactions(List<TLRPC.MessagePeerReaction> reactions, long selfUserId) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return;
        }
        if (reactions == null || reactions.isEmpty()) {
            return;
        }
        for (int i = 0; i < reactions.size(); i++) {
            TLRPC.MessagePeerReaction reaction = reactions.get(i);
            if (reaction == null || reaction.peer_id == null || reaction.date <= 0) {
                continue;
            }
            long peerId = getPeerId(reaction.peer_id);
            if (peerId <= 0 || peerId == selfUserId) {
                continue;
            }
            saveLastSeen(peerId, reaction.date);
        }
    }
}
