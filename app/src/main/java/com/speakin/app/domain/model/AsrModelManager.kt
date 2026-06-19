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
        private val MODEL_FILES = listOf(
            "whisper_pre_enc.pte",
            "whisper_decoder.pte",
            "tokenizer.json"
        )
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
        return MODEL_FILES.all { File(dir, it).exists() }
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
     * 下载所有模型文件（备用方案：当 asset pack 不可用时）。
     */
    /**
     * 模型文件已通过 Python 脚本本地导出（scripts/export_whisper_cpu.py），
     * 然后通过 Gradle task 或手动复制到 assets。
     * 此方法保留用于未来可能的远程下载场景。
     */
    suspend fun downloadAll(onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        Log.w(TAG, "Models are exported locally via Python script, not downloaded")
        prepareFromAssets()
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
