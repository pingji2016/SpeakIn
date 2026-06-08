package com.speakin.app.domain.asr

import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * ExecuTorch Whisper 推理引擎。
 *
 * 使用 software-mansion 预导出模型（单个 .pte 文件，含 encode/decode 两个方法）。
 * 模型直接接收原始 PCM 音频，内部处理 mel 频谱计算。
 *
 * 模型方法签名:
 *   encode(float32[1, 480000]) → float32[1, 1500, 384]
 *   decode(int64[1, 128], int64[128], float32[1, 1500, 384]) → float32[1, 128, 51865]
 */
class ExecuTorchWhisperEngine {

    private var module: Module? = null
    private var tokenizer: WhisperTokenizer? = null

    var isLoaded: Boolean = false
        private set

    companion object {
        private const val TAG = "ExecuTorchWhisper"
        private const val N_SAMPLES = 480000       // 30s @ 16kHz
        private const val MAX_DECODE_LEN = 128      // 最大 token 数
        private const val EOT_TOKEN = 50257
        private const val SOT_TOKEN = 50258
        private const val SOT_ZH_TOKEN = 50319
        private const val TRANSCRIBE_TOKEN = 50362
        private const val NOTIMESTAMPS_TOKEN = 50363
        private const val VOCAB_SIZE = 51865
    }

    /**
     * 加载 .pte 模型文件和 tokenizer。
     */
    fun load(modelDir: File): Boolean {
        return try {
            val pteFile = File(modelDir, "whisper_tiny_xnnpack_fp32.pte")
            if (!pteFile.exists()) {
                Log.e(TAG, ".pte not found: ${pteFile.absolutePath}")
                return false
            }

            module = Module.load(pteFile.absolutePath)
            Log.i(TAG, "Model loaded: ${pteFile.absolutePath}")

            // 检查方法是否存在
            val methods = module!!.getMethods()
            Log.i(TAG, "Available methods: ${methods.joinToString()}")

            // 加载 tokenizer
            val tokenizerFile = File(modelDir, "tokenizer.json")
            val vocabFile = File(modelDir, "vocab.json")
            tokenizer = when {
                tokenizerFile.exists() -> WhisperTokenizer(tokenizerFile)
                vocabFile.exists() -> WhisperTokenizer(vocabFile)
                else -> {
                    Log.w(TAG, "No tokenizer found, will return raw token IDs")
                    null
                }
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
     */
    fun transcribe(
        audioPcm: FloatArray,
        onProgress: ((Float) -> Unit)? = null
    ): String? {
        if (!isLoaded || module == null) {
            Log.e(TAG, "Engine not loaded")
            return null
        }

        onProgress?.invoke(0.0f)

        // Step 1: 准备音频输入（填充或截断到 480000 采样点）
        val audioInput = prepareAudio(audioPcm)
        Log.i(TAG, "Audio prepared: ${audioInput.size} samples")
        onProgress?.invoke(0.1f)

        // Step 2: Encoder 推理
        val encoderOutput = runEncode(audioInput) ?: return null
        Log.i(TAG, "Encoder done")
        onProgress?.invoke(0.4f)

        // Step 3: Decoder 自回归推理
        val tokenIds = runDecode(encoderOutput) ?: return null
        onProgress?.invoke(0.9f)

        // Step 4: Token → 文本
        val text = tokenizer?.decode(tokenIds) ?: tokenIds.joinToString(",")
        onProgress?.invoke(1.0f)

        return text
    }

    // ============================================================
    // 音频预处理
    // ============================================================

    private fun prepareAudio(audio: FloatArray): FloatArray {
        return if (audio.size >= N_SAMPLES) {
            audio.copyOf(N_SAMPLES)
        } else {
            val padded = FloatArray(N_SAMPLES)
            System.arraycopy(audio, 0, padded, 0, audio.size)
            padded
        }
    }

    // ============================================================
    // Encoder
    // ============================================================

    private fun runEncode(audio: FloatArray): Tensor? {
        val mod = module ?: return null

        return try {
            val audioTensor = Tensor.fromBlob(audio, longArrayOf(1, N_SAMPLES.toLong()))
            val result = mod.execute("encode", EValue.from(audioTensor))
            result[0].toTensor()
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed", e)
            null
        }
    }

    // ============================================================
    // Decoder 自回归
    // ============================================================

    private fun runDecode(encoderOutput: Tensor): List<Int>? {
        val mod = module ?: return null

        // 初始化 token 序列
        val tokens = mutableListOf<Int>(
            SOT_TOKEN,
            SOT_ZH_TOKEN,
            TRANSCRIBE_TOKEN,
            NOTIMESTAMPS_TOKEN
        )

        // 自回归解码
        for (step in tokens.size until MAX_DECODE_LEN) {
            val nextToken = decodeOneStep(mod, tokens, encoderOutput)
                ?: break

            if (nextToken == EOT_TOKEN) {
                Log.d(TAG, "EOT at step $step")
                break
            }

            tokens.add(nextToken)
        }

        // 过滤起始特殊 token
        val sotTokens = setOf(SOT_TOKEN, SOT_ZH_TOKEN, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
        return tokens.filter { it !in sotTokens }
    }

    private fun decodeOneStep(
        mod: Module,
        tokens: List<Int>,
        encoderOutput: Tensor
    ): Int? {
        return try {
            val n = tokens.size

            // [1, 128] token 张量，不足用 EOT 填充
            val tokenArr = LongArray(MAX_DECODE_LEN) { i ->
                if (i < n) tokens[i].toLong() else EOT_TOKEN.toLong()
            }
            val tokenTensor = Tensor.fromBlob(tokenArr, longArrayOf(1, MAX_DECODE_LEN.toLong()))

            // [128] attention mask，有效位置 1
            val maskArr = LongArray(MAX_DECODE_LEN) { i ->
                if (i < n) 1L else 0L
            }
            val maskTensor = Tensor.fromBlob(maskArr, longArrayOf(MAX_DECODE_LEN.toLong()))

            // decode forward
            val result = mod.execute(
                "decode",
                EValue.from(tokenTensor),
                EValue.from(maskTensor),
                EValue.from(encoderOutput)
            )

            // 输出: [1, 128, 51865]
            val logitsData = result[0].toTensor().dataAsFloatArray

            // 取最后一个有效 token 的 logits
            val offset = (n - 1) * VOCAB_SIZE
            val lastLogits = logitsData.copyOfRange(offset, offset + VOCAB_SIZE)

            // argmax
            var maxIdx = 0
            var maxVal = lastLogits[0]
            for (i in 1 until lastLogits.size) {
                if (lastLogits[i] > maxVal) {
                    maxVal = lastLogits[i]
                    maxIdx = i
                }
            }
            maxIdx
        } catch (e: Exception) {
            Log.e(TAG, "Decode step failed at ${tokens.size}", e)
            null
        }
    }

    fun release() {
        module?.destroy()
        module = null
        isLoaded = false
        Log.i(TAG, "Whisper engine released")
    }
}
