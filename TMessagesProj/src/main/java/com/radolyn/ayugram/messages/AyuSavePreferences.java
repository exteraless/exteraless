/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;


import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import tw.nekomimi.nekogram.NekoConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import xyz.nextalone.nagram.NaConfig;

public class AyuSavePreferences {
    public static final String saveExclusionPrefix = "saveDeletedExclusion_";
    public static final String saveEditedExclusionPrefix = "saveEditedExclusion_";
    public static ConcurrentHashMap<Long, Boolean> saveDeletedSelections = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<Long, Boolean> saveEditedSelections = new ConcurrentHashMap<>();
    public static boolean isSaveSelectionsLoaded = false;
    private final TLRPC.Message message;
    private final int accountId;
    private final long userId;
    private long dialogId = -1;
    private long topicId = -1;
    private int messageId = -1;
    private int requestCatchTime = -1;

    public AyuSavePreferences(TLRPC.Message msg, int accountId, long dialogId, long topicId, int messageId, int requestCatchTime) {
        this.message = msg;
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();

        if (msg == null) {
            return;
        }

        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
        this.requestCatchTime = requestCatchTime;
    }

    public AyuSavePreferences(TLRPC.Message msg, int accountId) {
        this.message = msg;
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();

        if (msg == null) {
            return;
        }

        this.dialogId = msg.dialog_id;
        this.topicId = MessageObject.getTopicId(accountId, msg, false);
        this.messageId = msg.id;
        this.requestCatchTime = (int) (System.currentTimeMillis() / 1000);
    }

    public static boolean saveDeletedMessageFor(int accountId, long dialogId, MessageObject messageObject) {
        if (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.from_id != null) {
            return saveDeletedMessageFor(accountId, dialogId, messageObject.messageOwner.from_id.user_id);
        }
        return saveDeletedMessageFor(accountId, dialogId, 0);
    }

    public static boolean saveDeletedMessageFor(int accountId, long dialogId, long userId) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return false;
        }

        if (getSaveDeletedExclusion(dialogId)) {
            return false;
        }

        if (!saveDeletedForDialogKind(accountId, dialogId) || !saveInDialogFolder(accountId, dialogId)) {
            return false;
        }

        if (userId != 0) {
            if (getSaveDeletedExclusion(userId)) {
                return false;
            }
            var fromUser = MessagesController.getInstance(accountId).getUser(userId);
            if (fromUser != null) {
                return !fromUser.bot || NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool();
            } else {
                final MessagesStorage messagesStorage = MessagesStorage.getInstance(accountId);
                final TLRPC.User[] user = {null};
                if (Thread.currentThread() == messagesStorage.getStorageQueue()) {
                    user[0] = messagesStorage.getUser(userId);
                } else {
                    final CountDownLatch countDownLatch = new CountDownLatch(1);
                    messagesStorage.getStorageQueue().postRunnable(() -> {
                        user[0] = messagesStorage.getUser(userId);
                        countDownLatch.countDown();
                    });
                    try {
                        countDownLatch.await();
                    } catch (Exception ignored) {
                    }
                }
                if (user[0] != null) {
                    return !user[0].bot || NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool();
                }
            }
        }

        var user = MessagesController.getInstance(accountId).getUser(Math.abs(dialogId));
        if (user == null) {
            return true;
        }

        return !user.bot || NaConfig.INSTANCE.getSaveDeletedMessageForBot().Bool();
    }

    private static boolean saveDeletedForDialogKind(int accountId, long dialogId) {
        if (dialogId >= 0) {
            return NaConfig.INSTANCE.getSaveDeletedInPrivateChats().Bool();
        }
        var chat = MessagesController.getInstance(accountId).getChat(-dialogId);
        if (chat == null) {
            return true;
        }
        return ChatObject.isChannelAndNotMegaGroup(chat)
                ? NaConfig.INSTANCE.getSaveDeletedInChannels().Bool()
                : NaConfig.INSTANCE.getSaveDeletedInGroups().Bool();
    }

    public static boolean saveInDialogFolder(int accountId, long dialogId) {
        if (NaConfig.INSTANCE.getSaveInArchivedChats().Bool()) {
            return true;
        }
        try {
            TLRPC.Dialog dialog = MessagesController.getInstance(accountId).dialogs_dict.get(dialogId);
            return dialog == null || dialog.folder_id != 1;
        } catch (Exception e) {
            return true;
        }
    }

    public static void setSaveDeletedExclusion(long chatId, boolean value) {
        saveDeletedSelections.put(Math.abs(chatId), value);
        NekoConfig.getPreferences().edit().putBoolean(saveExclusionPrefix + Math.abs(chatId), value).apply();
    }

    public static boolean getSaveDeletedExclusion(long chatId) {
        boolean selected = getSaveDeletedSelection(chatId);
        return NaConfig.INSTANCE.getSaveDeletedExceptionsMode().Int() == 1 ? !selected : selected;
    }

    public static boolean getSaveDeletedSelection(long chatId) {
        if (isSaveSelectionsLoaded) {
            return Boolean.TRUE.equals(saveDeletedSelections.getOrDefault(Math.abs(chatId), false));
        }
        return saveDeletedSelections.computeIfAbsent(Math.abs(chatId), k -> NekoConfig.getPreferences().getBoolean(saveExclusionPrefix + Math.abs(chatId), false));
    }

    public static void setSaveEditedExclusion(long chatId, boolean value) {
        saveEditedSelections.put(Math.abs(chatId), value);
        NekoConfig.getPreferences().edit().putBoolean(saveEditedExclusionPrefix + Math.abs(chatId), value).apply();
    }

    public static boolean getSaveEditedExclusion(long chatId) {
        boolean selected = getSaveEditedSelection(chatId);
        return NaConfig.INSTANCE.getSaveEditedExceptionsMode().Int() == 1 ? !selected : selected;
    }

    public static boolean getSaveEditedSelection(long chatId) {
        if (isSaveSelectionsLoaded) {
            return Boolean.TRUE.equals(saveEditedSelections.getOrDefault(Math.abs(chatId), false));
        }
        return saveEditedSelections.computeIfAbsent(Math.abs(chatId), k -> NekoConfig.getPreferences().getBoolean(saveEditedExclusionPrefix + Math.abs(chatId), false));
    }

    public static boolean saveEditedMessageFor(int accountId, long dialogId, long userId) {
        if (!NaConfig.INSTANCE.getEnableSaveEditsHistory().Bool()) {
            return false;
        }
        if (getSaveEditedExclusion(dialogId) || getSaveEditedExclusion(userId)) {
            return false;
        }
        return saveInDialogFolder(accountId, dialogId);
    }

    public static void loadAllExclusions() {
        Utilities.stageQueue.postRunnable(() -> {
            Map<String, ?> allEntries = NekoConfig.getPreferences().getAll();
            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                if (entry.getKey().startsWith(saveExclusionPrefix)) {
                    try {
                        long chatId = Long.parseLong(entry.getKey().substring(saveExclusionPrefix.length()));
                        if (entry.getValue() instanceof Boolean) {
                            saveDeletedSelections.put(chatId, (Boolean) entry.getValue());
                        }
                    } catch (Exception ignored) {
                    }
                } else if (entry.getKey().startsWith(saveEditedExclusionPrefix)) {
                    try {
                        long chatId = Long.parseLong(entry.getKey().substring(saveEditedExclusionPrefix.length()));
                        if (entry.getValue() instanceof Boolean) {
                            saveEditedSelections.put(chatId, (Boolean) entry.getValue());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            isSaveSelectionsLoaded = true;
        });
    }

    public TLRPC.Message getMessage() {
        return message;
    }

    public int getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public long getDialogId() {
        return dialogId;
    }

    public void setDialogId(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        this.dialogId = dialogId;
    }

    public long getTopicId() {
        return topicId;
    }

    public int getMessageId() {
        return messageId;
    }

    public int getRequestCatchTime() {
        return requestCatchTime;
    }

    public long getFromUserId() {
        if (message == null || message.from_id == null) {
            return 0;
        }
        return message.from_id.user_id;
    }

}
