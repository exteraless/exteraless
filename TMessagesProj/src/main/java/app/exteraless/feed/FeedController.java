package app.exteraless.feed;

import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Центральный класс ленты: держит состояние синтетического чата (страницы, курсоры,
 * непрочитанное), ходит за данными через {@link FeedTimelineLoader}, копит их в
 * {@link FeedStore} и рассылает результаты через NotificationCenter так, как их ждёт
 * ChatActivity в режиме поиска. Переживает изменения {@link FeedConfig}, сверяясь
 * с его generation.
 */
public class FeedController implements NotificationCenter.NotificationCenterDelegate {

    private static final int FULL_CHUNK_ROW_COUNT = 30;
    private static final int MAX_BACKFILL_ROUNDS = 3;
    private static final int MAX_RECENT_REPLIERS = 3;
    private static final long CLOSED_REFRESH_DELAY = 1000L;

    private static final int INACTIVE_CACHE_CAP_LOW = 300;
    private static final int INACTIVE_CACHE_CAP_AVERAGE = 600;
    private static final int INACTIVE_CACHE_CAP_HIGH = 1000;

    private static final int LOAD_TYPE_NEWER = 1;
    private static final int LOAD_TYPE_OLDER = 2;

    private static final int FEED_SEARCH_TYPE = 4;

    private static final FeedController[] Instance = new FeedController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int a = 0; a < lockObjects.length; a++) {
            lockObjects[a] = new Object();
        }
    }

    public final int currentAccount;

    private final FeedStore store = new FeedStore();
    private final FeedUnreadTracker unreadTracker;
    private final FeedTimelineLoader loader;
    private final FeedBackfillCoordinator backfill;

    private final ArrayList<int[]> initialLoadWaiters = new ArrayList<>();
    private final int closedRefreshGuid = ConnectionsManager.generateClassGuid();
    private final Runnable closedRefreshRunnable = this::runClosedRefresh;
    private boolean closedRefreshScheduled;

    private int attemptRounds;
    private int cachedIncludedChannelCount;
    private int configGeneration;
    private int sessionGeneration;
    private int heldGuid;
    private int heldLoadIndex;
    private int uiActiveClients;
    private int resumedUiClients;

    private boolean hasChannels;
    private boolean hasIncludedChannels;
    private boolean initialUnreadScrollPending = true;
    private boolean loading;
    private boolean loadingNewer;
    private boolean newerPagingBoundsDirty;
    private boolean olderPagingBoundsDirty;
    private boolean badgeRefreshPending;

    private SavedScrollPosition drawerScrollPosition;

    public interface ChannelsCallback {
        void onChannels(ArrayList<TLRPC.Chat> channels, int includedCount);
    }

    public static final class SavedScrollPosition {
        public final long dialogId;
        public final int messageId;
        public final int offsetTop;

        private SavedScrollPosition(long dialogId, int messageId, int offsetTop) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.offsetTop = offsetTop;
        }
    }

    private FeedController(int account) {
        currentAccount = account;
        unreadTracker = new FeedUnreadTracker(account, store.getMessages());
        loader = new FeedTimelineLoader(account);
        backfill = new FeedBackfillCoordinator(account, this::onBackfillRoundFinished);
        AndroidUtilities.runOnUIThread(() -> subscribe(account));
    }

    public static FeedController getInstance(int account) {
        FeedController localInstance = Instance[account];
        if (localInstance != null) {
            return localInstance;
        }
        synchronized (lockObjects[account]) {
            localInstance = Instance[account];
            if (localInstance == null) {
                localInstance = new FeedController(account);
                Instance[account] = localInstance;
            }
        }
        return localInstance;
    }

    public static FeedController peekInstance(int account) {
        return Instance[account];
    }

    /**
     * Канал годится для ленты, если это именно канал (не супергруппа и не сообщество)
     * и пользователь из него не вышел.
     */
    public static boolean isEligibleChannel(TLRPC.Chat chat) {
        return chat != null
                && ChatObject.isChannelAndNotMegaGroup(chat)
                && !ChatObject.isCommunity(chat)
                && !ChatObject.isNotInChat(chat);
    }

    private void subscribe(int account) {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(account);
        notificationCenter.addObserver(this, NotificationCenter.messagesDidLoad);
        notificationCenter.addObserver(this, NotificationCenter.loadingMessagesFailed);
        notificationCenter.addObserver(this, NotificationCenter.messagesDeleted);
        notificationCenter.addObserver(this, NotificationCenter.historyCleared);
        notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages);
        FeedChannelRegistry.getInstance(account).addListener(this::onFeedChannelsChanged);
    }

    private static int getInactiveCacheCap() {
        int performanceClass = SharedConfig.getDevicePerformanceClass();
        if (performanceClass == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return INACTIVE_CACHE_CAP_LOW;
        }
        if (performanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return INACTIVE_CACHE_CAP_HIGH;
        }
        return INACTIVE_CACHE_CAP_AVERAGE;
    }

    private static void addUpdated(ArrayList<MessageObject> updated, MessageObject messageObject) {
        if (!updated.contains(messageObject)) {
            updated.add(messageObject);
        }
    }

    private boolean isUiActive() {
        return uiActiveClients > 0;
    }

    private void applyEnumeration(FeedTimelineLoader.ChannelEnumeration enumeration) {
        hasChannels = enumeration.hasChannels;
        hasIncludedChannels = !enumeration.included.isEmpty();
        cachedIncludedChannelCount = enumeration.included.size();
        for (int a = 0, N = enumeration.included.size(); a < N; a++) {
            FeedTimelineLoader.ChannelSnapshot snapshot = enumeration.included.get(a);
            int readInboxMax = snapshot.readInboxMax;
            if (readInboxMax <= 0 && snapshot.unreadCount <= 0) {
                readInboxMax = snapshot.topMessage;
            }
            unreadTracker.applyReadInboxMax(snapshot.dialogId, readInboxMax);
        }
    }

    private ArrayList<MessageObject> createMessageObjects(ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        HashMap<Long, TLRPC.User> usersMap = new HashMap<>();
        HashMap<Long, TLRPC.Chat> chatsMap = new HashMap<>();
        for (int a = 0, N = users.size(); a < N; a++) {
            TLRPC.User user = users.get(a);
            usersMap.put(user.id, user);
        }
        for (int a = 0, N = chats.size(); a < N; a++) {
            TLRPC.Chat chat = chats.get(a);
            chatsMap.put(chat.id, chat);
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int a = 0, N = messages.size(); a < N; a++) {
            result.add(new MessageObject(currentAccount, messages.get(a), null, usersMap, chatsMap, null, null, true, true, 0L, false, false, false, FEED_SEARCH_TYPE));
        }
        return result;
    }

    private void ensureCurrentConfig() {
        if (configGeneration != FeedConfig.getInstance(currentAccount).getGeneration()) {
            applyConfigChange(this::postNeedReload);
        }
    }

    private void postNeedReload(Boolean truncated) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.feedNeedReload, truncated);
    }

    private void flushInitialLoadWaiters() {
        if (initialLoadWaiters.isEmpty()) {
            return;
        }
        ArrayList<int[]> waiters = new ArrayList<>(initialLoadWaiters);
        initialLoadWaiters.clear();
        ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();
        for (int a = 0, N = waiters.size(); a < N; a++) {
            int[] waiter = waiters.get(a);
            postFeedResults(waiter[0], waiter[1], visibleMessages, 0);
            postFeedCount(waiter[0]);
        }
    }

    private void onBackfillRoundFinished() {
        if (loading) {
            runAttempt();
        }
    }

    private void onFeedChannelsChanged(HashSet<Long> added, HashSet<Long> removed) {
        loader.invalidateChannelCache();
        for (Long dialogId : removed) {
            deleteHistory(dialogId, Integer.MAX_VALUE);
        }
        if (added.isEmpty()) {
            postNeedReload(Boolean.FALSE);
        } else {
            reconcileChannelSet(this::postNeedReload);
        }
    }

    private void onFeedRowsRemoved() {
        if (loading) {
            olderPagingBoundsDirty = true;
        }
        if (loadingNewer) {
            newerPagingBoundsDirty = true;
        }
    }

    private void postFeedCount(int classGuid) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.hashtagSearchUpdated,
                classGuid, store.getCount(), store.isEndReached(), 0, 0, 0);
    }

    private void postFeedResults(int classGuid, int loadIndex, ArrayList<MessageObject> messages, int loadType) {
        postFeedResults(classGuid, loadIndex, messages, loadType, false);
    }

    private void postFeedResults(int classGuid, int loadIndex, ArrayList<MessageObject> messages, int loadType, boolean hasMore) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad,
                0L, messages.size(), messages, Boolean.FALSE, 0, 0, 0, 0, loadType, Boolean.TRUE,
                classGuid, loadIndex, 0, 0, ChatActivity.MODE_SEARCH, hasMore);
    }

    private void postNewerMessagesLoaded(int classGuid, int loadIndex, ArrayList<MessageObject> messages, boolean hasMore) {
        ArrayList<MessageObject> ordered = new ArrayList<>();
        int loadType = 0;
        if (messages != null && !messages.isEmpty()) {
            ordered.addAll(messages);
            Collections.reverse(ordered);
            loadType = LOAD_TYPE_NEWER;
        }
        postFeedResults(classGuid, loadIndex, ordered, loadType, hasMore);
    }

    private void pruneStaleExclusions(FeedConfig config, MessagesController messagesController) {
        HashSet<Long> stale = null;
        for (Long dialogId : config.getExcludedSnapshot()) {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            if (chat != null && !isEligibleChannel(chat)) {
                if (stale == null) {
                    stale = new HashSet<>();
                }
                stale.add(dialogId);
            }
        }
        if (stale != null) {
            config.removeExcluded(stale);
            markConfigApplied();
        }
        HashSet<Long> staleMembers = null;
        for (FeedConfig.Category category : config.getCategories()) {
            for (Long dialogId : category.getChannels()) {
                TLRPC.Chat chat = messagesController.getChat(-dialogId);
                if (chat != null && !isEligibleChannel(chat)) {
                    if (staleMembers == null) {
                        staleMembers = new HashSet<>();
                    }
                    staleMembers.add(dialogId);
                }
            }
        }
        if (staleMembers != null) {
            config.removeFromCategories(staleMembers);
            markConfigApplied();
        }
    }

    public void requestBadgeRefresh() {
        badgeRefreshPending = true;
    }

    private void postBadgeRefreshIfPending() {
        if (badgeRefreshPending) {
            badgeRefreshPending = false;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces,
                    MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
        }
    }

    private void reconcileChannelSet(Utilities.Callback<Boolean> callback) {
        final int generation = sessionGeneration;
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        if (store.isEmpty()) {
            loadChannels((channels, includedCount) -> {
                if (callback != null) {
                    callback.run(Boolean.FALSE);
                }
            });
            return;
        }
        final HashSet<Long> loadedDialogIds = store.getLoadedDialogIds();
        final HashSet<Long> hiddenDialogIds = store.getHiddenSnapshot();
        final FeedTimelineLoader.Cursor newest = new FeedTimelineLoader.Cursor();
        final FeedTimelineLoader.Cursor oldest = new FeedTimelineLoader.Cursor();
        newest.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid);
        oldest.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, true);
            ArrayList<Long> freshDialogIds = new ArrayList<>();
            for (int a = 0, N = enumeration.included.size(); a < N; a++) {
                FeedTimelineLoader.ChannelSnapshot snapshot = enumeration.included.get(a);
                if (!loadedDialogIds.contains(snapshot.dialogId) || hiddenDialogIds.contains(snapshot.dialogId)) {
                    freshDialogIds.add(snapshot.dialogId);
                }
            }
            FeedTimelineLoader.WindowPage page = freshDialogIds.isEmpty() ? null : loader.loadChannelWindow(freshDialogIds, newest, oldest);
            ArrayList<MessageObject> messageObjects = page != null ? createMessageObjects(page.messages, page.users, page.chats) : null;
            boolean hasFreshDialogs = !freshDialogIds.isEmpty();
            AndroidUtilities.runOnUIThread(() -> applyReconciledChannelSet(generation, callback, enumeration, page, messageObjects, hasFreshDialogs));
        });
    }

    private void applyReconciledChannelSet(int generation, Utilities.Callback<Boolean> callback, FeedTimelineLoader.ChannelEnumeration enumeration,
                                           FeedTimelineLoader.WindowPage page, ArrayList<MessageObject> messageObjects, boolean hasFreshDialogs) {
        if (generation != sessionGeneration) {
            if (callback != null) {
                callback.run(Boolean.FALSE);
            }
            return;
        }
        applyEnumeration(enumeration);
        HashSet<Long> includedDialogIds = new HashSet<>();
        for (int a = 0, N = enumeration.included.size(); a < N; a++) {
            includedDialogIds.add(enumeration.included.get(a).dialogId);
        }
        store.applyIncludedDialogs(includedDialogIds);
        boolean truncated = page != null && page.truncated;
        if (page != null && !truncated && messageObjects != null && !messageObjects.isEmpty()) {
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            messagesController.putUsers(page.users, true);
            messagesController.putChats(page.chats, true);
            store.mergeRows(messageObjects);
        }
        if (hasFreshDialogs) {
            store.setEndReached(false);
            if (loading) {
                olderPagingBoundsDirty = true;
            }
            if (loadingNewer) {
                newerPagingBoundsDirty = true;
            }
        }
        if (callback != null) {
            callback.run(truncated);
        }
    }

    private void runAttempt() {
        final int classGuid = heldGuid;
        final int loadIndex = heldLoadIndex;
        final int generation = sessionGeneration;
        final boolean firstPage = store.getOldestCursor().isEmpty();
        final FeedTimelineLoader.Cursor oldest = new FeedTimelineLoader.Cursor();
        oldest.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid);
        final HashSet<Long> exhausted = backfill.getExhaustedSnapshot();
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, false);
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> applyEmptyOlderPage(generation, enumeration, classGuid, loadIndex));
                return;
            }
            FeedTimelineLoader.OlderPage page = loader.loadOlderPage(enumeration.included, oldest, exhausted);
            ArrayList<MessageObject> messageObjects = createMessageObjects(page.messages, page.users, page.chats);
            AndroidUtilities.runOnUIThread(() -> applyOlderPage(generation, enumeration, page, firstPage, messageObjects, classGuid, loadIndex));
        });
    }

    private void applyEmptyOlderPage(int generation, FeedTimelineLoader.ChannelEnumeration enumeration, int classGuid, int loadIndex) {
        if (generation != sessionGeneration) {
            return;
        }
        applyEnumeration(enumeration);
        olderPagingBoundsDirty = false;
        unreadTracker.clear();
        loading = false;
        store.setEndReached(true);
        postFeedResults(classGuid, loadIndex, new ArrayList<>(), LOAD_TYPE_OLDER);
        postFeedCount(classGuid);
        flushInitialLoadWaiters();
        postBadgeRefreshIfPending();
    }

    private void applyOlderPage(int generation, FeedTimelineLoader.ChannelEnumeration enumeration, FeedTimelineLoader.OlderPage page,
                                boolean firstPage, ArrayList<MessageObject> messageObjects, int classGuid, int loadIndex) {
        if (generation != sessionGeneration) {
            return;
        }
        if (olderPagingBoundsDirty) {
            olderPagingBoundsDirty = false;
            attemptRounds = 0;
            runAttempt();
            return;
        }
        applyEnumeration(enumeration);
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        pruneStaleExclusions(FeedConfig.getInstance(currentAccount), messagesController);
        store.getOldestCursor().set(page.last.date, page.last.uid, page.last.mid);
        if (firstPage && !page.first.isEmpty()) {
            store.getNewestCursor().set(page.first.date, page.first.uid, page.first.mid);
        }
        messagesController.putUsers(page.users, true);
        messagesController.putChats(page.chats, true);
        ArrayList<MessageObject> appended = store.appendMessages(messageObjects, false);
        if (appended.isEmpty() && page.lastChunkRowCount == FULL_CHUNK_ROW_COUNT) {
            runAttempt();
            return;
        }
        boolean endReached = !page.hasIncomplete && page.lastChunkRowCount < FULL_CHUNK_ROW_COUNT;
        if (appended.isEmpty() && !endReached && !page.backfillCandidates.isEmpty() && attemptRounds < MAX_BACKFILL_ROUNDS) {
            attemptRounds++;
            backfill.startRound(page.backfillCandidates);
            return;
        }
        loading = false;
        store.setEndReached(endReached);
        postFeedResults(classGuid, loadIndex, appended, LOAD_TYPE_OLDER);
        postFeedCount(classGuid);
        flushInitialLoadWaiters();
        postBadgeRefreshIfPending();
    }

    private void runLoadNewer(int classGuid, int loadIndex) {
        final int generation = sessionGeneration;
        final FeedTimelineLoader.Cursor newest = new FeedTimelineLoader.Cursor();
        newest.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid);
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, false);
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> applyEmptyNewerPage(generation, classGuid, loadIndex));
                return;
            }
            FeedTimelineLoader.NewerPage page = loader.loadNewerPage(enumeration.included, newest);
            ArrayList<MessageObject> messageObjects = createMessageObjects(page.messages, page.users, page.chats);
            AndroidUtilities.runOnUIThread(() -> applyNewerPage(generation, classGuid, loadIndex, enumeration, page, messageObjects));
        });
    }

    private void applyEmptyNewerPage(int generation, int classGuid, int loadIndex) {
        if (generation != sessionGeneration) {
            return;
        }
        newerPagingBoundsDirty = false;
        loadingNewer = false;
        postNewerMessagesLoaded(classGuid, loadIndex, null, false);
        postFeedCount(classGuid);
    }

    private void applyNewerPage(int generation, int classGuid, int loadIndex, FeedTimelineLoader.ChannelEnumeration enumeration,
                                FeedTimelineLoader.NewerPage page, ArrayList<MessageObject> messageObjects) {
        if (generation != sessionGeneration) {
            return;
        }
        if (newerPagingBoundsDirty) {
            newerPagingBoundsDirty = false;
            if (!store.getNewestCursor().isEmpty()) {
                runLoadNewer(classGuid, loadIndex);
                return;
            }
            loadingNewer = false;
            postNewerMessagesLoaded(classGuid, loadIndex, null, false);
            postFeedCount(classGuid);
            return;
        }
        loadingNewer = false;
        applyEnumeration(enumeration);
        store.getNewestCursor().set(page.first.date, page.first.uid, page.first.mid);
        if (page.messages.isEmpty()) {
            postNewerMessagesLoaded(classGuid, loadIndex, null, page.hasMore);
            if (!page.hasMore) {
                postFeedCount(classGuid);
            }
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        messagesController.putUsers(page.users, true);
        messagesController.putChats(page.chats, true);
        postNewerMessagesLoaded(classGuid, loadIndex, store.appendMessages(messageObjects, true), page.hasMore);
        if (!page.hasMore) {
            postFeedCount(classGuid);
        }
        trimForInactiveCache();
    }

    private void runClosedRefresh() {
        closedRefreshScheduled = false;
        if (isUiActive() || loadingNewer || store.isEmpty() || store.getNewestCursor().isEmpty()) {
            return;
        }
        loadNewer(closedRefreshGuid, 0);
    }

    private void scheduleClosedRefresh() {
        if (closedRefreshScheduled) {
            return;
        }
        closedRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(closedRefreshRunnable, CLOSED_REFRESH_DELAY);
    }

    private void updateCounters(LongSparseArray<SparseIntArray> counters, boolean views, ArrayList<MessageObject> updated) {
        if (counters == null) {
            return;
        }
        for (int a = 0; a < counters.size(); a++) {
            long dialogId = counters.keyAt(a);
            SparseIntArray values = counters.valueAt(a);
            for (int b = 0; b < values.size(); b++) {
                MessageObject messageObject = getMessage(dialogId, values.keyAt(b));
                if (messageObject == null) {
                    continue;
                }
                int value = values.valueAt(b);
                TLRPC.Message owner = messageObject.messageOwner;
                if (views) {
                    if (value > owner.views) {
                        owner.views = value;
                        addUpdated(updated, messageObject);
                    }
                } else if (value > owner.forwards) {
                    owner.forwards = value;
                    addUpdated(updated, messageObject);
                }
            }
        }
    }

    private void updateReplies(LongSparseArray<SparseArray<TLRPC.MessageReplies>> repliesArray, boolean added, ArrayList<MessageObject> updated) {
        if (repliesArray == null) {
            return;
        }
        for (int a = 0; a < repliesArray.size(); a++) {
            long dialogId = repliesArray.keyAt(a);
            SparseArray<TLRPC.MessageReplies> values = repliesArray.valueAt(a);
            for (int b = 0; b < values.size(); b++) {
                MessageObject messageObject = getMessage(dialogId, values.keyAt(b));
                TLRPC.MessageReplies update = values.valueAt(b);
                if (messageObject == null || update == null) {
                    continue;
                }
                TLRPC.Message owner = messageObject.messageOwner;
                if (added) {
                    if (owner.replies == null) {
                        owner.replies = new TLRPC.TL_messageReplies();
                    }
                    owner.replies.replies += update.replies;
                    for (int c = 0; c < update.recent_repliers.size(); c++) {
                        owner.replies.recent_repliers.remove(update.recent_repliers.get(c));
                    }
                    owner.replies.recent_repliers.addAll(0, update.recent_repliers);
                    while (owner.replies.recent_repliers.size() > MAX_RECENT_REPLIERS) {
                        owner.replies.recent_repliers.remove(0);
                    }
                } else if (owner.replies == null
                        || update.replies_pts > owner.replies.replies_pts
                        || update.read_max_id > owner.replies.read_max_id
                        || update.max_id > owner.replies.max_id) {
                    owner.replies = update;
                }
                messageObject.animateComments = true;
                addUpdated(updated, messageObject);
            }
        }
    }

    /**
     * Перечитывает набор каналов после смены настроек ленты и сообщает вызвавшему,
     * пришлось ли выкинуть уже загруженное окно постов.
     */
    public void applyConfigChange(Utilities.Callback<Boolean> callback) {
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        reconcileChannelSet(callback);
    }

    public void cancelLoads() {
        sessionGeneration++;
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        attemptRounds = 0;
        initialLoadWaiters.clear();
        backfill.cancel();
    }

    public void clear() {
        sessionGeneration++;
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        unreadTracker.clear();
        drawerScrollPosition = null;
        store.clear();
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        attemptRounds = 0;
        initialLoadWaiters.clear();
        backfill.cancel();
        backfill.clearExhausted();
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
    }

    public boolean consumeInitialUnreadScroll() {
        boolean pending = initialUnreadScrollPending;
        initialUnreadScrollPending = false;
        return pending;
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int index) {
        return unreadTracker.countUnreadBelow(messages, index);
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId) {
        boolean[] rowsRemoved = new boolean[1];
        ArrayList<Integer> deleted = store.deleteHistory(dialogId, maxId, rowsRemoved);
        if (rowsRemoved[0]) {
            onFeedRowsRemoved();
        }
        return deleted;
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> messageIds) {
        boolean[] rowsRemoved = new boolean[1];
        ArrayList<Integer> deleted = store.deleteMessages(dialogId, messageIds, rowsRemoved);
        if (rowsRemoved[0]) {
            onFeedRowsRemoved();
        }
        return deleted;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            backfill.onMessagesDidLoad(args);
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            backfill.onLoadingMessagesFailed(args);
        } else if (id == NotificationCenter.messagesDeleted) {
            if (isUiActive() || (Boolean) args[2]) {
                return;
            }
            long dialogId = (Long) args[1];
            if (dialogId == 0) {
                return;
            }
            if (dialogId > 0) {
                dialogId = -dialogId;
            }
            deleteMessages(dialogId, (ArrayList<Integer>) args[0]);
        } else if (id == NotificationCenter.historyCleared) {
            if (isUiActive()) {
                return;
            }
            long dialogId = (Long) args[0];
            if (DialogObject.isChatDialog(dialogId)) {
                deleteHistory(dialogId, (Integer) args[1]);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (isUiActive() || (Boolean) args[2] || store.isEmpty() || store.getNewestCursor().isEmpty()
                    || !isIncludedChannelPost((Long) args[0])) {
                return;
            }
            scheduleClosedRefresh();
        }
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        return unreadTracker.findFirstUnreadIndex(messages);
    }

    public SavedScrollPosition getDrawerScrollPosition() {
        return drawerScrollPosition;
    }

    public int getIncludedChannelCount() {
        return cachedIncludedChannelCount;
    }

    public MessageObject getMessage(long dialogId, int messageId) {
        return store.getMessage(dialogId, messageId);
    }

    public ArrayList<MessageObject> getMessages() {
        return store.getMessages();
    }

    public FeedStore getStore() {
        return store;
    }

    public int getUnreadCount() {
        if (!app.exteraless.appearance.AppearanceConfig.showFeedUnreadCounter()) {
            return 0;
        }
        return unreadTracker.getUnreadCount();
    }

    public boolean hasChannels() {
        return hasChannels;
    }

    public boolean hasIncludedChannels() {
        return hasIncludedChannels;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        return store.hasMessagesForDialog(dialogId);
    }

    public boolean hasNoSyntheticIds() {
        return store.hasNoSyntheticIds();
    }

    public boolean isIncludedChannelPost(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId) || FeedConfig.getInstance(currentAccount).isExcluded(dialogId)) {
            return false;
        }
        return isEligibleChannel(MessagesController.getInstance(currentAccount).getChat(-dialogId));
    }

    public void loadChannels(ChannelsCallback callback) {
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        final int generation = sessionGeneration;
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, false);
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != sessionGeneration) {
                    return;
                }
                applyEnumeration(enumeration);
                MessagesController.getInstance(currentAccount).putChats(enumeration.channels, true);
                if (callback != null) {
                    callback.onChannels(enumeration.channels, enumeration.included.size());
                }
            });
        });
    }

    /**
     * Отдаёт уже накопленное окно постов сразу, если оно есть; иначе запускает загрузку
     * и запоминает вызвавшего, чтобы ответить ему по её завершении. true — данные ушли синхронно.
     */
    public boolean loadInitial(int classGuid, int loadIndex) {
        ensureCurrentConfig();
        if (store.isEmpty()) {
            if (!loadMore(classGuid, loadIndex)) {
                initialLoadWaiters.add(new int[]{classGuid, loadIndex});
            }
            return false;
        }
        final ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();
        for (int a = 0, N = visibleMessages.size(); a < N; a++) {
            visibleMessages.get(a).viewsReloaded = false;
        }
        if (visibleMessages.isEmpty() && !store.isEndReached()) {
            if (!loadMore(classGuid, loadIndex)) {
                initialLoadWaiters.add(new int[]{classGuid, loadIndex});
            }
            return false;
        }
        final int generation = sessionGeneration;
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, true);
            AndroidUtilities.runOnUIThread(() -> {
                applyEnumeration(enumeration);
                postFeedResults(classGuid, loadIndex, visibleMessages, 0);
                postFeedCount(classGuid);
            });
        });
        return true;
    }

    public boolean loadMore(int classGuid, int loadIndex) {
        ensureCurrentConfig();
        if (loading || (store.isEndReached() && !store.getOldestCursor().isEmpty())) {
            return false;
        }
        loading = true;
        heldGuid = classGuid;
        heldLoadIndex = loadIndex;
        attemptRounds = 0;
        runAttempt();
        return true;
    }

    public boolean loadNewer(int classGuid, int loadIndex) {
        ensureCurrentConfig();
        if (loadingNewer || store.getNewestCursor().isEmpty()) {
            return false;
        }
        loadingNewer = true;
        runLoadNewer(classGuid, loadIndex);
        return true;
    }

    public void markAllRead() {
        unreadTracker.markAllRead();
    }

    public void markConfigApplied() {
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
    }

    public void onPostSeen(long dialogId, int messageId) {
        unreadTracker.onPostSeen(dialogId, messageId);
    }

    public void refreshReadState(Runnable callback) {
        final int generation = sessionGeneration;
        final FeedConfig config = FeedConfig.getInstance(currentAccount);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != sessionGeneration) {
                    return;
                }
                applyEnumeration(enumeration);
                if (callback != null) {
                    callback.run();
                }
            });
        });
    }

    public void replaceMessage(MessageObject oldMessage, MessageObject newMessage) {
        store.replaceMessage(oldMessage, newMessage);
    }

    public long resolveRealDialogId(int syntheticMessageId) {
        return store.resolveRealDialogId(syntheticMessageId);
    }

    public int resolveRealMessageId(long dialogId, int syntheticMessageId) {
        return store.resolveRealMessageId(dialogId, syntheticMessageId);
    }

    public void saveDrawerScrollPosition(long dialogId, int messageId, int offsetTop) {
        if (dialogId == 0 || messageId <= 0) {
            return;
        }
        drawerScrollPosition = new SavedScrollPosition(dialogId, messageId, offsetTop);
    }

    /**
     * Считает открытые экраны ленты: на первом включает её, на последнем гасит загрузки
     * и подрезает кэш.
     */
    public void setUiActive(boolean active) {
        if (!active) {
            if (uiActiveClients == 0) {
                return;
            }
            uiActiveClients--;
            if (uiActiveClients == 0) {
                cancelLoads();
                trimForInactiveCache();
            }
            return;
        }
        uiActiveClients++;
        if (uiActiveClients > 1) {
            return;
        }
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
        if (loadingNewer) {
            cancelLoads();
        }
    }

    public void setUiResumed(boolean resumed) {
        if (resumed) {
            resumedUiClients++;
        } else if (resumedUiClients > 0) {
            resumedUiClients--;
        }
    }

    public void trimForInactiveCache() {
        if (isUiActive() || store.isEmpty()) {
            return;
        }
        store.trim(getInactiveCacheCap());
    }

    public ArrayList<MessageObject> updateViews(LongSparseArray<SparseIntArray> views, LongSparseArray<SparseIntArray> forwards,
                                                LongSparseArray<SparseArray<TLRPC.MessageReplies>> replies, boolean addedReplies) {
        ArrayList<MessageObject> updated = new ArrayList<>();
        updateCounters(views, true, updated);
        updateCounters(forwards, false, updated);
        updateReplies(replies, addedReplies, updated);
        return updated;
    }
}
