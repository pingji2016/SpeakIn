package com.speakin.app.domain.asr

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯 Kotlin 实现的 mel 频谱图计算。
 *
 * 将 16kHz PCM 音频转换为 whisper 模型所需的 80 维 mel 频谱，
 * 无需任何 native 依赖。
 */
class MelSpectrogram(
    private val sampleRate: Int = 16000,
    private val fftSize: Int = 400,
    private val hopLength: Int = 160,
    private val windowLength: Int = 400,
    private val nMelBins: Int = 80,
    private val fMin: Double = 0.0,
    private val fMax: Double = 8000.0
) {

    private val hanningWindow: FloatArray
    private val melFilterBank: Array<FloatArray>

    init {
        hanningWindow = buildHanningWindow()
        melFilterBank = buildMelFilterBank()
    }

    /**
     * 将 16kHz PCM 音频 (FloatArray, [-1.0, 1.0]) 转换为 mel 频谱。
     *
     * @param audio PCM 音频数据，16kHz，归一化到 [-1.0, 1.0]
     * @return mel 频谱，shape: (nMelBins, nFrames)，即 (80, time_steps)
     */
    fun compute(audio: FloatArray): Array<FloatArray> {
        // 分帧 + 加窗
        val nFrames = (audio.size - windowLength) / hopLength + 1
        if (nFrames <= 0) return emptyArray()

        // STFT：每帧计算 FFT 功率谱
        val powerSpectra = mutableListOf<FloatArray>()
        for (frame in 0 until nFrames) {
            val start = frame * hopLength
            val windowed = FloatArray(fftSize) { i ->
                if (start + i < audio.size) audio[start + i] * hanningWindow[i] else 0f
            }

            val spectrum = fftPower(windowed)
            powerSpectra.add(spectrum)
        }

        // 应用 mel 滤波器组 → log mel 频谱
        val nFftBins = fftSize / 2 + 1  // 201
        val melSpectrogram = Array(nMelBins) { FloatArray(powerSpectra.size) }

        for (t in powerSpectra.indices) {
            for (m in 0 until nMelBins) {
                var sum = 0.0
                for (k in 0 until nFftBins) {
                    sum += powerSpectra[t][k].toDouble() * melFilterBank[m][k]
                }
                // 取 log（加小量避免 log(0)）
                melSpectrogram[m][t] = log10(max(sum, 1e-10)).toFloat()
            }
        }

        return melSpectrogram
    }

    /**
     * 将 mel 频谱展平为一维数组，供 ExecuTorch 输入。
     */
    fun flatten(mel: Array<FloatArray>): FloatArray {
        if (mel.isEmpty()) return floatArrayOf()
        val nFrames = mel[0].size
        val result = FloatArray(nMelBins * nFrames)
        for (m in 0 until nMelBins) {
            for (t in 0 until nFrames) {
                result[m * nFrames + t] = mel[m][t]
            }
        }
        return result
    }

    // ============================================================
    // Hanning 窗
    // ============================================================

    private fun buildHanningWindow(): FloatArray {
        return FloatArray(windowLength) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (windowLength - 1)))).toFloat()
        }
    }

    // ============================================================
    // FFT 功率谱（纯 Kotlin 实现，radix-2 Cooley-Tukey）
    // ============================================================

    private fun fftPower(windowed: FloatArray): FloatArray {
        // 找到 >= fftSize 的最小 2 的幂
        val n = nextPowerOf2(fftSize)

        // 准备复数数组
        val real = FloatArray(n) { if (it < fftSize) windowed[it] else 0f }
        val imag = FloatArray(n) { 0f }

        // 位反转排序
        bitReverse(real, imag)

        // Cooley-Tukey FFT
        var step = 1
        while (step < n) {
            val halfStep = step
            step = step shl 1

            for (k in 0 until n step step) {
                for (i in 0 until halfStep) {
                    val angle = -PI * i / halfStep
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()

                    val idx1 = k + i
                    val idx2 = k + i + halfStep

                    val tr = wr * real[idx2] - wi * imag[idx2]
                    val ti = wr * imag[idx2] + wi * real[idx2]

                    real[idx2] = real[idx1] - tr
                    imag[idx2] = imag[idx1] - ti
                    real[idx1] += tr
                    imag[idx1] += ti
                }
            }
        }

        // 功率谱：|X|^2 / n (只取前半段)
        val halfN = n / 2 + 1
        return FloatArray(halfN) { i ->
            val re = real[i]
            val im = imag[i]
            (re * re + im * im) / n
        }
    }

    private fun nextPowerOf2(n: Int): Int {
        var x = 1
        while (x < n) x = x shl 1
        return x
    }

    private fun bitReverse(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                // swap real[i] and real[j]
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var m = n shr 1
            while (m >= 1 && j and m != 0) {
                j = j xor m
                m = m shr 1
            }
            j = j xor m
        }
    }

    // ============================================================
    // Mel 滤波器组
    // ============================================================

    private fun buildMelFilterBank(): Array<FloatArray> {
        val nFftBins = fftSize / 2 + 1

        // 将频率范围映射到 mel 刻度
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // nMelBins + 2 个等间距的 mel 点
        val melPoints = FloatArray(nMelBins + 2) { i ->
            val mel = melMin + (melMax - melMin) * i / (nMelBins + 1)
            melToHz(mel.toDouble()).toFloat()
        }

        // 将 mel 点映射到 FFT bin 索引
        val binIndices = IntArray(nMelBins + 2) { i ->
            (melPoints[i] * nFftBins / (sampleRate / 2)).toInt()
                .coerceIn(0, nFftBins - 1)
        }

        // 构建滤波器组
        val filterBank = Array(nMelBins) { FloatArray(nFftBins) }

        for (m in 0 until nMelBins) {
            val left = binIndices[m]
            val center = binIndices[m + 1]
            val right = binIndices[m + 2]

            for (k in left until center) {
                filterBank[m][k] =
                    (k - left).toFloat() / (center - left).toFloat()
            }
            for (k in center until right) {
                filterBank[m][k] =
                    (right - k).toFloat() / (right - center).toFloat()
            }
        }

        return filterBank
    }

    /**
     * 归一化 mel 频谱（均值归零、方差归一）。
     */
    fun normalize(mel: Array<FloatArray>): Array<FloatArray> {
        if (mel.isEmpty()) return mel
        val nFrames = mel[0].size

        // 计算全局均值和标准差
        var sum = 0.0
        var count = 0
        for (m in mel) {
            for (v in m) {
                sum += v
                count++
            }
        }
        val mean = (sum / count).toFloat()

        var varSum = 0.0
        for (m in mel) {
            for (v in m) {
                val diff = v - mean
                varSum += diff * diff
            }
        }
        val std = sqrt(varSum / count).toFloat().coerceAtLeast(1e-10f)

        // 归一化
        return Array(mel.size) { m ->
            FloatArray(nFrames) { t ->
                (mel[m][t] - mean) / std
            }
        }
    }
}
