package dev.agenthub.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dev.agenthub.app.core.session.SessionStore
import dev.agenthub.app.settings.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class AgentHubMessagingService : FirebaseMessagingService() {
    @Inject lateinit var notifier: ApprovalNotifier
    @Inject lateinit var devices: DeviceRepository
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        // Only well-formed approval pushes are shown; the payload never carries action details.
        val payload = PushPayload.parse(message.data)
        // FCM delivers on a background thread already, so reading the preference here may block briefly.
        val enabled = runBlocking { settings.approvalNotifications.first() }
        if (PushPolicy.shouldShow(sessions.current() != null, enabled, payload)) notifier.show(payload!!)
    }

    override fun onNewToken(token: String) {
        // Register only for a signed-in user; the next sign-in registers the current token otherwise.
        scope.launch { if (sessions.current() != null) devices.register(token) }
    }
}
