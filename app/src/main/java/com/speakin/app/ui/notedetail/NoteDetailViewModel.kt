package com.speakin.app.ui.notedetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.BlockType
import com.speakin.app.data.local.entity.ContentBlockEntity
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.repository.NoteRepository
import com.speakin.app.di.AudioDir
import com.speakin.app.di.ImageDir
import com.speakin.app.domain.asr.AsrEngine
import com.speakin.app.domain.asr.StreamingAsrSession
import com.speakin.app.domain.audio.AudioPlayer
import com.speakin.app.domain.audio.AudioRecorder
import com.speakin.app.domain.llm.ModelManager
import com.speakin.app.domain.llm.ModelState
import com.speakin.app.domain.model.AsrModelManager
import com.speakin.app.domain.polish.PolishEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class NoteDetailUiState(
    val note: NoteEntity? = null,
    val blocks: List<ContentBlockEntity> = emptyList(),
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val playingBlockId: String? = null,
    val isLoading: Boolean = true,
    val transcribeError: String? = null,
    // ─── 流式识别 / 实时字幕 ───
    val liveCaption: String = "",
    val liveCaptionStableLen: Int = 0,
    val liveCaptionIsStable: Boolean = false
)

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val asrEngine: AsrEngine,
    private val polishEngine: PolishEngine,
    private val asrModelManager: AsrModelManager,
    @AudioDir private val audioOutputDir: File,
    @ImageDir private val imageOutputDir: File,
    private val modelManager: ModelManager
) : ViewModel() {

    private val noteId: String = savedStateHandle.get<String>("noteId") ?: ""

    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    private var currentAudioFile: File? = null
    private var streamingSession: StreamingAsrSession? = null
    private var accumulatedFinalText: String = ""

    val modelState: StateFlow<ModelState> = modelManager.modelState

    init {
        viewModelScope.launch {
            repository.getNoteByIdFlow(noteId).collect { note ->
                _uiState.value = _uiState.value.copy(
                    note = note,
                    isLoading = false
                )
            }
        }
        viewModelScope.launch {
            repository.getBlocksByNoteId(noteId).collect { blocks ->
                _uiState.value = _uiState.value.copy(blocks = blocks)
            }
        }
        // 提前准备 ASR 模型（从 APK assets 解压到内部存储）
        viewModelScope.launch {
            asrModelManager.prepareFromAssets()
        }
    }

    fun checkModel() {
        viewModelScope.launch {
            modelManager.checkAndPrepare()
        }
    }

    // ─── Voice Recording ───────────────────────────────────

    fun startRecording() {
        val fileName = "rec_${UUID.randomUUID().toString()}.m4a"
        val file = File(audioOutputDir, fileName)
        val started = audioRecorder.start(file)
        if (started) {
            currentAudioFile = file
            _uiState.value = _uiState.value.copy(
                isRecording = true,
                liveCaption = "",
                liveCaptionStableLen = 0,
                liveCaptionIsStable = false
            )

            // ─── 启动流式识别 ───
            startStreamingRecognition()
        }
    }

    /**
     * 启动流式识别会话，将 AudioRecorder 的音频块连接到 ASR 引擎。
     */
    private fun startStreamingRecognition() {
        val session = asrEngine.startStreaming(object : AsrEngine.StreamingCallback {
            override fun onPartialResult(result: AsrEngine.StreamingResult) {
                _uiState.value = _uiState.value.copy(
                    liveCaption = result.text,
                    liveCaptionStableLen = result.stableLen,
                    liveCaptionIsStable = result.isStable
                )
            }

            override fun onFinalResult(text: String) {
                // 最终结果作为 fallback 文本暂存
                accumulatedFinalText = text
            }

            override fun onError(error: String) {
                // 流式识别失败不中断录音，用户仍可在停止后获得完整转写
                Log.w(TAG, "Streaming recognition error: $error")
            }
        })

        streamingSession = session

        // 将录音块的原始 ShortArray 数据直接喂给 ASR 会话
        audioRecorder.setChunkListener(object : AudioRecorder.AudioChunkListener {
            override fun onAudioChunk(chunk: ShortArray) {
                session.feedAudio(chunk)
            }
        })
    }

    fun stopRecording() {
        // 停止接收音频块
        audioRecorder.setChunkListener(null)

        val durationMs = audioRecorder.stop()
        val file = currentAudioFile
        if (file == null || durationMs <= 0) {
            streamingSession?.cancel()
            streamingSession = null
            _uiState.value = _uiState.value.copy(
                isRecording = false,
                liveCaption = "",
                liveCaptionStableLen = 0
            )
            return
        }

        // 结束流式会话
        streamingSession?.finish()

        _uiState.value = _uiState.value.copy(
            isRecording = false,
            isTranscribing = true,
            transcribeError = null
        )

        viewModelScope.launch {
            // 兜底确保 ASR 模型已解压就绪
            asrModelManager.prepareFromAssets()

            val block = repository.addVoiceBlock(noteId, file, durationMs)

            withContext(Dispatchers.IO) {
                var errorMsg: String? = null
                val transcribeSignal = kotlinx.coroutines.CompletableDeferred<String>()
                asrEngine.transcribe(file, object : AsrEngine.Callback {
                    override fun onResult(text: String) {
                        transcribeSignal.complete(text)
                    }
                    override fun onProgress(progress: Float) {}
                    override fun onError(error: String) {
                        errorMsg = error
                        transcribeSignal.complete("")
                    }
                })
                val rawText = transcribeSignal.await()

                if (rawText.isNotBlank()) {
                    repository.updateTranscription(block.id, rawText)

                    val polishSignal = kotlinx.coroutines.CompletableDeferred<String>()
                    polishEngine.polish(rawText, object : PolishEngine.Callback {
                        override fun onResult(text: String) {
                            polishSignal.complete(text)
                        }
                        override fun onError(error: String) {
                            polishSignal.complete(rawText)
                        }
                    })
                    val polishedText = polishSignal.await()
                    repository.updatePolishedText(block.id, polishedText)
                } else if (errorMsg != null) {
                    // 转写失败，记录错误供 UI 展示
                    _uiState.value = _uiState.value.copy(transcribeError = errorMsg)
                }
            }

            _uiState.value = _uiState.value.copy(isTranscribing = false)
            streamingSession = null
        }
    }

    fun onPlaybackStarted(blockId: String) {
        val block = _uiState.value.blocks.find { it.id == blockId } ?: return
        val path = block.audioFilePath ?: return
        val file = File(path)
        if (!file.exists()) return

        _uiState.value = _uiState.value.copy(playingBlockId = blockId)

        audioPlayer.play(file, object : AudioPlayer.PlaybackListener {
            override fun onCompletion() {
                _uiState.value = _uiState.value.copy(playingBlockId = null)
            }
            override fun onError(error: String) {
                _uiState.value = _uiState.value.copy(playingBlockId = null)
            }
        })
    }

    fun onPlaybackStopped() {
        audioPlayer.stop()
        _uiState.value = _uiState.value.copy(playingBlockId = null)
    }

    // ─── Text Block ────────────────────────────────────────

    fun addTextBlock() {
        viewModelScope.launch {
            repository.addTextBlock(noteId)
        }
    }

    fun updateTextBlock(blockId: String, text: String) {
        viewModelScope.launch {
            repository.updateTextBlock(blockId, text)
        }
    }

    // ─── Image Block ───────────────────────────────────────

    fun addImageBlock(imageFile: File) {
        viewModelScope.launch {
            // Copy image to app storage
            val destDir = imageOutputDir
            destDir.mkdirs()
            val destFile = File(destDir, "img_${UUID.randomUUID()}.jpg")
            imageFile.copyTo(destFile, overwrite = true)
            repository.addImageBlock(noteId, destFile)
        }
    }

    // ─── Block Operations ──────────────────────────────────

    fun deleteBlock(blockId: String) {
        viewModelScope.launch {
            if (blockId == _uiState.value.playingBlockId) {
                audioPlayer.stop()
                _uiState.value = _uiState.value.copy(playingBlockId = null)
            }
            repository.deleteBlock(blockId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            repository.updateNoteTitle(noteId, title)
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamingSession?.cancel()
        streamingSession = null
        audioPlayer.release()
    }

    companion object {
        private const val TAG = "NoteDetailVM"
    }
}
