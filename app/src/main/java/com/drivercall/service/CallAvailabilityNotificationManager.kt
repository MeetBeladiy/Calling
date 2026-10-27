package com.drivercall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.drivercall.R
import com.drivercall.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallAvailabilityNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_AVAILABILITY,
                context.getString(R.string.availability_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.availability_notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun build(connected: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_AVAILABILITY)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                if (connected) context.getString(R.string.ready_for_calls)
                else context.getString(R.string.connecting_for_calls)
            )
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_AVAILABILITY = "calling_app_availability"
        const val NOTIFICATION_ID = 4100
    }
}
