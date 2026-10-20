package com.drivercall.push

import com.drivercall.domain.call.CallCoordinator
import com.drivercall.data.security.TokenStorage
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Data-only FCM wake-up. It never opens the microphone; answering remains an
 * explicit user action mediated by Telecom.
 */
@AndroidEntryPoint
class CallingFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var coordinator: CallCoordinator
    @Inject lateinit var tokenStorage: TokenStorage

    override fun onNewToken(token: String) {
        tokenStorage.savePushToken(token)
        // Deployment supplies the backend FCM adapter/registration endpoint.
        // Keeping the newest token lets that adapter register it after sign-in.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "incoming_call") return
        val callId = message.data["callId"] ?: return
        val from = message.data["from"] ?: return
        val name = message.data["name"].orEmpty().ifBlank { "Friend" }
        val expiresAt = message.data["expiresAt"]?.toLongOrNull()
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) return
        coordinator.prepareIncomingFromPush(callId, from, name)
    }
}
