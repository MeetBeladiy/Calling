package com.drivercall.service

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drivercall.domain.call.CallCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Holds call work outside the Activity. Core-Telecom supplies foreground-service
 * delegation while a registered call and CallStyle notification are present.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {
    @Inject lateinit var coordinator: CallCoordinator
    @Inject lateinit var notifications: CallNotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        promoteToForeground(includeMicrophone = !coordinator.state.value.incoming)
        coordinator.state.onEach { state ->
            notifications.show(state)
            if (!state.shouldShowCallUi) stopSelf()
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(
            includeMicrophone = intent?.action in setOf(ACTION_ACCEPT, ACTION_ENABLE_AUDIO) ||
                !coordinator.state.value.incoming
        )
        when (intent?.action) {
            ACTION_ACCEPT -> coordinator.acceptIncoming()
            ACTION_DECLINE -> coordinator.declineIncoming()
            ACTION_END -> coordinator.endCall()
            ACTION_TOGGLE_MUTE -> coordinator.toggleMute()
        }
        notifications.show(coordinator.state.value)
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(includeMicrophone: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        } else 0
        ServiceCompat.startForeground(
            this,
            CallNotificationManager.NOTIFICATION_ID,
            notifications.build(coordinator.state.value),
            type
        )
    }

    companion object {
        const val ACTION_SYNC = "com.drivercall.action.SYNC_CALL"
        const val ACTION_ACCEPT = "com.drivercall.action.ACCEPT_CALL"
        const val ACTION_DECLINE = "com.drivercall.action.DECLINE_CALL"
        const val ACTION_END = "com.drivercall.action.END_CALL"
        const val ACTION_TOGGLE_MUTE = "com.drivercall.action.TOGGLE_MUTE"
        const val ACTION_ENABLE_AUDIO = "com.drivercall.action.ENABLE_CALL_AUDIO"

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).setAction(ACTION_SYNC)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun enableMicrophone(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_ENABLE_AUDIO)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}

