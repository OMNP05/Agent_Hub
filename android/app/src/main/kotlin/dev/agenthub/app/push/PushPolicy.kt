package dev.agenthub.app.push

/**
 * Whether a received push may be shown. Kept separate from the service so the rules are unit tested.
 * A signed-out device shows nothing: it should already be unregistered, and a late push must not leak
 * that an agent exists.
 */
object PushPolicy {
    fun shouldShow(signedIn: Boolean, notificationsEnabled: Boolean, payload: ApprovalPush?): Boolean =
        payload != null && signedIn && notificationsEnabled
}
