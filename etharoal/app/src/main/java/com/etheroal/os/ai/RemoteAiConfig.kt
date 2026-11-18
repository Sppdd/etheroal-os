package com.etheroal.os.ai

import com.etheroal.os.R
import com.etheroal.os.core.ai.HybridMode
import com.etheroal.os.core.ai.RoutingConfig
import com.google.firebase.Firebase
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.tasks.await

/**
 * Routing and model choices come from Firebase Remote Config, so a model can be swapped or the
 * cloud path switched off without shipping a new build. Bundled XML defaults keep the app
 * working before the first fetch and when offline.
 */
@OptIn(PublicPreviewAPI::class)
class RemoteAiConfig {
    private val rc = Firebase.remoteConfig

    init {
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 })
        rc.setDefaultsAsync(R.xml.remote_config_defaults)
    }

    /** Best effort. Failure (offline, App Check not ready) leaves the previous values in place. */
    suspend fun refresh() {
        runCatching { rc.fetchAndActivate().await() }
    }

    val cloudModelFast: String get() = rc.getString("cloud_model_fast")
    val cloudModelAgent: String get() = rc.getString("cloud_model_agent")
    val cloudModelLive: String get() = rc.getString("cloud_model_live")
    val defaultPrivacyMode: String get() = rc.getString("default_privacy_mode").uppercase()
    val agentVerify: Boolean get() = rc.getBoolean("agent_verify")
    /** Whether the operator gets screenshots as well as the element list. */
    val agentVision: Boolean get() = rc.getBoolean("agent_vision")
    val agentMaxSteps: Int get() = rc.getLong("agent_max_steps").toInt().coerceIn(5, 40)
    val limitedUseAppCheckTokens: Boolean get() = rc.getBoolean("limited_use_app_check_tokens")

    val onDeviceModelOptionName: String get() = rc.getString("on_device_model_option").uppercase()

    val onDeviceModelOption: OnDeviceModelOption
        get() = when (onDeviceModelOptionName) {
            "PREVIEW" -> OnDeviceModelOption.PREVIEW
            "PREVIEW_FAST" -> OnDeviceModelOption.PREVIEW_FAST
            else -> OnDeviceModelOption.STABLE
        }

    fun routing(): RoutingConfig = RoutingConfig(
        cloudEnabled = rc.getBoolean("cloud_enabled"),
        onDeviceInputTokenBudget = rc.getLong("on_device_input_token_budget").toInt().coerceIn(500, 3800),
        busyRetries = rc.getLong("busy_retries").toInt().coerceIn(0, 5),
        modeOverrides = parseOverrides(rc.getString("mode_overrides")),
    )

    private fun parseOverrides(raw: String): Map<String, HybridMode> =
        raw.split(',').mapNotNull { pair ->
            val (task, mode) = pair.split(':').map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            val parsed = HybridMode.entries.firstOrNull { it.name == mode.uppercase() } ?: return@mapNotNull null
            task to parsed
        }.toMap()
}
