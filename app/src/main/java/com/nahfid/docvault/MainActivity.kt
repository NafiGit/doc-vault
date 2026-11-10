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
import androidx.compose.runtime.Composable
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
import com.nahfid.docvault.data.OnboardingPrefs
import com.nahfid.docvault.data.VaultFolder
import com.nahfid.docvault.share.ShareHelper
import com.nahfid.docvault.ui.DocVaultTheme
import com.nahfid.docvault.ui.OnboardingScreen
import com.nahfid.docvault.ui.VaultViewModel

/**
 * The app's own screen. It does three jobs:
 * 1. First-run onboarding: on the first launch, before anything
 *    else, the user is told which permissions Doc Vault needs and
 *    why, and grants them — "Display over other apps" (the bubble
 *    cannot appear without it) and notifications on Android 13+
 *    (the bubble's foreground-service notification). The main
 *    screen only appears once both are granted. Completion is
 *    remembered in SharedPreferences, so it never shows again, and
 *    users who already granted both permissions skip it entirely.
 *    No storage permission is asked for: importing uses the system
 *    file picker (Storage Access Framework).
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
    private lateinit var onboardingPrefs: OnboardingPrefs
    private var pendingImportFolderId: String? = null
    private var pendingPlaceholderName: String? = null

    // Onboarding state. The statuses live here (not in the
    // composable) so they can be refreshed from onResume, when the
    // user comes back from Settings.
    private var showOnboarding by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(true)
    private var notificationAsked = false
    private var overlayRequestedFromMain = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStates()
            if (Settings.canDrawOverlays(this)) {
                if (overlayRequestedFromMain) {
                    overlayRequestedFromMain = false
                    startBubble()
                }
            } else if (overlayRequestedFromMain) {
                overlayRequestedFromMain = false
                Toast.makeText(this, "Overlay permission is needed for the bubble.", Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationAsked = true
            refreshPermissionStates()
        }

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
        onboardingPrefs = OnboardingPrefs(this)
        pendingImportFolderId = intent.getStringExtra(EXTRA_IMPORT_FOLDER)
        pendingPlaceholderName = intent.getStringExtra(EXTRA_PLACEHOLDER_NAME)
        refreshPermissionStates()
        // First run, or an upgrade where a required permission is
        // still missing: show onboarding. Users who already granted
        // both go straight to the main screen.
        showOnboarding = !onboardingPrefs.isDone() && !(overlayGranted && notificationsGranted)

        setContent {
            DocVaultTheme {
                if (showOnboarding) {
                    OnboardingScreen(
                        overlayGranted = overlayGranted,
                        notificationsGranted = notificationsGranted,
                        notificationsApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        notificationsDenied = notificationAsked && !notificationsGranted,
                        onRequestOverlay = {
                            overlayPermissionLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            )
                        },
                        onRequestNotifications = { requestNotifications() },
                        onOpenAppSettings = { openAppSettings() },
                        onFinish = { finishOnboarding() }
                    )
                } else {
                    MainVaultScreen()
                }
            }
        }

        if (pendingImportFolderId != null) {
            requestImport(pendingImportFolderId!!, pendingPlaceholderName)
        }
        if (!showOnboarding &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from Settings: re-check
        // so the onboarding steps show their current status.
        refreshPermissionStates()
    }

    /** Re-reads both required permissions into the onboarding state. */
    private fun refreshPermissionStates() {
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun finishOnboarding() {
        onboardingPrefs.setDone()
        showOnboarding = false
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationAsked &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Denied permanently: the system prompt will not show
            // again, so the only route left is app settings.
            openAppSettings()
        } else {
            notificationAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    @Composable
    private fun MainVaultScreen() {
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

    private fun requestImport(folderId: String, placeholderName: String?) {
        pendingImportFolderId = folderId
        pendingPlaceholderName = placeholderName
        pickDocumentLauncher.launch(arrayOf("application/pdf", "image/*"))
    }

    private fun ensureOverlayPermissionAndStart() {
        if (Settings.canDrawOverlays(this)) {
            startBubble()
        } else {
            overlayRequestedFromMain = true
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
