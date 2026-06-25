package com.speakin.app.domain.llm

import android.content.Context
import com.speakin.app.data.local.ModelConfigRepository
import com.speakin.app.domain.service.ModelServiceFacade
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelService: ModelServiceFacade,
    private val configRepo: ModelConfigRepository
) {

    companion object {
        private const val MODEL_FILENAME = "qwen3-0.6b-q4_k_m.gguf"
        private const val ASSET_MODEL_PATH = "models/$MODEL_FILENAME"
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
        return getModelFile().exists() && getModelFile().length() > 100_000_000
    }

    suspend fun checkAndPrepare() {
        val modelFile = getModelFile()

        // Check if model is already loaded in remote process
        if (modelService.isLlmLoaded()) {
            _modelState.value = ModelState(status = ModelStatus.Ready)
            return
        }

        if (modelFile.exists() && modelFile.length() > 100_000_000) {
            loadModel(modelFile)
            return
        }

        // Try to copy from assets (bundled in APK)
        if (copyModelFromAssets()) {
            loadModel(modelFile)
            return
        }

        _modelState.value = ModelState(status = ModelStatus.NotDownloaded)
    }

    /**
     * 从 APK assets 中拷贝 LLM 模型到内部存储。
     * 模型通过 Gradle downloadLlmModel task 打包到 assets/models/ 中。
     */
    private fun copyModelFromAssets(): Boolean {
        return try {
            val modelFile = getModelFile()
            modelFile.parentFile?.mkdirs()
            context.assets.open(ASSET_MODEL_PATH).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            modelFile.exists() && modelFile.length() > 100_000_000
        } catch (e: Exception) {
            false
        }
    }

    suspend fun downloadModel() {
        val modelFile = getModelFile()
        modelFile.parentFile?.mkdirs()

        _modelState.value = ModelState(status = ModelStatus.Downloading, progress = 0f)

        try {
            withContext(Dispatchers.IO) {
                var lastError: String? = null

                for (urlStr in getModelUrls()) {
                    try {
                        val url = URL(urlStr)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 600_000   // 10 min for 400MB
                        connection.setRequestProperty("User-Agent", "SpeakIn/1.0")
                        connection.instanceFollowRedirects = true
                        connection.connect()

                        if (connection.responseCode != 200) {
                            connection.disconnect()
                            throw RuntimeException("HTTP ${connection.responseCode}")
                        }

                        val contentLength = connection.contentLengthLong
                        val inputStream = connection.inputStream
                        val outputStream = FileOutputStream(modelFile)

                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (contentLength > 0) {
                                val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                                _modelState.value = ModelState(
                                    status = ModelStatus.Downloading,
                                    progress = progress
                                )
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        _modelState.value = ModelState(status = ModelStatus.DownloadComplete)
                        return@withContext  // success
                    } catch (e: Exception) {
                        lastError = e.message
                        // Try next URL
                    }
                }

                throw RuntimeException(lastError ?: "All mirror URLs failed")
            }

            loadModel(modelFile)
        } catch (e: Exception) {
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
