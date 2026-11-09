package com.nahfid.docvault

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nahfid.docvault.bubble.BubbleService
import com.nahfid.docvault.bubble.FilesPanel
import com.nahfid.docvault.bubble.VaultPanel
import com.nahfid.docvault.data.VaultFolder
import com.nahfid.docvault.share.ShareHelper
import com.nahfid.docvault.ui.DocVaultTheme
import com.nahfid.docvault.ui.VaultViewModel

/**
 * The app's own screen. It does three jobs:
 * 1. Permission gate: "Draw over other apps" must be granted in
 *    Settings before the bubble can appear; the button below opens
 *    that exact Settings page. On Android 13+ the notification
 *    permission is also requested (the bubble is a foreground service).
 * 2. Bubble switch: start / hide the floating bubble.
 * 3. Full vault: the same folders + files panels as the bubble,
 *    full-screen, for organising without the overlay. Importing a
 *    file uses the Storage Access Framework picker (OpenDocument),
 *    so the user picks a PDF or image from anywhere on the device
 *    and it is copied into app-private storage.
 *
 * When the bubble asks to import (tap a placeholder, or + Import),
 * it opens this Activity with EXTRA_IMPORT_FOLDER; this screen then
 * launches the picker for that folder automatically.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: VaultViewModel
    private var pendingImportFolderId: String? = null
    private var pendingPlaceholderName: String? = null

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) startBubble() else
                Toast.makeText(this, "Overlay permission is needed for the bubble.", Toast.LENGTH_LONG).show()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val pickDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val folderId = pendingImportFolderId ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            val name = displayNameOf(uri) ?: pendingPlaceholderName ?: "document"
            val mime = contentResolver.getType(uri)
            viewModel.importFile(folderId, uri, name, mime)
            Toast.makeText(this, "Imported $name", Toast.LENGTH_SHORT).show()
            pendingImportFolderId = null
            pendingPlaceholderName = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = VaultViewModel((application as DocVaultApp).repository)
        pendingImportFolderId = intent.getStringExtra(EXTRA_IMPORT_FOLDER)
        pendingPlaceholderName = intent.getStringExtra(EXTRA_PLACEHOLDER_NAME)

        setContent {
            DocVaultTheme {
                var parentId by remember { mutableStateOf<String?>(null) }
                var openFolder by remember { mutableStateOf<VaultFolder?>(null) }
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                ) {
                    Text("Doc Vault", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                    Text("Your documents, on this device only. No account, no cloud.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { ensureOverlayPermissionAndStart() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Show floating bubble") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { stopService(BubbleService.stopIntent(this@MainActivity)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Hide bubble") }
                    Spacer(modifier = Modifier.height(16.dp))
                    VaultPanel(
                        viewModel = viewModel,
                        parentId = parentId,
                        onNavigateInto = { parentId = it },
                        onNavigateBack = { parentId = null },
                        onOpenFolder = { openFolder = it },
                        onClose = { openFolder = null }
                    )
                    openFolder?.let { folder ->
                        Spacer(modifier = Modifier.height(8.dp))
                        FilesPanel(
                            viewModel = viewModel,
                            folder = folder,
                            onCloseFiles = { openFolder = null },
                            onImportRequest = { folderId, placeholder ->
                                requestImport(folderId, placeholder)
                            },
                            onOpenFile = { entry ->
                                val file = viewModel.storedFile(entry) ?: return@FilesPanel
                                ShareHelper.openFile(this@MainActivity, entry, file)
                            },
                            onShareFile = { entry ->
                                val file = viewModel.storedFile(entry) ?: return@FilesPanel
                                ShareHelper.shareFile(this@MainActivity, entry, file)
                            },
                            onDragOut = { entry, view ->
                                val file = viewModel.storedFile(entry) ?: return@FilesPanel
                                ShareHelper.startDragOut(view, this@MainActivity, entry, file)
                            }
                        )
                    }
                }
            }
        }

        if (pendingImportFolderId != null) {
            requestImport(pendingImportFolderId!!, pendingPlaceholderName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestImport(folderId: String, placeholderName: String?) {
        pendingImportFolderId = folderId
        pendingPlaceholderName = placeholderName
        pickDocumentLauncher.launch(arrayOf("application/pdf", "image/*"))
    }

    private fun ensureOverlayPermissionAndStart() {
        if (Settings.canDrawOverlays(this)) {
            startBubble()
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun startBubble() {
        startForegroundService(BubbleService.startIntent(this))
        Toast.makeText(this, "Bubble is on — tap it anywhere to open your folders.", Toast.LENGTH_LONG).show()
    }

    private fun displayNameOf(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    companion object {
        const val EXTRA_IMPORT_FOLDER = "com.nahfid.docvault.IMPORT_FOLDER"
        const val EXTRA_PLACEHOLDER_NAME = "com.nahfid.docvault.PLACEHOLDER_NAME"

        fun importIntent(context: Context, folderId: String, placeholderName: String?): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_IMPORT_FOLDER, folderId)
                putExtra(EXTRA_PLACEHOLDER_NAME, placeholderName)
            }
    }
}
