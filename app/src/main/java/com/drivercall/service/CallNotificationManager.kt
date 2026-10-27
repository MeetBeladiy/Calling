package com.drivercall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.drivercall.R
import com.drivercall.domain.call.CallStage
import com.drivercall.domain.call.CallState
import com.drivercall.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.call_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.call_notification_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    fun build(state: CallState): Notification {
        val person = Person.Builder()
            .setName(state.peerName.ifBlank { "Friend" })
            .setImportant(true)
            .build()
        val contentIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).apply {
                action = "com.drivercall.action.OPEN_CALL"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endIntent = serviceAction(CallForegroundService.ACTION_END, 11)
        val style = if (state.stage == CallStage.RINGING && state.incoming) {
            NotificationCompat.CallStyle.forIncomingCall(
                person,
                serviceAction(CallForegroundService.ACTION_DECLINE, 12),
                serviceAction(CallForegroundService.ACTION_ACCEPT, 13)
            )
        } else {
            NotificationCompat.CallStyle.forOngoingCall(person, endIntent)
        }
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(state.peerName.ifBlank { "Calling App" })
            .setContentText(state.statusMessage)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.shouldShowCallUi)
            .setOnlyAlertOnce(state.stage != CallStage.RINGING)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setStyle(style)
            .setFullScreenIntent(
                contentIntent,
                state.stage == CallStage.RINGING && state.incoming && canUseFullScreenIntent()
            )
            .build()
    }

    fun show(state: CallState) {
        if (!state.shouldShowCallUi) {
            cancel()
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(state))
        } catch (_: SecurityException) {
            // Telecom still exposes the call to system surfaces; UI explains notification permission.
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, CallForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    companion object {
        const val CHANNEL_CALLS = "calling_app_calls"
        const val NOTIFICATION_ID = 4101
    }
}

