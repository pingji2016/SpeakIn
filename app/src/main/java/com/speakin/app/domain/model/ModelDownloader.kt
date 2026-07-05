package com.speakin.app.domain.model

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用模型文件下载器。
 *
 * 封装 HTTP 下载、多 URL 镜像重试、进度回调等公共逻辑，
 * 供 AsrModelManager / ModelManager 等使用，避免重复代码。
 */
@Singleton
class ModelDownloader @Inject constructor() {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 8192
        private const val DEFAULT_CONNECT_TIMEOUT = 30_000
        private const val DEFAULT_READ_TIMEOUT = 300_000
    }

    data class DownloadResult(
        val success: Boolean,
        val error: String? = null
    )

    /**
     * 从多个镜像 URL 下载单个文件。
     * 依次尝试每个 URL，第一个成功即返回。
     */
    suspend fun downloadSingle(
        urls: List<String>,
        destFile: File,
        minSize: Long = 0,
        connectTimeout: Int = DEFAULT_CONNECT_TIMEOUT,
        readTimeout: Int = DEFAULT_READ_TIMEOUT,
        onProgress: ((Float) -> Unit)? = null
    ): DownloadResult {
        destFile.parentFile?.mkdirs()

        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = connectTimeout
                connection.readTimeout = readTimeout
                connection.setRequestProperty("User-Agent", "SpeakIn/1.0")
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    continue
                }

                val contentLength = connection.contentLengthLong
                var totalRead = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0 && onProgress != null) {
                                onProgress(totalRead.toFloat() / contentLength)
                            }
                        }
                        output.flush()
                    }
                }
                connection.disconnect()

                if (destFile.exists() && destFile.length() >= minSize) {
                    onProgress?.invoke(1f)
                    return DownloadResult(success = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download failed from $urlStr: ${e.message}")
            }
        }

        return DownloadResult(
            success = false,
            error = "All mirrors failed for ${destFile.name}"
        )
    }

    /**
     * 从一组基础 URL 下载多个文件。
     * 每个文件 = baseUrl/filename，逐个下载并报告整体进度。
     */
    suspend fun downloadMultiple(
        baseUrls: List<String>,
        fileNames: List<String>,
        outputDir: File,
        minFileSize: Long = 0,
        connectTimeout: Int = DEFAULT_CONNECT_TIMEOUT,
        readTimeout: Int = DEFAULT_READ_TIMEOUT,
        onOverallProgress: ((Float) -> Unit)? = null,
        onFileError: ((String) -> Unit)? = null
    ): DownloadResult {
        outputDir.mkdirs()
        var completed = 0
        val total = fileNames.size

        for (fileName in fileNames) {
            val dest = File(outputDir, fileName)
            if (dest.exists() && dest.length() >= minFileSize) {
                completed++
                onOverallProgress?.invoke(completed.toFloat() / total)
                continue
            }

            val fileUrls = baseUrls.map { "$it/$fileName" }

            val result = downloadSingle(
                urls = fileUrls,
                destFile = dest,
                minSize = minFileSize,
                connectTimeout = connectTimeout,
                readTimeout = readTimeout,
                onProgress = { fileProgress ->
                    onOverallProgress?.invoke((completed + fileProgress) / total)
                }
            )

            if (!result.success) {
                onFileError?.invoke(fileName)
                return DownloadResult(
                    success = false,
                    error = "Failed to download $fileName: ${result.error}"
                )
            }

            completed++
            onOverallProgress?.invoke(completed.toFloat() / total)
        }

        return DownloadResult(success = true)
    }
}
