package com.speakin.app.domain.audio

/**
 * 线程安全的音频数据累积器。
 *
 * 用于流式识别场景：录制线程持续追加 ShortArray 块，
 * 推理线程通过 [drain] 批量取出已累积的 FloatArray 数据。
 *
 * 典型用法：
 * ```
 * val buffer = AudioBuffer()
 * // 录制线程
 * buffer.append(pcmChunk)
 * // 推理线程
 * val data = buffer.drain(480000)
 * ```
 */
class AudioBuffer(private val maxSamples: Int = 480000) { // 默认 30s @ 16kHz

    private val lock = Any()
    private val chunks = mutableListOf<FloatArray>()

    /** 当前缓冲区中的总采样数 */
    @Volatile
    var totalSamples: Int = 0
        private set

    /** 缓冲区是否为空 */
    val isEmpty: Boolean
        get() = synchronized(lock) { chunks.isEmpty() }

    /**
     * 追加一个音频块（录制线程调用）。
     *
     * @param pcm ShortArray PCM 数据，16kHz 16-bit
     */
    fun append(pcm: ShortArray) {
        val floatData = FloatArray(pcm.size) { i -> pcm[i].toFloat() / 32768f }
        synchronized(lock) {
            chunks.add(floatData)
            totalSamples += floatData.size
            // 超过最大长度时丢弃最旧的数据（滑窗）
            while (totalSamples > maxSamples && chunks.isNotEmpty()) {
                val removed = chunks.removeAt(0)
                totalSamples -= removed.size
            }
        }
    }

    /**
     * 取出已累积的音频数据（推理线程调用）。
     *
     * @param maxSamples 最多取出的采样数
     * @return 扁平化的 FloatArray，如果缓冲区为空则返回 null
     */
    fun drain(maxSamples: Int = this.maxSamples): FloatArray? {
        synchronized(lock) {
            if (chunks.isEmpty()) return null

            val toCopy = totalSamples.coerceAtMost(maxSamples)
            val result = FloatArray(toCopy)
            var offset = 0

            val iter = chunks.iterator()
            while (iter.hasNext() && offset < toCopy) {
                val chunk = iter.next()
                val copySize = minOf(chunk.size, toCopy - offset)
                System.arraycopy(chunk, 0, result, offset, copySize)
                offset += copySize
                if (copySize == chunk.size) {
                    iter.remove()
                } else {
                    // 部分取出：保留剩余部分
                    chunks[chunks.indexOf(chunk)] = chunk.copyOfRange(copySize, chunk.size)
                }
            }

            totalSamples = chunks.sumOf { it.size }
            return result
        }
    }

    /**
     * 获取当前累积的全部音频，不修改内部状态。
     *
     * @param targetSamples 目标长度，不足时补零
     * @return 长度为 targetSamples 的 FloatArray
     */
    fun toFloatArray(targetSamples: Int = maxSamples): FloatArray {
        synchronized(lock) {
            val result = FloatArray(targetSamples)
            var offset = 0
            for (chunk in chunks) {
                val copySize = minOf(chunk.size, targetSamples - offset)
                System.arraycopy(chunk, 0, result, offset, copySize)
                offset += copySize
                if (offset >= targetSamples) break
            }
            return result
        }
    }

    /**
     * 重置缓冲区（新录音会话开始时调用）。
     */
    fun reset() {
        synchronized(lock) {
            chunks.clear()
            totalSamples = 0
        }
    }
}
