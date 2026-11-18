package com.etheroal.os.triage

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.etheroal.os.EtheroalApp
import com.etheroal.os.data.ItemStatus
import com.etheroal.os.data.NotificationItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads every notification as it arrives and stores it, encrypted, for triage. Nothing is sent
 * anywhere from here: triage runs later, on the phone, when Etheroal is on screen (on-device
 * models only run in the foreground).
 *
 * Android 15+ hides one-time codes and other sensitive notifications from apps like this one,
 * which is what we want: Etheroal never sees them.
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val captured = capture(this, sbn) ?: return
        ReplyActions.remember(sbn)
        scope.launch {
            val graph = (application as EtheroalApp).graph
            if (graph.db.items().insert(captured) != -1L) graph.triage.kick()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // When Etheroal itself quietened the shade, the reply action must stay usable from the digest.
        if (quieted.remove(sbn.key) == null) ReplyActions.forget(sbn.key)
    }

    override fun onListenerConnected() {
        instance = this
        // Pick up what was already in the shade when access was granted or the service restarted.
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EtheroalCapture"

        @Volatile var instance: NotificationCaptureService? = null
            private set
        private val quieted = ConcurrentHashMap<String, Boolean>()

        /** Removes a notification from the shade (quiet shade). It stays in Etheroal's digest, reply action included. */
        fun quiet(key: String): Boolean {
            val svc = instance ?: return false
            quieted[key] = true
            return runCatching { svc.cancelNotification(key); true }.getOrElse { quieted.remove(key); false }
        }
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STATUS,
        )

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationCaptureService::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        /** Turns a notification into a stored item, or null for things not worth triaging. */
        fun capture(context: Context, sbn: StatusBarNotification): NotificationItem? {
            val n = sbn.notification
            if (sbn.packageName == context.packageName) return null
            if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            if (n.category in SKIP_CATEGORIES) return null
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = messages(extras) ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)
            val conversation = sbn.packageName + "|" + (n.shortcutId ?: title.ifBlank { sbn.key })
            Log.d(TAG, "captured from ${sbn.packageName} (${text.length} chars)")  // never log the text itself
            return NotificationItem(
                notificationKey = sbn.key,
                packageName = sbn.packageName,
                appLabel = label,
                title = title.take(200),
                text = text.take(2000),
                conversation = conversation,
                postedAt = sbn.postTime,
                canReply = ReplyActions.replyAction(n) != null,
                status = ItemStatus.PENDING,
            )
        }

        /** The last few lines of a messaging-style notification ("Kyle: ..."), oldest first. */
        private fun messages(extras: Bundle): String? {
            @Suppress("DEPRECATION")
            val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
            val lines = raw.mapNotNull { it as? Bundle }.takeLast(6).mapNotNull { b ->
                val body = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString()
                if (sender.isNullOrBlank()) body else "$sender: $body"
            }
            return lines.joinToString("\n").ifBlank { null }
        }
    }
}

/**
 * The live reply actions of notifications still in the shade. A PendingIntent cannot be stored,
 * so replies work only while the original notification exists; otherwise the app is opened.
 */
object ReplyActions {
    private val byKey = ConcurrentHashMap<String, Notification>()

    fun remember(sbn: StatusBarNotification) {
        if (replyAction(sbn.notification) != null) byKey[sbn.key] = sbn.notification
    }

    fun forget(key: String) {
        byKey.remove(key)
    }

    fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** Sends [text] through the app's own reply action. Returns null on success, or why it failed. */
    fun send(context: Context, notificationKey: String, text: String): String? {
        val n = byKey[notificationKey] ?: return "That notification is gone; open the app to reply"
        val action = replyAction(n) ?: return "This notification has no reply action"
        val inputs = action.remoteInputs ?: return "This notification has no reply action"
        val intent = Intent()
        val results = Bundle().apply { inputs.filter { it.allowFreeFormInput }.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            null
        } catch (e: PendingIntent.CanceledException) {
            "The app cancelled the reply"
        }
    }

    /** The app's own tap action, to reuse as "Open" on Etheroal's alert. */
    fun contentIntent(notificationKey: String): PendingIntent? = byKey[notificationKey]?.contentIntent

    /** Opens the conversation in its own app (the notification's tap action). */
    fun open(notificationKey: String): Boolean {
        val n = byKey[notificationKey] ?: return false
        return runCatching { n.contentIntent?.send(); true }.getOrDefault(false)
    }
}
