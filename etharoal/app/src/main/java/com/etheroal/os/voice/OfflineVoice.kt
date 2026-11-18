package com.etheroal.os.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

enum class Voice(val id: Long, val label: String) { F2(0, "Female"), M2(1, "Male") }

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Loading : VoiceState
    data class Speaking(val firstAudioMs: Long?) : VoiceState
    data class Done(val firstAudioMs: Long, val audioSeconds: Double, val synthMs: Long) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Speaks text on the phone with tiny-voca (4.5M parameters, Apache-2.0). Text becomes phonemes
 * with the Kotlin port of dizione, then ONNX Runtime turns them into 24 kHz audio. Nothing
 * leaves the device. Sentences are synthesised one at a time so speech starts quickly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineVoice(private val context: Context) {

    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var phonemizer: Phonemizer? = null
    private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var track: AudioTrack? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    private suspend fun ensureLoaded() = lock.withLock {
        if (session != null) return@withLock
        _state.value = VoiceState.Loading
        withContext(worker) {
            val open = { name: String -> context.assets.open("tts/$name") }
            phonemizer = Phonemizer(open)
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            session = env.createSession(open("tiny-voca.onnx").use { it.readBytes() }, options)
        }
    }

    /** Frees the voice model's memory; it loads again on the next request. */
    suspend fun release() = lock.withLock {
        withContext(worker) { session?.close(); session = null; phonemizer = null; _state.value = VoiceState.Idle }
    }

    /** Speaks [text]; returns when playback finishes or the coroutine is cancelled. */
    suspend fun speak(text: String, voice: Voice = Voice.F2, speed: Float = 1.0f) {
        try {
            ensureLoaded()
            val started = SystemClock.elapsedRealtime()
            var firstAudio: Long? = null
            var samples = 0L
            var synthMs = 0L
            _state.value = VoiceState.Speaking(null)
            val audio = openTrack()
            try {
                for (sentence in sentences(text)) {
                    coroutineContext.ensureActive()
                    val t0 = SystemClock.elapsedRealtime()
                    val pcm = withContext(worker) { synthesize(sentence, voice, speed) } ?: continue
                    synthMs += SystemClock.elapsedRealtime() - t0
                    if (firstAudio == null) {
                        firstAudio = SystemClock.elapsedRealtime() - started
                        _state.value = VoiceState.Speaking(firstAudio)
                        audio.play()
                    }
                    samples += pcm.size
                    withContext(Dispatchers.IO) { audio.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
                }
                // Let the tail of the buffer play out.
                withContext(Dispatchers.IO) { Thread.sleep(300) }
            } finally {
                audio.stop(); audio.release(); track = null
            }
            _state.value = VoiceState.Done(firstAudio ?: 0, samples / SAMPLE_RATE.toDouble(), synthMs)
            Log.i(TAG, "voice=${voice.name} firstAudio=${firstAudio}ms synth=${synthMs}ms audio=${samples / SAMPLE_RATE}s")
        } catch (e: CancellationException) {
            _state.value = VoiceState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = VoiceState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() {
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun synthesize(sentence: String, voice: Voice, speed: Float): FloatArray? {
        val p = phonemizer!!.let { runCatching { it.phonemize(sentence) }.getOrNull() } ?: return null
        val n = p.ids.size.coerceAtMost(MAX_TOKENS)
        val ids = p.ids.copyOf(n); val stress = p.stress.copyOf(n)
        if (n < p.ids.size) ids[n - 1] = p.ids.last()           // keep <eos> if we had to cut
        val inputs = mapOf(
            "tokens" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n.toLong())),
            "stress" to OnnxTensor.createTensor(env, LongBuffer.wrap(stress), longArrayOf(1, n.toLong())),
            "voice" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(voice.id)), longArrayOf(1)),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)),
            "pitch_shift" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "pitch_range" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)),
        )
        try {
            session!!.run(inputs).use { result ->
                val tensor = result.get("audio").get() as OnnxTensor
                val buffer = tensor.floatBuffer
                val pcm = FloatArray(buffer.remaining()).also { buffer.get(it) }
                // The model can peak above 1.0; scale so it never clips.
                val peak = pcm.maxOfOrNull { abs(it) } ?: 0f
                if (peak > 0.99f) { val k = 0.99f / peak; for (i in pcm.indices) pcm[i] *= k }
                return pcm
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun openTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(SAMPLE_RATE * 4)
        .build().also { track = it }

    /** Splits at sentence ends so the first sentence can play while the next is synthesised. */
    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }

    private companion object {
        const val TAG = "EtheroalVoice"
        const val SAMPLE_RATE = 24_000
        const val MAX_TOKENS = 512
    }
}
