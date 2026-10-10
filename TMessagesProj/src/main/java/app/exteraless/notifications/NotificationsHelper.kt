package app.exteraless.notifications

import android.content.Context
import androidx.collection.LongSparseArray
import androidx.core.content.edit
import org.telegram.messenger.ApplicationLoader
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
