package com.nahfid.docvault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * First-run onboarding: before the vault, the user is told which
 * permissions Doc Vault needs and why, and grants them here.
 *
 * Two permissions, both required:
 * - "Display over other apps": the bubble lives over WhatsApp and
 *   your other apps. Without it the bubble cannot appear at all.
 * - Notifications (Android 13+): the bubble runs as a foreground
 *   service, which shows a small notification while it is on.
 *
 * No storage permission is asked for: files are picked through the
 * system file picker, which needs no permission.
 *
 * "Start using Doc Vault" stays disabled until every required
 * permission is granted, and there is no way to skip ahead. Each
 * step shows its current status, refreshed when the user comes back
 * from Settings (the Activity re-checks in onResume).
 */
@Composable
fun OnboardingScreen(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    notificationsApplicable: Boolean,
    notificationsDenied: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onFinish: () -> Unit
) {
    val allGranted = overlayGranted && notificationsGranted
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Doc Vault", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Your school, university and office documents, one tap away — in a small bubble over any app.")
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "First, Doc Vault needs two permissions:",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermissionStep(
            title = "Display over other apps",
            description = "The bubble lives over WhatsApp and your other apps, so your documents are always one tap away.",
            granted = overlayGranted,
            actionLabel = "Open Settings",
            onAction = onRequestOverlay,
            blockedText = "Without this, the bubble cannot appear over other apps."
        )

        if (notificationsApplicable) {
            Spacer(modifier = Modifier.height(12.dp))
            PermissionStep(
                title = "Notifications",
                description = "Doc Vault shows a small notification while the bubble is on, so Android keeps it running.",
                granted = notificationsGranted,
                actionLabel = "Allow notifications",
                onAction = onRequestNotifications,
                blockedText = "Without this, Android may hide the bubble's notification and stop the bubble.",
                denied = notificationsDenied,
                deniedActionLabel = "Open app settings",
                onDeniedAction = onOpenAppSettings
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No storage permission needed — you pick files yourself with the system file picker.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onFinish,
            enabled = allGranted,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start using Doc Vault") }
        if (!allGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Grant the permissions above to continue.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionStep(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    blockedText: String,
    denied: Boolean = false,
    deniedActionLabel: String? = null,
    onDeniedAction: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Granted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text("Not yet", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    blockedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
                if (denied && deniedActionLabel != null && onDeniedAction != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onDeniedAction) { Text(deniedActionLabel) }
                }
            }
        }
    }
}
