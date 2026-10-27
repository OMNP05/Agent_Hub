package dev.agenthub.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dev.agenthub.app.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AgentHubMessagingService : FirebaseMessagingService() {
    @Inject lateinit var notifier: ApprovalNotifier
    @Inject lateinit var devices: DeviceRepository
    @Inject lateinit var sessions: SessionStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        // Only well-formed approval pushes are shown; the payload never carries action details.
        PushPayload.parse(message.data)?.let(notifier::show)
    }

    override fun onNewToken(token: String) {
        // Register only for a signed-in user; the next sign-in registers the current token otherwise.
        scope.launch { if (sessions.current() != null) devices.register(token) }
    }
}
