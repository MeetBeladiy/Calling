package com.drivercall.domain.call

import kotlinx.coroutines.flow.StateFlow

interface CallCoordinator {
    val state: StateFlow<CallState>
    val audioEndpoints: StateFlow<List<CallAudioEndpoint>>
    val currentAudioEndpoint: StateFlow<CallAudioEndpoint?>

    fun startOutgoing(peerId: String, peerName: String)
    fun prepareIncomingFromPush(callId: String, peerId: String, peerName: String)
    fun acceptIncoming()
    fun declineIncoming()
    fun endCall(reason: String = "Call ended")
    fun toggleMute()
    fun selectAudioEndpoint(endpointId: String)
    fun reconnect()
    fun prepareCustomerCall(customerName: String = "Customer")
    fun restoreAfterCustomerCall()
}
