package com.speakin.app.domain.polish

import android.util.Log
import com.speakin.app.domain.llm.ModelManager
import com.speakin.app.domain.service.ModelServiceFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 润色引擎实现 — 通过 AIDL 调用远程 :model 进程中的 LLM。
 */
@Singleton
class PolishEngineImpl @Inject constructor(
    private val modelService: ModelServiceFacade,
    private val modelManager: ModelManager
) : PolishEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun polish(text: String, callback: PolishEngine.Callback) {
        if (text.isBlank()) {
            callback.onResult(text)
            return
        }

        // LLM 模型未下载时直接返回原文，避免无意义的跨进程调用
        val modelFile = modelManager.getModelFile()
        if (!modelFile.exists()) {
            Log.d(TAG, "LLM model not downloaded, skipping polish")
            callback.onResult(text)
            return
        }

        scope.launch {
            try {
                modelService.bind()
                modelService.loadLlm(modelFile)

                val result = modelService.complete(
                    "请润色以下文字，修正标点和语病，使其更通顺自然。直接输出润色结果，不要添加任何额外说明。\n\n$text"
                )

                if (result.isNotBlank()) callback.onResult(result)
                else callback.onResult(text)

            } catch (e: Exception) {
                Log.e(TAG, "Polish failed", e)
                callback.onResult(text) // 降级返回原文
            }
        }
    }

    override fun isModelLoaded(): Boolean = true

    override fun release() {
        // 远程资源由 Service 管理
    }

    companion object {
        private const val TAG = "PolishEngineImpl"
    }
}
