package com.etheroal.os.triage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.etheroal.os.MainActivity
import com.etheroal.os.R
import com.etheroal.os.ai.TriageResult
import com.etheroal.os.core.agent.Disposition
import com.etheroal.os.core.agent.NotificationPolicy
import com.etheroal.os.data.ItemDao
import com.etheroal.os.data.NotificationItem
import com.etheroal.os.mind.Mind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The notification shade as a chief of staff would run it. After the model sorts a notification,
 * plain rules ([NotificationPolicy]) and the owner's habits decide:
 *
 * - INTERRUPT: Etheroal raises its own alert with a one-line summary, so what matters is not lost.
 * - DIGEST: kept for the "Now" list and the digest notification.
 * - FILE: promotions and senders the owner always ignores; collapsed, and one tap undoes it.
 *
 * With "Quiet the shade" on (the owner's choice), digest and filed items also leave the system
 * shade; the digest notification and Etheroal's home screen replace them, reply actions intact.
 */
class ChiefOfStaff(
    private val context: Context,
    private val dao: ItemDao,
    private val mind: Mind,
) {
    private val prefs = context.getSharedPreferences("chief", Context.MODE_PRIVATE)
    private val nm = context.getSystemService(NotificationManager::class.java)

    private val _quiet = MutableStateFlow(prefs.getBoolean("quiet_shade", false))
    val quietShade: StateFlow<Boolean> = _quiet

    fun setQuietShade(on: Boolean) {
        _quiet.value = on
        prefs.edit().putBoolean("quiet_shade", on).apply()
        if (!on) nm.cancel(DIGEST_ID)
    }

    suspend fun onSorted(item: NotificationItem, r: TriageResult) {
        val sender = item.title.ifBlank { item.appLabel }
        val decision = NotificationPolicy.decide(
            urgency = r.urgency.name, category = r.category.name, needsReply = r.needsReply && item.canReply,
            ignoredByOwner = item.conversation in mind.ignoredSenders(),
            answeredByOwner = item.conversation in mind.answeredSenders(),
            sender = sender,
        )
        dao.dispose(item.id, decision.disposition.name, decision.reason)
        when (decision.disposition) {
            Disposition.INTERRUPT -> interrupt(item, r.summary, decision.reason)
            Disposition.DIGEST, Disposition.FILE -> if (_quiet.value) NotificationCaptureService.quiet(item.notificationKey)
        }
        if (_quiet.value) postDigest()
    }

    /** Etheroal's own alert: who, one line on what, why it interrupted, and the app's own Open action. */
    private fun interrupt(item: NotificationItem, summary: String, reason: String) {
        channels(context)
        val original = ReplyActions.contentIntent(item.notificationKey)
        val n = NotificationCompat.Builder(context, CHANNEL_IMPORTANT)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("${item.title.ifBlank { item.appLabel }} · ${item.appLabel}")
            .setContentText(summary)
            .setSubText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openNotifications(1))
            .setAutoCancel(true)
            .apply { original?.let { addAction(0, "Open ${item.appLabel}", it) } }
            .addAction(0, "Draft reply", openNotifications(2))
            .build()
        nm.notify(INTERRUPT_BASE + (item.id % 1000).toInt(), n)
    }

    /** One quiet notification that stands in for everything Etheroal took out of the shade. */
    private suspend fun postDigest() {
        val sorted = dao.sortedSince(System.currentTimeMillis() - DAY)
        if (sorted.isEmpty()) { nm.cancel(DIGEST_ID); return }
        val replies = sorted.count { it.needsReply }
        val filed = sorted.count { it.disposition == Disposition.FILE.name }
        val keep = sorted.filter { it.disposition != Disposition.FILE.name }
        val style = NotificationCompat.InboxStyle()
        keep.take(5).forEach { style.addLine("${it.title.ifBlank { it.appLabel }}: ${it.summary ?: ""}") }
        val n = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("Etheroal sorted ${sorted.size} today")
            .setContentText(listOfNotNull(
                if (replies > 0) "$replies need a reply" else null,
                if (filed > 0) "$filed filed" else null,
            ).joinToString(" · ").ifEmpty { "Nothing needs you" })
            .setStyle(style)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openNotifications(3))
            .build()
        nm.notify(DIGEST_ID, n)
    }

    private fun openNotifications(code: Int): PendingIntent = PendingIntent.getActivity(
        context, 100 + code,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PLACE, "NOTIFICATIONS"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_IMPORTANT = "important"
        private const val CHANNEL_DIGEST = "digest"
        private const val DIGEST_ID = 60
        private const val INTERRUPT_BASE = 1000
        private const val DAY = 24L * 3600 * 1000

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_IMPORTANT, "What matters now", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Messages and events Etheroal judged worth interrupting you for" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DIGEST, "Digest", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "One quiet summary of everything Etheroal sorted" },
            )
        }
    }
}
