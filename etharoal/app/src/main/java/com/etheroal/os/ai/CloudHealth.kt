package com.etheroal.os.ai

import com.etheroal.os.core.ai.EngineException
import com.etheroal.os.core.ai.EngineFailure
import com.etheroal.os.core.ai.EngineRequest
import com.etheroal.os.core.ai.HybridMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether Gemini in the cloud answers right now, and if not, what the owner can do about it. */
sealed interface CloudStatus {
    data object Unknown : CloudStatus
    data object Checking : CloudStatus
    data class Ok(val model: String, val latencyMs: Long) : CloudStatus
    data class Down(val failure: EngineFailure, val title: String, val fix: String) : CloudStatus
}

/**
 * A one-word request to the fast cloud model. Cloud failures used to surface only as raw SDK text
 * in the Lab; this turns them into a cause and a fix, shown in the side panel.
 */
class CloudHealth(private val engine: FirebaseInferenceEngine) {
    /** Called when a check succeeds, e.g. to retry notifications that could not be sorted. */
    var onHealthy: suspend () -> Unit = {}

    private val _status = MutableStateFlow<CloudStatus>(CloudStatus.Unknown)
    val status: StateFlow<CloudStatus> = _status

    suspend fun check(): CloudStatus {
        _status.value = CloudStatus.Checking
        val started = System.currentTimeMillis()
        val result = try {
            val r = engine.generate(EngineRequest(HybridMode.ONLY_IN_CLOUD, "Reply with the word OK.", 5))
            CloudStatus.Ok(r.modelName ?: "Gemini", System.currentTimeMillis() - started)
        } catch (e: EngineException) {
            explain(e.failure)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            explain(EngineFailure.UNKNOWN)
        }
        _status.value = result
        if (result is CloudStatus.Ok) runCatching { onHealthy() }
        return result
    }

    companion object {
        fun explain(f: EngineFailure): CloudStatus.Down = when (f) {
            EngineFailure.APP_CHECK -> CloudStatus.Down(
                f, "This install isn't trusted by App Check",
                "Debug build: copy the debug secret from logcat (DebugAppCheckProvider) into Firebase console › App Check › Manage debug tokens.",
            )
            EngineFailure.CLOUD_QUOTA -> CloudStatus.Down(
                f, "Gemini credits or quota used up",
                "Add credits, or switch the project's Gemini API billing to pay-as-you-go or the free tier, in AI Studio › Billing.",
            )
            EngineFailure.CLOUD_REJECTED -> CloudStatus.Down(
                f, "The Gemini API refused the project",
                "Run `firebase init ailogic` for this project, then check the API is enabled in Firebase console › AI Logic.",
            )
            EngineFailure.OFFLINE -> CloudStatus.Down(f, "No connection", "Etheroal will retry when the phone is online.")
            EngineFailure.BLOCKED -> CloudStatus.Down(f, "The model refused the test request", "Try again later.")
            else -> CloudStatus.Down(f, "Gemini didn't answer", "Check the EtheroalCloud log for the error code.")
        }
    }
}
