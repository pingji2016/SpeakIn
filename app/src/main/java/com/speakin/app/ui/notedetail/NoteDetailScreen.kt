package com.speakin.app.ui.notedetail

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.layout.ContentScale
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
import com.speakin.app.data.local.entity.RichSegment
import com.speakin.app.data.local.entity.SpanInfo
import com.speakin.app.data.local.entity.SpanType
import com.speakin.app.ui.recording.RecordingBar
import com.speakin.app.ui.theme.SpeakInRecording
import com.speakin.app.util.FormatUtils
import java.io.File

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
                        val updated = uiState.segments.toMutableList()
                        updated.add(RichSegment.Text(""))
                        viewModel.onSegmentsChanged(updated)
                        focusSegmentIndex = updated.size - 1
                    },
                    onAddImage = {
                        imagePickerLauncher.launch("image/*")
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
                    uiState.isTranscribing && uiState.segments.isEmpty() -> {
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
                    uiState.segments.isEmpty() && !uiState.isRecording && uiState.transcribeError != null -> {
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
                    uiState.segments.isEmpty() && !uiState.isRecording && !uiState.isTranscribing -> {
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
                        val displaySegments = if (uiState.segments.isEmpty()) {
                            listOf(RichSegment.Text(""))
                        } else {
                            uiState.segments
                        }
                        RichContentArea(
                            segments = displaySegments,
                            isInitialEmpty = uiState.segments.isEmpty(),
                            playingAudioPath = uiState.playingAudioPath,
                            transcribeError = uiState.transcribeError,
                            focusSegmentIndex = focusSegmentIndex,
                            onFocusDone = { focusSegmentIndex = null },
                            onSegmentsChanged = { viewModel.onSegmentsChanged(it) },
                            onImageClick = { fullscreenImagePath = it },
                            onAudioPlayPause = { path, play ->
                                if (play) viewModel.onPlaybackStarted(path)
                                else viewModel.onPlaybackStopped()
                            },
                            onDeleteSegment = { viewModel.deleteSegment(it) },
                            onEditAudio = { index ->
                                viewModel.onPlaybackStopped()
                                onNavigateToAudioEditor(index)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Rich Content Area — inline segments flowing vertically
// ═══════════════════════════════════════════════════════════════

@Composable
private fun RichContentArea(
    segments: List<RichSegment>,
    isInitialEmpty: Boolean = false,
    playingAudioPath: String?,
    transcribeError: String?,
    focusSegmentIndex: Int? = null,
    onFocusDone: () -> Unit = {},
    onSegmentsChanged: (List<RichSegment>) -> Unit,
    onImageClick: (String) -> Unit,
    onAudioPlayPause: (String, Boolean) -> Unit,
    onDeleteSegment: (Int) -> Unit,
    onEditAudio: (Int) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when a new segment is added
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) {
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

        // Render each segment inline
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is RichSegment.Text -> EditableTextSegment(
                    text = segment.text,
                    spans = segment.spans,
                    onTextChanged = { newText ->
                        if (isInitialEmpty) {
                            // First keystroke: persist the new segment
                            onSegmentsChanged(listOf(RichSegment.Text(newText)))
                        } else {
                            val updated = segments.toMutableList()
                            updated[index] = RichSegment.Text(newText, segment.spans)
                            onSegmentsChanged(updated)
                        }
                    },
                    onDelete = {
                        if (!isInitialEmpty) onDeleteSegment(index)
                    },
                    showDelete = !isInitialEmpty || segments.size > 1,
                    requestFocus = index == focusSegmentIndex,
                    onFocusRequested = onFocusDone
                )
                is RichSegment.Image -> ImageSegmentView(
                    imagePath = segment.imagePath,
                    altText = segment.altText,
                    onImageClick = { onImageClick(segment.imagePath) },
                    onDelete = { onDeleteSegment(index) }
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
                    onDelete = { onDeleteSegment(index) },
                    onLongPress = { onEditAudio(index) }
                )
            }
        }

        // Bottom spacer so content doesn't hide behind recording bar
        Spacer(modifier = Modifier.height(32.dp))
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onImageClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(File(imagePath))
                .crossfade(true)
                .size(600)
                .build(),
            contentDescription = altText.ifEmpty { "Image" },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
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
