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
 * 管理 whisper .pte 模型的下载与文件定位。
 * 模型文件可通过导出脚本在 PC 上生成，然后通过 ADB 推送，或在 App 内下载。
 */
@Singleton
class AsrModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AsrModelManager"

        // 默认模型下载地址（HuggingFace 镜像站）
        private const val ENCODER_URL =
            "https://hf-mirror.com/davercn/whisper-small-executorch/resolve/main/whisper_encoder.pte"
        private const val DECODER_URL =
            "https://hf-mirror.com/davercn/whisper-small-executorch/resolve/main/whisper_decoder.pte"
        private const val CONFIG_URL =
            "https://hf-mirror.com/davercn/whisper-small-executorch/resolve/main/whisper_config.json"
        private const val TOKENIZER_URL =
            "https://hf-mirror.com/davercn/whisper-small-executorch/resolve/main/tokenizer.json"

        // 本地缓存目录名
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
        return File(dir, "whisper_encoder.pte").exists() &&
                File(dir, "whisper_decoder.pte").exists() &&
                File(dir, "whisper_config.json").exists()
    }

    /**
     * 下载所有模型文件。
     */
    suspend fun downloadAll(onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        val dir = getModelDir()
        dir.mkdirs()

        try {
            val files = listOf(
                "whisper_encoder.pte" to ENCODER_URL,
                "whisper_decoder.pte" to DECODER_URL,
                "whisper_config.json" to CONFIG_URL,
                "tokenizer.json" to TOKENIZER_URL
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
