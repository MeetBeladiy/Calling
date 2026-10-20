package com.drivercall.presentation.call

import androidx.lifecycle.ViewModel
import com.drivercall.domain.call.CallAudioEndpoint
import com.drivercall.domain.call.CallCoordinator
import com.drivercall.domain.call.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val coordinator: CallCoordinator
) : ViewModel() {
    val state: StateFlow<CallState> = coordinator.state
    val audioEndpoints: StateFlow<List<CallAudioEndpoint>> = coordinator.audioEndpoints
    val currentAudioEndpoint: StateFlow<CallAudioEndpoint?> = coordinator.currentAudioEndpoint

    fun start(peerId: String, peerName: String) = coordinator.startOutgoing(peerId, peerName)
    fun accept() = coordinator.acceptIncoming()
    fun decline() = coordinator.declineIncoming()
    fun end() = coordinator.endCall()
    fun toggleMute() = coordinator.toggleMute()
    fun selectAudio(endpointId: String) = coordinator.selectAudioEndpoint(endpointId)
    fun reconnect() = coordinator.reconnect()
    fun prepareCustomerCall(customerName: String) = coordinator.prepareCustomerCall(customerName)
    fun restoreFriendCall() = coordinator.restoreAfterCustomerCall()
}
