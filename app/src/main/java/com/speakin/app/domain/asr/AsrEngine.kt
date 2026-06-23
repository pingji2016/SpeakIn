package com.speakin.app.domain.asr

import java.io.File

interface AsrEngine {

    // ─── 现有 API（保留，向后兼容） ───

    interface Callback {
        fun onResult(text: String)
        fun onProgress(progress: Float)
        fun onError(error: String)
    }

    /** 完整文件转写（保留用于最终转写和回退场景） */
    fun transcribe(audioFile: File, callback: Callback)

    fun isModelLoaded(): Boolean

    fun release()

    // ─── 流式 API（新增） ───

    /**
     * 渐进式识别结果。
     */
    data class StreamingResult(
        /** 完整文本 */
        val text: String,
        /** 稳定前缀长度（字符数），[0, stableLen) 为已稳定部分 */
        val stableLen: Int,
        /** 是否与上次结果完全相同（连续两次一致 → 稳定） */
        val isStable: Boolean
    )

    /**
     * 流式识别回调。
     */
    interface StreamingCallback {
        /** 部分识别结果（周期性更新，供 UI 展示 live caption） */
        fun onPartialResult(result: StreamingResult)
        /** 最终识别结果（finish() 后的完整转写） */
        fun onFinalResult(text: String)
        /** 识别出错 */
        fun onError(error: String)
    }

    /**
     * 启动一个流式识别会话。
     * 调用方通过 [StreamingAsrSession.feedAudio] 持续喂入音频，
     * 引擎周期性触发识别并通过 [StreamingCallback.onPartialResult] 回传渐进式结果。
     */
    fun startStreaming(callback: StreamingCallback): StreamingAsrSession
}
