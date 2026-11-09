package com.nahfid.docvault.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.content.FileProvider
import com.nahfid.docvault.data.VaultFile
import java.io.File

/**
 * Getting a file out of the vault.
 *
 * Two routes, both through FileProvider (the receiving app gets a
 * temporary read grant for one URI, never access to the vault dir):
 *
 * 1. Share sheet (primary, reliable). ACTION_SEND with the file URI,
 *    wrapped in a chooser. WhatsApp appears as a target; the user
 *    picks a chat and sends. This is the route to rely on.
 * 2. Drag out (best-effort). startDragAndDrop with DRAG_FLAG_GLOBAL
 *    lets the user drag the file icon out of the bubble and drop it
 *    on WhatsApp or another app. Whether the target accepts the drop
 *    is up to that app; WhatsApp's drop handling varies by version,
 *    so if a drop does nothing, use the share sheet instead.
 *
 * Opening/preview uses the same URI with ACTION_VIEW.
 */
object ShareHelper {

    const val AUTHORITY = "com.nahfid.docvault.fileprovider"

    fun contentUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, AUTHORITY, file)

    fun shareFile(context: Context, entry: VaultFile, file: File) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, entry.displayName)
            clipData = ClipData.newUri(context.contentResolver, entry.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Send ${entry.displayName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openFile(context: Context, entry: VaultFile, file: File) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Start a cross-app drag from a View. Call from a long-press /
     * drag gesture on a file icon, passing the touched View.
     * Returns false when the platform refuses the drag.
     */
    fun startDragOut(view: View, context: Context, entry: VaultFile, file: File): Boolean {
        val uri = contentUri(context, file)
        val clip = ClipData.newUri(context.contentResolver, entry.displayName, uri)
        val shadow = View.DragShadowBuilder(view)
        return try {
            view.startDragAndDrop(clip, shadow, null, View.DRAG_FLAG_GLOBAL)
        } catch (_: Exception) {
            false
        }
    }
}
