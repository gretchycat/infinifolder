package com.infinifolder.audio.cast

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

/** Hosts the Cast route chooser and returns to the player when it closes. */
class CastRouteActivity : AppCompatActivity() {
    private var castContext: CastContext? = null
    private var chooserWasVisible = false
    private var leftForSystemChooser = false
    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, fragment: Fragment) {
            if (fragment is DialogFragment) chooserWasVisible = true
        }
        override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
            if (chooserWasVisible && fragment is DialogFragment && !isFinishing && !isChangingConfigurations) finish()
        }
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) { finish() }
        override fun onSessionStarted(session: CastSession, sessionId: String) { finish() }
        override fun onSessionStartFailed(session: CastSession, error: Int) { finish() }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        layout.addView(TextView(this).apply { text = "Choose a Cast device"; textSize = 18f })
        val button = MediaRouteButton(this).apply { contentDescription = "Choose a Cast device" }
        layout.addView(button, LinearLayout.LayoutParams(96, 96))
        setContentView(layout)
        try {
            supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, false)
            castContext = CastContext.getSharedInstance(applicationContext)
            castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            CastButtonFactory.setUpMediaRouteButton(applicationContext, button)
            button.post { button.performClick() }
        } catch (error: Exception) {
            Toast.makeText(this, "Google Cast is unavailable on this device", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // On Android 13+ the chooser may be a separate system activity.
        if (!isFinishing) leftForSystemChooser = true
    }

    override fun onResume() {
        super.onResume()
        if (leftForSystemChooser && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onDestroy()
    }
}
