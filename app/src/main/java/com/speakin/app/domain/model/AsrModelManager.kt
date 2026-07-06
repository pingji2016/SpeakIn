package com.speakin.app.domain.model

import android.content.Context
import android.util.Log
import com.speakin.app.data.local.ModelConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 模型管理器。
 *
 * 管理 whisper .pte 模型的获取与文件定位。
 * 模型通过 [ModelDownloader] 下载，使用 [ModelConfigRepository] 中的 URL 配置。
 */
@Singleton
class AsrModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepo: ModelConfigRepository,
    private val downloader: ModelDownloader
) {

    companion object {
        private const val TAG = "AsrModelManager"
        private val MODEL_FILES = listOf(
            "whisper_pre_enc.pte",
            "whisper_decoder.pte",
            "tokenizer.json"
        )
        private const val MODEL_DIR_NAME = "whisper"
        private const val MIN_FILE_SIZE = 1000L
    }

    /** 获取有效的 ASR 下载基础 URL 列表：用户自定义 > 内置默认 */
    private fun getDownloadBaseUrls(): List<String> = configRepo.getEffectiveAsrUrls()

    data class ModelState(
        val status: Status = Status.NotDownloaded,
        val progress: Float = 0f,
        val error: String? = null
    )

    enum class Status {
        NotDownloaded,
        Downloading,
        Ready,
        Error
    }

    private val _modelState = MutableStateFlow(ModelState())
    /** ASR 模型的当前状态（供 UI 观察） */
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /**
     * 获取模型文件存放目录：context.filesDir/whisper/
     */
    fun getModelDir(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    /**
     * 检查模型是否已就绪（所有必需文件存在且非空）。
     */
    fun isModelReady(): Boolean {
        val dir = getModelDir()
        return MODEL_FILES.all { File(dir, it).exists() && File(dir, it).length() > MIN_FILE_SIZE }
    }

    /**
     * 确保模型可用。
     *
     * 已下载 → 直接使用；否则返回 false（调用方触发网络下载）。
     */
    suspend fun ensureModelAvailable(): Boolean {
        if (isModelReady()) {
            _modelState.value = ModelState(status = Status.Ready, progress = 1f)
            return true
        }

        _modelState.value = ModelState(status = Status.NotDownloaded)
        return false
    }

    /**
     * 从网络下载所有模型文件。
     * 需要 [ensureModelAvailable] 返回 false 时调用。
     */
    suspend fun downloadModel(): Boolean {
        val dir = getModelDir()
        _modelState.value = ModelState(status = Status.Downloading, progress = 0f)

        val result = downloader.downloadMultiple(
            baseUrls = getDownloadBaseUrls(),
            fileNames = MODEL_FILES,
            outputDir = dir,
            minFileSize = MIN_FILE_SIZE,
            onOverallProgress = { progress ->
                _modelState.value = ModelState(status = Status.Downloading, progress = progress)
            },
            onFileError = { fileName ->
                Log.w(TAG, "Failed to download file: $fileName")
            }
        )

        return if (result.success && isModelReady()) {
            _modelState.value = ModelState(status = Status.Ready, progress = 1f)
            Log.i(TAG, "ASR model download complete. Dir: ${dir.absolutePath}")
            true
        } else {
            _modelState.value = ModelState(
                status = Status.Error,
                error = result.error ?: "模型文件校验失败"
            )
            false
        }
    }

}
