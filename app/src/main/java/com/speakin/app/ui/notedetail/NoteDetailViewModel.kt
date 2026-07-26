package com.speakin.app.ui.notedetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.local.entity.DocNode
import com.speakin.app.data.local.entity.RichSegment
import com.speakin.app.data.repository.NoteRepository
import com.speakin.app.di.AudioDir
import com.speakin.app.di.ImageDir
import com.speakin.app.domain.asr.AsrEngine
import com.speakin.app.domain.asr.StreamingAsrSession
import com.speakin.app.domain.audio.AudioDecoder
import com.speakin.app.domain.audio.AudioPlayer
import com.speakin.app.domain.audio.AudioRecorder
import com.speakin.app.domain.audio.WavFile
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
    val blocks: List<DocNode> = emptyList(),
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val playingAudioPath: String? = null,
    val isLoading: Boolean = true,
    val transcribeError: String? = null,
    // ─── 流式识别 / 实时字幕 ───
    val liveCaption: String = "",
    val liveCaptionStableLen: Int = 0,
    val liveCaptionIsStable: Boolean = false,
    // Legacy fallback: true = still using old blocks format
    val isLegacyFormat: Boolean = false
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

    val modelState: StateFlow<ModelState> = modelManager.modelState

    init {
        viewModelScope.launch {
            // Load note and migrate legacy blocks to rich content if needed
            val note = repository.getNoteById(noteId)
            var blocks: List<DocNode> = emptyList()
            if (note != null) {
                blocks = if (note.contentJson == null) {
                    // Legacy note — convert blocks to rich segments
                    repository.migrateLegacyNoteIfNeeded(noteId) ?: emptyList()
                } else {
                    repository.parseSegments(note.contentJson) ?: emptyList()
                }
            }
            _uiState.value = _uiState.value.copy(
                note = note,
                blocks = blocks,
                isLegacyFormat = false,
                isLoading = false
            )

            // Then reactively observe future changes
            repository.getNoteByIdFlow(noteId).collect { updatedNote ->
                _uiState.value = _uiState.value.copy(
                    note = updatedNote,
                    blocks = repository.parseSegments(updatedNote?.contentJson) ?: _uiState.value.blocks,
                    isLoading = false
                )
            }
        }
        // 提前确保 ASR 模型可用（未下载时调用方触发下载）
        viewModelScope.launch {
            asrModelManager.ensureModelAvailable()
        }
    }

    fun checkModel() {
        viewModelScope.launch {
            modelManager.checkAndPrepare()
        }
    }

    // ─── Content persistence ────────────────────────────

    fun onBlocksChanged(blocks: List<DocNode>) {
        _uiState.value = _uiState.value.copy(blocks = blocks)
        viewModelScope.launch {
            try {
                repository.saveContent(noteId, blocks)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save content for note $noteId", e)
            }
        }
    }

    // ─── Voice Recording ───────────────────────────────

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
            startStreamingRecognition()
        }
    }

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
                // Stored as fallback
            }

            override fun onError(error: String) {
                Log.w(TAG, "Streaming recognition error: $error")
            }
        })

        streamingSession = session

        audioRecorder.setChunkListener(object : AudioRecorder.AudioChunkListener {
            override fun onAudioChunk(chunk: ShortArray) {
                session.feedAudio(chunk)
            }
        })
    }

    fun stopRecording() {
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

        streamingSession?.finish()

        _uiState.value = _uiState.value.copy(
            isRecording = false,
            isTranscribing = true,
            transcribeError = null
        )

        viewModelScope.launch {
            asrModelManager.ensureModelAvailable()

            val (rawText, polishResult, errorMsg) = transcribeFile(file)

            // Always save audio segment, even without transcription (model may be unavailable)
            val currentBlocks = _uiState.value.blocks.toMutableList()
            currentBlocks.add(
                DocNode.Segment(
                    RichSegment.Audio(
                        audioPath = file.absolutePath,
                        durationMs = durationMs,
                        transcription = rawText.ifBlank { null },
                        polishedText = polishResult
                    )
                )
            )
            repository.saveContent(noteId, currentBlocks)
            _uiState.value = _uiState.value.copy(
                blocks = currentBlocks,
                isTranscribing = false,
                transcribeError = if (errorMsg != null && rawText.isBlank()) errorMsg else null
            )
            streamingSession = null
        }
    }

    // ─── Playback ───────────────────────────────────────

    fun onPlaybackStarted(audioPath: String) {
        val file = File(audioPath)
        if (!file.exists()) return

        _uiState.value = _uiState.value.copy(playingAudioPath = audioPath)

        audioPlayer.play(file, object : AudioPlayer.PlaybackListener {
            override fun onCompletion() {
                _uiState.value = _uiState.value.copy(playingAudioPath = null)
            }
            override fun onError(error: String) {
                _uiState.value = _uiState.value.copy(playingAudioPath = null)
            }
        })
    }

    fun onPlaybackStopped() {
        audioPlayer.stop()
        _uiState.value = _uiState.value.copy(playingAudioPath = null)
    }

    // ─── Image ──────────────────────────────────────────

    fun addImage(tempFile: File) {
        viewModelScope.launch {
            val destDir = imageOutputDir
            destDir.mkdirs()
            val destFile = File(destDir, "img_${UUID.randomUUID()}.jpg")
            tempFile.copyTo(destFile, overwrite = true)

            val currentBlocks = _uiState.value.blocks.toMutableList()
            currentBlocks.add(DocNode.Segment(RichSegment.Image(imagePath = destFile.absolutePath)))
            repository.saveContent(noteId, currentBlocks)
            _uiState.value = _uiState.value.copy(blocks = currentBlocks)
        }
    }

    // ─── Audio Import ───────────────────────────────────

    /**
     * 导入外部音频文件。
     * 自动检测 WAV 格式，非 WAV 文件通过 MediaCodec 解码后转写。
     *
     * @param tempFile 临时文件（已从 URI 复制到缓存目录）
     */
    fun importAudio(tempFile: File) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isTranscribing = true, transcribeError = null)

                val (audioFile, durationMs) = withContext(Dispatchers.IO) {
                    // 复制到音频目录
                    val isWav = try {
                        WavFile.read(tempFile)
                        true
                    } catch (_: Exception) {
                        false
                    }

                    if (isWav) {
                        // WAV 文件直接使用
                        val dest = File(audioOutputDir, "import_${UUID.randomUUID()}.wav")
                        tempFile.copyTo(dest, overwrite = true)
                        tempFile.delete()
                        val wavData = WavFile.read(dest)
                        Pair(dest, wavData.durationMs)
                    } else {
                        // 非 WAV → 解码为 16kHz 单声道 WAV
                        val decoded = File(audioOutputDir, "import_${UUID.randomUUID()}.wav")
                        val success = AudioDecoder.decodeToWav(tempFile, decoded)
                        if (success) {
                            tempFile.delete()
                            val wavData = WavFile.read(decoded)
                            Pair(decoded, wavData.durationMs)
                        } else {
                            // 解码失败，保留原文件（仍可播放，但无法转写）
                            val ext = tempFile.extension.ifEmpty { "m4a" }
                            val dest = File(audioOutputDir, "import_${UUID.randomUUID()}.$ext")
                            tempFile.copyTo(dest, overwrite = true)
                            tempFile.delete()
                            val dur = AudioDecoder.getDurationMs(dest)
                            Pair(dest, dur)
                        }
                    }
                }

                // 添加音频段到笔记
                val currentBlocks = _uiState.value.blocks.toMutableList()
                val blockIndex = currentBlocks.size
                currentBlocks.add(
                    DocNode.Segment(
                        RichSegment.Audio(
                            audioPath = audioFile.absolutePath,
                            durationMs = durationMs,
                            transcription = null,
                            polishedText = null
                        )
                    )
                )
                repository.saveContent(noteId, currentBlocks)
                _uiState.value = _uiState.value.copy(blocks = currentBlocks)

                // 转写 + 润色
                asrModelManager.ensureModelAvailable()
                val (rawText, polishResult, errorMsg) = transcribeFile(audioFile)

                // 更新段中的转写结果
                val updatedBlocks = _uiState.value.blocks.toMutableList()
                val segNode = updatedBlocks.getOrNull(blockIndex) as? DocNode.Segment
                val seg = segNode?.content as? RichSegment.Audio
                if (seg != null) {
                    updatedBlocks[blockIndex] = DocNode.Segment(seg.copy(
                        transcription = rawText.ifBlank { null },
                        polishedText = polishResult
                    ))
                    repository.saveContent(noteId, updatedBlocks)
                    _uiState.value = _uiState.value.copy(
                        blocks = updatedBlocks,
                        isTranscribing = false,
                        transcribeError = if (errorMsg != null && rawText.isBlank()) errorMsg else null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isTranscribing = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import audio", e)
                _uiState.value = _uiState.value.copy(
                    isTranscribing = false,
                    transcribeError = e.message ?: "Import failed"
                )
            }
        }
    }

    /**
     * 转写音频文件并润色文本。
     * @return Triple(rawText, polishedText?, errorMsg?)
     */
    private suspend fun transcribeFile(file: File): Triple<String, String?, String?> =
        withContext(Dispatchers.IO) {
            var errorMsg: String? = null

            // Transcribe
            val transcribeSignal = kotlinx.coroutines.CompletableDeferred<String>()
            asrEngine.transcribe(file, object : AsrEngine.Callback {
                override fun onResult(text: String) { transcribeSignal.complete(text) }
                override fun onProgress(progress: Float) {}
                override fun onError(error: String) {
                    errorMsg = error
                    transcribeSignal.complete("")
                }
            })
            val rawText = transcribeSignal.await()

            // Polish
            var polishedText: String? = null
            if (rawText.isNotBlank()) {
                val polishSignal = kotlinx.coroutines.CompletableDeferred<String>()
                polishEngine.polish(rawText, object : PolishEngine.Callback {
                    override fun onResult(text: String) { polishSignal.complete(text) }
                    override fun onError(error: String) { polishSignal.complete(rawText) }
                })
                polishedText = polishSignal.await()
                if (polishedText == rawText) polishedText = null
            }

            Triple(rawText, polishedText, errorMsg)
        }

    // ─── Title ──────────────────────────────────────────

    fun updateTitle(title: String) {
        viewModelScope.launch {
            repository.updateNoteTitle(noteId, title)
        }
    }

    // ─── Delete block ─────────────────────────────────

    fun deleteBlock(index: Int) {
        val currentBlocks = _uiState.value.blocks.toMutableList()
        if (index in currentBlocks.indices) {
            val node = currentBlocks[index]
            // Stop playback if this block contains the playing audio
            val playingPath = _uiState.value.playingAudioPath
            if (playingPath != null) {
                val nodePaths = when (node) {
                    is DocNode.Segment -> listOf((node.content as? RichSegment.Audio)?.audioPath)
                    is DocNode.FlowGroup -> node.items.filterIsInstance<RichSegment.Audio>().map { it.audioPath }
                    is DocNode.ColumnGroup -> node.columns.flatMap { col ->
                        col.children.filterIsInstance<RichSegment.Audio>().map { it.audioPath }
                    }
                }
                if (nodePaths.any { it == playingPath }) {
                    audioPlayer.stop()
                    _uiState.value = _uiState.value.copy(playingAudioPath = null)
                }
            }
            currentBlocks.removeAt(index)
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(blocks = currentBlocks)
                try {
                    repository.saveContent(noteId, currentBlocks)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save after block delete", e)
                }
                // Clean up media files
                when (node) {
                    is DocNode.Segment -> {
                        when (val seg = node.content) {
                            is RichSegment.Image -> File(seg.imagePath).delete()
                            is RichSegment.Audio -> File(seg.audioPath).delete()
                            else -> {}
                        }
                    }
                    is DocNode.FlowGroup -> {
                        node.items.forEach { seg ->
                            when (seg) {
                                is RichSegment.Image -> File(seg.imagePath).delete()
                                is RichSegment.Audio -> File(seg.audioPath).delete()
                                else -> {}
                            }
                        }
                    }
                    is DocNode.ColumnGroup -> {
                        node.columns.flatMap { it.children }.forEach { seg ->
                            when (seg) {
                                is RichSegment.Image -> File(seg.imagePath).delete()
                                is RichSegment.Audio -> File(seg.audioPath).delete()
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Flow group management ──────────────────────────

    /** Append a flow group with one empty text item to start. */
    fun addFlowGroup() {
        val currentBlocks = _uiState.value.blocks.toMutableList()
        currentBlocks.add(
            DocNode.FlowGroup(items = listOf(RichSegment.Text("")))
        )
        onBlocksChanged(currentBlocks)
    }

    /** Add a segment to an existing flow group. */
    fun addItemToFlowGroup(blockIndex: Int, segment: RichSegment) {
        val currentBlocks = _uiState.value.blocks.toMutableList()
        val group = currentBlocks.getOrNull(blockIndex) as? DocNode.FlowGroup ?: return
        currentBlocks[blockIndex] = group.copy(items = group.items + segment)
        onBlocksChanged(currentBlocks)
    }

    /** Update a segment inside a flow group. */
    fun updateItemInFlowGroup(blockIndex: Int, itemIndex: Int, segment: RichSegment) {
        val currentBlocks = _uiState.value.blocks.toMutableList()
        val group = currentBlocks.getOrNull(blockIndex) as? DocNode.FlowGroup ?: return
        if (itemIndex !in group.items.indices) return
        val newItems = group.items.toMutableList().apply { set(itemIndex, segment) }
        currentBlocks[blockIndex] = group.copy(items = newItems)
        onBlocksChanged(currentBlocks)
    }

    /** Remove a segment from a flow group.  If the group becomes empty, remove it entirely. */
    fun removeItemFromFlowGroup(blockIndex: Int, itemIndex: Int) {
        val currentBlocks = _uiState.value.blocks.toMutableList()
        val group = currentBlocks.getOrNull(blockIndex) as? DocNode.FlowGroup ?: return
        if (itemIndex !in group.items.indices) return

        val removed = group.items[itemIndex]
        // Clean up media files
        when (removed) {
            is RichSegment.Image -> File(removed.imagePath).delete()
            is RichSegment.Audio -> {
                File(removed.audioPath).delete()
                if (_uiState.value.playingAudioPath == removed.audioPath) {
                    audioPlayer.stop()
                    _uiState.value = _uiState.value.copy(playingAudioPath = null)
                }
            }
            else -> {}
        }

        val newItems = group.items.toMutableList().apply { removeAt(itemIndex) }
        if (newItems.isEmpty()) {
            currentBlocks.removeAt(blockIndex)
        } else {
            currentBlocks[blockIndex] = group.copy(items = newItems)
        }
        onBlocksChanged(currentBlocks)
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
