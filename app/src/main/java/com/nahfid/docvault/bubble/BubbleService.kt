package com.nahfid.docvault.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.nahfid.docvault.DocVaultApp
import com.nahfid.docvault.MainActivity
import com.nahfid.docvault.R
import com.nahfid.docvault.data.VaultFile
import com.nahfid.docvault.share.ShareHelper
import com.nahfid.docvault.ui.DocVaultTheme
import com.nahfid.docvault.ui.VaultViewModel

/**
 * Foreground service that owns the floating bubble window.
 *
 * A ComposeView cannot live in a Service on its own: Compose needs a
 * LifecycleOwner, a ViewModelStoreOwner and a SavedStateRegistryOwner
 * in the view tree, which an Activity normally provides. This service
 * supplies minimal ones itself (see [OverlayLifecycleOwner]).
 *
 * Window behaviour:
 * - Collapsed: a 56dp bubble, FLAG_NOT_FOCUSABLE so touches pass
 *   through everywhere except the bubble itself. Dragging the bubble
 *   moves the window by updating the LayoutParams x/y.
 * - Expanded: the panel needs text input (new folder name) and
 *   scrolling, so FLAG_NOT_FOCUSABLE is cleared and the window
 *   grows to wrap the panel. Tapping Close shrinks it back.
 *
 * The service is started from MainActivity after the user grants
 * "Draw over other apps". Stopping it removes the bubble.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var lifecycleOwner: OverlayLifecycleOwner
    private lateinit var viewModel: VaultViewModel

    // Bubble position before the panel opened, so collapse can put
    // the bubble back where it was.
    private var collapsedX = 0
    private var collapsedY = 0

    override fun onCreate() {
        super.onCreate()
        val repository = (application as DocVaultApp).repository
        viewModel = VaultViewModel(repository)
        lifecycleOwner = OverlayLifecycleOwner().apply { handleCreate() }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start near the right edge, vertically centred.
            x = resources.displayMetrics.widthPixels - 200
            y = resources.displayMetrics.heightPixels / 3
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                DocVaultTheme {
                    BubbleRoot(
                        viewModel = viewModel,
                        onDragBubble = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            runCatching { windowManager.updateViewLayout(this, params) }
                        },
                        onExpand = { setExpanded(true) },
                        onCollapse = { setExpanded(false) },
                        onImportRequest = { folderId, placeholderName ->
                            startActivity(
                                MainActivity.importIntent(this@BubbleService, folderId, placeholderName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        onOpenFile = { entry -> openFile(entry) },
                        onShareFile = { entry -> shareFile(entry) },
                        onDragOut = { entry, view -> dragOut(entry, view) }
                    )
                }
            }
        }
        windowManager.addView(composeView, params)
        lifecycleOwner.handleResume()
    }

    /** Called when the panel opens or closes; resizes the window. */
    fun setExpanded(value: Boolean) {
        params.flags = if (value) {
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        // The panel is 320dp wide but the bubble sits near the right
        // edge (x = width - 200px), so without this the expanded panel
        // would hang mostly off the right of the screen. Keep the whole
        // panel on screen when it opens, and put the bubble back where
        // it was when it closes.
        val metrics = resources.displayMetrics
        val panelWidthPx = (320 * metrics.density).toInt()
        val panelHeightPx = (420 * metrics.density).toInt()
        if (value) {
            collapsedX = params.x
            collapsedY = params.y
            params.x = params.x.coerceIn(0, metrics.widthPixels - panelWidthPx)
            params.y = params.y.coerceIn(0, metrics.heightPixels - panelHeightPx)
        } else {
            params.x = collapsedX
            params.y = collapsedY
        }
        runCatching { windowManager.updateViewLayout(composeView, params) }
    }

    private fun storedFile(entry: VaultFile) =
        (application as DocVaultApp).repository.storedFile(entry)

    private fun openFile(entry: VaultFile) {
        val file = storedFile(entry) ?: return
        ShareHelper.openFile(this, entry, file)
    }

    private fun shareFile(entry: VaultFile) {
        val file = storedFile(entry) ?: return
        ShareHelper.shareFile(this, entry, file)
    }

    private fun dragOut(entry: VaultFile, view: View) {
        val file = storedFile(entry) ?: return
        // Best-effort: if the target app rejects the drop, the user
        // can still share from the file's share button.
        ShareHelper.startDragOut(view, this, entry, file)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "doc_vault_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Doc Vault bubble",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Doc Vault bubble is on")
            .setContentText("Tap the bubble anywhere to open your documents.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Hide bubble", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        lifecycleOwner.handleDestroy()
        runCatching { windowManager.removeView(composeView) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.nahfid.docvault.STOP_BUBBLE"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context) =
            Intent(context, BubbleService::class.java)

        fun stopIntent(context: Context) =
            Intent(context, BubbleService::class.java).setAction(ACTION_STOP)
    }
}

/**
 * Minimal LifecycleOwner + ViewModelStoreOwner + SavedStateRegistryOwner
 * so a ComposeView can run inside a Service (no Activity).
 */
private class OverlayLifecycleOwner :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun handleCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun handleResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun handleDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
