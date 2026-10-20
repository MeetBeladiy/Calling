package com.drivercall.domain.call

/**
 * The single source of truth for a Calling App conversation. UI, notifications,
 * Telecom callbacks, WebRTC and network callbacks all observe this model.
 */
enum class CallStage {
    IDLE,
    OUTGOING_VOIP_CALL,
    RINGING,
    CONNECTED,
    RECONNECTING,
    POOR_CONNECTION,
    CELLULAR_CALL_STARTED,
    CELLULAR_CALL_ACTIVE,
    CELLULAR_CALL_ENDED,
    RESTORING_VOIP,
    VOIP_RESTORED,
    VOIP_DISCONNECTED,
    CALL_ENDED
}

enum class CallQuality { UNKNOWN, EXCELLENT, GOOD, WEAK, NO_INTERNET }

enum class CallAudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET }

data class CallAudioEndpoint(
    val id: String,
    val name: String,
    val route: CallAudioRoute
)

data class CallDiagnostics(
    val latencyMs: Int? = null,
    val jitterMs: Int? = null,
    val packetLossPercent: Double? = null,
    val availableOutgoingBitrateKbps: Int? = null,
    val networkLabel: String = "Checking connection",
    val reconnectionCount: Int = 0
)

data class CallState(
    val stage: CallStage = CallStage.IDLE,
    val callId: String? = null,
    val peerId: String? = null,
    val peerName: String = "",
    val incoming: Boolean = false,
    val startedAtEpochMs: Long? = null,
    val connectedAtEpochMs: Long? = null,
    val muted: Boolean = false,
    val heldForCellularCall: Boolean = false,
    val mutedBeforeCellularCall: Boolean = false,
    val audioRoute: CallAudioRoute = CallAudioRoute.EARPIECE,
    val quality: CallQuality = CallQuality.UNKNOWN,
    val diagnostics: CallDiagnostics = CallDiagnostics(),
    val statusMessage: String = "Ready"
) {
    val hasLiveSession: Boolean
        get() = stage !in setOf(CallStage.IDLE, CallStage.CALL_ENDED, CallStage.VOIP_DISCONNECTED)

    val canTalk: Boolean
        get() = stage in setOf(CallStage.CONNECTED, CallStage.POOR_CONNECTION, CallStage.VOIP_RESTORED)
}

sealed interface CallEvent {
    data class StartOutgoing(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val nowEpochMs: Long
    ) : CallEvent

    data class ReceiveIncoming(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val nowEpochMs: Long
    ) : CallEvent

    data class AssignServerCallId(
        val provisionalCallId: String,
        val callId: String
    ) : CallEvent

    data class RemoteRinging(val callId: String) : CallEvent
    data class Connected(val callId: String, val nowEpochMs: Long) : CallEvent
    data class NetworkLost(val callId: String) : CallEvent
    data class NetworkAvailable(val callId: String, val label: String) : CallEvent
    data class IceRestartSucceeded(val callId: String) : CallEvent
    data class QualityChanged(
        val callId: String,
        val quality: CallQuality,
        val diagnostics: CallDiagnostics
    ) : CallEvent

    data class CellularCallStarted(val callId: String) : CallEvent
    data class CellularCallActive(val callId: String) : CallEvent
    data class CellularCallEnded(val callId: String) : CallEvent
    data class RestoringVoip(val callId: String) : CallEvent
    data class VoipRestored(val callId: String) : CallEvent
    data class ResumeConnected(val callId: String) : CallEvent
    data class SetMuted(val callId: String, val muted: Boolean) : CallEvent
    data class SetAudioRoute(val callId: String, val route: CallAudioRoute) : CallEvent
    data class Failed(val callId: String, val reason: String) : CallEvent
    data class Ended(val callId: String, val reason: String = "Call ended") : CallEvent
    data object Reset : CallEvent
}

