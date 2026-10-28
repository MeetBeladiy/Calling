package com.drivercall.domain.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises events from Telecom, WebRTC, telephony and connectivity callbacks. */
class CallStateMachine(initialState: CallState = CallState()) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<CallState> = mutableState.asStateFlow()

    suspend fun dispatch(event: CallEvent): CallState = mutex.withLock {
        val next = reduce(mutableState.value, event)
        mutableState.value = next
        next
    }

    companion object {
        fun reduce(current: CallState, event: CallEvent): CallState {
            if (event is CallEvent.Reset) return CallState()

            // Terminal calls must not be resurrected by queued WebRTC, signaling,
            // diagnostics, or network callbacks. IDLE likewise accepts only the
            // two events that intentionally create a new call.
            if (current.stage in setOf(CallStage.IDLE, CallStage.CALL_ENDED) &&
                event !is CallEvent.StartOutgoing && event !is CallEvent.ReceiveIncoming
            ) return current

            val eventCallId = when (event) {
                is CallEvent.StartOutgoing -> event.callId
                is CallEvent.ReceiveIncoming -> event.callId
                is CallEvent.AssignServerCallId -> event.provisionalCallId
                is CallEvent.RemoteRinging -> event.callId
                is CallEvent.Negotiating -> event.callId
                is CallEvent.AcceptFailed -> event.callId
                is CallEvent.Connected -> event.callId
                is CallEvent.NetworkLost -> event.callId
                is CallEvent.ConnectionInterrupted -> event.callId
                is CallEvent.RetryStarted -> event.callId
                is CallEvent.NetworkAvailable -> event.callId
                is CallEvent.IceRestartSucceeded -> event.callId
                is CallEvent.QualityChanged -> event.callId
                is CallEvent.CellularCallStarted -> event.callId
                is CallEvent.CellularCallActive -> event.callId
                is CallEvent.CellularCallEnded -> event.callId
                is CallEvent.RestoringVoip -> event.callId
                is CallEvent.VoipRestored -> event.callId
                is CallEvent.ResumeConnected -> event.callId
                is CallEvent.SetMuted -> event.callId
                is CallEvent.SetAudioRoute -> event.callId
                is CallEvent.Failed -> event.callId
                is CallEvent.Ended -> event.callId
                CallEvent.Reset -> null
            }
            // Ignore a late callback from a previous peer connection.
            if (current.callId != null && event !is CallEvent.StartOutgoing &&
                event !is CallEvent.ReceiveIncoming && eventCallId != current.callId
            ) return current

            return when (event) {
                is CallEvent.StartOutgoing -> if (current.hasLiveSession) current else CallState(
                    stage = CallStage.OUTGOING_VOIP_CALL,
                    callId = event.callId,
                    peerId = event.peerId,
                    peerName = event.peerName,
                    startedAtEpochMs = event.nowEpochMs,
                    statusMessage = "Calling ${event.peerName}…"
                )
                is CallEvent.ReceiveIncoming -> if (current.hasLiveSession) current else CallState(
                    stage = CallStage.RINGING,
                    callId = event.callId,
                    peerId = event.peerId,
                    peerName = event.peerName,
                    incoming = true,
                    startedAtEpochMs = event.nowEpochMs,
                    statusMessage = "Incoming call"
                )
                is CallEvent.AssignServerCallId -> if (
                    current.callId == event.provisionalCallId &&
                    current.stage == CallStage.OUTGOING_VOIP_CALL
                ) {
                    current.copy(callId = event.callId)
                } else current
                is CallEvent.RemoteRinging -> current.takeIf {
                    it.stage == CallStage.OUTGOING_VOIP_CALL
                }?.copy(stage = CallStage.RINGING, statusMessage = "Ringing…") ?: current
                is CallEvent.Negotiating -> current.takeIf {
                    it.stage in setOf(CallStage.OUTGOING_VOIP_CALL, CallStage.RINGING)
                }?.copy(
                    stage = CallStage.NEGOTIATING,
                    quality = CallQuality.UNKNOWN,
                    statusMessage = "Connecting secure audio…"
                ) ?: current
                is CallEvent.AcceptFailed -> current.takeIf {
                    it.incoming && it.stage == CallStage.NEGOTIATING
                }?.copy(
                    stage = CallStage.RINGING,
                    quality = CallQuality.UNKNOWN,
                    statusMessage = event.reason
                ) ?: current
                is CallEvent.Connected -> current.takeIf {
                    it.stage in setOf(
                        CallStage.OUTGOING_VOIP_CALL,
                        CallStage.RINGING,
                        CallStage.NEGOTIATING,
                        CallStage.RECONNECTING,
                        CallStage.RESTORING_VOIP,
                        CallStage.VOIP_RESTORED
                    )
                }?.copy(
                    stage = CallStage.CONNECTED,
                    connectedAtEpochMs = current.connectedAtEpochMs ?: event.nowEpochMs,
                    quality = if (current.quality == CallQuality.NO_INTERNET) CallQuality.UNKNOWN else current.quality,
                    statusMessage = "Connected"
                ) ?: current
                is CallEvent.NetworkLost -> current.takeIf { it.hasLiveSession }?.copy(
                    stage = CallStage.RECONNECTING,
                    quality = CallQuality.NO_INTERNET,
                    statusMessage = "Internet connection lost. Trying to reconnect…",
                    diagnostics = current.diagnostics.copy(networkLabel = "No internet")
                ) ?: current
                is CallEvent.ConnectionInterrupted -> current.takeIf {
                    it.stage in setOf(
                        CallStage.CONNECTED,
                        CallStage.POOR_CONNECTION,
                        CallStage.RECONNECTING,
                        CallStage.RESTORING_VOIP,
                        CallStage.VOIP_RESTORED
                    )
                }?.copy(
                    stage = CallStage.RECONNECTING,
                    quality = CallQuality.UNKNOWN,
                    statusMessage = "Secure audio interrupted. Reconnecting…"
                ) ?: current
                is CallEvent.RetryStarted -> current.takeIf {
                    it.stage in setOf(CallStage.VOIP_DISCONNECTED, CallStage.RECONNECTING)
                }?.copy(
                    stage = CallStage.RECONNECTING,
                    quality = CallQuality.UNKNOWN,
                    statusMessage = "Reconnecting secure audio…"
                ) ?: current
                is CallEvent.NetworkAvailable -> current.copy(
                    statusMessage = if (current.stage == CallStage.RECONNECTING) {
                        "Connection restored. Securing audio…"
                    } else current.statusMessage,
                    diagnostics = current.diagnostics.copy(networkLabel = event.label)
                )
                is CallEvent.IceRestartSucceeded -> current.takeIf {
                    it.stage in setOf(CallStage.RECONNECTING, CallStage.RESTORING_VOIP)
                }?.copy(
                    stage = CallStage.CONNECTED,
                    quality = CallQuality.GOOD,
                    diagnostics = current.diagnostics.copy(
                        reconnectionCount = current.diagnostics.reconnectionCount + 1
                    ),
                    statusMessage = "Connection restored"
                ) ?: current
                is CallEvent.QualityChanged -> current.copy(
                    stage = when {
                        event.quality == CallQuality.WEAK && current.stage == CallStage.CONNECTED ->
                            CallStage.POOR_CONNECTION
                        event.quality in setOf(CallQuality.GOOD, CallQuality.EXCELLENT) &&
                            current.stage == CallStage.POOR_CONNECTION -> CallStage.CONNECTED
                        else -> current.stage
                    },
                    quality = event.quality,
                    diagnostics = event.diagnostics,
                    statusMessage = if (event.quality == CallQuality.WEAK) "Poor connection" else current.statusMessage
                )
                is CallEvent.CellularCallStarted -> current.takeIf { it.canTalk }?.copy(
                    stage = CallStage.CELLULAR_CALL_STARTED,
                    heldForCellularCall = true,
                    mutedBeforeCellularCall = current.muted,
                    statusMessage = "Pausing friend audio for customer call"
                ) ?: current
                is CallEvent.CellularCallActive -> current.takeIf {
                    it.stage in setOf(CallStage.CELLULAR_CALL_STARTED, CallStage.CELLULAR_CALL_ACTIVE)
                }?.copy(
                    stage = CallStage.CELLULAR_CALL_ACTIVE,
                    heldForCellularCall = true,
                    statusMessage = "Customer call active · friend session preserved"
                ) ?: current
                is CallEvent.CellularCallEnded -> current.takeIf {
                    it.stage in setOf(CallStage.CELLULAR_CALL_STARTED, CallStage.CELLULAR_CALL_ACTIVE)
                }?.copy(
                    stage = CallStage.CELLULAR_CALL_ENDED,
                    statusMessage = "Customer call ended"
                ) ?: current
                is CallEvent.RestoringVoip -> current.takeIf {
                    it.stage in setOf(CallStage.CELLULAR_CALL_ENDED, CallStage.RECONNECTING)
                }?.copy(
                    stage = CallStage.RESTORING_VOIP,
                    statusMessage = "Restoring friend call…"
                ) ?: current
                is CallEvent.VoipRestored -> current.takeIf {
                    it.stage == CallStage.RESTORING_VOIP
                }?.copy(
                    stage = CallStage.VOIP_RESTORED,
                    heldForCellularCall = false,
                    muted = current.mutedBeforeCellularCall,
                    statusMessage = "Friend call restored"
                ) ?: current
                is CallEvent.ResumeConnected -> current.takeIf {
                    it.stage == CallStage.VOIP_RESTORED
                }?.copy(stage = CallStage.CONNECTED, statusMessage = "Connected") ?: current
                is CallEvent.SetMuted -> current.copy(muted = event.muted)
                is CallEvent.SetAudioRoute -> current.copy(audioRoute = event.route)
                is CallEvent.Failed -> current.copy(
                    stage = CallStage.VOIP_DISCONNECTED,
                    quality = if (current.diagnostics.networkLabel == "No internet") {
                        CallQuality.NO_INTERNET
                    } else {
                        CallQuality.UNKNOWN
                    },
                    statusMessage = event.reason
                )
                is CallEvent.Ended -> current.copy(
                    stage = CallStage.CALL_ENDED,
                    heldForCellularCall = false,
                    statusMessage = event.reason
                )
                CallEvent.Reset -> CallState()
            }
        }
    }
}

