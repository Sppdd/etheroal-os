package com.etheroal.os.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.etheroal.os.core.ai.DeviceState
import com.etheroal.os.core.ai.DeviceStateProvider
import com.etheroal.os.core.ai.HybridMode
import com.etheroal.os.core.ai.ModelStatus
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface DownloadProgress {
    data class Started(val totalBytes: Long) : DownloadProgress
    data class Running(val downloadedBytes: Long) : DownloadProgress
    data object Done : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}

/** Lifecycle of the on-device model: is it there, fetch it, load it into memory. */
@OptIn(PublicPreviewAPI::class)
class OnDeviceModelManager(private val engine: FirebaseInferenceEngine) {

    @Volatile
    private var knownUnavailable = false

    /** Gemini Nano status as reported by AICore. "Unavailable" is cached: it cannot change without a system update. */
    suspend fun status(): ModelStatus {
        if (knownUnavailable) return ModelStatus.UNAVAILABLE
        return checkStatus().also { if (it == ModelStatus.UNAVAILABLE) knownUnavailable = true }
    }

    private suspend fun checkStatus(): ModelStatus = try {
        when (FirebaseAIOnDevice.checkStatus()) {
            OnDeviceModelStatus.AVAILABLE -> ModelStatus.AVAILABLE
            OnDeviceModelStatus.DOWNLOADABLE -> ModelStatus.DOWNLOADABLE
            OnDeviceModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
            else -> ModelStatus.UNAVAILABLE
        }
    } catch (e: Exception) {
        // Emulators and unsupported phones throw here; that simply means "no on-device model".
        ModelStatus.UNAVAILABLE
    }

    /** The SDK never downloads the model on its own, so the app must ask for it. */
    fun download(): Flow<DownloadProgress> = FirebaseAIOnDevice.download().map { s ->
        when (s) {
            is DownloadStatus.DownloadStarted -> DownloadProgress.Started(s.bytesToDownload)
            is DownloadStatus.DownloadInProgress -> DownloadProgress.Running(s.totalBytesDownloaded)
            is DownloadStatus.DownloadFailed -> DownloadProgress.Failed("The on-device model could not be downloaded")
            else -> DownloadProgress.Done
        }
    }

    /** Loads the model into memory so the first real request is not slow. */
    suspend fun warmUp() {
        runCatching { engine.model(HybridMode.ONLY_ON_DEVICE).onDeviceExtension?.warmUp() }
    }

    /** Which Gemini Nano build AICore selected, for example "nano-v4-full". */
    suspend fun modelName(): String? =
        runCatching { engine.model(HybridMode.ONLY_ON_DEVICE).onDeviceExtension?.getModelName() }.getOrNull()
}

class AndroidDeviceState(
    context: Context,
    private val models: OnDeviceModelManager,
    private val local: LocalLlm,
) : DeviceStateProvider {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var coolUntil = 0L

    override suspend fun current() = DeviceState(
        modelStatus = models.status().let { nano ->
            // No Gemini Nano: a local model stands in only while the user has switched it on in the Lab.
            if (nano == ModelStatus.UNAVAILABLE && local.active()) ModelStatus.AVAILABLE else nano
        },
        appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        online = isOnline(),
        onDeviceCoolingDown = SystemClock.elapsedRealtime() < coolUntil,
    )

    override fun startOnDeviceCooldown() {
        coolUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
    }

    private fun isOnline(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val COOLDOWN_MS = 30 * 60 * 1000L
    }
}
