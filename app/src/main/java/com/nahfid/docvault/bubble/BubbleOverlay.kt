package com.nahfid.docvault.bubble

import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nahfid.docvault.data.VaultFile
import com.nahfid.docvault.data.VaultFolder
import com.nahfid.docvault.ui.VaultViewModel
import kotlin.math.abs
import kotlin.math.min

/** Geometry of the strip UI, shared with [BubbleService] so it can anchor the window. */
const val BUBBLE_SIZE_DP = 56
const val STRIP_SLOT_WIDTH_DP = 84
const val STRIP_SLOT_HEIGHT_DP = 64
const val STRIP_HAMBURGER_WIDTH_DP = 56
const val STRIP_GAP_DP = 8
const val STRIP_PADDING_DP = 8
const val DETAIL_STRIP_WIDTH_DP = 200
const val DETAIL_ROW_HEIGHT_DP = 52

/** Width of the horizontal strip for [folderCount] top-level folders, in dp. */
fun stripWidthDp(folderCount: Int): Int =
    STRIP_PADDING_DP * 2 + STRIP_HAMBURGER_WIDTH_DP +
        folderCount * (STRIP_SLOT_WIDTH_DP + STRIP_GAP_DP)

/**
 * The floating UI, in three states:
 *
 *  Collapsed -> a small draggable bubble (WhisperFlow-style).
 *  Strip     -> tap the bubble, it opens into a slim horizontal strip:
 *               a hamburger/X slot plus one small rectangle per
 *               top-level folder (icon + name). The strip grows away
 *               from whichever screen edge the bubble is on, so it
 *               never runs off-screen. Long-press a folder slot and
 *               drag sideways to reorder it.
 *  Detail    -> tap a folder and a slim vertical strip opens above it
 *               (below it when the bubble is near the top of the
 *               screen), listing that folder's children — the two
 *               strips form an L. A folder with no children opens
 *               its files instead. The hamburger slot turns into an
 *               X; tapping it collapses everything back to the bubble.
 *
 * The same [VaultPanel] content is reused full-screen by MainActivity,
 * so the vault is usable with or without the bubble. Adding folders
 * happens there; the strip is for opening things fast.
 *
 * The overlay window stays non-focusable in every state: there is no
 * text input in the strips, so touches outside the bubble and strips
 * keep reaching the app underneath.
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
    onDragOut: (VaultFile, View) -> Unit,
    openLeft: Boolean,
    anchorYPx: Int,
    onContentSize: (widthPx: Int, heightPx: Int, detailAbove: Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Path from the tapped top-level folder down to the folder shown
    // in the detail area. Empty means no detail is open.
    var detailPath by remember { mutableStateOf<List<String>>(emptyList()) }

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

    val state by viewModel.state.collectAsState()
    val folders = viewModel.topLevelFolders()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val stripWidth = min(stripWidthDp(folders.size), screenWidthDp - 16)

    val detailId = detailPath.lastOrNull()
    val detailFolder = detailId?.let { id -> state.folders.firstOrNull { it.id == id } }
    val detailChildren = detailFolder?.let { viewModel.childFolders(it.id) } ?: emptyList()
    // Height the detail area will need, in dp. Only used to decide
    // whether it fits above the strip.
    val detailHeightDp = when {
        detailFolder == null -> 0
        detailChildren.isEmpty() -> 340
        else -> 16 + detailChildren.size * DETAIL_ROW_HEIGHT_DP +
            if (detailPath.size > 1) DETAIL_ROW_HEIGHT_DP else 0
    }
    val anchorYDp = with(density) { anchorYPx.toDp().value }
    val nearTop = detailFolder != null &&
        anchorYDp < detailHeightDp + STRIP_SLOT_HEIGHT_DP + 48
    val detailAbove = detailFolder != null && !nearTop

    // X offset of the vertical strip, lined up under the tapped
    // top-level slot and clamped so it never leaves the window.
    val topIndex = detailPath.firstOrNull()
        ?.let { id -> folders.indexOfFirst { it.id == id } } ?: -1
    val innerWidth = stripWidth - STRIP_PADDING_DP * 2
    val slotPos = if (topIndex < 0) 0 else topIndex + if (openLeft) 0 else 1
    val detailOffsetXDp = (slotPos * (STRIP_SLOT_WIDTH_DP + STRIP_GAP_DP))
        .coerceIn(0, (innerWidth - DETAIL_STRIP_WIDTH_DP).coerceAtLeast(0))

    val closeDetail = { detailPath = emptyList() }
    val goBack = { detailPath = detailPath.dropLast(1) }

    Column(
        modifier = Modifier
            .width(stripWidth.dp)
            .onGloballyPositioned { coords ->
                onContentSize(coords.size.width, coords.size.height, detailAbove)
            }
            .padding(STRIP_PADDING_DP.dp)
    ) {
        if (detailFolder != null && detailAbove) {
            DetailArea(
                viewModel = viewModel,
                folder = detailFolder,
                offsetXDp = detailOffsetXDp,
                hasParent = detailPath.size > 1,
                onNavigateInto = { detailPath = detailPath + it },
                onNavigateBack = goBack,
                onImportRequest = onImportRequest,
                onOpenFile = onOpenFile,
                onShareFile = onShareFile,
                onDragOut = onDragOut
            )
            Spacer(modifier = Modifier.height(STRIP_GAP_DP.dp))
        }
        FolderStrip(
            folders = folders,
            openLeft = openLeft,
            detailTopId = detailPath.firstOrNull(),
            detailOpen = detailFolder != null,
            onToggleFolder = { folder ->
                detailPath =
                    if (detailPath.firstOrNull() == folder.id) emptyList()
                    else listOf(folder.id)
            },
            onMoveFolder = { folder, index, delta ->
                viewModel.moveFolder(folder.id, index + delta)
            },
            onCollapse = {
                expanded = false
                detailPath = emptyList()
                onCollapse()
            }
        )
        if (detailFolder != null && !detailAbove) {
            Spacer(modifier = Modifier.height(STRIP_GAP_DP.dp))
            DetailArea(
                viewModel = viewModel,
                folder = detailFolder,
                offsetXDp = detailOffsetXDp,
                hasParent = detailPath.size > 1,
                onNavigateInto = { detailPath = detailPath + it },
                onNavigateBack = goBack,
                onImportRequest = onImportRequest,
                onOpenFile = onOpenFile,
                onShareFile = onShareFile,
                onDragOut = onDragOut
            )
        }
    }
}

/**
 * The slim horizontal strip: the collapse slot plus one small
 * rectangle per top-level folder. The collapse slot sits on the side
 * the bubble came from, so the strip grows away from the screen edge.
 */
@Composable
fun FolderStrip(
    folders: List<VaultFolder>,
    openLeft: Boolean,
    detailTopId: String?,
    detailOpen: Boolean,
    onToggleFolder: (VaultFolder) -> Unit,
    onMoveFolder: (VaultFolder, Int, Int) -> Unit,
    onCollapse: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!openLeft) CollapseSlot(detailOpen = detailOpen, onCollapse = onCollapse)
        folders.forEachIndexed { index, folder ->
            FolderSlot(
                folder = folder,
                selected = folder.id == detailTopId,
                onToggle = { onToggleFolder(folder) },
                onMove = { delta -> onMoveFolder(folder, index, delta) }
            )
        }
        if (openLeft) CollapseSlot(detailOpen = detailOpen, onCollapse = onCollapse)
    }
}

/**
 * The slot where the bubble was. Shows a hamburger while only the
 * strip is open, morphs into an X once a detail is open. Either way,
 * tapping it collapses everything back to the bubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollapseSlot(detailOpen: Boolean, onCollapse: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(
                width = STRIP_HAMBURGER_WIDTH_DP.dp,
                height = STRIP_SLOT_HEIGHT_DP.dp
            )
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .combinedClickable(onClick = onCollapse)
    ) {
        AnimatedContent(
            targetState = detailOpen,
            transitionSpec = {
                fadeIn() + scaleIn(initialScale = 0.6f) togetherWith
                    fadeOut() + scaleOut(targetScale = 0.6f)
            },
            label = "collapseIcon"
        ) { open ->
            Icon(
                imageVector = if (open) Icons.Filled.Close else Icons.Filled.Menu,
                contentDescription = "Collapse back to bubble",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** One small rectangle in the horizontal strip: a folder's icon + name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderSlot(
    folder: VaultFolder,
    selected: Boolean,
    onToggle: () -> Unit,
    onMove: (delta: Int) -> Unit
) {
    val density = LocalDensity.current
    var dragAccum by remember { mutableStateOf(0f) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(
                width = STRIP_SLOT_WIDTH_DP.dp,
                height = STRIP_SLOT_HEIGHT_DP.dp
            )
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .combinedClickable(onClick = onToggle)
            .pointerInput(folder.id) {
                val thresholdPx =
                    with(density) { (STRIP_SLOT_WIDTH_DP + STRIP_GAP_DP).dp.toPx() }
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragAccum = 0f },
                    onDragEnd = { dragAccum = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount.x
                        // One slot is about 92dp wide; cross it to swap once.
                        if (dragAccum > thresholdPx) { onMove(1); dragAccum = 0f }
                        if (dragAccum < -thresholdPx) { onMove(-1); dragAccum = 0f }
                    }
                )
            }
            .padding(4.dp)
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The second half of the L: either the vertical strip of a folder's
 * children (lined up under the tapped slot) or, for a folder with no
 * children, that folder's files.
 */
@Composable
fun DetailArea(
    viewModel: VaultViewModel,
    folder: VaultFolder,
    offsetXDp: Int,
    hasParent: Boolean,
    onNavigateInto: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onImportRequest: (folderId: String, placeholderName: String?) -> Unit,
    onOpenFile: (VaultFile) -> Unit,
    onShareFile: (VaultFile) -> Unit,
    onDragOut: (VaultFile, View) -> Unit
) {
    val children = viewModel.childFolders(folder.id)
    if (children.isEmpty()) {
        FilesPanel(
            viewModel = viewModel,
            folder = folder,
            onCloseFiles = onNavigateBack,
            onImportRequest = onImportRequest,
            onOpenFile = onOpenFile,
            onShareFile = onShareFile,
            onDragOut = onDragOut
        )
        return
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = Modifier
            .width(DETAIL_STRIP_WIDTH_DP.dp)
            .padding(start = offsetXDp.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (hasParent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            children.forEachIndexed { index, child ->
                FolderRow(
                    folder = child,
                    hasChildren = viewModel.childFolders(child.id).isNotEmpty(),
                    onOpen = { onNavigateInto(child.id) },
                    onMove = { delta -> viewModel.moveFolder(child.id, index + delta) }
                )
                if (index < children.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
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
                            // Finger lifted: changedToUpOrCanceled() does not
                            // exist in Compose 1.6, so check it directly.
                            if (change.previousPressed && !change.pressed) break
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
