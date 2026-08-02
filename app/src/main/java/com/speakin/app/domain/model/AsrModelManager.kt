package com.speakin.app.domain.model

import android.content.Context
import android.util.Log
import com.speakin.app.data.local.ModelConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
        private const val MIN_FILE_SIZE = 100_000L  // 100KB — catches truncated downloads
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
     * 获取模型文件存放目录。
     * 优先使用用户自定义存储路径，否则使用默认内部存储。
     */
    fun getModelDir(): File {
        val customPath = configRepo.getModelStoragePath()
        return if (customPath != null) {
            File(customPath, MODEL_DIR_NAME)
        } else {
            File(context.filesDir, MODEL_DIR_NAME)
        }
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
     * 优先级：filesDir 已有 → APK assets 解压 → 返回 false（调用方触发网络下载）。
     */
    suspend fun ensureModelAvailable(): Boolean {
        if (isModelReady()) {
            _modelState.value = ModelState(status = Status.Ready, progress = 1f)
            return true
        }

        // 尝试从 APK assets 中解压（首次安装时模型已打包在 APK 中）
        val copied = withContext(Dispatchers.IO) { copyFromAssets() }
        if (copied) {
            _modelState.value = ModelState(status = Status.Ready, progress = 1f)
            Log.i(TAG, "ASR models extracted from APK assets")
            return true
        }

        _modelState.value = ModelState(status = Status.NotDownloaded)
        return false
    }

    /**
     * 从 APK assets/models/whisper/ 复制模型文件到 filesDir。
     * 跳过已存在且大小有效的文件；任一文件复制失败则清理所有目标文件。
     */
    private fun copyFromAssets(): Boolean {
        val dir = getModelDir()
        dir.mkdirs()

        return try {
            val assetDir = "models/$MODEL_DIR_NAME"
            val assetFiles = context.assets.list(assetDir) ?: emptyArray()

            for (fileName in MODEL_FILES) {
                val destFile = File(dir, fileName)
                // 跳过已存在且有效的文件
                if (destFile.exists() && destFile.length() > MIN_FILE_SIZE) continue

                val assetPath = "$assetDir/$fileName"
                if (!copyAssetFile(assetPath, destFile)) {
                    // 复制失败，清理所有已复制的文件，避免残留不完整文件
                    Log.w(TAG, "Failed to copy $fileName from assets, cleaning up")
                    for (f in MODEL_FILES) {
                        File(dir, f).delete()
                    }
                    return false
                }
            }

            isModelReady()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract models from assets: ${e.message}")
            false
        }
    }

    /**
     * 从 APK assets 复制单个文件到目标路径。
     */
    private fun copyAssetFile(assetPath: String, destFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            destFile.exists() && destFile.length() > MIN_FILE_SIZE
        } catch (e: Exception) {
            Log.w(TAG, "copyAssetFile failed: $assetPath → ${destFile.name}: ${e.message}")
            false
        }
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
