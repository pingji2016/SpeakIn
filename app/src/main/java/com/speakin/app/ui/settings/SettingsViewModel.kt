package com.speakin.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.domain.llm.ModelManager
import com.speakin.app.domain.llm.ModelState
import com.speakin.app.domain.model.AsrModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val llmModelManager: ModelManager,
    private val asrModelManager: AsrModelManager
) : ViewModel() {

    val llmState: StateFlow<ModelState> = llmModelManager.modelState
    val asrState: StateFlow<AsrModelManager.ModelState> = asrModelManager.modelState
    val asrReady: Boolean get() = asrModelManager.isModelReady()

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
}
