package com.speakin.modelservice

import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * ExecuTorch Whisper — 两模型管线:
 *   1. whisper_pre_enc.pte: raw audio → encoder hidden [1,1500,384]
 *   2. whisper_decoder.pte: autoregressive token generation
 */
class ExecuTorchWhisperEngine {

    private var tokenizer: WhisperTokenizer? = null
    private var modelDir: File? = null
    var isLoaded: Boolean = false; private set

    companion object {
        private const val TAG = "ExeTorchWhisper"
        private const val N_SAMPLES = 480000
        private const val MAX_DECODE_LEN = 128
        private const val VOCAB_SIZE = 51865
        private const val EOT_TOKEN = 50257
    }

    fun load(modelDir: File): Boolean {
        return try {
            val files = listOf("whisper_pre_enc.pte", "whisper_decoder.pte", "tokenizer.json")
            if (files.any { !File(modelDir, it).exists() }) { Log.e(TAG, "Missing models"); return false }
            this.modelDir = modelDir
            tokenizer = WhisperTokenizer(File(modelDir, "tokenizer.json"))
            Log.i(TAG, "Tokenizer: ${tokenizer!!.size} tokens")
            isLoaded = true; true
        } catch (e: Exception) { Log.e(TAG, "Load failed", e); false }
    }

    fun transcribe(audioPcm: FloatArray, onProgress: ((Float) -> Unit)? = null): String? {
        if (!isLoaded || modelDir == null) return null
        val dir = modelDir!!

        // Pad + RMS gain
        val padded = FloatArray(N_SAMPLES)
        var rms = 0f; for (s in audioPcm) if (s != 0f) rms += s * s
        rms = kotlin.math.sqrt(rms / audioPcm.count { it != 0f }.coerceAtLeast(1))
        val gain = if (rms > 0.001f) (0.15f / rms).coerceIn(0.5f, 10f) else 1f
        for (i in audioPcm.indices) padded[i] = audioPcm[i] * gain
        Log.i(TAG, "Audio: ${audioPcm.size}samp, rms=${"%.4f".format(rms)}, gain=${"%.1f".format(gain)}")
        onProgress?.invoke(0.05f)

        // ── Encoder ──
        var module = Module.load(File(dir, "whisper_pre_enc.pte").absolutePath)
        val encResult = module.execute("forward",
            EValue.from(Tensor.fromBlob(padded, longArrayOf(N_SAMPLES.toLong()))))
        val encData = encResult[0].toTensor().dataAsFloatArray
        val encShape = encResult[0].toTensor().shape()
        Log.i(TAG, "Encoder: [${encShape.joinToString()}], min=${"%.2f".format(encData.minOrNull()!!)}, max=${"%.2f".format(encData.maxOrNull()!!)}")
        module.destroy()
        onProgress?.invoke(0.3f)

        // ── Decoder (fixed-length padding to avoid ExecuTorch static shape errors) ──
        val encHidden = Tensor.fromBlob(encData, longArrayOf(1, encShape[1], encShape[2]))
        module = Module.load(File(dir, "whisper_decoder.pte").absolutePath)
        val sotTokens = tokenizer!!.getSotTokens()

        // Fixed-length buffers: model was exported with seq_len=MAX_DECODE_LEN
        val tokenArr = LongArray(MAX_DECODE_LEN)
        val maskArr = FloatArray(MAX_DECODE_LEN)
        for (i in sotTokens.indices) {
            tokenArr[i] = sotTokens[i].toLong()
            maskArr[i] = 1f
        }
        var curLen = sotTokens.size
        val resultTokens = mutableListOf<Int>().also { it.addAll(sotTokens) }

        for (step in curLen until MAX_DECODE_LEN) {
            val decResult = module.execute("forward",
                EValue.from(Tensor.fromBlob(tokenArr, longArrayOf(1, MAX_DECODE_LEN.toLong()))),
                EValue.from(Tensor.fromBlob(maskArr, longArrayOf(1, MAX_DECODE_LEN.toLong()))),
                EValue.from(encHidden)
            )
            val logits = decResult[0].toTensor().dataAsFloatArray
            val offset = (curLen - 1) * VOCAB_SIZE
            val last = logits.copyOfRange(offset, offset + VOCAB_SIZE)
            var next = 0; var mv = last[0]
            for (i in 1 until last.size) if (last[i] > mv) { mv = last[i]; next = i }
            if (step < 8) Log.i(TAG, "Step $step: token=$next val=${"%.1f".format(mv)}")
            if (next == EOT_TOKEN) break
            tokenArr[curLen] = next.toLong()
            maskArr[curLen] = 1f
            resultTokens.add(next)
            curLen++
        }

        module.destroy()
        onProgress?.invoke(0.95f)
        val text = tokenizer?.decode(resultTokens.filter { it < EOT_TOKEN }) ?: ""
        onProgress?.invoke(1f)
        Log.i(TAG, "Result: ${resultTokens.size} tokens, '${text.take(100)}'")
        return text
    }

    fun release() { tokenizer = null; modelDir = null; isLoaded = false }
}
