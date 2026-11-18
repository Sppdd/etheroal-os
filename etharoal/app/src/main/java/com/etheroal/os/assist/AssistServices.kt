package com.etheroal.os.assist

import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.text.InputType
import com.etheroal.os.agent.ScreenContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Etheroal as the phone's digital assistant: long-press the power or home button in any app and
 * Etheroal opens over it, already knowing what is on screen (if the owner allows "Use text from
 * screen" and "Use screenshot" in the assistant settings).
 */
class EtheroalAssistService : VoiceInteractionService()

class EtheroalAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Hands the current screen to [AssistActivity]. Kept only until the activity reads it. */
object AssistContext {
    private val _screen = MutableStateFlow<ScreenContext?>(null)
    val screen: StateFlow<ScreenContext?> = _screen
    internal var session: VoiceInteractionSession? = null

    internal fun update(f: (ScreenContext?) -> ScreenContext) { _screen.value = f(_screen.value) }
    fun clear() { _screen.value = null }

    /** Closes the assistant session when the overlay goes away. */
    fun finish() {
        runCatching { session?.hide() }
        session = null
    }
}

class AssistSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistContext.clear()
        AssistContext.session = this
        startAssistantActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure ?: return
        val app = structure.activityComponent?.packageName.orEmpty()
        if (app == context.packageName) return
        val text = ScreenText.of(structure)
        AssistContext.update { prev -> ScreenContext(app, text, prev?.image) }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val small = screenshot?.let {
            val scale = 896f / maxOf(it.width, it.height)
            if (scale < 1f) Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true) else it
        } ?: return
        AssistContext.update { prev -> (prev ?: ScreenContext("", "", null)).copy(image = small) }
    }

    override fun onHide() {
        super.onHide()
        if (AssistContext.session === this) AssistContext.session = null
    }
}

/** The visible text of the app the owner called Etheroal from. Password fields are skipped. */
object ScreenText {
    fun of(structure: AssistStructure): String {
        val out = StringBuilder()
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode, out, 0)
        return out.toString().trim().take(4000)
    }

    private fun visit(node: AssistStructure.ViewNode, out: StringBuilder, depth: Int) {
        if (depth > 40 || out.length > 4000) return
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (!password) {
            val t = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.append(t.replace('\n', ' ').take(200)).append('\n')
        }
        for (i in 0 until node.childCount) visit(node.getChildAt(i), out, depth + 1)
    }
}

/**
 * Android requires an assistant to name a speech recognizer. Etheroal's voice runs through Gemini
 * Live instead, so this one forwards to the phone's on-device recognizer where there is one, and
 * otherwise reports that it is unavailable.
 */
class EtheroalRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, listener: Callback) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { delegate = it }
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = runCatching { listener.readyForSpeech(p ?: Bundle()) }.let {}
                override fun onBeginningOfSpeech() = runCatching { listener.beginningOfSpeech() }.let {}
                override fun onRmsChanged(v: Float) = runCatching { listener.rmsChanged(v) }.let {}
                override fun onBufferReceived(b: ByteArray?) = runCatching { b?.let(listener::bufferReceived) }.let {}
                override fun onEndOfSpeech() = runCatching { listener.endOfSpeech() }.let {}
                override fun onError(e: Int) = runCatching { listener.error(e) }.let {}
                override fun onResults(b: Bundle?) = runCatching { listener.results(b ?: Bundle()) }.let {}
                override fun onPartialResults(b: Bundle?) = runCatching { listener.partialResults(b ?: Bundle()) }.let {}
                override fun onEvent(t: Int, b: Bundle?) = Unit
            })
            r.startListening(intent)
        } else {
            runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        }
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }
    override fun onCancel(listener: Callback) { delegate?.cancel(); delegate?.destroy(); delegate = null }
}
