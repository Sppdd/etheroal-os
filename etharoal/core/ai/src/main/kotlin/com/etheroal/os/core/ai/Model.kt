package com.etheroal.os.core.ai

/** How much of a task's input is allowed to leave the phone. */
enum class Sensitivity {
    /** Never leaves the device. Runs on-device or not at all. */
    PRIVATE,

    /** May go to the cloud, but only after the Airlock has replaced personal details with placeholders. */
    REDACTABLE,

    /** Nothing personal in it; may go to the cloud as is. */
    PUBLIC,
}

/** Which side a task would rather run on when both are possible. */
enum class Preference { ON_DEVICE_FIRST, CLOUD_FIRST }

/** Mirrors the four Firebase AI Logic inference modes. */
enum class HybridMode(val mayUseCloud: Boolean, val mayUseDevice: Boolean) {
    ONLY_ON_DEVICE(mayUseCloud = false, mayUseDevice = true),
    PREFER_ON_DEVICE(mayUseCloud = true, mayUseDevice = true),
    PREFER_IN_CLOUD(mayUseCloud = true, mayUseDevice = true),
    ONLY_IN_CLOUD(mayUseCloud = true, mayUseDevice = false),
}

enum class InferenceSource { ON_DEVICE, IN_CLOUD }

/** Capabilities the on-device model does not have through Firebase AI Logic. */
data class TaskNeeds(
    val tools: Boolean = false,
    val multiTurn: Boolean = false,
    val imageCount: Int = 0,
) {
    val cloudOnly: Boolean get() = tools || multiTurn || imageCount > 1
}

/**
 * A kind of AI work the product does. Every feature declares one of these instead of
 * calling a model directly, so privacy and routing are decided in one place.
 */
data class AiTask(
    val id: String,
    val sensitivity: Sensitivity,
    val preference: Preference = Preference.ON_DEVICE_FIRST,
    val needs: TaskNeeds = TaskNeeds(),
    val maxOutputTokens: Int = 256,
    /** Key of a structured-output schema the engine knows, or null for free text. */
    val schemaKey: String? = null,
    /** Fixed instructions placed before the user content. Never contains user data. */
    val instructions: String = "",
)

enum class ModelStatus { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** Everything about the phone that affects where inference can run right now. */
data class DeviceState(
    val modelStatus: ModelStatus,
    /** On-device inference is refused unless the app is the top foreground app. */
    val appInForeground: Boolean,
    val online: Boolean,
    /** True after the on-device battery quota was hit; cleared when the cool-down ends. */
    val onDeviceCoolingDown: Boolean = false,
) {
    val onDeviceReady: Boolean
        get() = modelStatus == ModelStatus.AVAILABLE && appInForeground && !onDeviceCoolingDown
}

/** Tunables, fed from Remote Config so routing can change without an app release. */
data class RoutingConfig(
    val cloudEnabled: Boolean = true,
    /** Leave headroom under the ~4000-token on-device request limit. */
    val onDeviceInputTokenBudget: Int = 3000,
    /** On-device output quality drops past roughly 256 tokens. */
    val onDeviceMaxOutputTokens: Int = 256,
    val busyRetries: Int = 3,
    /** Opt-in by the user for phones without an on-device model. Private input is then redacted and sent. */
    val allowCloudForPrivate: Boolean = false,
    /** Per-task mode overrides. An override can never send a PRIVATE task to the cloud. */
    val modeOverrides: Map<String, HybridMode> = emptyMap(),
)

sealed interface RouteDecision {
    val reason: String

    data class Run(val mode: HybridMode, val redact: Boolean, override val reason: String) : RouteDecision

    /** Cannot run now, but will be able to later (for example when the app is foreground again). */
    data class Defer(override val reason: String) : RouteDecision

    /** Cannot run on this phone with the current settings. */
    data class Reject(override val reason: String) : RouteDecision
}
