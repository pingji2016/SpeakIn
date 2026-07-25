package com.speakin.app.ui.notedetail

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.speakin.app.R
import com.speakin.app.data.local.entity.ColumnData
import com.speakin.app.data.local.entity.DocNode
import com.speakin.app.data.local.entity.RichSegment
import com.speakin.app.data.local.entity.SpanInfo
import com.speakin.app.data.local.entity.SpanType
import com.speakin.app.ui.recording.RecordingBar
import com.speakin.app.ui.theme.SpeakInRecording
import com.speakin.app.util.FormatUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAudioEditor: (Int) -> Unit = {},
    viewModel: NoteDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var focusSegmentIndex by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val tempFile = File(context.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(it)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            viewModel.addImage(tempFile)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val ext = context.contentResolver.getType(it)
                ?.substringAfterLast("/")
                ?.takeIf { type -> type != "*" && type.length <= 5 }
                ?: "m4a"
            val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(it)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            viewModel.importAudio(tempFile)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    fun requestMicThenRecord() {
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (perm == PackageManager.PERMISSION_GRANTED) {
            viewModel.startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Fullscreen image dialog
    fullscreenImagePath?.let { path ->
        Dialog(
            onDismissRequest = { fullscreenImagePath = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullscreenImagePath = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(path))
                        .crossfade(true)
                        .build(),
                    contentDescription = "Fullscreen image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = { fullscreenImagePath = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.note),
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
            },
            bottomBar = {
                RecordingBar(
                    isRecording = uiState.isRecording,
                    onStartRecording = { requestMicThenRecord() },
                    onStopRecording = { viewModel.stopRecording() },
                    liveCaption = uiState.liveCaption,
                    liveCaptionStableLen = uiState.liveCaptionStableLen,
                    isTranscribing = uiState.isTranscribing,
                    onAddText = {
                        val updated = uiState.blocks.toMutableList()
                        updated.add(DocNode.Segment(RichSegment.Text("")))
                        viewModel.onBlocksChanged(updated)
                        focusSegmentIndex = updated.size - 1
                    },
                    onAddImage = {
                        imagePickerLauncher.launch("image/*")
                    },
                    onImportAudio = {
                        audioPickerLauncher.launch("audio/*")
                    },
                    onAddColumn = {
                        viewModel.addColumnGroup()
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    // Loading
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    // Transcribing with no segments yet
                    uiState.isTranscribing && uiState.blocks.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.transcribing),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Transcribe error with no segments yet — show error with retry option
                    uiState.blocks.isEmpty() && !uiState.isRecording && uiState.transcribeError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = uiState.transcribeError ?: "Transcription failed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Empty state (no segments, not recording)
                    uiState.blocks.isEmpty() && !uiState.isRecording && !uiState.isTranscribing -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.tap_to_record),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Rich content editor — always shows at least one editable text area
                    else -> {
                        val displayBlocks = if (uiState.blocks.isEmpty()) {
                            listOf(DocNode.Segment(RichSegment.Text("")))
                        } else {
                            uiState.blocks
                        }
                        RichContentArea(
                            blocks = displayBlocks,
                            isInitialEmpty = uiState.blocks.isEmpty(),
                            playingAudioPath = uiState.playingAudioPath,
                            transcribeError = uiState.transcribeError,
                            focusSegmentIndex = focusSegmentIndex,
                            onFocusDone = { focusSegmentIndex = null },
                            onBlocksChanged = { viewModel.onBlocksChanged(it) },
                            onImageClick = { fullscreenImagePath = it },
                            onAudioPlayPause = { path, play ->
                                if (play) viewModel.onPlaybackStarted(path)
                                else viewModel.onPlaybackStopped()
                            },
                            onDeleteBlock = { viewModel.deleteBlock(it) },
                            onEditAudio = { index ->
                                viewModel.onPlaybackStopped()
                                onNavigateToAudioEditor(index)
                            },
                            // Column callbacks
                            onSegmentInColumnChanged = { blockIdx, colIdx, segIdx, seg ->
                                viewModel.updateSegmentInColumn(blockIdx, colIdx, segIdx, seg)
                            },
                            onSegmentInColumnDeleted = { blockIdx, colIdx, segIdx ->
                                viewModel.deleteSegmentInColumn(blockIdx, colIdx, segIdx)
                            },
                            onColumnResized = { blockIdx, colIdx, delta ->
                                viewModel.resizeColumn(blockIdx, colIdx, delta)
                            },
                            onAddColumnToGroup = { blockIdx ->
                                viewModel.addColumnToGroup(blockIdx)
                            },
                            onRemoveColumn = { blockIdx, colIdx ->
                                viewModel.removeColumn(blockIdx, colIdx)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Rich Content Area — renders DocNode list with column support
// ═══════════════════════════════════════════════════════════════

private fun collectAudioPaths(blocks: List<DocNode>): Set<String> {
    return blocks.flatMap { node ->
        when (node) {
            is DocNode.Segment -> listOf((node.content as? RichSegment.Audio)?.audioPath)
            is DocNode.ColumnGroup -> node.columns.flatMap { col ->
                col.children.filterIsInstance<RichSegment.Audio>().map { it.audioPath }
            }
        }
    }.filterNotNull().toSet()
}

@Composable
private fun RichContentArea(
    blocks: List<DocNode>,
    isInitialEmpty: Boolean = false,
    playingAudioPath: String?,
    transcribeError: String?,
    focusSegmentIndex: Int? = null,
    onFocusDone: () -> Unit = {},
    onBlocksChanged: (List<DocNode>) -> Unit,
    onImageClick: (String) -> Unit,
    onAudioPlayPause: (String, Boolean) -> Unit,
    onDeleteBlock: (Int) -> Unit,
    onEditAudio: (Int) -> Unit = {},
    // Column callbacks
    onSegmentInColumnChanged: (blockIndex: Int, colIndex: Int, segIndex: Int, RichSegment) -> Unit = { _, _, _, _ -> },
    onSegmentInColumnDeleted: (blockIndex: Int, colIndex: Int, segIndex: Int) -> Unit = { _, _, _ -> },
    onColumnResized: (blockIndex: Int, colIndex: Int, weightDelta: Float) -> Unit = { _, _, _ -> },
    onAddColumnToGroup: (blockIndex: Int) -> Unit = {},
    onRemoveColumn: (blockIndex: Int, colIndex: Int) -> Unit = { _, _ -> }
) {
    val scrollState = rememberScrollState()
    val totalSegments = remember(blocks) { blocks.flatMap {
        when (it) {
            is DocNode.Segment -> listOf(it.content)
            is DocNode.ColumnGroup -> it.columns.flatMap { col -> col.children }
        }
    } }

    // Auto-scroll to bottom when a new block is added
    LaunchedEffect(blocks.size, totalSegments.size) {
        if (blocks.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Error banner
        if (transcribeError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = transcribeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Render each DocNode
        blocks.forEachIndexed { blockIndex, node ->
            when (node) {
                is DocNode.Segment -> {
                    val segment = node.content
                    when (segment) {
                        is RichSegment.Text -> EditableTextSegment(
                            text = segment.text,
                            spans = segment.spans,
                            onTextChanged = { newText ->
                                val updated = blocks.toMutableList()
                                updated[blockIndex] = DocNode.Segment(
                                    RichSegment.Text(newText, segment.spans)
                                )
                                onBlocksChanged(updated)
                            },
                            onDelete = {
                                if (!isInitialEmpty) onDeleteBlock(blockIndex)
                            },
                            showDelete = !isInitialEmpty || blocks.size > 1,
                            requestFocus = blockIndex == focusSegmentIndex,
                            onFocusRequested = onFocusDone
                        )
                        is RichSegment.Image -> ImageSegmentView(
                            imagePath = segment.imagePath,
                            altText = segment.altText,
                            onImageClick = { onImageClick(segment.imagePath) },
                            onDelete = { onDeleteBlock(blockIndex) }
                        )
                        is RichSegment.Audio -> AudioSegmentView(
                            audioPath = segment.audioPath,
                            durationMs = segment.durationMs,
                            transcription = segment.transcription,
                            polishedText = segment.polishedText,
                            isPlaying = playingAudioPath == segment.audioPath,
                            onPlayPause = { play ->
                                onAudioPlayPause(segment.audioPath, play)
                            },
                            onDelete = { onDeleteBlock(blockIndex) },
                            onLongPress = { onEditAudio(blockIndex) }
                        )
                    }
                }
                is DocNode.ColumnGroup -> {
                    ColumnGroupView(
                        columns = node.columns,
                        blockIndex = blockIndex,
                        playingAudioPath = playingAudioPath,
                        onSegmentInColumnChanged = { colIdx, segIdx, seg ->
                            onSegmentInColumnChanged(blockIndex, colIdx, segIdx, seg)
                        },
                        onSegmentInColumnDeleted = { colIdx, segIdx ->
                            onSegmentInColumnDeleted(blockIndex, colIdx, segIdx)
                        },
                        onColumnResized = { colIdx, delta ->
                            onColumnResized(blockIndex, colIdx, delta)
                        },
                        onImageClick = onImageClick,
                        onAudioPlayPause = onAudioPlayPause,
                        onAddColumn = { onAddColumnToGroup(blockIndex) },
                        onRemoveColumn = { colIdx -> onRemoveColumn(blockIndex, colIdx) }
                    )
                }
            }
        }

        // Bottom spacer so content doesn't hide behind recording bar
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// Column Group View — side-by-side columns with draggable dividers
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ColumnGroupView(
    columns: List<ColumnData>,
    blockIndex: Int,
    playingAudioPath: String?,
    onSegmentInColumnChanged: (colIndex: Int, segIndex: Int, RichSegment) -> Unit,
    onSegmentInColumnDeleted: (colIndex: Int, segIndex: Int) -> Unit,
    onColumnResized: (colIndex: Int, weightDelta: Float) -> Unit,
    onImageClick: (String) -> Unit,
    onAudioPlayPause: (String, Boolean) -> Unit,
    onAddColumn: () -> Unit,
    onRemoveColumn: (colIndex: Int) -> Unit
) {
    val totalWeight = columns.sumOf { it.weight.toDouble() }.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                columns.forEachIndexed { colIdx, colData ->
                    // Column content
                    Column(
                        modifier = Modifier
                            .weight(colData.weight / totalWeight)
                            .fillMaxHeight()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Render segments in this column
                        colData.children.forEachIndexed { segIdx, segment ->
                            when (segment) {
                                is RichSegment.Text -> EditableTextSegment(
                                    text = segment.text,
                                    spans = segment.spans,
                                    onTextChanged = { newText ->
                                        onSegmentInColumnChanged(colIdx, segIdx,
                                            RichSegment.Text(newText, segment.spans))
                                    },
                                    onDelete = {
                                        onSegmentInColumnDeleted(colIdx, segIdx)
                                    },
                                    showDelete = true
                                )
                                is RichSegment.Image -> ImageSegmentView(
                                    imagePath = segment.imagePath,
                                    altText = segment.altText,
                                    onImageClick = { onImageClick(segment.imagePath) },
                                    onDelete = {
                                        onSegmentInColumnDeleted(colIdx, segIdx)
                                    }
                                )
                                is RichSegment.Audio -> AudioSegmentView(
                                    audioPath = segment.audioPath,
                                    durationMs = segment.durationMs,
                                    transcription = segment.transcription,
                                    polishedText = segment.polishedText,
                                    isPlaying = playingAudioPath == segment.audioPath,
                                    onPlayPause = { play ->
                                        onAudioPlayPause(segment.audioPath, play)
                                    },
                                    onDelete = {
                                        onSegmentInColumnDeleted(colIdx, segIdx)
                                    },
                                    onLongPress = {}
                                )
                            }
                        }

                        // "+" button to add content to this column
                        var showColumnMenu by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { showColumnMenu = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.TextFields,
                                    contentDescription = "Add to column",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Mini add-menu
                        if (showColumnMenu) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                listOf(
                                    "文本" to Icons.Default.TextFields,
                                    "图片" to Icons.Default.Image,
                                    "音频" to Icons.Default.Mic
                                ).forEach { (label, icon) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                showColumnMenu = false
                                                when (label) {
                                                    "文本" -> onSegmentInColumnChanged(
                                                        colIdx, colData.children.size,
                                                        RichSegment.Text("")
                                                    )
                                                    // Image/Audio handled via external pickers;
                                                    // for now, just add a text placeholder
                                                    else -> onSegmentInColumnChanged(
                                                        colIdx, colData.children.size,
                                                        RichSegment.Text("")
                                                    )
                                                }
                                            }
                                            .padding(4.dp)
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = label,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Delete column button (only if > 2 columns)
                        if (columns.size > 2) {
                            IconButton(
                                onClick = { onRemoveColumn(colIdx) },
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.CenterHorizontally)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove column",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    // Draggable divider between columns
                    if (colIdx < columns.size - 1) {
                        ColumnDivider(
                            onDrag = { delta ->
                                onColumnResized(colIdx, delta)
                            }
                        )
                    }
                }
            }

            // "Add column" button at the bottom of the group (max 4)
            if (columns.size < 4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddColumn() }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "＋ 添加列",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Column Divider — draggable vertical separator between columns
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ColumnDivider(onDrag: (Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = Modifier
            .width(20.dp)  // visual + touch target combined
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    // Convert pixel delta to weight delta.
                    // A larger drag = more weight shift. Normalize roughly by screen width.
                    val weightDelta = dragAmount / 400f  // heuristic: ~400px per weight unit
                    onDrag(weightDelta)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Visual divider line
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Editable Text Segment
// ═══════════════════════════════════════════════════════════════

@Composable
private fun EditableTextSegment(
    text: String,
    spans: List<SpanInfo>,
    onTextChanged: (String) -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean = true,
    requestFocus: Boolean = false,
    onFocusRequested: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                annotatedString = buildAnnotatedForDisplay(text, spans),
                selection = TextRange(text.length)
            )
        )
    }

    // Sync when external changes modify the text (e.g. undo, paste from another source)
    LaunchedEffect(text) {
        if (text != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                annotatedString = buildAnnotatedForDisplay(text, spans),
                selection = TextRange(text.length)
            )
        }
    }

    // Request focus when this segment is newly added
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            onFocusRequested()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                onTextChanged(newValue.text)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            minLines = 1
        )
        if (showDelete) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Image Segment View
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ImageSegmentView(
    imagePath: String,
    altText: String,
    onImageClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    // Decode image dimensions to detect aspect ratio (height / width)
    var imageAspectRatio by remember { mutableStateOf(1f) }
    var dimensionsReady by remember { mutableStateOf(false) }
    LaunchedEffect(imagePath) {
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                imageAspectRatio = options.outHeight.toFloat() / options.outWidth.toFloat()
            }
            dimensionsReady = true
        }
    }

    // Phone screenshot or photo: portrait orientation (height > width)
    val isPhoneLike = dimensionsReady && imageAspectRatio > 1.0f

    // Thumbnail width: 1/5 of screen width
    val thumbnailWidth = (screenWidthDp / 5).dp

    val boxModifier = Modifier
        .width(thumbnailWidth)
        .then(
            if (isPhoneLike) {
                // Phone-shaped rectangle: 9:16 aspect ratio
                Modifier.height(thumbnailWidth * (16f / 9f))
            } else {
                // Non-phone images: auto height with reasonable cap
                Modifier.heightIn(max = 200.dp)
            }
        )
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onImageClick)

    Box(modifier = boxModifier) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(imagePath))
                .crossfade(true)
                .size(600)
                .build(),
            contentDescription = altText.ifEmpty { "Image" },
            contentScale = if (isPhoneLike) ContentScale.Crop else ContentScale.FillWidth,
            modifier = if (isPhoneLike) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        )

        // Delete button
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove image",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }

        // Alt text overlay
        if (altText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = altText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Audio Segment View
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioSegmentView(
    audioPath: String,
    durationMs: Long,
    transcription: String?,
    polishedText: String?,
    isPlaying: Boolean,
    onPlayPause: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val label = polishedText?.takeIf { it.isNotBlank() }
        ?: transcription?.takeIf { it.isNotBlank() }
        ?: "Audio (${FormatUtils.formatDuration(durationMs)})"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { onPlayPause(!isPlaying) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = FormatUtils.formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Remove audio",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Expandable transcription details
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
            ) {
                if (transcription.isNullOrEmpty() && polishedText.isNullOrEmpty()) {
                    // Model not ready / transcription unavailable
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Transcription unavailable · download ASR model in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    if (!transcription.isNullOrEmpty()) {
                        Text(
                            text = transcription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!polishedText.isNullOrEmpty() && polishedText != transcription) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = polishedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════

/** Build an AnnotatedString with formatting spans for display/editing. */
private fun buildAnnotatedForDisplay(
    text: String,
    spans: List<SpanInfo>
): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        for (sp in spans) {
            val start = sp.start.coerceIn(0, text.length)
            val end = sp.end.coerceIn(0, text.length)
            if (start < end) {
                addStyle(spanTypeToDisplayStyle(sp.type), start, end)
            }
        }
    }
}

private fun spanTypeToDisplayStyle(type: SpanType): androidx.compose.ui.text.SpanStyle {
    return when (type) {
        SpanType.BOLD -> androidx.compose.ui.text.SpanStyle(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        SpanType.ITALIC -> androidx.compose.ui.text.SpanStyle(
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
        SpanType.UNDERLINE -> androidx.compose.ui.text.SpanStyle(
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
        )
        SpanType.STRIKETHROUGH -> androidx.compose.ui.text.SpanStyle(
            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
        )
        SpanType.HEADING -> androidx.compose.ui.text.SpanStyle(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}
