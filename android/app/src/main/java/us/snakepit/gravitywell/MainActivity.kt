package us.snakepit.gravitywell

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import us.snakepit.gravitywell.service.AppForegroundTracker
import us.snakepit.gravitywell.service.DeepLinkIntentQueue
import us.snakepit.gravitywell.ui.shell.PendingDeepLink

class MainActivity : ComponentActivity() {

    internal val deepLinkQueue = DeepLinkIntentQueue()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        deepLinkQueue.restoreFromBundle(savedInstanceState)
        enqueueAndClearDeepLinkExtras(intent)

        setContent {
            val headDeepLink by deepLinkQueue.head.collectAsState()
            GravityWellApp(
                pendingDeepLink = headDeepLink,
                onDeepLinkConsumed = {
                    val current = headDeepLink
                    if (current != null) {
                        deepLinkQueue.acknowledge(current)
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        AppForegroundTracker.onActivityStarted()
    }

    override fun onStop() {
        AppForegroundTracker.onActivityStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueAndClearDeepLinkExtras(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        deepLinkQueue.saveToBundle(outState)
    }

    private fun enqueueAndClearDeepLinkExtras(targetIntent: Intent?) {
        if (targetIntent == null) return
        if (deepLinkQueue.enqueueIntent(targetIntent)) {
            targetIntent.removeExtra(EXTRA_SESSION_ID)
            targetIntent.removeExtra(EXTRA_HOST_ID)
        }
    }

    companion object {
        const val EXTRA_HOST_ID = "us.snakepit.gravitywell.extra.HOST_ID"
        const val EXTRA_SESSION_ID = "us.snakepit.gravitywell.extra.SESSION_ID"

        fun extractDeepLink(intent: Intent?): PendingDeepLink? {
            if (intent == null) return null
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
            if (sessionId.isEmpty()) return null
            val hostId = intent.getStringExtra(EXTRA_HOST_ID)?.trim()?.ifEmpty { null }
            return PendingDeepLink(hostId = hostId, sessionId = sessionId)
        }
    }
}
