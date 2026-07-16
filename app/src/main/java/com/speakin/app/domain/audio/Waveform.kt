package com.speakin.app.domain.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * 波形峰值计算工具。
 */
object Waveform {

    /**
     * 将 PCM 数据降采样为 N 个桶的归一化峰值（0f..1f），用于波形绘制。
     * 每个桶取该范围内采样绝对值的最大值。
     */
    fun computePeaks(data: WavData, buckets: Int = 400): FloatArray {
        val totalFrames = data.pcm.size / data.channels
        if (totalFrames == 0 || buckets <= 0) return FloatArray(0)

        val peaks = FloatArray(buckets)
        val framesPerBucket = max(1, totalFrames / buckets)

        for (b in 0 until buckets) {
            val startFrame = b * totalFrames / buckets
            val endFrame = ((b + 1) * totalFrames / buckets).coerceAtMost(totalFrames)
            var peak = 0
            var frame = startFrame
            // 长音频按步长抽样，避免全量扫描过慢
            val step = max(1, (endFrame - startFrame) / framesPerBucket.coerceAtMost(256))
            while (frame < endFrame) {
                // 多声道取第一个声道
                peak = max(peak, abs(data.pcm[frame * data.channels].toInt()))
                frame += step
            }
            peaks[b] = (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
        }
        return peaks
    }
}
