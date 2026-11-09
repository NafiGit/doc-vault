package com.nahfid.docvault.bubble

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nahfid.docvault.data.VaultFile
import com.nahfid.docvault.data.VaultFolder
import com.nahfid.docvault.ui.VaultViewModel
import kotlin.math.abs

/**
 * The floating UI, in three states:
 *
 *  Collapsed  -> a small draggable bubble (WhisperFlow-style).
 *  Folders    -> tap the bubble, it expands into a small rectangle
 *                panel listing folders. Long-press a folder's drag
 *                handle and drag up/down to reorder it.
 *  Files      -> tap a leaf folder and a second rectangle slides up
 *                from the bottom of the panel with the files inside
 *                as icons. Tap a file to open it. Long-press a file
 *                and drag it out of the bubble to drop it on another
 *                app (best-effort); the share button always works
 *                and opens the system share sheet (WhatsApp included).
 *
 * The same [VaultPanel] content is reused full-screen by MainActivity,
 * so the vault is usable with or without the bubble.
 */

@Composable
fun BubbleRoot(
    viewModel: VaultViewModel,
    onDragBubble: (dx: Float, dy: Float) -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onImportRequest: (folderId: String, placeholderName: String?) -> Unit,
    onOpenFile: (VaultFile) -> Unit,
    onShareFile: (VaultFile) -> Unit,
    onDragOut: (VaultFile, View) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var parentId by remember { mutableStateOf<String?>(null) }
    var openFolder by remember { mutableStateOf<VaultFolder?>(null) }

    // Tell the service to grow the window (and take focus) when the
    // panel opens, and shrink it back when it closes.
    LaunchedEffect(expanded) {
        if (expanded) onExpand() else onCollapse()
    }

    if (!expanded) {
        BubbleButton(
            onTap = { expanded = true },
            onDrag = onDragBubble
        )
        return
    }

    Column(modifier = Modifier.width(320.dp)) {
        VaultPanel(
            viewModel = viewModel,
            parentId = parentId,
            onNavigateInto = { parentId = it },
            onNavigateBack = { parentId = null },
            onOpenFolder = { openFolder = it },
            onClose = {
                expanded = false
                openFolder = null
                parentId = null
                onCollapse()
            }
        )
        // Second rectangle: slides up when a folder is open.
        AnimatedVisibility(
            visible = openFolder != null,
            enter = slideInVertically(initialOffsetY = { it }) + expandIn() + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + shrinkOut() + fadeOut()
        ) {
            openFolder?.let { folder ->
                FilesPanel(
                    viewModel = viewModel,
                    folder = folder,
                    onCloseFiles = { openFolder = null },
                    onImportRequest = onImportRequest,
                    onOpenFile = onOpenFile,
                    onShareFile = onShareFile,
                    onDragOut = onDragOut
                )
            }
        }
    }
}

/**
 * Draggable bubble. A plain tap expands it; a drag moves it.
 * The two gestures are told apart by distance: if the finger moves
 * more than a few dp before lifting, it was a drag, not a tap.
 */
@Composable
fun BubbleButton(onTap: () -> Unit, onDrag: (dx: Float, dy: Float) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                // Tap vs drag, told apart by distance. detectDragGestures
                // cannot do this alone: it only reports onDragEnd after
                // a drag has actually started, so a plain tap would
                // never fire. Track the pointer manually instead.
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        var moved = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUpOrCanceled()) break
                            val delta = change.position - change.previousPosition
                            val step = abs(delta.x) + abs(delta.y)
                            if (!dragging && moved + step > 8f) dragging = true
                            if (dragging) {
                                change.consume()
                                onDrag(delta.x, delta.y)
                            }
                            moved += step
                        }
                        if (!dragging) onTap()
                    }
                }
            }
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = "Open Doc Vault",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultPanel(
    viewModel: VaultViewModel,
    parentId: String?,
    onNavigateInto: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenFolder: (VaultFolder) -> Unit,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val folders = if (parentId == null) viewModel.topLevelFolders()
        else viewModel.childFolders(parentId)
    val title = if (parentId == null) "Doc Vault" else
        state.folders.firstOrNull { it.id == parentId }?.name ?: "Doc Vault"

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (parentId != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add folder")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Text(
                text = "Long-press the handle to drag a folder and reorder it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(folders, key = { _, f -> f.id }) { index, folder ->
                    FolderRow(
                        folder = folder,
                        hasChildren = viewModel.childFolders(folder.id).isNotEmpty(),
                        onOpen = {
                            if (viewModel.childFolders(folder.id).isNotEmpty()) {
                                onNavigateInto(folder.id)
                            } else {
                                onOpenFolder(folder)
                            }
                        },
                        onMove = { delta -> viewModel.moveFolder(folder.id, index + delta) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Files stay on this device. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addFolder(newName, parentId)
                    newName = ""
                    showAdd = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderRow(
    folder: VaultFolder,
    hasChildren: Boolean,
    onOpen: () -> Unit,
    onMove: (delta: Int) -> Unit
) {
    var dragAccum by remember { mutableStateOf(0f) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = {})
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = folder.name + if (hasChildren) "  ›" else "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Drag handle: long-press and drag vertically to reorder.
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = Modifier.pointerInput(folder.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragAccum = 0f },
                        onDragEnd = { dragAccum = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount.y
                            // One row is about 72dp; cross it to swap once.
                            if (dragAccum > 72f) { onMove(1); dragAccum = 0f }
                            if (dragAccum < -72f) { onMove(-1); dragAccum = 0f }
                        }
                    )
                }
            )
        }
    }
}

@Composable
fun FilesPanel(
    viewModel: VaultViewModel,
    folder: VaultFolder,
    onCloseFiles: () -> Unit,
    onImportRequest: (folderId: String, placeholderName: String?) -> Unit,
    onOpenFile: (VaultFile) -> Unit,
    onShareFile: (VaultFile) -> Unit,
    onDragOut: (VaultFile, View) -> Unit
) {
    val files = viewModel.filesIn(folder.id)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onImportRequest(folder.id, null) }) { Text("+ Import") }
                IconButton(onClick = onCloseFiles) {
                    Icon(Icons.Filled.Close, contentDescription = "Close files")
                }
            }
            if (files.isEmpty()) {
                Text("No files yet. Tap + Import to add one.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(220.dp)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileTile(
                            file = file,
                            onTap = {
                                if (file.isPlaceholder) onImportRequest(folder.id, file.displayName)
                                else onOpenFile(file)
                            },
                            onShare = { onShareFile(file) },
                            onDragOut = onDragOut
                        )
                    }
                }
            }
            Text(
                text = "Tap to open. Long-press a file, then drag it out to drop it on WhatsApp — or use the share button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    file: VaultFile,
    onTap: () -> Unit,
    onShare: () -> Unit,
    onDragOut: (VaultFile, View) -> Unit
) {
    val view: View = LocalView.current
    val tint = when {
        file.isPlaceholder -> MaterialTheme.colorScheme.onSurfaceVariant
        file.isPdf -> Color(0xFFC0392B)
        file.isImage -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (file.isPlaceholder) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            // Plain click only: a long-press here must belong to the
            // drag-out gesture below. If combinedClickable also had an
            // onLongClick, it would fire the share sheet at the same
            // moment the drag starts. Sharing is on the share button.
            .combinedClickable(onClick = onTap)
            .pointerInput(file.id) {
                // Drag-out: long-press, then drag. On drag start the
                // View starts a global drag with the FileProvider URI;
                // the drop target decides whether it accepts it.
                // Placeholders have no file, so they cannot be dragged.
                if (!file.isPlaceholder) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragOut(file, view) },
                        onDrag = { _, _ -> }
                    )
                }
            }
            .padding(8.dp)
    ) {
        Icon(
            imageVector = when {
                file.isImage -> Icons.Filled.Image
                file.isPdf -> Icons.Filled.PictureAsPdf
                else -> Icons.Filled.Description
            },
            contentDescription = file.displayName,
            tint = tint,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = file.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (file.isPlaceholder) {
            Text("tap to import", style = MaterialTheme.typography.labelSmall)
        } else {
            IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Share ${file.displayName}", modifier = Modifier.size(18.dp))
            }
        }
    }
}
