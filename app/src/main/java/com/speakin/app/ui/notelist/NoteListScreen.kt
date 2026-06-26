package com.speakin.app.ui.notelist

import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.speakin.app.R
import com.speakin.app.data.local.dto.NoteStats
import com.speakin.app.data.local.entity.NoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: NoteListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameNoteId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteNoteId by remember { mutableStateOf<String?>(null) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var detailsStats by remember { mutableStateOf<NoteStats?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is NoteListEvent.NavigateToDetail -> onNavigateToDetail(event.noteId)
                is NoteListEvent.ShowExportShareSheet -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, event.title)
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.export_note))
                    )
                }
                is NoteListEvent.ShowNoteDetails -> {
                    detailsStats = event.stats
                    showDetailsDialog = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isSearchActive) {
                // ── 搜索栏 ──────────────────────────────
                TopAppBar(
                    title = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    stringResource(R.string.search_hint),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                cursorColor = MaterialTheme.colorScheme.onPrimary,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            } else {
                // ── 普通顶栏 ────────────────────────────
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.note_list_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_hint),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                if (uiState.isGridView) Icons.AutoMirrored.Filled.ViewList
                                else Icons.Default.ViewModule,
                                contentDescription = if (uiState.isGridView)
                                    stringResource(R.string.list_view)
                                else stringResource(R.string.grid_view),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSearchActive) {
                FloatingActionButton(
                onClick = { viewModel.createNote() },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_note),
                    modifier = Modifier.size(24.dp)
                )
            }
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.loading))
            }
        } else if (uiState.notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (uiState.isSearchActive) {
                        // 搜索无结果
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        // 无笔记
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .then(
                                    Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.LibraryMusic,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            stringResource(R.string.no_notes_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.no_notes_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (uiState.isGridView) {
            // ── 网格视图 ────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
            ) {
                items(uiState.notes, key = { it.id }) { note ->
                    NoteGridCard(
                        note = note,
                        onClick = { onNavigateToDetail(note.id) },
                        onPin = { viewModel.togglePin(note.id) },
                        onRename = {
                            renameNoteId = note.id
                            renameText = note.title
                            showRenameDialog = true
                        },
                        onDetails = { viewModel.showNoteDetails(note.id) },
                        onExport = { viewModel.exportNote(note.id) },
                        onDelete = {
                            deleteNoteId = note.id
                            showDeleteConfirm = true
                        },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(400),
                            fadeOutSpec = tween(300)
                        )
                    )
                }
            }
        } else {
            // ── 列表视图 ────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(uiState.notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onNavigateToDetail(note.id) },
                        onPin = { viewModel.togglePin(note.id) },
                        onRename = {
                            renameNoteId = note.id
                            renameText = note.title
                            showRenameDialog = true
                        },
                        onDetails = { viewModel.showNoteDetails(note.id) },
                        onExport = { viewModel.exportNote(note.id) },
                        onDelete = {
                            deleteNoteId = note.id
                            showDeleteConfirm = true
                        },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(400),
                            fadeOutSpec = tween(300)
                        )
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }

        // Rename dialog
        if (showRenameDialog && renameNoteId != null) {
            AlertDialog(
                onDismissRequest = {
                    showRenameDialog = false
                    renameNoteId = null
                },
                title = { Text(stringResource(R.string.rename_note_title)) },
                text = {
                    TextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.untitled_note)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        renameNoteId?.let { viewModel.renameNote(it, renameText) }
                        showRenameDialog = false
                        renameNoteId = null
                    }) {
                        Text(stringResource(R.string.rename))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showRenameDialog = false
                        renameNoteId = null
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Delete confirmation dialog
        if (showDeleteConfirm && deleteNoteId != null) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteConfirm = false
                    deleteNoteId = null
                },
                title = { Text(stringResource(R.string.delete_note_title)) },
                text = { Text(stringResource(R.string.delete_note_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteNoteId?.let { viewModel.deleteNote(it) }
                        showDeleteConfirm = false
                        deleteNoteId = null
                    }) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        deleteNoteId = null
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Note details dialog
        if (showDetailsDialog && detailsStats != null) {
            val stats = detailsStats!!
            val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }
            AlertDialog(
                onDismissRequest = {
                    showDetailsDialog = false
                    detailsStats = null
                },
                title = { Text(stringResource(R.string.note_details_title)) },
                text = {
                    Column {
                        DetailRow(stringResource(R.string.title_label), stats.title.ifEmpty { stringResource(R.string.untitled_note) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        DetailRow(stringResource(R.string.created_time), dateFormat.format(Date(stats.createdAt)))
                        DetailRow(stringResource(R.string.modified_time), dateFormat.format(Date(stats.updatedAt)))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        DetailRow(stringResource(R.string.content_size), stringResource(R.string.blocks_summary, stats.blockCount))
                        DetailRow(stringResource(R.string.text_blocks_count), "${stats.textBlockCount}")
                        DetailRow(stringResource(R.string.voice_blocks_count), "${stats.voiceBlockCount}")
                        DetailRow(stringResource(R.string.image_blocks_count), "${stats.imageBlockCount}")
                        if (stats.totalAudioDurationMs > 0) {
                            DetailRow(
                                stringResource(R.string.total_audio_duration),
                                formatDuration(stats.totalAudioDurationMs)
                            )
                        }
                        DetailRow(
                            stringResource(R.string.total_text),
                            stringResource(R.string.characters_count, stats.totalTextLength)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDetailsDialog = false
                        detailsStats = null
                    }) {
                        Text(stringResource(R.string.close_label))
                    }
                }
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDuration(totalMs: Long): String {
    val totalSeconds = totalMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧图标
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.RateReview,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // 中间内容
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (note.isPinned) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.pinned),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = note.title.ifEmpty { stringResource(R.string.untitled_note) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dateFormat.format(Date(note.updatedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.segments_count, note.blockCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 右侧箭头
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 长按弹出菜单
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (note.isPinned) stringResource(R.string.unpin_note)
                        else stringResource(R.string.pin_note)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onPin()
                },
                leadingIcon = {
                    Icon(
                        if (note.isPinned) Icons.Outlined.PushPin
                        else Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_note)) },
                onClick = {
                    menuExpanded = false
                    onRename()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.note_details)) },
                onClick = {
                    menuExpanded = false
                    onDetails()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_note)) },
                onClick = {
                    menuExpanded = false
                    onExport()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.IosShare,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete_note),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteGridCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶部：置顶图标
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (note.isPinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.pinned),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 中间：图标
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.RateReview,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                    )
                }

                // 底部：标题和日期
                Column {
                    Text(
                        text = note.title.ifEmpty { stringResource(R.string.untitled_note) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dateFormat.format(Date(note.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        // 长按弹出菜单
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (note.isPinned) stringResource(R.string.unpin_note)
                        else stringResource(R.string.pin_note)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onPin()
                },
                leadingIcon = {
                    Icon(
                        if (note.isPinned) Icons.Outlined.PushPin
                        else Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_note)) },
                onClick = {
                    menuExpanded = false
                    onRename()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.note_details)) },
                onClick = {
                    menuExpanded = false
                    onDetails()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_note)) },
                onClick = {
                    menuExpanded = false
                    onExport()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.IosShare,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete_note),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}
