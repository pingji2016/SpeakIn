package com.speakin.app.ui.modeldownload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.domain.llm.ModelManager
import com.speakin.app.domain.llm.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val modelManager: ModelManager
) : ViewModel() {

    val modelState: StateFlow<ModelState> = modelManager.modelState

    fun checkModel() {
        viewModelScope.launch {
            modelManager.checkAndPrepare()
        }
    }

    fun download() {
        viewModelScope.launch {
            modelManager.downloadModel()
        }
    }
}
