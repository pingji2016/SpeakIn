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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 模型管理器。
 *
 * 管理 whisper .pte 模型的获取与文件定位。
 * 模型通过 Play Asset Delivery 的 install-time 资产包提供，
 * 首次启动时从 assets 复制到内部存储。
 */
@Singleton
class AsrModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepo: ModelConfigRepository
) {

    companion object {
        private const val TAG = "AsrModelManager"
        private val MODEL_FILES = listOf(
            "whisper_pre_enc.pte",
            "whisper_decoder.pte",
            "tokenizer.json"
        )
        private const val MODEL_DIR_NAME = "whisper"
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
        return MODEL_FILES.all { File(dir, it).exists() && File(dir, it).length() > 1000 }
    }

    /**
     * 确保模型可用。
     *
     * 优先级：
     * 1. 已下载 → 直接使用
     * 2. APK assets → 复制使用（兼容旧版升级用户）
     * 3. 都没有 → 返回 false（调用方触发网络下载）
     */
    suspend fun ensureModelAvailable(): Boolean {
        // 更新状态
        if (isModelReady()) {
            _modelState.value = ModelState(status = Status.Ready, progress = 1f)
            return true
        }

        _modelState.value = ModelState(status = Status.Downloading, progress = 0f)

        // 尝试 assets 复制（兼容升级用户）
        val assetsReady = prepareFromAssets()
        if (assetsReady) {
            _modelState.value = ModelState(status = Status.Ready, progress = 1f)
            return true
        }

        _modelState.value = ModelState(status = Status.NotDownloaded)
        return false
    }

    /**
     * 从网络下载所有模型文件。
     * 需要 ensureModelAvailable() 返回 false 时调用。
     */
    suspend fun downloadModel(): Boolean {
        val dir = getModelDir()
        dir.mkdirs()

        _modelState.value = ModelState(status = Status.Downloading, progress = 0f)

        var totalDownloaded = 0L
        val totalFiles = MODEL_FILES.size

        return try {
            withContext(Dispatchers.IO) {
                for ((index, filename) in MODEL_FILES.withIndex()) {

                    val dest = File(dir, filename)

                    // 已存在且大小合理则跳过
                    if (dest.exists() && dest.length() > 1000) {
                        totalDownloaded++
                        val progress = totalDownloaded.toFloat() / totalFiles
                        _modelState.value = ModelState(status = Status.Downloading, progress = progress)
                        continue
                    }

                    // 尝试每个 URL 镜像
                    var downloadSuccess = false
                    for (baseUrl in getDownloadBaseUrls()) {
                        try {
                            val url = "$baseUrl/$filename"
                            Log.i(TAG, "Downloading $url → ${dest.absolutePath}")
                            downloadFile(url, dest)
                            if (dest.exists() && dest.length() > 1000) {
                                downloadSuccess = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to download from $baseUrl: ${e.message}")
                        }
                    }

                    if (!downloadSuccess) {
                        _modelState.value = ModelState(
                            status = Status.Error,
                            error = "下载 $filename 失败，请检查网络后重试"
                        )
                        return@withContext false
                    }

                    totalDownloaded++
                    val progress = totalDownloaded.toFloat() / totalFiles
                    _modelState.value = ModelState(status = Status.Downloading, progress = progress)
                }

                if (isModelReady()) {
                    _modelState.value = ModelState(status = Status.Ready, progress = 1f)
                    true
                } else {
                    _modelState.value = ModelState(
                        status = Status.Error,
                        error = "模型文件校验失败"
                    )
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel failed", e)
            _modelState.value = ModelState(
                status = Status.Error,
                error = e.message ?: "下载失败"
            )
            false
        }
    }

    /**
     * 从 asset pack assets 复制模型文件到内部存储。
     * 首次启动时调用，如果内部存储已有则跳过。
     */
    suspend fun prepareFromAssets(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Preparing ASR model from assets...")
        try {
            val dir = getModelDir()
            dir.mkdirs()

            for (filename in MODEL_FILES) {
                val dest = File(dir, filename)
                if (dest.exists() && dest.length() > 1000) {
                    Log.i(TAG, "Model file already exists: $filename (${dest.length() / 1024 / 1024} MB)")
                    continue
                }

                Log.i(TAG, "Copying $filename from assets to ${dest.absolutePath}")

                // Try asset pack path → bundled APK assets → direct assets fallback
                val assetPaths = listOf(
                    "speakin_assets/$filename",    // Play Asset Delivery asset pack
                    "models/whisper/$filename",    // Bundled in APK assets/models/whisper/
                    filename                         // Direct assets fallback
                )

                var copied = false
                for (assetPath in assetPaths) {
                    try {
                        context.assets.open(assetPath).use { input ->
                            FileOutputStream(dest).use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "Copied from assets: $assetPath → $filename (${dest.length() / 1024 / 1024} MB)")
                        copied = true
                        break
                    } catch (_: Exception) {
                        // Try next path
                    }
                }

                if (!copied) {
                    Log.w(TAG, "Model file not found in any asset path: $filename. " +
                            "Run './gradlew downloadAllModels' to download models first.")
                }
            }

            val ready = isModelReady()
            Log.i(TAG, "ASR model preparation done. Ready: $ready. " +
                    "Dir: ${dir.absolutePath}, files: ${dir.listFiles()?.joinToString { it.name } ?: "none"}")
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare ASR model from assets", e)
            false
        }
    }

    /**
     * 下载所有模型文件（从网络下载，用于首次使用或恢复）。
     * 优先使用 ensureModelAvailable()（会先尝试 assets），
     * 此方法直接走网络下载路径。
     */
    suspend fun downloadAll(onProgress: ((Float) -> Unit)? = null): Boolean {
        Log.i(TAG, "Starting model download...")
        return downloadModel()
    }

    private fun downloadFile(urlStr: String, file: File) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000  // 5 min for large files
        connection.setRequestProperty("User-Agent", "SpeakIn/1.0")
        connection.instanceFollowRedirects = true
        connection.connect()

        if (connection.responseCode != 200) {
            connection.disconnect()
            throw RuntimeException("HTTP ${connection.responseCode} for $urlStr")
        }

        connection.inputStream.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }
        }
        connection.disconnect()
    }
}
