package com.infinifolder.overlay

import android.app.Service
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.MotionEvent
import android.view.WindowManager
import com.infinifolder.R
import com.infinifolder.theme.ThemeManager
import io.github.gretchycat.infiniselect.FileSelectionContract

/** Floating host for Folder's complete browser, also hosted by embedded sessions. */
class FloatingContainerService : Service() {
    private lateinit var browser: FolderBrowserController
    private var floatingDialog: Dialog? = null
    private var floatingWindowReceivedFocus = false

    override fun onCreate() {
        super.onCreate()
        browser =
            FolderBrowserController(
                this,
                attachView = ::attachFloatingWindow,
                detachView = {
                    val dialog = floatingDialog
                    floatingDialog = null
                    floatingWindowReceivedFocus = false
                    dialog?.dismiss()
                },
                closeRequested = { stopSelf() },
                updateAttachedWindow = { _, params ->
                    floatingDialog?.window?.let { window ->
                        val attributes = WindowManager.LayoutParams().apply {
                            copyFrom(params)
                            flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                                WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                            dimAmount = 0f
                        }
                        window.attributes = attributes
                    }
                },
                setWindowBackgroundBlurRadius = { radius ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        floatingDialog?.window?.setBackgroundBlurRadius(radius)
                    }
                },
            )
        browser.initialize()
    }

    private fun attachFloatingWindow(view: android.view.View, params: WindowManager.LayoutParams) {
        val theme =
            if (ThemeManager.activeTheme.isDark) R.style.FloatingOverlayDialogDark
            else R.style.FloatingOverlayDialog
        val dialog = Dialog(this, theme).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(view)
        }
        val window = requireNotNull(dialog.window) { "Floating browser dialog has no window" }
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val attributes = WindowManager.LayoutParams().apply {
            copyFrom(params)
            flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            dimAmount = 0f
        }
        window.attributes = attributes
        floatingDialog = dialog
        dialog.show()
        // Dialog.show() can normalize some attributes; reapply the overlay geometry after attach.
        window.attributes = attributes
        val decor = window.decorView
        floatingWindowReceivedFocus = decor.hasWindowFocus()
        decor.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                browser.dismissFromHost()
                true
            } else false
        }
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasWindowFocus ->
            if (hasWindowFocus) {
                floatingWindowReceivedFocus = true
            } else if (floatingWindowReceivedFocus) {
                browser.dismissFromHost()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        browser.open(intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TOOLBAR_HEIGHT_DP = 39
        private const val MAX_SELECTION_LIST_ATTEMPTS = 8
        const val EXTRA_URI = "com.infinifolder.overlay.URI"
        const val EXTRA_DISPLAY_NAME = "com.infinifolder.overlay.DISPLAY_NAME"
        const val EXTRA_SELECTION_MODE = "com.infinifolder.overlay.SELECTION_MODE"
        const val EXTRA_SELECTION_SAVE_MODE = "com.infinifolder.overlay.SELECTION_SAVE_MODE"
        const val EXTRA_ALLOW_MULTIPLE = "com.infinifolder.overlay.ALLOW_MULTIPLE"
        const val EXTRA_ALLOW_RECURSIVE_AUDIO_DIRECTORIES =
            "com.infinifolder.overlay.ALLOW_RECURSIVE_AUDIO_DIRECTORIES"
        const val EXTRA_SELECTION_RECEIVER = "com.infinifolder.overlay.SELECTION_RECEIVER"
        const val EXTRA_SELECTION_MIME_TYPES = "com.infinifolder.overlay.SELECTION_MIME_TYPES"
        const val EXTRA_SELECTION_EXTENSIONS = "com.infinifolder.overlay.SELECTION_EXTENSIONS"
        const val EXTRA_HIDE_UNSUPPORTED = "com.infinifolder.overlay.HIDE_UNSUPPORTED"
        const val EXTRA_SELECTION_SCROLL_Y = "com.infinifolder.overlay.SELECTION_SCROLL_Y"

        fun intent(context: android.content.Context, uri: Uri) =
            Intent(context, FloatingContainerService::class.java).setData(uri)

        fun intent(context: android.content.Context, uri: Uri, displayName: String?) =
            intent(context, uri).putExtra(EXTRA_DISPLAY_NAME, displayName)

        fun intent(
            context: android.content.Context,
            uri: Uri,
            displayName: String?,
            preferredViewMode: io.github.gretchycat.infiniselect.FileViewMode,
        ) =
            intent(context, uri, displayName)
                .putExtra(FileSelectionContract.EXTRA_PREFERRED_VIEW_MODE, preferredViewMode.name)
    }
}
