package com.etheroal.os.core.ai

/**
 * Decides where a task runs. Pure function of (task, input size, device state, config),
 * so every row of the routing matrix is covered by unit tests.
 *
 * The mode is always chosen explicitly. We never hand a PRIVATE task to a PREFER_* mode,
 * because the SDK's automatic fallback would then be free to send it to the cloud.
 */
object RoutePolicy {

    fun decide(
        task: AiTask,
        estimatedInputTokens: Int,
        state: DeviceState,
        config: RoutingConfig,
    ): RouteDecision {
        val sensitivity = effectiveSensitivity(task, config)
        val fitsOnDevice = estimatedInputTokens <= config.onDeviceInputTokenBudget &&
            task.maxOutputTokens <= config.onDeviceMaxOutputTokens
        val deviceCapable = !task.needs.cloudOnly && fitsOnDevice
        val cloudAllowed = config.cloudEnabled && sensitivity != Sensitivity.PRIVATE
        val cloudReady = cloudAllowed && state.online
        val redact = sensitivity == Sensitivity.REDACTABLE

        if (sensitivity == Sensitivity.PRIVATE) {
            return when {
                !deviceCapable -> RouteDecision.Reject(
                    "Private task needs more than the on-device model can do, and it may not use the cloud",
                )
                state.onDeviceReady -> RouteDecision.Run(
                    HybridMode.ONLY_ON_DEVICE, redact = false,
                    reason = "Private: locked to the on-device model",
                )
                else -> notReady(state)
            }
        }

        config.modeOverrides[task.id]?.let { forced ->
            overrideDecision(forced, deviceCapable, cloudReady, state, redact)?.let { return it }
        }

        if (!deviceCapable) {
            return when {
                cloudReady -> RouteDecision.Run(
                    HybridMode.ONLY_IN_CLOUD, redact,
                    reason = if (task.needs.cloudOnly) "Needs tools, multi-turn or several images: cloud only"
                    else "Input is larger than the on-device budget: cloud only",
                )
                !cloudAllowed -> RouteDecision.Reject("Cloud is switched off and the on-device model cannot do this task")
                else -> RouteDecision.Defer("Offline: waiting for a connection")
            }
        }

        val deviceReady = state.onDeviceReady
        return when (task.preference) {
            Preference.ON_DEVICE_FIRST -> when {
                deviceReady && cloudReady -> RouteDecision.Run(
                    HybridMode.PREFER_ON_DEVICE, redact, "On-device first, cloud as fallback",
                )
                deviceReady -> RouteDecision.Run(
                    HybridMode.ONLY_ON_DEVICE, redact = false, reason = "On-device; cloud not reachable or switched off",
                )
                cloudReady -> RouteDecision.Run(
                    HybridMode.ONLY_IN_CLOUD, redact, "On-device model not ready: using cloud",
                )
                // The cloud would do but the phone is offline: wait for it rather than fail.
                cloudAllowed && !state.online -> RouteDecision.Defer("Offline: waiting for a connection")
                else -> notReady(state)
            }
            Preference.CLOUD_FIRST -> when {
                cloudReady && deviceReady -> RouteDecision.Run(
                    HybridMode.PREFER_IN_CLOUD, redact, "Cloud first for quality, on-device if offline",
                )
                cloudReady -> RouteDecision.Run(HybridMode.ONLY_IN_CLOUD, redact, "Cloud first for quality")
                deviceReady -> RouteDecision.Run(
                    HybridMode.ONLY_ON_DEVICE, redact = false, reason = "Cloud not reachable: on-device",
                )
                // The cloud would do but the phone is offline: wait for it rather than fail.
                cloudAllowed && !state.online -> RouteDecision.Defer("Offline: waiting for a connection")
                else -> notReady(state)
            }
        }
    }

    fun effectiveSensitivity(task: AiTask, config: RoutingConfig): Sensitivity =
        if (task.sensitivity == Sensitivity.PRIVATE && config.allowCloudForPrivate) Sensitivity.REDACTABLE
        else task.sensitivity

    /** An override is honoured only when the phone can actually satisfy it; otherwise normal routing applies. */
    private fun overrideDecision(
        forced: HybridMode,
        deviceCapable: Boolean,
        cloudReady: Boolean,
        state: DeviceState,
        redact: Boolean,
    ): RouteDecision? {
        val deviceOk = deviceCapable && state.onDeviceReady
        val usable = when (forced) {
            HybridMode.ONLY_ON_DEVICE -> deviceOk
            HybridMode.ONLY_IN_CLOUD -> cloudReady
            HybridMode.PREFER_ON_DEVICE, HybridMode.PREFER_IN_CLOUD -> deviceOk && cloudReady
        }
        if (!usable) return null
        return RouteDecision.Run(forced, redact && forced.mayUseCloud, "Mode set by remote configuration")
    }

    private fun notReady(state: DeviceState): RouteDecision = when {
        state.modelStatus == ModelStatus.UNAVAILABLE ->
            RouteDecision.Reject("This phone has no on-device model")
        state.modelStatus != ModelStatus.AVAILABLE ->
            RouteDecision.Defer("On-device model is still downloading")
        state.onDeviceCoolingDown ->
            RouteDecision.Defer("On-device model is cooling down after hitting its battery quota")
        !state.appInForeground ->
            RouteDecision.Defer("Waiting until Etheroal is on screen: on-device inference only runs in the foreground")
        else -> RouteDecision.Defer("Offline: waiting for a connection")
    }
}
