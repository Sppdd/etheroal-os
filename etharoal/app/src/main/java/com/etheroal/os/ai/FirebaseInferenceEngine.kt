package com.etheroal.os.ai

import android.graphics.Bitmap
import com.etheroal.os.core.ai.EngineException
import com.etheroal.os.core.ai.EngineFailure
import com.etheroal.os.core.ai.EngineRequest
import com.etheroal.os.core.ai.EngineResult
import com.etheroal.os.core.ai.HybridMode
import com.etheroal.os.core.ai.InferenceEngine
import com.etheroal.os.core.ai.InferenceSource
import com.google.firebase.Firebase
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.FirebaseAIOnDeviceInvalidRequestException
import com.google.firebase.ai.type.FirebaseAIOnDeviceNotAvailableException
import com.google.firebase.ai.type.FirebaseAIOnDeviceUnknownException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.APINotConfiguredException
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.PermissionMissingException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The only class that talks to a model. Every call goes through Firebase AI Logic with an explicit
 * hybrid [InferenceMode] chosen by the router, and reports back where it actually ran.
 */
@OptIn(PublicPreviewAPI::class)
class FirebaseInferenceEngine(private val config: RemoteAiConfig) : InferenceEngine {

    val ai: FirebaseAI by lazy {
        Firebase.ai(
            backend = GenerativeBackend.googleAI(),
            // Limited-use App Check tokens give replay protection for the Gemini endpoint.
            useLimitedUseAppCheckTokens = config.limitedUseAppCheckTokens,
        )
    }

    fun model(mode: HybridMode, maxOutputTokens: Int? = null, agent: Boolean = false): GenerativeModel = ai.generativeModel(
        modelName = if (agent) config.cloudModelAgent else config.cloudModelFast,
        onDeviceConfig = OnDeviceConfig(
            mode = mode.toSdk(),
            maxOutputTokens = maxOutputTokens,
            modelOption = config.onDeviceModelOption,
        ),
    )

    override suspend fun generate(request: EngineRequest): EngineResult {
        val agent = request.schemaKey == Tasks.SCHEMA_AGENT
        val model = model(request.mode, request.maxOutputTokens, agent)
        try {
            if (request.schemaKey == Tasks.SCHEMA_TRIAGE) {
                val typed = model.generateObject(TriageResult.firebaseAISchema(), request.prompt)
                val value = typed.getObject()
                    ?: throw EngineException(EngineFailure.UNKNOWN, "Model returned no structured result")
                val source = typed.response.inferenceSource.toCore()
                return EngineResult(
                    text = Json.encodeToString(TriageResult.serializer(), value),
                    source = source,
                    modelName = label(source, typed.response.modelVersion),
                )
            }
            val bitmap = request.image as? Bitmap
            val response = if (bitmap != null) {
                // On-device accepts exactly one Bitmap together with text.
                model.generateContent(content { image(bitmap); text(request.prompt) })
            } else {
                model.generateContent(request.prompt)
            }
            val text = response.text?.trim().orEmpty()
            if (text.isEmpty()) throw EngineException(EngineFailure.BLOCKED, "The model returned an empty answer")
            val source = response.inferenceSource.toCore()
            return EngineResult(text, source, label(source, response.modelVersion ?: config.cloudModelAgent.takeIf { agent }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }
    }

    fun classify(e: Exception): EngineException {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(6).toList()
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        val failure = when {
            // AICore error codes arrive inside the on-device exception chain.
            "BACKGROUND_USE_BLOCKED" in text -> EngineFailure.BACKGROUND_BLOCKED
            "PER_APP_BATTERY_USE_QUOTA_EXCEEDED" in text -> EngineFailure.BATTERY_QUOTA
            "BUSY" in text -> EngineFailure.BUSY
            e is FirebaseAIOnDeviceNotAvailableException -> EngineFailure.ON_DEVICE_UNAVAILABLE
            e is FirebaseAIOnDeviceInvalidRequestException -> EngineFailure.ON_DEVICE_UNAVAILABLE
            e is FirebaseAIOnDeviceUnknownException -> EngineFailure.ON_DEVICE_UNAVAILABLE
            "App Check" in text || "AppCheck" in text -> EngineFailure.APP_CHECK
            e is QuotaExceededException -> EngineFailure.CLOUD_QUOTA
            // Billing or prepaid credits ran out: the cloud is unusable for now, like a quota.
            "credits are depleted" in text || "billing" in text.lowercase() || "RESOURCE_EXHAUSTED" in text -> EngineFailure.CLOUD_QUOTA
            e is ServiceDisabledException || e is APINotConfiguredException ||
                e is PermissionMissingException || e is InvalidAPIKeyException ||
                "403" in text || "PERMISSION_DENIED" in text || "SERVICE_DISABLED" in text -> EngineFailure.CLOUD_REJECTED
            e is PromptBlockedException || e is ResponseStoppedException -> EngineFailure.BLOCKED
            e is RequestTimeoutException || chain.any { it is IOException } -> EngineFailure.OFFLINE
            e is FirebaseAIException -> EngineFailure.UNKNOWN
            else -> EngineFailure.UNKNOWN
        }
        // SDK error text only (status, error code), never the prompt.
        android.util.Log.w("EtheroalCloud", "${failure.name}: ${chain.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(400)}" }}")
        // The message is shown in the debug lab; it contains SDK error text, never user content.
        return EngineException(failure, "${failure.name}: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}", e)
    }

    private fun label(source: InferenceSource, version: String?): String =
        if (source == InferenceSource.ON_DEVICE) "Gemini Nano" + (version?.let { " ($it)" } ?: "")
        else "Gemini cloud" + ((version ?: config.cloudModelFast).let { " ($it)" })

    private fun HybridMode.toSdk(): InferenceMode = when (this) {
        HybridMode.ONLY_ON_DEVICE -> InferenceMode.ONLY_ON_DEVICE
        HybridMode.PREFER_ON_DEVICE -> InferenceMode.PREFER_ON_DEVICE
        HybridMode.PREFER_IN_CLOUD -> InferenceMode.PREFER_IN_CLOUD
        HybridMode.ONLY_IN_CLOUD -> InferenceMode.ONLY_IN_CLOUD
    }

    private fun com.google.firebase.ai.InferenceSource.toCore(): InferenceSource =
        if (this == com.google.firebase.ai.InferenceSource.ON_DEVICE) InferenceSource.ON_DEVICE
        else InferenceSource.IN_CLOUD
}
