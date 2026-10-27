package dev.agenthub.app.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPolicyTest {
    private val push = ApprovalPush("p1", "Work")

    @Test
    fun showsAWellFormedPushForASignedInUserWithNotificationsOn() {
        assertTrue(PushPolicy.shouldShow(signedIn = true, notificationsEnabled = true, payload = push))
    }

    @Test
    fun respectsTheUsersNotificationSwitch() {
        assertFalse(PushPolicy.shouldShow(signedIn = true, notificationsEnabled = false, payload = push))
    }

    @Test
    fun aSignedOutDeviceShowsNothing() {
        assertFalse(PushPolicy.shouldShow(signedIn = false, notificationsEnabled = true, payload = push))
    }

    @Test
    fun malformedPushesAreDropped() {
        assertFalse(PushPolicy.shouldShow(signedIn = true, notificationsEnabled = true, payload = null))
    }
}
