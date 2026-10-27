package com.drivercall.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drivercall.data.security.TokenStorage
import com.drivercall.domain.call.CallCoordinator
import com.drivercall.domain.repository.AuthRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the authenticated signaling connection reachable while the user is signed in. */
@AndroidEntryPoint
class CallAvailabilityService : Service() {
    @Inject lateinit var coordinator: CallCoordinator
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var tokenStorage: TokenStorage
    @Inject lateinit var notifications: CallAvailabilityNotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        promoteToForeground(connected = false)

        // Accessing the coordinator keeps its authenticated WebSocket collector alive.
        coordinator.state
        scope.launch {
            authRepository.observeSession().collectLatest { session ->
                if (session != null) {
                    updateNotification(connected = true)
                } else if (tokenStorage.getToken() == null) {
                    stopSelf()
                } else {
                    updateNotification(connected = false)
                }
            }
        }
        scope.launch {
            if (tokenStorage.getToken() != null) runCatching { authRepository.tryRestoreSession() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(connected: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(
            this,
            CallAvailabilityNotificationManager.NOTIFICATION_ID,
            notifications.build(connected),
            type
        )
    }

    private fun updateNotification(connected: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            NotificationManagerCompat.from(this).notify(
                CallAvailabilityNotificationManager.NOTIFICATION_ID,
                notifications.build(connected)
            )
        }
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallAvailabilityService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallAvailabilityService::class.java))
        }
    }
}
