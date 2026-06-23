package com.speakin.app.domain.asr

/**
 * 流式识别会话。
 *
 * 管理一次完整的"录音→渐进式识别→最终结果"流程。
 *
 * 典型用法：
 * ```
 * val session = asrEngine.startStreaming(callback)
 * audioRecorder.setChunkListener { chunk -> session.feedAudio(chunk) }
 * // ... 用户停止录音 ...
 * audioRecorder.setChunkListener(null)
 * session.finish()
 * ```
 */
interface StreamingAsrSession {
    /**
     * 喂入一个音频块（录制线程调用，必须快速返回）。
     */
    fun feedAudio(chunk: ShortArray)

    /**
     * 结束音频喂入，触发最后一次完整识别。
     * 调用后不再接受新的音频块。
     */
    fun finish()

    /**
     * 取消识别，立即释放资源。
     */
    fun cancel()

    /** 当前是否正在执行推理 */
    val isRunning: Boolean
}
