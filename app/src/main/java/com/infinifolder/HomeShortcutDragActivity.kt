package com.infinifolder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.DragEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.storage.DiagnosticLog

/** Temporary transparent drop surface above Home. Android launchers do not share
 * a general external-shortcut drop protocol, so the actual drop requests a pin
 * through the public API while this activity is foreground. */
class HomeShortcutDragActivity : Activity() {
    private var cancellation: ResultReceiver? = null
    private var dragEnded = false
    private var pinRequested = false
    private var receivedFocus = false
    private val handler = Handler(Looper.getMainLooper())
    private var sourceConfirmed = false
    private val missingSource = Runnable {
        if (!sourceConfirmed && !dragEnded && !pinRequested) {
            Toast.makeText(this, "Shortcut drag is no longer available", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cancellation = parcelable(EXTRA_CANCEL, ResultReceiver::class.java)
        pinRequested = savedInstanceState?.getBoolean("pinRequested") == true
        val shortcut = parcelable(EXTRA_SHORTCUT, ShortcutInfo::class.java)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (shortcut == null || shortcut.`package` != packageName || token == null || pinRequested) {
            finish()
            return
        }
        // The drag may end before this activity's window is attached. Observe
        // the source as well as view events so that a quick release cannot leave
        // a transparent activity covering Home without an active drag.
        val observer = object : ResultReceiver(Handler(mainLooper)) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == DRAG_ENDED) {
                    dragEnded = true
                    finish()
                } else if (resultCode == DRAG_ACTIVE) {
                    sourceConfirmed = true
                    handler.removeCallbacks(missingSource)
                }
            }
        }
        handler.postDelayed(missingSource, 5000)
        cancellation?.send(ATTACH_OBSERVER, Bundle().apply { putParcelable(EXTRA_OBSERVER, observer) })
        val surface = FrameLayout(this).apply { setBackgroundColor(0x16000000) }
        val prompt = TextView(this).apply {
            text = "Drop to add ${shortcut.shortLabel} to Home screen"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD202820.toInt())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        surface.addView(prompt, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(surface) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        surface.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType(MIME_TYPE) == true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> true
                DragEvent.ACTION_DROP -> {
                    val data = event.clipData
                    if (pinRequested || data == null || data.itemCount != 1 || data.getItemAt(0).text?.toString() != token) false
                    else requestPin(shortcut)
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragEnded = true
                    finish()
                    true
                }
                else -> false
            }
        }
        setContentView(surface)
        androidx.core.view.ViewCompat.requestApplyInsets(surface)
    }

    private fun requestPin(shortcut: ShortcutInfo): Boolean {
        pinRequested = true
        return try {
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager?.isRequestPinShortcutSupported != true) {
                Toast.makeText(this, "This launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show()
                false
            } else {
                val accepted = manager.requestPinShortcut(shortcut, null)
                DiagnosticLog.record("home.shortcut.drop-request", "accepted=$accepted")
                if (!accepted) Toast.makeText(this, "Launcher could not add this shortcut", Toast.LENGTH_LONG).show()
                accepted
            }
        } catch (error: Exception) {
            DiagnosticLog.record("home.shortcut.drop-failed", error = error)
            Toast.makeText(this, error.message ?: "Cannot add shortcut", Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun onPause() {
        super.onPause()
        // Home/Recents, another app or Back cancels a pending placement. A pin
        // confirmation launched by our drop is allowed to take foreground.
        if (!pinRequested && !dragEnded && !isChangingConfigurations) {
            cancellation?.send(0, Bundle.EMPTY)
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) receivedFocus = true
        else if (receivedFocus && !pinRequested && !dragEnded) {
            cancellation?.send(0, Bundle.EMPTY)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pinRequested", pinRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(missingSource)
        if (!dragEnded && !isChangingConfigurations) cancellation?.send(0, Bundle.EMPTY)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun <T : android.os.Parcelable> parcelable(key: String, type: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, type)
        else intent.getParcelableExtra(key)

    companion object {
        const val MIME_TYPE = "application/vnd.infinifolder.launcher-shortcut"
        internal const val ATTACH_OBSERVER = 1
        internal const val DRAG_ENDED = 0
        internal const val DRAG_ACTIVE = 1
        internal const val EXTRA_OBSERVER = "observer"
        private const val EXTRA_SHORTCUT = "shortcut"
        private const val EXTRA_TOKEN = "dragToken"
        private const val EXTRA_CANCEL = "cancelDrag"

        fun intent(context: Context, shortcut: ShortcutInfo, token: String, cancellation: ResultReceiver): Intent =
            Intent(context, HomeShortcutDragActivity::class.java)
                .putExtra(EXTRA_SHORTCUT, shortcut).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_CANCEL, cancellation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
