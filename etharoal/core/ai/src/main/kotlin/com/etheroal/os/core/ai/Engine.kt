package com.etheroal.os.core.ai

/** What the router hands to an engine. The engine never decides privacy; it only executes. */
data class EngineRequest(
    val mode: HybridMode,
    val prompt: String,
    val maxOutputTokens: Int,
    val schemaKey: String? = null,
    /** Opaque platform image (an Android Bitmap in the app). At most one for on-device. */
    val image: Any? = null,
    /**
     * The same request split in two, for engines with a chat template (system turn + user turn).
     * [prompt] is the single-string form of these; both carry the same, already-redacted text.
     */
    val system: String = "",
    val content: String = "",
)

data class EngineResult(
    val text: String,
    val source: InferenceSource,
    val modelName: String? = null,
)

enum class EngineFailure {
    /** AICore per-app rate limit. Retry with backoff. */
    BUSY,

    /** AICore long-window battery quota. Stop using on-device for a while. */
    BATTERY_QUOTA,

    /** On-device inference was attempted while the app was not the top foreground app. */
    BACKGROUND_BLOCKED,
    ON_DEVICE_UNAVAILABLE,
    CLOUD_QUOTA,

    /** The cloud API refused the call because of project setup (API disabled, App Check, key). */
    CLOUD_REJECTED,

    /** App Check refused this install (debug token not registered, or attestation failed). */
    APP_CHECK,
    OFFLINE,

    /** The model refused or the response was blocked by safety filters. */
    BLOCKED,
    UNKNOWN,
}

class EngineException(val failure: EngineFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The only thing that actually talks to a model. Implemented with Firebase AI Logic in the app. */
fun interface InferenceEngine {
    suspend fun generate(request: EngineRequest): EngineResult
}

interface DeviceStateProvider {
    suspend fun current(): DeviceState

    /** Called when the on-device battery quota is hit. */
    fun startOnDeviceCooldown()
}

/** One row of the privacy ledger. For cloud calls it holds exactly what left the phone. */
data class LedgerEntry(
    val timestampMs: Long,
    val taskId: String,
    val mode: HybridMode?,
    val source: InferenceSource?,
    val outcome: String,
    val reason: String,
    val latencyMs: Long,
    val redactions: Int,
    /** Text sent to the cloud after redaction, or null when nothing left the device. */
    val sentToCloud: String?,
    /** Which model actually answered, e.g. "Gemini Nano (nano-v4-fast)" or "Local: gemma-4-E2B-it". */
    val engine: String? = null,
)

fun interface Ledger {
    fun record(entry: LedgerEntry)
}

/** Cheap token estimate. The SDK's countTokens differs between cloud and on-device, so we stay conservative. */
object TokenEstimator {
    fun estimate(text: String): Int = (text.length + 3) / 4
}

/** Exponential backoff with full jitter. */
class Backoff(
    private val baseMs: Long = 400,
    private val maxMs: Long = 4000,
    private val random: () -> Double = { Math.random() },
) {
    fun delayMs(attempt: Int): Long {
        val ceiling = (baseMs shl attempt.coerceIn(0, 10)).coerceAtMost(maxMs)
        return (ceiling * random()).toLong().coerceAtLeast(1)
    }
}
