package com.etheroal.os.triage

import android.util.Log
import com.etheroal.os.ai.Tasks
import com.etheroal.os.ai.TriageResult
import com.etheroal.os.core.ai.AiOutcome
import com.etheroal.os.core.ai.InferenceRouter
import com.etheroal.os.data.ItemDao
import com.etheroal.os.data.NotificationItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json

/**
 * Triages stored notifications through the router as the PRIVATE "triage" task: on-device only,
 * never the cloud. On-device models only run while Etheroal is in the foreground, so the queue
 * drains when the app comes to the front and whenever something arrives while it is open; in the
 * background, items simply wait.
 */
class TriageQueue(
    private val dao: ItemDao,
    private val router: InferenceRouter,
    /** Whether triage may run now (always in CLOUD mode; only in the foreground in PRIVATE mode). */
    private val isForeground: () -> Boolean,
    /** What Etheroal knows about the owner's people, to judge who matters. */
    private val ownerContext: suspend () -> String = { "" },
    /** Called after each item is sorted: the Chief of Staff decides what to do with it. */
    private val onSorted: suspend (NotificationItem, TriageResult) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val draining = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    /** Why the queue is waiting, if it is (e.g. "Waiting until Etheroal is on screen"). */
    val status: StateFlow<String?> = _status

    /** Starts a drain if one isn't already running. Cheap to call often. */
    fun kick() {
        if (!isForeground()) return
        scope.launch { drain() }
    }

    private suspend fun drain() {
        if (!draining.tryLock()) return          // one drain at a time; a running one will see new items
        try {
            dao.deleteOlderThan(System.currentTimeMillis() - KEEP_MS)
            while (isForeground()) {
                val batch = dao.pending(BATCH)
                if (batch.isEmpty()) { _status.value = null; return }
                for (item in batch) {
                    if (!triageOne(item)) return
                }
            }
        } finally {
            draining.unlock()
        }
    }

    /** Returns false when the queue should stop for now (deferred). */
    private suspend fun triageOne(item: NotificationItem): Boolean {
        _status.value = "Sorting ${item.appLabel}…"
        val known = runCatching { ownerContext() }.getOrDefault("")
        return when (val out = router.run(Tasks.Triage, describe(item, known))) {
            is AiOutcome.Success -> {
                val r = runCatching { JSON.decodeFromString(TriageResult.serializer(), out.text) }.getOrNull()
                if (r == null) dao.failed(item.id, "Unreadable triage result")
                else {
                    dao.triaged(
                        item.id, r.urgency.name, r.category.name, r.needsReply && item.canReply, r.summary.take(160),
                        out.source.name, out.modelName, System.currentTimeMillis(),
                    )
                    runCatching { onSorted(item, r) }.onFailure { Log.w(TAG, "chief of staff failed: ${it.javaClass.simpleName}") }
                }
                _status.value = null
                true
            }
            is AiOutcome.Deferred -> { _status.value = out.reason; false }
            is AiOutcome.Failed -> {
                Log.w(TAG, "triage failed: ${out.failure}")
                dao.failed(item.id, out.reason.take(200))
                true
            }
        }
    }

    private fun describe(item: NotificationItem, known: String): String = buildString {
        if (known.isNotBlank()) append("What the owner has told their assistant about people in their life:\n").append(known.take(800)).append("\n\n")
        append("App: ").append(item.appLabel).append('\n')
        if (item.title.isNotBlank()) append("From: ").append(item.title).append('\n')
        append("Message: ").append(item.text)
    }

    private companion object {
        const val TAG = "EtheroalTriage"
        const val BATCH = 10
        const val KEEP_MS = 3L * 24 * 3600 * 1000
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
