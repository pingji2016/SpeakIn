package com.speakin.app.domain.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频处理器 — 音频编辑管线中的单个处理步骤。
 * 未来的声调调整（变声）实现此接口后即可插入管线。
 */
interface AudioProcessor {
    fun process(input: WavData): WavData
}

/**
 * 裁剪处理器：保留 [startMs, endMs) 范围。
 */
class TrimProcessor(
    private val startMs: Long,
    private val endMs: Long
) : AudioProcessor {
    override fun process(input: WavData): WavData =
        WavFile.sliceByMs(input, startMs, endMs)
}

/**
 * 音频编辑引擎：按顺序应用一组处理器。
 */
@Singleton
class AudioEditEngine @Inject constructor() {

    fun apply(input: WavData, processors: List<AudioProcessor>): WavData =
        processors.fold(input) { data, processor -> processor.process(data) }
}
