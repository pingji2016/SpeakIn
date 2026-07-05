package com.speakin.app.domain.llm

import android.content.Context
import android.util.Log
import com.speakin.app.data.local.ModelConfigRepository
import com.speakin.app.domain.model.ModelDownloader
import com.speakin.app.domain.service.ModelServiceFacade
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ModelState(
    val status: ModelStatus = ModelStatus.NotDownloaded,
    val progress: Float = 0f,
    val error: String? = null
)

enum class ModelStatus {
    NotDownloaded,
    Downloading,
    DownloadComplete,
    Loading,
    Ready,
    Error
}

/**
 * LLM 模型管理器。
 *
 * 管理 qwen3 GGUF 模型的下载与加载。
 * 模型通过 [ModelDownloader] 下载到内部存储，然后通过 [ModelServiceFacade] 加载到远程进程。
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelService: ModelServiceFacade,
    private val configRepo: ModelConfigRepository,
    private val downloader: ModelDownloader
) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_FILENAME = "qwen3-0.6b-q4_k_m.gguf"
        private const val MIN_FILE_SIZE = 100_000_000L // 100MB
    }

    private val _modelState = MutableStateFlow(ModelState())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** 获取有效的 LLM 下载 URL 列表：用户自定义 > 内置默认 */
    private fun getModelUrls(): List<String> = configRepo.getEffectiveLlmUrls()

    fun getModelFile(): File {
        return File(context.filesDir, "models/$MODEL_FILENAME")
    }

    fun isModelReady(): Boolean {
        return _modelState.value.status == ModelStatus.Ready
    }

    fun isModelDownloaded(): Boolean {
        return getModelFile().exists() && getModelFile().length() > MIN_FILE_SIZE
    }

    suspend fun checkAndPrepare() {
        val modelFile = getModelFile()

        // Check if model is already loaded in remote process
        if (modelService.isLlmLoaded()) {
            _modelState.value = ModelState(status = ModelStatus.Ready)
            return
        }

        if (modelFile.exists() && modelFile.length() > MIN_FILE_SIZE) {
            loadModel(modelFile)
            return
        }

        _modelState.value = ModelState(status = ModelStatus.NotDownloaded)
    }

    suspend fun downloadModel() {
        val modelFile = getModelFile()
        modelFile.parentFile?.mkdirs()

        _modelState.value = ModelState(status = ModelStatus.Downloading, progress = 0f)

        try {
            val result = downloader.downloadSingle(
                urls = getModelUrls(),
                destFile = modelFile,
                minSize = MIN_FILE_SIZE,
                connectTimeout = 15_000,
                readTimeout = 600_000,
                onProgress = { progress ->
                    _modelState.value = ModelState(status = ModelStatus.Downloading, progress = progress)
                }
            )

            if (result.success) {
                _modelState.value = ModelState(status = ModelStatus.DownloadComplete)
                loadModel(modelFile)
            } else {
                _modelState.value = ModelState(
                    status = ModelStatus.Error,
                    error = result.error ?: "Download failed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel failed", e)
            _modelState.value = ModelState(
                status = ModelStatus.Error,
                error = e.message ?: "Download failed"
            )
        }
    }

    private suspend fun loadModel(modelFile: File) {
        _modelState.value = ModelState(status = ModelStatus.Loading)

        try {
            // Bind to model service and load via AIDL (runs in :model process)
            modelService.bind()
            val success = modelService.loadLlm(modelFile)
            if (success) {
                _modelState.value = ModelState(status = ModelStatus.Ready)
            } else {
                _modelState.value = ModelState(
                    status = ModelStatus.Error,
                    error = "Failed to load model"
                )
            }
        } catch (e: Exception) {
            _modelState.value = ModelState(
                status = ModelStatus.Error,
                error = "Load failed: ${e.message}"
            )
        }
    }

    fun release() {
        modelService.releaseAll()
        _modelState.value = ModelState(status = ModelStatus.NotDownloaded)
    }
}
