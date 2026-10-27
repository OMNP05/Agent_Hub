package dev.agenthub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.agenthub.app.agents.oauth.OAuthLinkParser
import dev.agenthub.app.agents.oauth.OAuthReturnBus
import dev.agenthub.app.push.ApprovalNotifier
import dev.agenthub.app.push.NotificationNavigation
import javax.inject.Inject
import dev.agenthub.app.core.ui.theme.AgentHubTheme
import dev.agenthub.app.core.ui.theme.ThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var oauthReturns: OAuthReturnBus
    @Inject lateinit var notificationNavigation: NotificationNavigation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAppLink(intent)
        enableEdgeToEdge()
        setContent {
            AgentHubTheme(mode = ThemeMode.System) {
                AuthGate()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppLink(intent)
    }

    private fun handleAppLink(intent: Intent?) {
        OAuthLinkParser.parse(intent?.dataString, BuildConfig.APP_LINK_HOST)?.let(oauthReturns::publish)
        if (intent?.action == ApprovalNotifier.ACTION_OPEN_APPROVAL) {
            intent.getStringExtra(ApprovalNotifier.EXTRA_APPROVAL_ID)?.takeIf { it.isNotBlank() }
                ?.let(notificationNavigation::openApproval)
        }
    }
}
