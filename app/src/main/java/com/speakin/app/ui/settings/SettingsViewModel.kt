package com.speakin.app.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.ModelConfigRepository
import com.speakin.app.domain.llm.ModelManager
import com.speakin.app.domain.llm.ModelState
import com.speakin.app.domain.model.AsrModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmModelManager: ModelManager,
    private val asrModelManager: AsrModelManager,
    private val configRepo: ModelConfigRepository
) : ViewModel() {

    val llmState: StateFlow<ModelState> = llmModelManager.modelState
    val asrState: StateFlow<AsrModelManager.ModelState> = asrModelManager.modelState
    val asrReady: Boolean get() = asrModelManager.isModelReady()

    private val _modelStoragePath = MutableStateFlow(getDisplayPath())
    val modelStoragePath: StateFlow<String> = _modelStoragePath.asStateFlow()

    private fun getDisplayPath(): String {
        return configRepo.getModelStoragePath() ?: context.filesDir.absolutePath
    }

    init {
        viewModelScope.launch {
            llmModelManager.checkAndPrepare()
        }
        viewModelScope.launch {
            asrModelManager.ensureModelAvailable()
        }
    }

    fun downloadLlmModel() {
        viewModelScope.launch {
            llmModelManager.downloadModel()
        }
    }

    fun downloadAsrModel() {
        viewModelScope.launch {
            asrModelManager.downloadModel()
        }
    }

    fun deleteLlmModel() {
        val file = llmModelManager.getModelFile()
        if (file.exists()) file.delete()
        llmModelManager.release()
        viewModelScope.launch {
            llmModelManager.checkAndPrepare()
        }
    }

    // ─── 模型存储路径管理 ───

    /**
     * 处理 SAF 目录选择器返回的 URI。
     * 解析文件系统路径并持久化。
     */
    fun onModelStorageUriSelected(uri: Uri) {
        // 持久化 URI 权限，确保重启后仍可访问
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 某些设备可能不支持 WRITE，尝试只读
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 忽略，仍然尝试使用
            }
        }

        val path = resolveUriToPath(uri)
        if (path != null) {
            configRepo.setModelStoragePath(path)
            configRepo.setModelStorageUri(uri.toString())
            _modelStoragePath.value = path
        }
    }

    /**
     * 恢复默认模型存储路径。
     */
    fun resetModelStoragePath() {
        configRepo.setModelStoragePath(null)
        _modelStoragePath.value = getDisplayPath()
    }

    fun isCustomPath(): Boolean = configRepo.getModelStoragePath() != null

    /**
     * 尝试从 SAF tree URI 解析出文件系统路径。
     *
     * 典型格式:
     *   content://com.android.externalstorage.documents/tree/primary%3AModels
     *   → /storage/emulated/0/Models
     *
     *   content://com.android.externalstorage.documents/tree/XXXX-XXXX%3AModels
     *   → /storage/XXXX-XXXX/Models (SD card)
     */
    private fun resolveUriToPath(uri: Uri): String? {
        // 方法1: 从 URI path segment 解析
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        }

        if (docId != null) {
            // docId 格式: "primary:Models" 或 "XXXX-XXXX:Models"
            val colon = docId.indexOf(':')
            if (colon >= 0) {
                val volume = docId.substring(0, colon)
                val subPath = docId.substring(colon + 1)
                val storageRoot = if (volume == "primary") {
                    "/storage/emulated/0"
                } else {
                    "/storage/$volume"
                }
                return "$storageRoot/$subPath"
            }
        }

        // 方法2: 从 URI decoded path 解析
        val uriPath = uri.path ?: return null
        // uriPath 格式: /tree/primary:Models
        val parts = uriPath.split("/tree/")
        if (parts.size >= 2) {
            val decoded = Uri.decode(parts[1]) // primary:Models
            val colon = decoded.indexOf(':')
            if (colon >= 0) {
                val volume = decoded.substring(0, colon)
                val subPath = decoded.substring(colon + 1)
                val storageRoot = if (volume == "primary") {
                    "/storage/emulated/0"
                } else {
                    "/storage/$volume"
                }
                return "$storageRoot/$subPath"
            }
        }

        return null
    }
}
