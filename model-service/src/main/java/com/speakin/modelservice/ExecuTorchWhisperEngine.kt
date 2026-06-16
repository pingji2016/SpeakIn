package com.speakin.modelservice

import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * ExecuTorch Whisper 推理引擎。
 * 使用 software-mansion 预导出模型（单个 .pte 文件，含 encode/decode 方法）。
 * 运行在 :model 进程中。
 */
class ExecuTorchWhisperEngine {

    private var module: Module? = null
    private var tokenizer: WhisperTokenizer? = null

    var isLoaded: Boolean = false
        private set

    companion object {
        private const val TAG = "ExeTorchWhisper"
        private const val N_SAMPLES = 480000
        private const val MAX_DECODE_LEN = 128
        private const val EOT_TOKEN = 50257
        private const val SOT_TOKEN = 50258
        private const val SOT_ZH_TOKEN = 50319
        private const val TRANSCRIBE_TOKEN = 50362
        private const val NOTIMESTAMPS_TOKEN = 50363
        private const val VOCAB_SIZE = 51865
    }

    fun load(modelDir: File): Boolean {
        return try {
            val pteFile = File(modelDir, "whisper_tiny_xnnpack_fp32.pte")
            if (!pteFile.exists()) {
                Log.e(TAG, ".pte not found: ${pteFile.absolutePath}")
                return false
            }
            module = Module.load(pteFile.absolutePath)
            Log.i(TAG, "Model loaded: ${pteFile.absolutePath}")

            val methods = module!!.methods
            Log.i(TAG, "Methods: ${methods.joinToString()}")

            val tokenizerFile = File(modelDir, "tokenizer.json")
            tokenizer = if (tokenizerFile.exists()) WhisperTokenizer(tokenizerFile)
            else {
                Log.w(TAG, "No tokenizer found")
                null
            }

            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load whisper model", e)
            isLoaded = false
            false
        }
    }

    /**
     * 转写音频为文本。
     * @param audioPcm 16kHz PCM float 数组
     * @param onProgress 进度回调
     */
    fun transcribe(
        audioPcm: FloatArray,
        onProgress: ((Float) -> Unit)? = null
    ): String? {
        if (!isLoaded || module == null) return null

        onProgress?.invoke(0f)

        val audioInput = if (audioPcm.size >= N_SAMPLES) audioPcm.copyOf(N_SAMPLES)
        else FloatArray(N_SAMPLES).also { System.arraycopy(audioPcm, 0, it, 0, audioPcm.size) }
        onProgress?.invoke(0.1f)

        // Encoder
        val audioTensor = Tensor.fromBlob(audioInput, longArrayOf(1, N_SAMPLES.toLong()))
        var result = module!!.execute("encode", EValue.from(audioTensor))
        val encoderOutput = result[0].toTensor()
        onProgress?.invoke(0.4f)

        // Decoder 自回归
        val tokens = mutableListOf(SOT_TOKEN, SOT_ZH_TOKEN, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
        for (step in tokens.size until MAX_DECODE_LEN) {
            val n = tokens.size
            val tokenArr = LongArray(MAX_DECODE_LEN) { i ->
                if (i < n) tokens[i].toLong() else EOT_TOKEN.toLong()
            }
            val maskArr = LongArray(MAX_DECODE_LEN) { i -> if (i < n) 1L else 0L }

            val tokenTensor = Tensor.fromBlob(tokenArr, longArrayOf(1, MAX_DECODE_LEN.toLong()))
            val maskTensor = Tensor.fromBlob(maskArr, longArrayOf(MAX_DECODE_LEN.toLong()))

            result = module!!.execute("decode", EValue.from(tokenTensor), EValue.from(maskTensor), EValue.from(encoderOutput))
            val logitsData = result[0].toTensor().dataAsFloatArray

            val offset = (n - 1) * VOCAB_SIZE
            val lastLogits = logitsData.copyOfRange(offset, offset + VOCAB_SIZE)

            var nextToken = 0; var maxVal = lastLogits[0]
            for (i in 1 until lastLogits.size) { if (lastLogits[i] > maxVal) { maxVal = lastLogits[i]; nextToken = i } }

            if (nextToken == EOT_TOKEN) break
            tokens.add(nextToken)
        }

        onProgress?.invoke(0.9f)
        val sotTokens = setOf(SOT_TOKEN, SOT_ZH_TOKEN, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
        val text = tokenizer?.decode(tokens.filter { it !in sotTokens }) ?: ""

        onProgress?.invoke(1f)
        return text
    }

    fun release() {
        module?.destroy()
        module = null
        isLoaded = false
    }
}
