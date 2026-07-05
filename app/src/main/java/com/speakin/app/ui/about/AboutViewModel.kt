package com.speakin.app.ui.about

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import com.speakin.app.data.local.ModelConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class AboutUiState(
    val versionName: String = "",
    val versionCode: Long = 0,
    val asrUrls: String = "",
    val llmUrls: String = "",
    val asrModelType: String = ModelConfigRepository.DEFAULT_ASR_MODEL_TYPE,
    val llmModelType: String = ModelConfigRepository.DEFAULT_LLM_MODEL_TYPE,
    val hasCustomConfig: Boolean = false,
    val asrSaved: Boolean = false,
    val llmSaved: Boolean = false,
    val asrTypeSaved: Boolean = false,
    val llmTypeSaved: Boolean = false
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepo: ModelConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private val versionName: String
    private val versionCode: Long

    init {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        versionName = packageInfo.versionName ?: "unknown"
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val asrUrls = configRepo.getAsrBaseUrls()
        val llmUrls = configRepo.getLlmUrls()
        _uiState.value = AboutUiState(
            versionName = versionName,
            versionCode = versionCode,
            asrUrls = asrUrls.joinToString("\n"),
            llmUrls = llmUrls.joinToString("\n"),
            asrModelType = configRepo.getAsrModelType(),
            llmModelType = configRepo.getLlmModelType(),
            hasCustomConfig = configRepo.hasCustomConfig()
        )
    }

    fun updateAsrUrls(urls: String) {
        _uiState.update { it.copy(asrUrls = urls, asrSaved = false) }
    }

    fun updateLlmUrls(urls: String) {
        _uiState.update { it.copy(llmUrls = urls, llmSaved = false) }
    }

    fun updateAsrModelType(type: String) {
        _uiState.update { it.copy(asrModelType = type, asrTypeSaved = false) }
    }

    fun updateLlmModelType(type: String) {
        _uiState.update { it.copy(llmModelType = type, llmTypeSaved = false) }
    }

    fun saveAsrUrls() {
        configRepo.setAsrBaseUrls(_uiState.value.asrUrls)
        _uiState.update {
            it.copy(
                asrSaved = true,
                hasCustomConfig = configRepo.hasCustomConfig()
            )
        }
    }

    fun saveLlmUrls() {
        configRepo.setLlmUrls(_uiState.value.llmUrls)
        _uiState.update {
            it.copy(
                llmSaved = true,
                hasCustomConfig = configRepo.hasCustomConfig()
            )
        }
    }

    fun saveAsrModelType() {
        configRepo.setAsrModelType(_uiState.value.asrModelType)
        _uiState.update {
            it.copy(
                asrTypeSaved = true,
                hasCustomConfig = configRepo.hasCustomConfig()
            )
        }
    }

    fun saveLlmModelType() {
        configRepo.setLlmModelType(_uiState.value.llmModelType)
        _uiState.update {
            it.copy(
                llmTypeSaved = true,
                hasCustomConfig = configRepo.hasCustomConfig()
            )
        }
    }

    fun resetToDefaults() {
        configRepo.resetAll()
        _uiState.update {
            AboutUiState(
                versionName = versionName,
                versionCode = versionCode,
                asrUrls = "",
                llmUrls = "",
                asrModelType = ModelConfigRepository.DEFAULT_ASR_MODEL_TYPE,
                llmModelType = ModelConfigRepository.DEFAULT_LLM_MODEL_TYPE,
                hasCustomConfig = false,
                asrSaved = true,
                llmSaved = true,
                asrTypeSaved = true,
                llmTypeSaved = true
            )
        }
    }
}
