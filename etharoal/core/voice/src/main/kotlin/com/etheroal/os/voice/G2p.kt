package com.etheroal.os.voice

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.zip.ZipInputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The unknown-word model: letters -> CMUdict phonemes in one forward pass (port of dizione/g2p.py).
 * Letter embeddings with two slots per letter, optional residual convolutions, a 2-layer
 * bidirectional GRU and a CTC output read greedily. Weights come from g2p.npz (float32 .npy arrays).
 */
class G2p(settingsJson: String, npz: InputStream) {
    private val letters: Map<Char, Int>
    private val symbols: List<String>
    private val slots: Int
    private val convs: Int
    private val layers: Int
    private val w: Map<String, Tensor>

    class Tensor(val shape: IntArray, val data: FloatArray) {
        fun row(i: Int): FloatArray { val n = shape[1]; return data.copyOfRange(i * n, (i + 1) * n) }
    }

    init {
        val s = MiniJson.parse(settingsJson) as Map<*, *>
        letters = (s["letters"] as String).withIndex().associate { (i, c) -> c to i + 1 }
        @Suppress("UNCHECKED_CAST")
        symbols = s["symbols"] as List<String>
        slots = (s["slots"] as Number).toInt()
        convs = (s["convs"] as Number).toInt()
        layers = (s["layers"] as Number).toInt()
        w = readNpz(npz)
    }

    fun spellable(word: String): String {
        val plain = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFKD)
        return plain.filter { it in letters }.trim('\'')
    }

    /** CMUdict symbols with stress digits, or empty. */
    fun predict(wordIn: String): List<String> {
        val word = spellable(wordIn)
        if (word.isEmpty()) return emptyList()
        val emb = w.getValue("embed__weight"); val slot = w.getValue("slot")
        val width = emb.shape[1]
        var x = Array(word.length * slots) { t ->
            val e = emb.row(letters.getValue(word[t / slots])); val sl = slot.row(t % slots)
            FloatArray(width) { e[it] + sl[it] }
        }
        for (b in 0 until convs) {
            val normed = layerNorm(x, w.getValue("norms__${b}__weight").data, w.getValue("norms__${b}__bias").data)
            val c = conv(normed, w.getValue("convs__${b}__weight"), w.getValue("convs__${b}__bias").data)
            x = Array(x.size) { t -> FloatArray(x[t].size) { x[t][it] + max(0f, c[t][it]) } }
        }
        for (layer in 0 until layers) {
            val fwd = gru(x, layer, "", false); val bwd = gru(x, layer, "_reverse", true)
            x = Array(x.size) { t -> fwd[t] + bwd[t] }
        }
        val outW = w.getValue("out__weight"); val outB = w.getValue("out__bias").data
        val classes = outW.shape[0]
        val result = mutableListOf<String>()
        var previous = 0
        for (t in x.indices) {
            var bestK = 0; var best = Float.NEGATIVE_INFINITY
            for (k in 0 until classes) {
                var v = outB[k]; val off = k * outW.shape[1]
                for (j in x[t].indices) v += outW.data[off + j] * x[t][j]
                if (v > best) { best = v; bestK = k }
            }
            if (bestK != previous && bestK != 0) result += symbols[bestK - 1]
            previous = bestK
        }
        return result
    }

    private fun gru(x: Array<FloatArray>, layer: Int, suffix: String, reverse: Boolean): Array<FloatArray> {
        val wih = w.getValue("gru__weight_ih_l$layer$suffix"); val whh = w.getValue("gru__weight_hh_l$layer$suffix")
        val bih = w.getValue("gru__bias_ih_l$layer$suffix").data; val bhh = w.getValue("gru__bias_hh_l$layer$suffix").data
        val size = whh.shape[1]; val inDim = wih.shape[1]
        val out = Array(x.size) { FloatArray(size) }
        var state = FloatArray(size)
        val order = if (reverse) (x.indices.reversed()) else x.indices
        for (t in order) {
            val inp = FloatArray(3 * size) { r -> var v = bih[r]; val o = r * inDim; for (j in 0 until inDim) v += wih.data[o + j] * x[t][j]; v }
            val rec = FloatArray(3 * size) { r -> var v = bhh[r]; val o = r * size; for (j in 0 until size) v += whh.data[o + j] * state[j]; v }
            val next = FloatArray(size)
            for (k in 0 until size) {
                val reset = sigmoid(inp[k] + rec[k])
                val update = sigmoid(inp[size + k] + rec[size + k])
                val cand = tanh(inp[2 * size + k] + reset * rec[2 * size + k])
                next[k] = (1f - update) * cand + update * state[k]
            }
            state = next
            out[t] = next
        }
        return out
    }

    private fun layerNorm(x: Array<FloatArray>, weight: FloatArray, bias: FloatArray) = Array(x.size) { t ->
        val v = x[t]; val mean = v.average().toFloat()
        val variance = v.map { (it - mean) * (it - mean) }.average().toFloat()
        FloatArray(v.size) { (v[it] - mean) / sqrt(variance + 1e-5f) * weight[it] + bias[it] }
    }

    private fun conv(x: Array<FloatArray>, weight: Tensor, bias: FloatArray): Array<FloatArray> {
        val outC = weight.shape[0]; val inC = weight.shape[1]; val kernel = weight.shape[2]; val pad = kernel / 2
        return Array(x.size) { t ->
            FloatArray(outC) { o ->
                var v = bias[o]
                for (k in 0 until kernel) {
                    val src = t + k - pad
                    if (src < 0 || src >= x.size) continue
                    for (i in 0 until inC) v += x[src][i] * weight.data[(o * inC + i) * kernel + k]
                }
                v
            }
        }
    }

    private fun sigmoid(v: Float) = 1f / (1f + exp(-v))

    private operator fun FloatArray.plus(o: FloatArray): FloatArray = FloatArray(size + o.size) { if (it < size) this[it] else o[it - size] }

    private companion object {
        /** Reads float32 little-endian arrays from an .npz (zip of .npy files). */
        fun readNpz(input: InputStream): Map<String, Tensor> {
            val out = HashMap<String, Tensor>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.name.endsWith(".npy")) continue
                    val bytes = zip.readBytes()
                    out[entry.name.removeSuffix(".npy")] = parseNpy(bytes, entry.name)
                }
            }
            return out
        }

        fun parseNpy(bytes: ByteArray, name: String): Tensor {
            require(bytes[0] == 0x93.toByte() && String(bytes, 1, 5) == "NUMPY") { "$name is not an .npy file" }
            val major = bytes[6].toInt()
            val (headerLen, start) = if (major == 1) {
                (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8) to 10
            } else {
                ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int to 12
            }
            val header = String(bytes, start, headerLen)
            require("'<f4'" in header) { "$name: only little-endian float32 is supported ($header)" }
            require("'fortran_order': False" in header) { "$name: Fortran order is not supported" }
            val shape = Regex("'shape': \\(([^)]*)\\)").find(header)!!.groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
            val count = shape.fold(1) { a, b -> a * b }
            val data = FloatArray(count)
            ByteBuffer.wrap(bytes, start + headerLen, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data)
            return Tensor(if (shape.size == 1) intArrayOf(shape[0], 1) else shape, data)
        }
    }
}

/** Just enough JSON for g2p.json and config.json (objects, arrays, strings, numbers, booleans). */
object MiniJson {
    fun parse(text: String): Any? = Parser(text).run { skip(); value() }

    private class Parser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skip()
            return when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; skip()
            if (s[i] == '}') { i++; return m }
            while (true) { skip(); val k = str(); skip(); i++; m[k] = value(); skip(); if (s[i++] == '}') return m }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; skip()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); skip(); if (s[i++] == ']') return l }
        }
        fun str(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(s[i])
                    }
                } else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}
