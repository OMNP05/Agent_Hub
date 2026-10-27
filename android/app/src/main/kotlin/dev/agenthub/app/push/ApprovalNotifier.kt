package dev.agenthub.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.agenthub.app.MainActivity
import dev.agenthub.app.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApprovalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.push_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Shows "<agent> needs your approval". The text is generic on purpose: the action details are only
     * shown inside the app after sign-in, never on the lock screen.
     */
    fun show(push: ApprovalPush) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_APPROVAL
            putExtra(EXTRA_APPROVAL_ID, push.approvalId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, push.approvalId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (push.agentName.isBlank()) context.getString(R.string.push_title_generic)
        else context.getString(R.string.push_title, push.agentName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.push_text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(push.approvalId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "approvals"
        const val ACTION_OPEN_APPROVAL = "dev.agenthub.app.OPEN_APPROVAL"
        const val EXTRA_APPROVAL_ID = "approval_id"
    }
}
