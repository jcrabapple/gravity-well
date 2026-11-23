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
import kotlinx.coroutines.flow.MutableStateFlow
import us.snakepit.gravitywell.ui.shell.PendingDeepLink

class MainActivity : ComponentActivity() {

    private val pendingDeepLinkFlow = MutableStateFlow<PendingDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        enqueueIntentIfDeepLink(intent)

        setContent {
            val deepLink by pendingDeepLinkFlow.collectAsState()
            GravityWellApp(
                pendingDeepLink = deepLink,
                onDeepLinkConsumed = { pendingDeepLinkFlow.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueIntentIfDeepLink(intent)
    }

    private fun enqueueIntentIfDeepLink(intent: Intent?) {
        val parsed = extractDeepLink(intent) ?: return
        pendingDeepLinkFlow.value = parsed
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
