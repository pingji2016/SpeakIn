package com.speakin.app.ui.about

import androidx.lifecycle.ViewModel
import com.speakin.app.BuildConfig
import com.speakin.app.data.local.ModelConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class AboutUiState(
    val versionName: String = "",
    val versionCode: Int = 0,
    val asrUrls: String = "",
    val llmUrls: String = "",
    val hasCustomConfig: Boolean = false,
    val asrSaved: Boolean = false,
    val llmSaved: Boolean = false
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val configRepo: ModelConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        val asrUrls = configRepo.getAsrBaseUrls()
        val llmUrls = configRepo.getLlmUrls()
        _uiState.value = AboutUiState(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            asrUrls = asrUrls.joinToString("\n"),
            llmUrls = llmUrls.joinToString("\n"),
            hasCustomConfig = configRepo.hasCustomConfig()
        )
    }

    fun updateAsrUrls(urls: String) {
        _uiState.update { it.copy(asrUrls = urls, asrSaved = false) }
    }

    fun updateLlmUrls(urls: String) {
        _uiState.update { it.copy(llmUrls = urls, llmSaved = false) }
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

    fun resetToDefaults() {
        configRepo.resetAll()
        _uiState.update {
            AboutUiState(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                asrUrls = "",
                llmUrls = "",
                hasCustomConfig = false,
                asrSaved = true,
                llmSaved = true
            )
        }
    }
}
