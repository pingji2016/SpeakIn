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
    val hasCustomConfig: Boolean = false,
    val asrSaved: Boolean = false,
    val llmSaved: Boolean = false
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
        // Read version info from PackageInfo (BuildConfig not enabled in this project)
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
                versionName = versionName,
                versionCode = versionCode,
                asrUrls = "",
                llmUrls = "",
                hasCustomConfig = false,
                asrSaved = true,
                llmSaved = true
            )
        }
    }
}
