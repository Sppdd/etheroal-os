package com.etheroal.os.ai

import com.etheroal.os.core.ai.EngineException
import com.etheroal.os.core.ai.EngineFailure
import com.etheroal.os.core.ai.EngineRequest
import com.etheroal.os.core.ai.EngineResult
import com.etheroal.os.core.ai.HybridMode
import com.etheroal.os.core.ai.InferenceEngine
import com.etheroal.os.core.ai.ModelStatus

/**
 * Executes the router's chosen mode.
 *
 * - Phone has Gemini Nano: everything goes through Firebase AI Logic, which performs the hybrid
 *   on-device/cloud execution itself.
 * - Phone has no Nano and the user switched a local model on in the Lab: the "on-device" half of each mode is served
 *   by [LocalLlm]; the cloud half still goes through Firebase AI Logic (ONLY_IN_CLOUD). The mode
 *   semantics are the same, so privacy decisions made by the router hold unchanged.
 */
class HybridEngine(
    private val firebase: FirebaseInferenceEngine,
    private val local: LocalLlm,
    private val nanoStatus: suspend () -> ModelStatus,
) : InferenceEngine {

    override suspend fun generate(request: EngineRequest): EngineResult {
        if (nanoStatus() != ModelStatus.UNAVAILABLE || !local.active()) {
            return firebase.generate(request)
        }
        return when (request.mode) {
            HybridMode.ONLY_ON_DEVICE -> local.generate(request)
            HybridMode.ONLY_IN_CLOUD -> cloud(request)
            HybridMode.PREFER_ON_DEVICE -> try {
                local.generate(request)
            } catch (e: EngineException) {
                if (e.failure == EngineFailure.BLOCKED) throw e
                cloud(request)
            }
            HybridMode.PREFER_IN_CLOUD -> try {
                cloud(request)
            } catch (e: EngineException) {
                if (e.failure !in CLOUD_DOWN) throw e
                local.generate(request)
            }
        }
    }

    private suspend fun cloud(request: EngineRequest) =
        firebase.generate(request.copy(mode = HybridMode.ONLY_IN_CLOUD))

    private companion object {
        val CLOUD_DOWN = setOf(
            EngineFailure.OFFLINE, EngineFailure.CLOUD_QUOTA, EngineFailure.CLOUD_REJECTED, EngineFailure.APP_CHECK, EngineFailure.UNKNOWN,
        )
    }
}
