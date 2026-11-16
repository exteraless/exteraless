package app.exteraless.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.collection.LongSparseArray
import androidx.core.content.edit
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import java.util.concurrent.ConcurrentHashMap

object NotificationsHelper {
    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("exteraless_notifications", Context.MODE_PRIVATE)
    }

    // Stock's `NotificationsController.wearNotificationsIds` (dialogId -> notification id) is the only record of
    // what is on screen, and every cancel path diffs against it — but it is in-memory only, while posted
    // notifications outlive the process. Mirroring it to disk is what makes those cancel paths survive a restart.
    private fun getWearIdsKey(account: Int) = "wear_ids_$account"

    @JvmStatic
    fun migrateNotificationTags(account: Int, legacyGroup: String) {
        val key = "notification_tags_v1_$account"
        if (prefs.getBoolean(key, false) || Build.VERSION.SDK_INT < 23) return
        try {
            val manager = ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            for (posted in manager.activeNotifications) {
                val notification = posted.notification
                // Legacy numeric IDs also belong to foreground services and other accounts.
                // Only remove untagged messages whose group/channel proves account ownership.
                if (posted.tag != null || notification.category != Notification.CATEGORY_MESSAGE ||
                    notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
                val ownsGroup = notification.group == legacyGroup
                val ownsChannel = Build.VERSION.SDK_INT >= 26 &&
                    notification.channelId?.startsWith("${account}channel_") == true
                if (ownsGroup || ownsChannel) manager.cancel(null, posted.id)
            }
            // Do not mark a failed sweep complete; a later process can retry it.
            prefs.edit(commit = true) { putBoolean(key, true) }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun loadWearNotificationIds(account: Int, into: LongSparseArray<Int>) {
        into.clear()
        val stored = prefs.getString(getWearIdsKey(account), null) ?: return
        for (entry in stored.splitToSequence(',')) {
            val separator = entry.indexOf(':')
            if (separator <= 0) continue
            val dialogId = entry.substring(0, separator).toLongOrNull() ?: continue
            val notificationId = entry.substring(separator + 1).toIntOrNull() ?: continue
            into.put(dialogId, notificationId)
        }
    }

    // Every showOrUpdateNotification re-notify()s ALL per-chat notifications, and notification bridges
    // (Mi Fitness etc.) re-forward every onNotificationPosted without deduping by key or respecting
    // FLAG_ONLY_ALERT_ONCE — so unchanged reposts must be skipped on our side. Signatures are keyed by
    // notification id; per-account maps are only touched from that account's notificationsQueue.
    private val postedSignatures = ConcurrentHashMap<Int, MutableMap<Int, String>>()

    @JvmStatic
    fun computeNotificationSignature(
        channelId: String?,
        name: String?,
        messages: List<MessageObject>?,
        storyCount: Int,
        maxId: Int,
        locked: Boolean,
        hasAvatar: Boolean,
        grouped: Boolean,
    ): String = buildString {
        append(channelId).append('|').append(name).append('|').append(storyCount).append('|')
        append(maxId).append('|').append(locked).append('|').append(hasAvatar).append('|').append(grouped)
        messages?.forEach {
            append('|').append(it.id).append(':').append(it.messageOwner?.edit_date ?: 0)
        }
    }

    @JvmStatic
    fun shouldSkipNotify(account: Int, notificationId: Int, signature: String?): Boolean {
        if (signature == null) return false
        val map = postedSignatures.getOrPut(account) { HashMap() }
        if (map[notificationId] == signature) return true
        map[notificationId] = signature
        return false
    }

    @JvmStatic
    fun removePostedSignature(account: Int, notificationId: Int) {
        postedSignatures[account]?.remove(notificationId)
    }

    @JvmStatic
    fun clearPostedSignatures(account: Int) {
        postedSignatures[account]?.clear()
    }

    @JvmStatic
    fun isSummaryPosted(summaryTag: String, summaryId: Int): Boolean = try {
        val manager = ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.any { it.id == summaryId && it.tag == summaryTag }
    } catch (e: Exception) {
        false
    }

    @JvmStatic
    fun saveWearNotificationIds(account: Int, ids: LongSparseArray<Int>) {
        val key = getWearIdsKey(account)
        val stored = (0 until ids.size()).joinToString(",") { "${ids.keyAt(it)}:${ids.valueAt(it)}" }
        if (prefs.getString(key, "") == stored) return
        // commit: this races a process death that may come right after posting the notifications
        prefs.edit(commit = true) {
            if (stored.isEmpty()) remove(key) else putString(key, stored)
        }
    }
}
