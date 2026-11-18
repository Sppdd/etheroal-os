package com.etheroal.os.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.etheroal.os.EtheroalApp
import com.etheroal.os.MainActivity
import com.etheroal.os.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps Etheroal running while a task is in progress, and is the task's face in the status bar:
 * one ongoing notification with the current step and a Stop button. When a task needs the owner
 * it posts a separate, high-priority "Needs you" notification with Allow and Skip.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessions get() = (application as EtheroalApp).graph.sessions

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing(null, 0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        scope.launch {
            sessions.live.collectLatest { live ->
                val nm = getSystemService(NotificationManager::class.java)
                if (live.isEmpty()) {
                    nm.cancel(ATTENTION_ID)
                    ServiceCompat.stopForeground(this@AgentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val current = live.values.first()
                nm.notify(ONGOING_ID, ongoing(current, live.size))
                val waiting = live.values.firstOrNull { it.question != null }
                if (waiting != null) nm.notify(ATTENTION_ID, attention(waiting)) else nm.cancel(ATTENTION_ID)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION)
        when (intent?.action) {
            ACTION_STOP -> id?.let(sessions::stop)
            ACTION_ALLOW -> id?.let { sessions.answer(it, true) }
            ACTION_SKIP -> id?.let { sessions.answer(it, false) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ongoing(s: LiveSession?, count: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(s?.title ?: "Etheroal is working")
            .setContentText(s?.let { "Step ${it.step} · ${it.status}" } ?: "Starting…")
            .setSubText(if (count > 1) "$count tasks" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(s?.id))
            .apply { s?.let { addAction(0, "Stop", action(ACTION_STOP, it.id, 1)) } }
            .build()

    private fun attention(s: LiveSession): Notification =
        NotificationCompat.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("Etheroal needs you · ${s.title}")
            .setContentText(s.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open(s.id))
            .addAction(0, "Allow", action(ACTION_ALLOW, s.id, 2))
            .addAction(0, "Skip", action(ACTION_SKIP, s.id, 3))
            .setAutoCancel(true)
            .build()

    private fun action(name: String, id: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, AgentService::class.java).setAction(name).putExtra(EXTRA_SESSION, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun open(id: String?): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { id?.let { putExtra(EXTRA_SESSION, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val EXTRA_SESSION = "session"
        private const val ACTION_STOP = "com.etheroal.os.STOP"
        private const val ACTION_ALLOW = "com.etheroal.os.ALLOW"
        private const val ACTION_SKIP = "com.etheroal.os.SKIP"
        private const val CHANNEL_TASKS = "tasks"
        private const val CHANNEL_ATTENTION = "tasks_attention"
        private const val ONGOING_ID = 41
        private const val ATTENTION_ID = 42

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
        }

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TASKS, "Running tasks", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "What Etheroal is doing right now, with a Stop button" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ATTENTION, "Tasks that need you", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "When a task needs your OK before it sends, pays or signs in" },
            )
        }
    }
}
