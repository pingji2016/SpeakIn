package com.speakin.app.ui.audioeditor

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.speakin.app.R
import com.speakin.app.util.FormatUtils
import kotlin.math.abs

/**
 * 音频编辑界面。
 *
 * 长按笔记详情页的音频段进入。支持波形显示、选区裁剪预览、
 * 保存裁剪（替换段引用）和导出 M4A 分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: AudioEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 保存完成后自动返回
    LaunchedEffect(uiState.savedAndClosed) {
        if (uiState.savedAndClosed) onNavigateBack()
    }

    // 一次性事件：导出分享 / 错误提示
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AudioEditorEvent.ExportReady -> {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", event.file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.export_audio))
                    )
                }
                is AudioEditorEvent.Error -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.audio_export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.audio_editor),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.loadError -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.audio_editor_load_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> EditorContent(uiState, viewModel)
            }
        }
    }
}

@Composable
private fun EditorContent(
    uiState: AudioEditorUiState,
    viewModel: AudioEditorViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // ─── 波形 + 选区 ───
        WaveformTrimmer(
            peaks = uiState.peaks,
            durationMs = uiState.durationMs,
            trimStartMs = uiState.trimStartMs,
            trimEndMs = uiState.trimEndMs,
            playHeadMs = uiState.playHeadMs,
            onTrimRangeChanged = viewModel::onTrimRangeChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ─── 起止时间标签 ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TimeLabel(
                label = stringResource(R.string.trim_start),
                timeMs = uiState.trimStartMs
            )
            TimeLabel(
                label = stringResource(R.string.trim),
                timeMs = uiState.trimEndMs - uiState.trimStartMs,
                highlight = true
            )
            TimeLabel(
                label = stringResource(R.string.trim_end),
                timeMs = uiState.trimEndMs
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ─── 预览播放 ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FilledTonalIconButton(
                onClick = {
                    if (uiState.isPreviewPlaying) viewModel.stopPreview()
                    else viewModel.previewSelection()
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (uiState.isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (uiState.isPreviewPlaying) R.string.stop else R.string.preview
                    ),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ─── 底部操作 ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::export,
                enabled = !uiState.isExporting && !uiState.isSaving,
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.export_audio))
            }

            Button(
                onClick = viewModel::saveTrim,
                enabled = uiState.hasTrimChange && !uiState.isSaving && !uiState.isExporting,
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.save_trim))
            }
        }
    }
}

@Composable
private fun TimeLabel(label: String, timeMs: Long, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = FormatUtils.formatDuration(timeMs),
            style = MaterialTheme.typography.titleSmall,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 波形 + 裁剪选区组件。
 *
 * Canvas 绘制波形竖条；选区高亮；两侧把手可拖动调整起止；
 * 播放时绘制 playhead 竖线。
 */
@Composable
private fun WaveformTrimmer(
    peaks: List<Float>,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playHeadMs: Long,
    onTrimRangeChanged: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val waveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val selectedWaveColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val handleColor = MaterialTheme.colorScheme.primary
    val playHeadColor = MaterialTheme.colorScheme.tertiary

    // pointerInput 的协程不随重组重启，用 rememberUpdatedState 避免闭包捕获过期值
    val currentStartMs by rememberUpdatedState(trimStartMs)
    val currentEndMs by rememberUpdatedState(trimEndMs)
    // 正在拖动的把手：0=起点，1=终点，null=未拖动
    var draggingHandle by remember { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier.pointerInput(durationMs) {
            detectDragGestures(
                onDragStart = { offset ->
                    if (durationMs <= 0) return@detectDragGestures
                    val startX = currentStartMs.toFloat() / durationMs * size.width
                    val endX = currentEndMs.toFloat() / durationMs * size.width
                    // 选择距离触点更近的把手
                    draggingHandle = if (abs(offset.x - startX) <= abs(offset.x - endX)) 0 else 1
                },
                onDrag = { change, _ ->
                    change.consume()
                    if (durationMs <= 0) return@detectDragGestures
                    val ms = (change.position.x / size.width * durationMs).toLong()
                    when (draggingHandle) {
                        0 -> onTrimRangeChanged(ms, currentEndMs)
                        1 -> onTrimRangeChanged(currentStartMs, ms)
                    }
                },
                onDragEnd = { draggingHandle = null },
                onDragCancel = { draggingHandle = null }
            )
        }
    ) {
        if (peaks.isEmpty() || durationMs <= 0) return@Canvas

        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val startX = trimStartMs.toFloat() / durationMs * width
        val endX = trimEndMs.toFloat() / durationMs * width

        // 选区背景
        drawRect(
            color = selectionColor,
            topLeft = Offset(startX, 0f),
            size = Size(endX - startX, height)
        )

        // 波形竖条
        val barWidth = width / peaks.size
        val barPaint = barWidth * 0.7f
        peaks.forEachIndexed { i, peak ->
            val x = i * barWidth + barWidth / 2f
            val barHeight = (peak * height * 0.85f).coerceAtLeast(2.dp.toPx())
            val inSelection = x in startX..endX
            drawRoundRect(
                color = if (inSelection) selectedWaveColor else waveColor,
                topLeft = Offset(x - barPaint / 2f, centerY - barHeight / 2f),
                size = Size(barPaint, barHeight),
                cornerRadius = CornerRadius(barPaint / 2f)
            )
        }

        // 起止把手
        val handleWidth = 3.dp.toPx()
        val knobRadius = 7.dp.toPx()
        listOf(startX, endX).forEach { x ->
            drawRect(
                color = handleColor,
                topLeft = Offset(x - handleWidth / 2f, 0f),
                size = Size(handleWidth, height)
            )
            drawCircle(color = handleColor, radius = knobRadius, center = Offset(x, 0f))
            drawCircle(color = handleColor, radius = knobRadius, center = Offset(x, height))
        }

        // 播放头
        if (playHeadMs >= 0) {
            val playX = playHeadMs.toFloat() / durationMs * width
            drawRect(
                color = playHeadColor,
                topLeft = Offset(playX - 1.dp.toPx(), 0f),
                size = Size(2.dp.toPx(), height)
            )
        }
    }
}
