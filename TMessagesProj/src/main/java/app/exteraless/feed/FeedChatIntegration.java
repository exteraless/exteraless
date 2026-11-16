package app.exteraless.feed;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Прослойка между лентой и встроенным ChatActivity: лента показывается как синтетический чат,
 * поэтому весь список строк живёт в хосте, а этот класс отвечает за то, что в нём лежит.
 * Сюда собрано всё, что нельзя выразить одним лишь источником данных: разделитель непрочитанного
 * и прокрутка к нему, заголовки дат, сверка списка со снимком {@link FeedStore}, счётчик кнопки
 * «вниз», дозагрузка реакций видимых постов и скрытие канала с возможностью отмены.
 *
 * <p>Хост ({@link Host}) реализует ChatActivity: класс никогда не трогает список напрямую в обход
 * уведомлений адаптера — на каждую вставку и удаление идёт notify, иначе RecyclerView разъедется.
 */
public class FeedChatIntegration {

    private static final int PAGEDOWN_SCROLL_THRESHOLD = AndroidUtilities.dp(100.0f);
    private static final int NEAR_NEWEST_THRESHOLD = AndroidUtilities.dp(160.0f);

    private static final int UNREAD_DIVIDER_SCROLL_OFFSET_DP = 48;
    private static final long REACTIONS_RECHECK_INTERVAL = 15000L;

    private final int currentAccount;
    private final Host host;
    private final boolean restoreDrawerScrollPosition;

    private Runnable channelsChangedCallback;
    private boolean destroyed;
    private boolean initialScrollApplied;
    private boolean pagedownShownByScroll;
    private boolean pendingDividerScroll;
    private long pendingHideDialogId;
    private ScrollAnchor pendingInitialScrollRestore;
    private boolean reactionsRefreshScheduled;
    private boolean readyToMarkAsRead;
    private boolean scrollPreservedNewerToUnread;
    private boolean settleAtNewestScheduled;
    private int totalScrollDy;
    private MessageObject unreadDivider;
    private boolean viewportActive;
    private int preserveScrollLoadIndex = -1;
    private int lastPagedownCount = -1;
    private int restorePagesRequested;
    private static final int MAX_RESTORE_PAGES = 30;

    private final Runnable settleAtNewestRunnable = this::settleAtNewestNow;

    private final int reactionsRequestGuid = ConnectionsManager.generateClassGuid();
    private final LongSparseArray<Long> reactionsLastCheckTimes = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<Integer>> pendingReactionIds = new LongSparseArray<>();
    private final Runnable reactionsRefreshRunnable = this::flushReactionsRefresh;

    /**
     * Всё, что интеграции нужно от экрана-хозяина: доступ к списку строк, уведомления адаптера,
     * состояние прокрутки и кнопки «вниз».
     */
    public interface Host {
        boolean canScrollToNewer();

        ScrollAnchor captureScrollAnchor();

        void deleteRows(ArrayList<Integer> ids);

        int getDistanceToNewerPx();

        BaseFragment getFragment();

        int getLastVisibleMessageIndex();

        ArrayList<MessageObject> getMessages();

        int getNewestVisibleMessageIndex();

        void invalidateVisiblePart();

        boolean isFirstLoadComplete();

        boolean isListReady();

        boolean isListScrollIdle();

        boolean isPagedownButtonVisible();

        boolean isScrollAnimationRunning();

        void materializeRow(MessageObject message);

        int nextStableId();

        void notifyAllMessagesChanged();

        void notifyMessageInserted(int index);

        void notifyMessageRemoved(int index);

        void onFeedListChanged();

        void reloadFeed();

        void requestOlderFeedPage();

        void restoreScrollAnchor(ScrollAnchor anchor);

        void scrollToMessage(int index, int offset);

        void scrollToMessageAnimated(int index, int offset);

        void setPagedownButtonVisible(boolean visible);

        void setPagedownCount(int count);

        void showEmptyFeedProgress();

        void showEmptyFeedState();

        int stableIdForDateHeader(int dateKey);
    }

    /**
     * Строка списка и её отступ от верха — точка, за которую держится прокрутка при перестройке.
     */
    public static final class ScrollAnchor {
        public final int offsetTop;
        public final MessageObject row;

        public ScrollAnchor(MessageObject row, int offsetTop) {
            this.row = row;
            this.offsetTop = offsetTop;
        }
    }

    public FeedChatIntegration(int currentAccount, Host host, boolean restoreDrawerScrollPosition) {
        this.currentAccount = currentAccount;
        this.host = host;
        this.restoreDrawerScrollPosition = restoreDrawerScrollPosition;
    }

    private void applyUnreadDivider(boolean scrollToDivider, boolean incrementalNotify) {
        if (!host.isListReady()) {
            return;
        }
        ArrayList<MessageObject> rows = host.getMessages();
        if (rows.isEmpty()) {
            unreadDivider = null;
            readyToMarkAsRead = false;
            return;
        }
        int previousIndex = unreadDivider == null ? -1 : rows.indexOf(unreadDivider);
        MessageObject divider = previousIndex >= 0 ? unreadDivider : null;
        if (previousIndex >= 0) {
            rows.remove(previousIndex);
        }
        unreadDivider = null;

        int firstUnreadIndex = FeedController.getInstance(currentAccount).findFirstUnreadIndex(rows);
        if (firstUnreadIndex < 0) {
            pendingDividerScroll = false;
            if (previousIndex >= 0) {
                if (!scrollToDivider || incrementalNotify) {
                    host.notifyMessageRemoved(previousIndex);
                    host.invalidateVisiblePart();
                } else {
                    host.notifyAllMessagesChanged();
                }
            }
            readyToMarkAsRead = true;
            return;
        }

        int insertIndex = findDividerInsertIndex(rows, firstUnreadIndex);
        if (divider == null) {
            divider = FeedMessageUtils.createUnreadDivider(currentAccount, host.nextStableId());
        }
        rows.add(insertIndex, divider);
        unreadDivider = divider;

        if (!scrollToDivider) {
            readyToMarkAsRead = true;
            if (previousIndex < 0) {
                host.notifyMessageInserted(insertIndex);
                host.invalidateVisiblePart();
            } else if (previousIndex != insertIndex) {
                host.notifyMessageRemoved(previousIndex);
                host.notifyMessageInserted(insertIndex);
                host.invalidateVisiblePart();
            }
            return;
        }

        readyToMarkAsRead = false;
        if (!incrementalNotify) {
            host.notifyAllMessagesChanged();
        } else if (previousIndex < 0) {
            host.notifyMessageInserted(insertIndex);
        } else if (previousIndex != insertIndex) {
            host.notifyMessageRemoved(previousIndex);
            host.notifyMessageInserted(insertIndex);
        }
        pendingDividerScroll = true;
        requestPendingInitialPosition();
        host.invalidateVisiblePart();
    }

    private void cancelPendingReactionsRefresh() {
        AndroidUtilities.cancelRunOnUIThread(reactionsRefreshRunnable);
        reactionsRefreshScheduled = false;
        pendingReactionIds.clear();
    }

    private MessageObject createDateHeader(MessageObject message) {
        return FeedMessageUtils.createDateHeader(currentAccount, message, host.stableIdForDateHeader(message.dateKeyInt));
    }

    private static int findDividerInsertIndex(ArrayList<MessageObject> rows, int firstUnreadIndex) {
        MessageObject firstUnread = rows.get(firstUnreadIndex);
        long groupId = firstUnread.getGroupId();
        if (groupId == 0) {
            return firstUnreadIndex + 1;
        }
        long dialogId = firstUnread.getDialogId();
        int insertIndex = firstUnreadIndex + 1;
        for (int i = 0; i < rows.size(); i++) {
            MessageObject row = rows.get(i);
            if (row != null && row.getGroupId() == groupId && row.getDialogId() == dialogId) {
                insertIndex = Math.max(insertIndex, i + 1);
            }
        }
        return insertIndex;
    }

    private void flushReactionsRefresh() {
        reactionsRefreshScheduled = false;
        if (destroyed || !viewportActive) {
            pendingReactionIds.clear();
            return;
        }
        for (int i = 0; i < pendingReactionIds.size(); i++) {
            TLRPC.TL_messages_getMessagesReactions request = new TLRPC.TL_messages_getMessagesReactions();
            request.peer = MessagesController.getInstance(currentAccount).getInputPeer(pendingReactionIds.keyAt(i));
            request.id.addAll(pendingReactionIds.valueAt(i));
            int requestToken = ConnectionsManager.getInstance(currentAccount)
                    .sendRequest(request, (response, error) -> onReactionsLoaded(response));
            ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(requestToken, reactionsRequestGuid);
        }
        pendingReactionIds.clear();
    }

    private void onReactionsLoaded(TLObject response) {
        if (!(response instanceof TLRPC.Updates)) {
            return;
        }
        TLRPC.Updates updates = (TLRPC.Updates) response;
        for (int i = 0; i < updates.updates.size(); i++) {
            TLRPC.Update update = updates.updates.get(i);
            if (update instanceof TL_update.TL_updateMessageReactions) {
                ((TL_update.TL_updateMessageReactions) update).updateUnreadState = false;
            }
        }
        MessagesController.getInstance(currentAccount).processUpdates(updates, false);
    }

    private static int getInsertIndex(ArrayList<MessageObject> rows, MessageObject row, ArrayList<MessageObject> visibleMessages, int visibleIndex, int cursor) {
        int index = Math.min(cursor, rows.size());
        if (visibleIndex <= 0 || index >= rows.size()) {
            return index;
        }
        MessageObject previousVisible = visibleMessages.get(visibleIndex - 1);
        MessageObject rowAtIndex = rows.get(index);
        if (previousVisible == null || rowAtIndex == null || !rowAtIndex.isDateObject) {
            return index;
        }
        int previousDateKey = previousVisible.dateKeyInt;
        if (previousDateKey == row.dateKeyInt || rowAtIndex.dateKeyInt != previousDateKey) {
            return index;
        }
        return index + 1;
    }

    private boolean hasMaterializedPostRows() {
        ArrayList<MessageObject> rows = host.getMessages();
        for (int i = 0; i < rows.size(); i++) {
            if (FeedMessageUtils.isPostRow(rows.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingInitialPosition() {
        return pendingInitialScrollRestore != null || pendingDividerScroll;
    }

    private void maybeScrollToDivider() {
        if (!pendingDividerScroll) {
            return;
        }
        if (!host.isListReady() || unreadDivider == null) {
            pendingDividerScroll = false;
            readyToMarkAsRead = true;
            return;
        }
        int lastVisibleIndex = host.getLastVisibleMessageIndex();
        if (lastVisibleIndex == Integer.MIN_VALUE) {
            return;
        }
        int dividerIndex = host.getMessages().indexOf(unreadDivider);
        if (dividerIndex >= 0 && dividerIndex > lastVisibleIndex) {
            host.scrollToMessage(dividerIndex, AndroidUtilities.dp(UNREAD_DIVIDER_SCROLL_OFFSET_DP));
        }
        pendingDividerScroll = false;
        readyToMarkAsRead = true;
    }

    public static void mergeDeletedIds(ArrayList<Integer> target, ArrayList<Integer> source) {
        for (int i = 0; i < source.size(); i++) {
            if (!target.contains(source.get(i))) {
                target.add(source.get(i));
            }
        }
    }

    private boolean normalizeDateHeaders(ArrayList<MessageObject> rows) {
        boolean changed = false;
        MessageObject pendingRun = null;
        int index = 0;
        while (index < rows.size()) {
            MessageObject row = rows.get(index);
            if (row == null || row.type == MessageObject.TYPE_LOADING || row.isSponsored()) {
                index++;
                continue;
            }
            if (row.isDateObject) {
                if (pendingRun == null) {
                    rows.remove(index);
                    host.notifyMessageRemoved(index);
                    changed = true;
                    continue;
                }
                if (pendingRun.dateKeyInt != row.dateKeyInt) {
                    rows.add(index, createDateHeader(pendingRun));
                    host.notifyMessageInserted(index);
                    changed = true;
                    pendingRun = null;
                    index++;
                    continue;
                }
                pendingRun = null;
                index++;
                continue;
            }
            if (pendingRun != null && pendingRun.dateKeyInt != row.dateKeyInt) {
                rows.add(index, createDateHeader(pendingRun));
                host.notifyMessageInserted(index);
                changed = true;
                pendingRun = null;
                index++;
                continue;
            }
            pendingRun = row;
            index++;
        }
        if (pendingRun != null) {
            rows.add(createDateHeader(pendingRun));
            host.notifyMessageInserted(rows.size() - 1);
            changed = true;
        }
        return changed;
    }

    private void requestPendingInitialPosition() {
        if (host.getFragment().isPaused() || !host.isListReady()) {
            return;
        }
        if (pendingInitialScrollRestore != null) {
            host.restoreScrollAnchor(pendingInitialScrollRestore);
            return;
        }
        if (!pendingDividerScroll) {
            return;
        }
        int dividerIndex = unreadDivider == null ? -1 : host.getMessages().indexOf(unreadDivider);
        if (dividerIndex >= 0) {
            host.scrollToMessage(dividerIndex, AndroidUtilities.dp(UNREAD_DIVIDER_SCROLL_OFFSET_DP));
        } else {
            pendingDividerScroll = false;
            readyToMarkAsRead = true;
        }
    }

    private void requestReactionsRefresh(MessageObject message) {
        if (destroyed || !viewportActive || message.messageOwner == null) {
            return;
        }
        int realId = message.getRealId();
        long dialogId = message.getDialogId();
        if (realId <= 0 || dialogId == 0) {
            return;
        }
        if (message.messageOwner.action != null && !message.canSetReaction()) {
            return;
        }
        long now = System.currentTimeMillis();
        long syntheticId = message.getId();
        if (now - reactionsLastCheckTimes.get(syntheticId, 0L) <= REACTIONS_RECHECK_INTERVAL) {
            return;
        }
        reactionsLastCheckTimes.put(syntheticId, now);
        ArrayList<Integer> ids = pendingReactionIds.get(dialogId);
        if (ids == null) {
            ids = new ArrayList<>();
            pendingReactionIds.put(dialogId, ids);
        }
        ids.add(realId);
        if (reactionsRefreshScheduled) {
            return;
        }
        reactionsRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(reactionsRefreshRunnable);
    }

    private void resetMetadataRefresh() {
        cancelPendingReactionsRefresh();
        reactionsLastCheckTimes.clear();
        ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(reactionsRequestGuid);
    }

    private void settleAtNewestNow() {
        settleAtNewestScheduled = false;
        if (destroyed || !viewportActive || !host.isListReady() || host.isScrollAnimationRunning() || host.canScrollToNewer()) {
            return;
        }
        settleUnreadDivider();
    }

    private void undoHideChannel() {
        long dialogId = pendingHideDialogId;
        if (dialogId == 0) {
            return;
        }
        pendingHideDialogId = 0L;
        FeedController feedController = FeedController.getInstance(currentAccount);
        FeedConfig.getInstance(currentAccount).setExcluded(dialogId, false);
        feedController.markConfigApplied();
        feedController.getStore().setHidden(dialogId, false);
        reconcileWithStore();
        onFeedExclusionsChanged();
        notifyChannelsChanged();
    }

    private void updatePagedownCounter() {
        if (!host.isListReady() || host.isScrollAnimationRunning()) {
            return;
        }
        int newestVisibleIndex = host.getNewestVisibleMessageIndex();
        int unreadBelow = newestVisibleIndex == Integer.MIN_VALUE
                ? 0
                : FeedController.getInstance(currentAccount).countUnreadBelow(host.getMessages(), newestVisibleIndex);
        if (unreadBelow != lastPagedownCount) {
            lastPagedownCount = unreadBelow;
            host.setPagedownCount(unreadBelow);
        }
        if (unreadBelow > 0) {
            pagedownShownByScroll = false;
            host.setPagedownButtonVisible(true);
        } else if (!host.canScrollToNewer()) {
            pagedownShownByScroll = false;
            host.setPagedownButtonVisible(false);
        }
    }

    public boolean afterPreservedNewerMessagesInserted() {
        boolean scrollToUnread = scrollPreservedNewerToUnread;
        applyUnreadDivider(scrollToUnread, true);
        scrollPreservedNewerToUnread = false;
        return scrollToUnread;
    }

    public void applyUnreadDivider(boolean scrollToDivider) {
        applyUnreadDivider(scrollToDivider, false);
    }

    public void beforePreservedNewerMessagesInserted() {
        scrollPreservedNewerToUnread = host.isListScrollIdle()
                && !host.isScrollAnimationRunning()
                && host.getDistanceToNewerPx() <= NEAR_NEWEST_THRESHOLD;
    }

    public boolean canMarkVisibleAsRead() {
        return viewportActive
                && !host.getFragment().isPaused()
                && initialScrollApplied
                && readyToMarkAsRead
                && !pendingDividerScroll
                && pendingInitialScrollRestore == null
                && !BaseFragment.hasSheets(host.getFragment());
    }

    /**
     * Синтетические id строк канала, которые надо убрать из списка: либо перечисленные явно,
     * либо все настоящие id вплоть до {@code maxId}.
     */
    public ArrayList<Integer> collectLocalRowIds(long dialogId, ArrayList<Integer> realIds, int maxId) {
        ArrayList<Integer> result = new ArrayList<>();
        HashSet<Integer> realIdSet = realIds != null ? new HashSet<>(realIds) : null;
        ArrayList<MessageObject> rows = host.getMessages();
        for (int i = 0; i < rows.size(); i++) {
            MessageObject row = rows.get(i);
            if (!FeedMessageUtils.isPostRow(row) || row.getDialogId() != dialogId) {
                continue;
            }
            int realId = row.getRealId();
            boolean matches = realIdSet != null
                    ? realIdSet.contains(realId)
                    : realId > 0 && realId <= maxId;
            if (matches) {
                result.add(row.getId());
            }
        }
        return result;
    }

    public boolean consumePreserveScrollLoad(int loadIndex) {
        if (preserveScrollLoadIndex != loadIndex) {
            return false;
        }
        preserveScrollLoadIndex = -1;
        return true;
    }

    public void destroy() {
        destroyed = true;
        resetMetadataRefresh();
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable);
            settleAtNewestScheduled = false;
        }
        pendingInitialScrollRestore = null;
    }

    /**
     * Прячет канал из ленты сразу, но показывает бюллетень с отменой: до истечения таймера
     * {@link #undoHideChannel()} может вернуть всё назад.
     */
    public void hideChannelWithUndo(final long dialogId, CharSequence title) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        FeedController feedController = FeedController.getInstance(currentAccount);
        feedConfig.setExcluded(dialogId, true);
        feedController.markConfigApplied();
        feedController.getStore().setHidden(dialogId, true);
        pendingHideDialogId = dialogId;
        reconcileWithStore();
        onFeedExclusionsChanged();
        notifyChannelsChanged();
        BulletinFactory.of(host.getFragment())
                .createUndoBulletin(
                        AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedChannelHidden, title)),
                        this::undoHideChannel,
                        () -> {
                            if (pendingHideDialogId == dialogId) {
                                pendingHideDialogId = 0L;
                            }
                        })
                .show();
    }

    /**
     * Догружает ответы: посты ленты пришли из разных каналов, поэтому запрос идёт по каждому диалогу
     * отдельно.
     */
    public void loadReplyMessages(ArrayList<MessageObject> rows, int chatMode, int classGuid) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        LongSparseArray<ArrayList<MessageObject>> byDialog = new LongSparseArray<>();
        for (int i = 0; i < rows.size(); i++) {
            MessageObject row = rows.get(i);
            if (row == null || row.isDateObject) {
                continue;
            }
            long dialogId = row.getDialogId();
            if (dialogId == 0) {
                continue;
            }
            ArrayList<MessageObject> group = byDialog.get(dialogId);
            if (group == null) {
                group = new ArrayList<>();
                byDialog.put(dialogId, group);
            }
            group.add(row);
        }
        for (int i = 0; i < byDialog.size(); i++) {
            MediaDataController.getInstance(currentAccount)
                    .loadReplyMessagesForMessages(byDialog.valueAt(i), byDialog.keyAt(i), chatMode, 0L, null, classGuid, null);
        }
    }

    public void markAllRead() {
        FeedController.getInstance(currentAccount).markAllRead();
        applyUnreadDivider(false);
        requestPendingInitialPosition();
        host.invalidateVisiblePart();
    }

    public void notifyChannelsChanged() {
        if (channelsChangedCallback != null) {
            channelsChangedCallback.run();
        }
    }

    public void onFeedExclusionsChanged() {
        lastPagedownCount = -1;
        updatePagedownCounter();
        NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
    }

    public void onHostResumed() {
        if (!host.getMessages().isEmpty()) {
            onMessagesLoaded();
        }
        requestPendingInitialPosition();
    }

    public void onMessagesDeleted() {
        if (unreadDivider == null) {
            return;
        }
        ArrayList<MessageObject> rows = host.getMessages();
        int dividerIndex = rows.indexOf(unreadDivider);
        for (int i = 0; i < dividerIndex; i++) {
            if (FeedMessageUtils.isPostRow(rows.get(i))) {
                return;
            }
        }
        unreadDivider = null;
        if (dividerIndex < 0 || !host.isListReady()) {
            return;
        }
        rows.remove(dividerIndex);
        host.notifyMessageRemoved(dividerIndex);
    }

    /**
     * Первая порция строк доехала до списка: решает, куда встать — на сохранённую позицию из шторки
     * или на разделитель непрочитанного.
     */
    public void onMessagesLoaded() {
        if (host.getFragment().isPaused() || !host.isListReady() || !hasMaterializedPostRows()) {
            return;
        }
        if (initialScrollApplied) {
            return;
        }

        FeedController feedController = FeedController.getInstance(currentAccount);
        FeedController.SavedScrollPosition savedPosition = restoreDrawerScrollPosition ? feedController.getDrawerScrollPosition() : null;
        MessageObject savedRow = savedPosition != null ? feedController.getMessage(savedPosition.dialogId, savedPosition.messageId) : null;
        boolean savedRowLoaded = savedRow != null && host.getMessages().contains(savedRow);
        if (!savedRowLoaded && shouldLoadOlderForSavedPosition(feedController, savedPosition)) {
            restorePagesRequested++;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && !initialScrollApplied) {
                    host.requestOlderFeedPage();
                }
            });
            return;
        }
        initialScrollApplied = true;
        boolean initialUnreadScroll = feedController.consumeInitialUnreadScroll();

        if (!savedRowLoaded) {
            applyUnreadDivider(initialUnreadScroll || host.getDistanceToNewerPx() <= NEAR_NEWEST_THRESHOLD);
            return;
        }
        applyUnreadDivider(false);
        pendingInitialScrollRestore = new ScrollAnchor(savedRow, savedPosition.offsetTop);
        requestPendingInitialPosition();
    }

    private boolean shouldLoadOlderForSavedPosition(FeedController feedController, FeedController.SavedScrollPosition savedPosition) {
        if (savedPosition == null || savedPosition.date <= 0 || restorePagesRequested >= MAX_RESTORE_PAGES) {
            return false;
        }
        if (feedController.getStore().isEndReached() || !feedController.isIncludedChannelPost(savedPosition.dialogId)) {
            return false;
        }
        int oldestDate = Integer.MAX_VALUE;
        ArrayList<MessageObject> rows = host.getMessages();
        for (int i = 0; i < rows.size(); i++) {
            MessageObject row = rows.get(i);
            if (FeedMessageUtils.isPostRow(row) && row.messageOwner != null) {
                oldestDate = Math.min(oldestDate, row.messageOwner.date);
            }
        }
        return oldestDate != Integer.MAX_VALUE && savedPosition.date <= oldestDate;
    }

    public void onPostCellVisible(MessageObject message, boolean fullyVisible, boolean tallerThanViewport) {
        if (message == null || message.isSponsored()) {
            return;
        }
        requestReactionsRefresh(message);
        if (canMarkVisibleAsRead() && (fullyVisible || tallerThanViewport)) {
            FeedController.getInstance(currentAccount).onPostSeen(message.getDialogId(), message.getRealId());
        }
    }

    public void onPreserveScrollLoadStarted(int loadIndex) {
        preserveScrollLoadIndex = loadIndex;
    }

    public void onReadStateRefreshed() {
        ScrollAnchor anchor = host.captureScrollAnchor();
        boolean hadPendingPosition = hasPendingInitialPosition();
        applyUnreadDivider(false);
        requestPendingInitialPosition();
        if (!hadPendingPosition || !hasPendingInitialPosition()) {
            host.restoreScrollAnchor(anchor);
        }
        lastPagedownCount = -1;
        updatePagedownCounter();
        host.invalidateVisiblePart();
    }

    public void onScrollAnimationFinished() {
        if (destroyed || !viewportActive || settleAtNewestScheduled) {
            return;
        }
        settleAtNewestScheduled = true;
        AndroidUtilities.runOnUIThread(settleAtNewestRunnable);
    }

    /**
     * Кнопка «вниз» в ленте живёт по своим правилам: при непрочитанных она видна всегда,
     * иначе появляется и прячется по накопленному сдвигу прокрутки.
     */
    public void onScrolled(int dy) {
        if (!viewportActive || !host.isListReady() || host.isScrollAnimationRunning()) {
            return;
        }
        if (!host.canScrollToNewer()) {
            totalScrollDy = 0;
            pagedownShownByScroll = false;
            host.setPagedownButtonVisible(false);
            if (dy > 0 && !settleAtNewestScheduled) {
                settleAtNewestScheduled = true;
                AndroidUtilities.runOnUIThread(settleAtNewestRunnable);
            }
            return;
        }
        if (lastPagedownCount > 0) {
            return;
        }
        boolean pagedownVisible = host.isPagedownButtonVisible();
        if (dy > 0) {
            if (pagedownVisible) {
                return;
            }
            totalScrollDy += dy;
            if (totalScrollDy > PAGEDOWN_SCROLL_THRESHOLD) {
                totalScrollDy = 0;
                pagedownShownByScroll = true;
                host.setPagedownButtonVisible(true);
            }
            return;
        }
        if (dy < 0 && pagedownShownByScroll && pagedownVisible) {
            totalScrollDy += dy;
            if (totalScrollDy < -PAGEDOWN_SCROLL_THRESHOLD) {
                totalScrollDy = 0;
                host.setPagedownButtonVisible(false);
            }
        }
    }

    public void onVisiblePartInvalidated() {
        if (!viewportActive || host.getFragment().isPaused()) {
            return;
        }
        if (pendingInitialScrollRestore != null) {
            host.restoreScrollAnchor(pendingInitialScrollRestore);
            pendingInitialScrollRestore = null;
        }
        maybeScrollToDivider();
        updatePagedownCounter();
    }

    /**
     * Сводит показанный список со снимком {@link FeedStore}: убирает строки, которых в снимке уже нет,
     * вставляет появившиеся, чинит заголовки дат и восстанавливает прокрутку.
     */
    public void reconcileWithStore() {
        if (!host.isListReady()) {
            return;
        }
        FeedStore store = FeedController.getInstance(currentAccount).getStore();
        ArrayList<MessageObject> rows = host.getMessages();

        int postRowCount = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (FeedMessageUtils.isPostRow(rows.get(i))) {
                postRowCount++;
            }
        }
        ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();

        if (postRowCount == 0) {
            if (!visibleMessages.isEmpty() && host.isFirstLoadComplete()) {
                host.reloadFeed();
            } else if (!store.isEmpty() && !store.isEndReached()) {
                host.requestOlderFeedPage();
            }
            return;
        }
        if (store.isEmpty()) {
            host.reloadFeed();
            return;
        }

        HashSet<MessageObject> visibleSet = new HashSet<>(visibleMessages);
        ArrayList<Integer> staleRowIds = null;
        ArrayList<MessageObject> hiddenRows = null;
        for (int i = 0; i < rows.size(); i++) {
            MessageObject row = rows.get(i);
            if (!FeedMessageUtils.isPostRow(row) || visibleSet.contains(row)) {
                continue;
            }
            if (store.getMessage(row.getDialogId(), row.getId()) == row) {
                if (hiddenRows == null) {
                    hiddenRows = new ArrayList<>();
                }
                hiddenRows.add(row);
            } else {
                if (staleRowIds == null) {
                    staleRowIds = new ArrayList<>();
                }
                staleRowIds.add(row.getId());
            }
        }

        int removedCount = (staleRowIds == null ? 0 : staleRowIds.size()) + (hiddenRows == null ? 0 : hiddenRows.size());
        if (removedCount == 0 && postRowCount == visibleMessages.size()) {
            return;
        }

        ScrollAnchor anchor = host.captureScrollAnchor();
        boolean hadPendingPosition = hasPendingInitialPosition();

        if (staleRowIds != null) {
            host.deleteRows(staleRowIds);
        }
        if (hiddenRows != null) {
            for (int i = 0; i < hiddenRows.size(); i++) {
                int index = rows.indexOf(hiddenRows.get(i));
                if (index >= 0) {
                    rows.remove(index);
                    host.notifyMessageRemoved(index);
                }
            }
        }

        HashSet<MessageObject> presentRows = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (FeedMessageUtils.isPostRow(rows.get(i))) {
                presentRows.add(rows.get(i));
            }
        }

        boolean insertedAny = false;
        int cursor = 0;
        for (int i = 0; i < visibleMessages.size(); i++) {
            MessageObject message = visibleMessages.get(i);
            if (presentRows.contains(message)) {
                while (cursor < rows.size() && rows.get(cursor) != message) {
                    cursor++;
                }
                if (cursor < rows.size()) {
                    cursor++;
                }
                continue;
            }
            host.materializeRow(message);
            int insertIndex = getInsertIndex(rows, message, visibleMessages, i, cursor);
            rows.add(insertIndex, message);
            host.notifyMessageInserted(insertIndex);
            cursor = insertIndex + 1;
            insertedAny = true;
        }

        boolean listChanged = removedCount > 0 || insertedAny;
        if (!normalizeDateHeaders(rows) && !listChanged) {
            return;
        }

        host.onFeedListChanged();
        applyUnreadDivider(false);
        requestPendingInitialPosition();
        onFeedExclusionsChanged();
        if (!hadPendingPosition || !hasPendingInitialPosition()) {
            host.restoreScrollAnchor(anchor);
        }
        host.invalidateVisiblePart();

        if (store.getVisibleCount() == 0) {
            if (store.isEndReached()) {
                host.showEmptyFeedState();
            } else {
                host.showEmptyFeedProgress();
                host.requestOlderFeedPage();
            }
        }
    }

    public void resetUiState() {
        resetMetadataRefresh();
        initialScrollApplied = false;
        restorePagesRequested = 0;
        readyToMarkAsRead = false;
        pendingDividerScroll = false;
        pendingInitialScrollRestore = null;
        scrollPreservedNewerToUnread = false;
        preserveScrollLoadIndex = -1;
        unreadDivider = null;
        lastPagedownCount = -1;
        pagedownShownByScroll = false;
        totalScrollDy = 0;
        pendingHideDialogId = 0L;
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable);
            settleAtNewestScheduled = false;
        }
    }

    public void saveDrawerScrollPosition() {
        ScrollAnchor anchor = host.captureScrollAnchor();
        if (anchor == null || anchor.row == null) {
            return;
        }
        FeedController.getInstance(currentAccount).saveDrawerScrollPosition(anchor.row.getDialogId(), anchor.row.getRealId(),
                anchor.row.messageOwner != null ? anchor.row.messageOwner.date : 0, anchor.offsetTop);
    }

    public boolean scrollToUnreadDividerIfAbove() {
        if (!host.isListReady()) {
            return false;
        }
        ArrayList<MessageObject> rows = host.getMessages();
        if (unreadDivider == null || !rows.contains(unreadDivider)) {
            applyUnreadDivider(false);
            requestPendingInitialPosition();
            rows = host.getMessages();
        }
        int dividerIndex = rows.indexOf(unreadDivider);
        if (dividerIndex < 0) {
            return false;
        }
        int newestVisibleIndex = host.getNewestVisibleMessageIndex();
        if (newestVisibleIndex == Integer.MIN_VALUE || dividerIndex >= newestVisibleIndex) {
            return false;
        }
        host.scrollToMessageAnimated(dividerIndex, AndroidUtilities.dp(UNREAD_DIVIDER_SCROLL_OFFSET_DP));
        host.invalidateVisiblePart();
        return true;
    }

    public void setChannelsChangedCallback(Runnable callback) {
        channelsChangedCallback = callback;
    }

    public void setViewportActive(boolean active) {
        if (viewportActive == active) {
            return;
        }
        viewportActive = active;
        if (active) {
            onHostResumed();
            if (!host.getMessages().isEmpty()) {
                onVisiblePartInvalidated();
            }
            return;
        }
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable);
            settleAtNewestScheduled = false;
        }
        cancelPendingReactionsRefresh();
    }

    /**
     * Помечает прочитанным всё до нижней видимой строки — а если список уже у самого свежего края,
     * то и весь остаток, чтобы разделитель не подвисал под кнопкой «вниз».
     */
    public void settleUnreadDivider() {
        if (!canMarkVisibleAsRead() || !host.isListReady()) {
            return;
        }
        int lastVisibleIndex = host.getLastVisibleMessageIndex();
        if (lastVisibleIndex == Integer.MIN_VALUE) {
            return;
        }
        ArrayList<MessageObject> rows = host.getMessages();
        int lastIndexToMark = host.canScrollToNewer()
                ? Math.min(lastVisibleIndex, rows.size() - 1)
                : rows.size() - 1;
        if (lastIndexToMark >= 0) {
            FeedController feedController = FeedController.getInstance(currentAccount);
            for (int i = 0; i <= lastIndexToMark; i++) {
                MessageObject row = rows.get(i);
                if (row != null && !row.isDateObject && row.type != MessageObject.TYPE_LOADING && !row.isSponsored()) {
                    feedController.onPostSeen(row.getDialogId(), row.getRealId());
                }
            }
        }
        applyUnreadDivider(false);
        requestPendingInitialPosition();
        updatePagedownCounter();
    }
}
