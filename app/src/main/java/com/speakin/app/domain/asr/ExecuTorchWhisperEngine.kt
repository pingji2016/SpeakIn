package com.speakin.app.domain.asr

import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * ExecuTorch Whisper 推理引擎。
 *
 * 加载 PC 端导出的 .pte 文件（whisper_encoder.pte + whisper_decoder.pte），
 * 在 Android 上运行完整的 whisper 推理：
 *   audio PCM → mel 频谱 → encoder → decoder (auto-regressive) → token IDs → text
 */
class ExecuTorchWhisperEngine {

    private var encoderModule: Module? = null
    private var decoderModule: Module? = null
    private var config: WhisperConfig? = null
    private var tokenizer: WhisperTokenizer? = null

    var isLoaded: Boolean = false
        private set

    /**
     * 加载 .pte 模型文件和 tokenizer。
     */
    fun load(modelDir: File, configFile: File? = null, tokenizerFile: File? = null): Boolean {
        return try {
            val encoderFile = File(modelDir, "whisper_encoder.pte")
            if (!encoderFile.exists()) {
                Log.e(TAG, "encoder .pte not found: ${encoderFile.absolutePath}")
                return false
            }
            encoderModule = Module.load(encoderFile.absolutePath)
            Log.i(TAG, "Encoder loaded: ${encoderFile.absolutePath}")

            val decoderFile = File(modelDir, "whisper_decoder.pte")
            if (!decoderFile.exists()) {
                Log.e(TAG, "decoder .pte not found: ${decoderFile.absolutePath}")
                return false
            }
            decoderModule = Module.load(decoderFile.absolutePath)
            Log.i(TAG, "Decoder loaded: ${decoderFile.absolutePath}")

            config = if (configFile?.exists() == true) {
                WhisperConfig.fromFile(configFile)
            } else {
                val defaultConfig = File(modelDir, "whisper_config.json")
                if (defaultConfig.exists()) WhisperConfig.fromFile(defaultConfig)
                else WhisperConfig()
            }

            tokenizer = if (tokenizerFile?.exists() == true) {
                WhisperTokenizer(tokenizerFile)
            } else {
                val defaultTokenizer = File(modelDir, "tokenizer.json")
                val vocabFile = File(modelDir, "vocab.json")
                when {
                    defaultTokenizer.exists() -> WhisperTokenizer(defaultTokenizer)
                    vocabFile.exists() -> WhisperTokenizer(vocabFile)
                    else -> {
                        Log.w(TAG, "No tokenizer found")
                        null
                    }
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
     * 转写音频文件为文本。
     */
    fun transcribe(
        audioPcm: FloatArray,
        onProgress: ((Float) -> Unit)? = null
    ): String? {
        if (!isLoaded || encoderModule == null || decoderModule == null || config == null) {
            Log.e(TAG, "Engine not loaded")
            return null
        }

        val cfg = config!!
        val melSpec = MelSpectrogram(
            sampleRate = cfg.sampleRate,
            fftSize = cfg.fftSize,
            hopLength = cfg.hopLength,
            windowLength = cfg.windowLength,
            nMelBins = cfg.nMelBins
        )

        onProgress?.invoke(0.0f)

        // Step 1: 计算 mel 频谱
        var mel = melSpec.compute(audioPcm)
        if (mel.isEmpty()) {
            Log.w(TAG, "Audio too short")
            return ""
        }
        mel = melSpec.normalize(mel)
        Log.i(TAG, "Mel spectrogram: ${mel.size} bands x ${mel[0].size} frames")
        onProgress?.invoke(0.1f)

        // Step 2: 编码器推理
        val melFlat = melSpec.flatten(mel)
        val melShape = longArrayOf(1, cfg.nMelBins.toLong(), mel[0].size.toLong())
        val melTensor = Tensor.fromBlob(melFlat, melShape)

        val encoderResults: Array<EValue>
        try {
            encoderResults = encoderModule!!.forward(EValue.from(melTensor))
        } catch (e: Exception) {
            Log.e(TAG, "Encoder forward failed", e)
            return null
        }

        val encoderOutputTensor = encoderResults[0].toTensor()
        onProgress?.invoke(0.4f)

        // Step 3: 解码器自回归推理
        val tokenIds = autoregressiveDecode(encoderOutputTensor, cfg) ?: return null
        onProgress?.invoke(0.9f)

        // Step 4: 解码 token → 文本
        val text = if (tokenizer != null) {
            tokenizer!!.decode(tokenIds)
        } else {
            tokenIds.joinToString(",")
        }

        onProgress?.invoke(1.0f)
        return text
    }

    private fun autoregressiveDecode(
        encoderOutputTensor: Tensor,
        cfg: WhisperConfig
    ): List<Int>? {
        val decoder = decoderModule ?: return null

        val tokens = mutableListOf<Int>()
        tokens.addAll(tokenizer?.getSotTokens("zh") ?: listOf(cfg.sotToken))
        tokens.add(NOTIMESTAMPS_TOKEN)

        val maxTokens = cfg.nTextCtx

        for (step in tokens.size until maxTokens) {
            val tokenArray = tokens.toIntArray()
            val tokenTensor = Tensor.fromBlob(tokenArray, longArrayOf(1, tokenArray.size.toLong()))

            val results: Array<EValue>
            try {
                results = decoder.forward(
                    EValue.from(tokenTensor),
                    EValue.from(encoderOutputTensor)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Decoder forward failed at step $step", e)
                break
            }

            val logitsTensor = results[0].toTensor()
            val logitsData = logitsTensor.dataAsFloatArray

            val vocabSize = logitsData.size / tokenArray.size
            val lastLogits = logitsData.copyOfRange(
                (tokenArray.size - 1) * vocabSize,
                tokenArray.size * vocabSize
            )

            val nextToken = argmax(lastLogits)

            if (tokenizer?.isEndOfText(nextToken) == true || nextToken == cfg.eotToken) {
                break
            }

            tokens.add(nextToken)
        }

        val sotTokens = tokenizer?.getSotTokens("zh") ?: listOf(cfg.sotToken)
        return tokens.filter { it !in sotTokens && it != NOTIMESTAMPS_TOKEN }
    }

    private fun argmax(array: FloatArray): Int {
        var maxIdx = 0
        var maxVal = array[0]
        for (i in 1 until array.size) {
            if (array[i] > maxVal) {
                maxVal = array[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    fun release() {
        encoderModule?.destroy()
        decoderModule?.destroy()
        encoderModule = null
        decoderModule = null
        isLoaded = false
        Log.i(TAG, "Whisper engine released")
    }

    data class WhisperConfig(
        val nMelBins: Int = 80,
        val nAudioCtx: Int = 1500,
        val nTextCtx: Int = 448,
        val dModel: Int = 768,
        val sampleRate: Int = 16000,
        val fftSize: Int = 400,
        val hopLength: Int = 160,
        val windowLength: Int = 400,
        val sotToken: Int = 50258,
        val eotToken: Int = 50257
    ) {
        companion object {
            fun fromFile(file: File): WhisperConfig {
                return try {
                    val json = org.json.JSONObject(file.readText())
                    WhisperConfig(
                        nMelBins = json.optInt("n_mel_bins", 80),
                        nAudioCtx = json.optInt("n_audio_ctx", 1500),
                        nTextCtx = json.optInt("n_text_ctx", 448),
                        dModel = json.optInt("d_model", 768),
                        sampleRate = json.optInt("sample_rate", 16000),
                        fftSize = json.optInt("fft_size", 400),
                        hopLength = json.optInt("hop_length", 160),
                        windowLength = json.optInt("window_length", 400),
                        sotToken = json.optInt("sot_token", 50258),
                        eotToken = json.optInt("eot_token", 50257)
                    )
                } catch (e: Exception) {
                    Log.w("WhisperConfig", "Failed to parse config, using defaults", e)
                    WhisperConfig()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ExecuTorchWhisper"
        private const val NOTIMESTAMPS_TOKEN = 50363
    }
}
