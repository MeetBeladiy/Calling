package com.drivercall.service

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
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
        // A sticky service can be recreated while the app has no visible activity.
        // Promote without the while-in-use microphone type first; an explicit,
        // eligible call action upgrades the service in onStartCommand below.
        promoteToForeground(includeMicrophone = false)
        coordinator.state.onEach { state ->
            notifications.show(state)
            if (!state.shouldShowCallUi) stopSelf()
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(
            includeMicrophone = callActionNeedsMicrophone(
                action = intent?.action,
                incoming = coordinator.state.value.incoming
            )
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
                .onFailure { Log.e(TAG, "Could not start active-call service", it) }
        }

        fun enableMicrophone(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_ENABLE_AUDIO)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Could not enable foreground call audio", it) }
        }

        private const val TAG = "CallForeground"
    }
}

/**
 * Only actions that originate from an eligible user-visible flow may add the
 * while-in-use microphone foreground-service type. In particular, a null
 * action is an Android START_STICKY recreation and must remain phone-call-only.
 */
internal fun callActionNeedsMicrophone(action: String?, incoming: Boolean): Boolean = when (action) {
    CallForegroundService.ACTION_ACCEPT,
    CallForegroundService.ACTION_ENABLE_AUDIO -> true
    CallForegroundService.ACTION_SYNC -> !incoming
    else -> false
}

