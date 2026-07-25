package com.speakin.app.ui.audioeditor

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.DocNode
import com.speakin.app.data.local.entity.RichSegment
import com.speakin.app.data.repository.NoteRepository
import com.speakin.app.di.AudioDir
import com.speakin.app.domain.audio.AacEncoder
import com.speakin.app.domain.audio.AudioEditEngine
import com.speakin.app.domain.audio.RangePlayer
import com.speakin.app.domain.audio.TrimProcessor
import com.speakin.app.domain.audio.WavData
import com.speakin.app.domain.audio.WavFile
import com.speakin.app.domain.audio.Waveform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音频编辑器一次性事件 */
sealed class AudioEditorEvent {
    /** 导出完成，file 位于 cacheDir/exports，可通过 FileProvider 分享 */
    data class ExportReady(val file: File) : AudioEditorEvent()
    data class Error(val message: String) : AudioEditorEvent()
}

data class AudioEditorUiState(
    val isLoading: Boolean = true,
    val loadError: Boolean = false,
    val durationMs: Long = 0L,
    val peaks: List<Float> = emptyList(),
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val playHeadMs: Long = -1L,
    val isPreviewPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val isExporting: Boolean = false,
    val savedAndClosed: Boolean = false
) {
    /** 选区是否与整段不同（决定保存按钮是否可用） */
    val hasTrimChange: Boolean
        get() = trimStartMs > 0L || (durationMs > 0L && trimEndMs < durationMs)
}

/**
 * 音频编辑器 ViewModel。
 *
 * 加载音频段的 WAV 数据，支持选区预览、裁剪保存（另存新文件并更新段引用）、
 * 导出 M4A。PCM 数据保存在 VM 字段中，不进入 UiState。
 */
@HiltViewModel
class AudioEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository,
    private val editEngine: AudioEditEngine,
    @AudioDir private val audioDir: File,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "AudioEditorViewModel"
        private const val MIN_TRIM_MS = 200L
        private const val WAVEFORM_BUCKETS = 400
    }

    private val noteId: String = savedStateHandle["noteId"] ?: ""
    private val segmentIndex: Int = savedStateHandle["segmentIndex"] ?: -1

    private val _uiState = MutableStateFlow(AudioEditorUiState())
    val uiState: StateFlow<AudioEditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<AudioEditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var wavData: WavData? = null
    private var currentAudioPath: String? = null
    private val rangePlayer = RangePlayer()

    init {
        loadAudio()
    }

    private fun loadAudio() {
        viewModelScope.launch {
            try {
                val blocks = repository.getContentOnce(noteId)
                val node = blocks?.getOrNull(segmentIndex) as? DocNode.Segment
                val segment = node?.content as? RichSegment.Audio
                if (segment == null) {
                    _uiState.update { it.copy(isLoading = false, loadError = true) }
                    return@launch
                }
                currentAudioPath = segment.audioPath

                val (data, peaks) = withContext(Dispatchers.IO) {
                    val d = WavFile.read(File(segment.audioPath))
                    d to Waveform.computePeaks(d, WAVEFORM_BUCKETS)
                }
                if (data.durationMs <= 0L) {
                    _uiState.update { it.copy(isLoading = false, loadError = true) }
                    return@launch
                }
                wavData = data
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        durationMs = data.durationMs,
                        peaks = peaks.toList(),
                        trimStartMs = 0L,
                        trimEndMs = data.durationMs
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load audio", e)
                _uiState.update { it.copy(isLoading = false, loadError = true) }
            }
        }
    }

    /** 更新裁剪选区（自动 clamp，保证最小选区长度） */
    fun onTrimRangeChanged(startMs: Long, endMs: Long) {
        val duration = _uiState.value.durationMs
        if (duration <= 0) return
        val clampedStart = startMs.coerceIn(0L, duration - MIN_TRIM_MS)
        val clampedEnd = endMs.coerceIn(clampedStart + MIN_TRIM_MS, duration)
        stopPreview()
        _uiState.update { it.copy(trimStartMs = clampedStart, trimEndMs = clampedEnd) }
    }

    /** 预览播放当前选区 */
    fun previewSelection() {
        val data = wavData ?: return
        val state = _uiState.value
        _uiState.update { it.copy(isPreviewPlaying = true, playHeadMs = state.trimStartMs) }
        rangePlayer.play(
            data = data,
            startMs = state.trimStartMs,
            endMs = state.trimEndMs,
            onProgressMs = { posMs ->
                _uiState.update { it.copy(playHeadMs = posMs) }
            },
            onComplete = {
                _uiState.update { it.copy(isPreviewPlaying = false, playHeadMs = -1L) }
            }
        )
    }

    fun stopPreview() {
        if (!_uiState.value.isPreviewPlaying) return
        rangePlayer.stop()
        _uiState.update { it.copy(isPreviewPlaying = false, playHeadMs = -1L) }
    }

    /**
     * 保存裁剪：写新 WAV 文件 → 更新段引用 → 删除旧文件。
     * transcription/polishedText 保留（可能与裁剪后音频不完全对应，但仍有标识价值）。
     */
    fun saveTrim() {
        val data = wavData ?: return
        val state = _uiState.value
        if (state.isSaving || !state.hasTrimChange) return
        stopPreview()
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                val newFile = File(audioDir, "rec_${UUID.randomUUID()}.m4a")
                withContext(Dispatchers.IO) {
                    val trimmed = editEngine.apply(
                        data, listOf(TrimProcessor(state.trimStartMs, state.trimEndMs))
                    )
                    WavFile.write(newFile, trimmed)
                    repository.updateAudioSegment(
                        noteId, segmentIndex, newFile.absolutePath, trimmed.durationMs
                    )
                    // 段引用已切换，删除旧文件
                    currentAudioPath?.let { old ->
                        if (old != newFile.absolutePath) File(old).delete()
                    }
                }
                _uiState.update { it.copy(isSaving = false, savedAndClosed = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save trim", e)
                _uiState.update { it.copy(isSaving = false) }
                _events.trySend(AudioEditorEvent.Error(e.message ?: "save failed"))
            }
        }
    }

    /** 导出当前选区为 M4A（AAC），文件名格式为「便签名_日期时间.m4a」 */
    fun export() {
        val data = wavData ?: return
        val state = _uiState.value
        if (state.isExporting) return
        stopPreview()
        _uiState.update { it.copy(isExporting = true) }

        viewModelScope.launch {
            try {
                val outFile = withContext(Dispatchers.IO) {
                    val selection = editEngine.apply(
                        data, listOf(TrimProcessor(state.trimStartMs, state.trimEndMs))
                    )
                    // 便签名_日期时间.m4a — 例如「会议记录_20260723_143052.m4a」
                    val note = repository.getNoteById(noteId)
                    val noteTitle = note?.title?.takeIf { it.isNotBlank() } ?: "note"
                    val safeTitle = noteTitle
                        .replace(Regex("""[\s\\/:*?"<>|]+"""), "_")
                        .trimEnd('_')
                        .take(60)
                    val dateStr = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    val fileName = "${safeTitle}_${dateStr}.m4a"
                    val file = File(
                        File(context.cacheDir, "exports"),
                        fileName
                    )
                    AacEncoder.encodeToM4a(selection, file)
                    file
                }
                _uiState.update { it.copy(isExporting = false) }
                _events.trySend(AudioEditorEvent.ExportReady(outFile))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export audio", e)
                _uiState.update { it.copy(isExporting = false) }
                _events.trySend(AudioEditorEvent.Error(e.message ?: "export failed"))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rangePlayer.release()
    }
}
