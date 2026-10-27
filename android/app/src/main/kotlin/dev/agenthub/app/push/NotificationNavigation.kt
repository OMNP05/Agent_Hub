package dev.agenthub.app.push

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Carries "open this approval" from a tapped notification (delivered to the Activity) to the navigation host. */
@Singleton
class NotificationNavigation @Inject constructor() {
    private val channel = Channel<String>(Channel.BUFFERED)
    val approvals: Flow<String> = channel.receiveAsFlow()

    fun openApproval(approvalId: String) { channel.trySend(approvalId) }
}
