package com.speakin.app.domain.asr

import android.util.Log
import com.speakin.app.domain.service.ModelServiceFacade
import com.speakin.app.domain.model.AsrModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 引擎实现 — 通过 AIDL 调用远程 :model 进程。
 *
 * 模型推理在独立进程中执行，崩溃不影响 UI。
 */
@Singleton
class AsrEngineImpl @Inject constructor(
    private val modelService: ModelServiceFacade,
    private val asrModelManager: AsrModelManager
) : AsrEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun isModelLoaded(): Boolean {
        // 模型加载状态在远程进程中，这里简化处理
        return asrModelManager.isModelReady()
    }

    override fun transcribe(audioFile: File, callback: AsrEngine.Callback) {
        callback.onProgress(0f)

        scope.launch {
            try {
                // 1. 确保 ASR 模型已加载
                if (!asrModelManager.isModelReady()) {
                    callback.onError("ASR 模型未就绪")
                    return@launch
                }

                // 2. 连接远程服务
                modelService.bind()

                // 3. 加载 ASR 模型（如果尚未加载）
                val loaded = modelService.loadAsr(asrModelManager.getModelDir())
                if (!loaded) {
                    callback.onError("ASR 模型加载失败")
                    return@launch
                }

                // 4. 远程转写
                callback.onProgress(0.05f)
                val result = modelService.transcribe(audioFile) { progress ->
                    callback.onProgress(progress)
                }

                result.fold(
                    onSuccess = { text -> callback.onResult(text) },
                    onFailure = { e -> callback.onError(e.message ?: "转写失败") }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcribe failed", e)
                callback.onError("转写出错: ${e.message}")
            }
        }
    }

    override fun release() {
        // 远程资源由 Service 管理
    }

    companion object {
        private const val TAG = "AsrEngineImpl"
    }
}
