package com.speakin.app.domain.model

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AsrModelManager"
        private const val MODEL_PTE = "whisper_tiny_xnnpack_fp32.pte"
        private const val MODEL_TOKENIZER = "tokenizer.json"
        private const val MODEL_DIR_NAME = "whisper"
    }

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

    /**
     * 获取模型文件存放目录：context.filesDir/whisper/
     */
    fun getModelDir(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    /**
     * 检查模型是否已就绪（所有必需文件存在）。
     */
    fun isModelReady(): Boolean {
        val dir = getModelDir()
        return File(dir, MODEL_PTE).exists() &&
                File(dir, MODEL_TOKENIZER).exists()
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

            val files = listOf(MODEL_PTE, MODEL_TOKENIZER)
            for (filename in files) {
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
     * 下载所有模型文件（备用方案：当 asset pack 不可用时）。
     */
    suspend fun downloadAll(onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        val dir = getModelDir()
        dir.mkdirs()

        try {
            val files = listOf(
                MODEL_PTE to "https://hf-mirror.com/software-mansion/react-native-executorch-whisper-tiny/resolve/main/xnnpack/whisper_tiny_xnnpack_fp32.pte",
                MODEL_TOKENIZER to "https://hf-mirror.com/software-mansion/react-native-executorch-whisper-small/resolve/main/tokenizer.json"
            )

            var completed = 0
            val total = files.size.toFloat()

            for ((filename, url) in files) {
                val file = File(dir, filename)
                if (file.exists() && file.length() > 1000) {
                    Log.i(TAG, "Skipping existing file: $filename")
                    completed++
                    onProgress?.invoke(completed / total)
                    continue
                }

                Log.i(TAG, "Downloading: $filename")
                downloadFile(url, file)
                completed++
                onProgress?.invoke(completed / total)
            }

            Log.i(TAG, "All model files downloaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    private fun downloadFile(urlStr: String, file: File) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(file)
        val buffer = ByteArray(8192)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }

        outputStream.flush()
        outputStream.close()
        inputStream.close()
    }
}
