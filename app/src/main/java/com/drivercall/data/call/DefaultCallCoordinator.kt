package com.drivercall.data.call

import android.content.Context
import android.telecom.DisconnectCause
import com.drivercall.data.network.NetworkMonitor
import com.drivercall.data.rtc.RtcConnectionState
import com.drivercall.data.rtc.RtcIceCandidate
import com.drivercall.data.rtc.WebRtcEngine
import com.drivercall.data.rtc.WebRtcListener
import com.drivercall.data.signaling.SignalingClient
import com.drivercall.data.signaling.SignalingEvent
import com.drivercall.di.ApplicationScope
import com.drivercall.domain.call.CallAudioEndpoint
import com.drivercall.domain.call.CallCoordinator
import com.drivercall.domain.call.CallEvent
import com.drivercall.domain.call.CallQuality
import com.drivercall.domain.call.CallStage
import com.drivercall.domain.call.CallState
import com.drivercall.domain.call.CallStateMachine
import com.drivercall.domain.model.CallRecord
import com.drivercall.domain.model.CallType
import com.drivercall.domain.model.NetworkStatus
import com.drivercall.domain.repository.CallHistoryRepository
import com.drivercall.domain.repository.AuthRepository
import com.drivercall.service.CallForegroundService
import com.drivercall.service.CallAvailabilityService
import com.drivercall.service.CallNotificationManager
import com.drivercall.telecom.CellularCallMonitor
import com.drivercall.telecom.CellularCallState
import com.drivercall.telecom.TelecomCallController
import com.drivercall.telecom.TelecomCallbacks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCallCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val signaling: SignalingClient,
    private val rtc: WebRtcEngine,
    private val networkMonitor: NetworkMonitor,
    private val cellularCallMonitor: CellularCallMonitor,
    private val telecom: TelecomCallController,
    private val notifications: CallNotificationManager,
    private val sessionStore: CallSessionStore,
    private val historyRepository: CallHistoryRepository,
    private val authRepository: AuthRepository
) : CallCoordinator, WebRtcListener {
    private val restoredSession = sessionStore.restore()
    private val machine = CallStateMachine(
        restoredSession?.let { restored ->
            if (restored.hasLiveSession) {
                restored.copy(
                    stage = CallStage.VOIP_DISCONNECTED,
                    quality = CallQuality.NO_INTERNET,
                    statusMessage = "Call was interrupted. Tap Retry to reconnect."
                )
            } else restored
        } ?: CallState()
    )
    override val state: StateFlow<CallState> = machine.state
    override val audioEndpoints: StateFlow<List<CallAudioEndpoint>> = telecom.endpoints
    override val currentAudioEndpoint: StateFlow<CallAudioEndpoint?> = telecom.currentEndpoint

    private val negotiationMutex = Mutex()
    private val ending = AtomicBoolean(false)
    private var pendingOffer: String? = null
    private var incomingAccepted = false
    private var telecomRegistration: Job? = null
    private var lastNetwork: NetworkStatus? = null
    private var lastNetworkLabel = "Checking connection"
    private var customerCallStartedAt: Long? = null
    private var customerCallObservedOffhook = false
    private var customerDisplayName = "Customer"
    private var presenceUserId: String? = null

    init {
        // A native PeerConnection cannot survive process death. Keep enough data
        // in memory to offer a retry, but never present a restored snapshot as a
        // live call or repeatedly resurrect it on every process start.
        if (restoredSession?.hasLiveSession == true) sessionStore.clear()
        rtc.setListener(this)
        scope.launch { signaling.events.collectLatest(::handleSignalingEvent) }
        scope.launch {
            authRepository.observeSession().collectLatest { session ->
                val nextUserId = session?.user?.id
                if (presenceUserId != nextUserId) signaling.disconnect()
                presenceUserId = nextUserId
                if (session == null) return@collectLatest

                // Keep the authenticated signaling socket alive while the user is
                // signed in. This is both the online-presence channel and the path
                // used to deliver incoming calls while the app process is running.
                while (currentCoroutineContext().isActive) {
                    runCatching { signaling.connect() }
                    delay(15_000)
                }
            }
        }
        scope.launch { networkMonitor.networkStatus.collectLatest(::handleNetworkStatus) }
        scope.launch { cellularCallMonitor.states.collectLatest(::handleCellularCallState) }
        scope.launch {
            telecom.currentEndpoint.collectLatest { endpoint ->
                val callId = state.value.callId ?: return@collectLatest
                endpoint?.let { transition(CallEvent.SetAudioRoute(callId, it.route)) }
            }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                val snapshot = state.value
                if (!snapshot.hasLiveSession) continue
                runCatching {
                    rtc.collectDiagnostics(
                        lastNetworkLabel,
                        snapshot.diagnostics.reconnectionCount
                    )
                }.onSuccess { (quality, diagnostics) ->
                    snapshot.callId?.let {
                        transition(CallEvent.QualityChanged(it, quality, diagnostics))
                    }
                }
            }
        }
    }

    override fun startOutgoing(peerId: String, peerName: String) {
        if (state.value.hasLiveSession || ending.get()) return
        scope.launch {
            val provisionalCallId = "pending-${UUID.randomUUID()}"
            transition(
                CallEvent.StartOutgoing(
                    provisionalCallId,
                    peerId,
                    peerName,
                    System.currentTimeMillis()
                )
            )
            runCatching {
                signaling.connect()
                val callId = signaling.createCall(peerId)
                transition(CallEvent.AssignServerCallId(provisionalCallId, callId))
                startCallInfrastructure(callId, peerName, incoming = false)
                val offer = negotiationMutex.withLock { rtc.createOffer() }
                signaling.sendOffer(callId, peerId, offer)
            }.onFailure { error ->
                val callId = state.value.callId ?: provisionalCallId
                transition(CallEvent.Failed(callId, error.userFacingMessage()))
            }
        }
    }

    override fun prepareIncomingFromPush(callId: String, peerId: String, peerName: String) {
        scope.launch { prepareIncoming(callId, peerId, peerName, System.currentTimeMillis()) }
    }

    private suspend fun prepareIncoming(
        callId: String,
        peerId: String,
        peerName: String,
        startedAt: Long
    ) {
        val current = state.value
        if (current.callId == callId && current.hasLiveSession) return
        if (current.hasLiveSession) {
            signaling.decline(callId)
            return
        }
        incomingAccepted = false
        pendingOffer = null
        transition(CallEvent.ReceiveIncoming(callId, peerId, peerName, startedAt))
        startCallInfrastructure(callId, peerName, incoming = true)
        runCatching { signaling.connect() }
    }

    private fun startCallInfrastructure(callId: String, peerName: String, incoming: Boolean) {
        CallAvailabilityService.stop(context)
        notifications.show(state.value)
        CallForegroundService.start(context)
        telecomRegistration?.cancel()
        telecomRegistration = scope.launch {
            runCatching {
                telecom.registerCall(callId, peerName, incoming, telecomCallbacks)
            }.onFailure {
                // Devices without Telecom support can still use the in-app call surface.
            }
        }
    }

    override fun acceptIncoming() {
        scope.launch { acceptIncomingInternal(fromTelecom = false) }
    }

    private suspend fun acceptIncomingInternal(fromTelecom: Boolean) {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        if (!snapshot.incoming || snapshot.stage != CallStage.RINGING || incomingAccepted) return
        incomingAccepted = true
        CallForegroundService.enableMicrophone(context)
        runCatching { signaling.connect() }
            .onFailure {
                incomingAccepted = false
                transition(CallEvent.Failed(callId, "Unable to reach the call server"))
                return
            }
        signaling.accept(callId)
        if (!fromTelecom && telecom.registered.value) telecom.answer()
        pendingOffer?.let { offer ->
            pendingOffer = null
            answerOffer(callId, snapshot.peerId.orEmpty(), offer)
        }
    }

    override fun declineIncoming() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        signaling.decline(callId)
        telecom.disconnect(DisconnectCause.REJECTED)
        finishCall("Call declined", notifyRemote = false, notifyTelecom = false, addToHistory = false)
    }

    override fun endCall(reason: String) {
        finishCall(reason, notifyRemote = true, notifyTelecom = true, addToHistory = true)
    }

    override fun toggleMute() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        val muted = !snapshot.muted
        rtc.setMicrophoneEnabled(!muted && !snapshot.heldForCellularCall)
        scope.launch { transition(CallEvent.SetMuted(callId, muted)) }
    }

    override fun selectAudioEndpoint(endpointId: String) {
        telecom.selectEndpoint(endpointId)
    }

    override fun reconnect() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        if (callId.startsWith("pending-")) {
            val peerId = snapshot.peerId ?: return
            scope.launch {
                // The invite never reached the server, so retry with a new logical
                // call instead of attempting ICE against a provisional identifier.
                signaling.disconnect()
                sessionStore.clear()
                machine.dispatch(CallEvent.Reset)
                notifications.cancel()
                startOutgoing(peerId, snapshot.peerName)
            }
            return
        }
        scope.launch {
            if (!telecom.registered.value) {
                startCallInfrastructure(callId, snapshot.peerName, snapshot.incoming)
            }
            transition(CallEvent.NetworkLost(callId))
            attemptIceRestart(callId, snapshot.peerId.orEmpty())
        }
    }

    override fun prepareCustomerCall(customerName: String) {
        val snapshot = state.value
        customerDisplayName = customerName.trim().ifBlank { "Customer" }
        customerCallStartedAt = System.currentTimeMillis()
        customerCallObservedOffhook = false
        if (!snapshot.canTalk) return
        val callId = snapshot.callId ?: return
        // Stop media synchronously before the cellular call intent is launched.
        sessionStore.save(snapshot)
        rtc.setMicrophoneEnabled(false)
        rtc.setRemoteAudioEnabled(false)
        scope.launch {
            transition(CallEvent.CellularCallStarted(callId))
            if (telecom.registered.value) telecom.hold()
        }
    }

    override fun restoreAfterCustomerCall() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        if (snapshot.stage !in setOf(
                CallStage.CELLULAR_CALL_STARTED,
                CallStage.CELLULAR_CALL_ACTIVE,
                CallStage.CELLULAR_CALL_ENDED
            )
        ) return
        scope.launch {
            if (snapshot.stage != CallStage.CELLULAR_CALL_ENDED) {
                transition(CallEvent.CellularCallEnded(callId))
            }
            transition(CallEvent.RestoringVoip(callId))
            if (telecom.registered.value) telecom.activate()
            else restoreVoipAfterTelecomActivation()
        }
    }

    override fun onLocalIceCandidate(candidate: RtcIceCandidate) {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        val peerId = snapshot.peerId ?: return
        signaling.sendIceCandidate(callId, peerId, candidate)
    }

    override fun onConnectionStateChanged(state: RtcConnectionState) {
        val snapshot = this.state.value
        val callId = snapshot.callId ?: return
        when (state) {
            RtcConnectionState.CONNECTED -> scope.launch {
                when (snapshot.stage) {
                    CallStage.RECONNECTING -> transition(CallEvent.IceRestartSucceeded(callId))
                    CallStage.RESTORING_VOIP -> {
                        transition(CallEvent.VoipRestored(callId))
                        delay(1_500)
                        transition(CallEvent.ResumeConnected(callId))
                    }
                    else -> transition(CallEvent.Connected(callId, System.currentTimeMillis()))
                }
                if (telecom.registered.value && !snapshot.incoming) telecom.activate()
            }
            RtcConnectionState.FAILED -> scope.launch {
                transition(CallEvent.NetworkLost(callId))
                attemptIceRestart(callId, snapshot.peerId.orEmpty())
            }
            RtcConnectionState.DISCONNECTED -> scope.launch {
                // ICE "disconnected" is often transient; give it a short grace period.
                delay(3_000)
                if (this@DefaultCallCoordinator.state.value.callId == callId &&
                    this@DefaultCallCoordinator.state.value.canTalk
                ) {
                    transition(CallEvent.NetworkLost(callId))
                    attemptIceRestart(callId, snapshot.peerId.orEmpty())
                }
            }
            else -> Unit
        }
    }

    override fun onRenegotiationNeeded() = Unit

    private suspend fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            SignalingEvent.Connected -> {
                val snapshot = state.value
                if (snapshot.stage == CallStage.RECONNECTING) {
                    snapshot.callId?.let { attemptIceRestart(it, snapshot.peerId.orEmpty()) }
                }
            }
            is SignalingEvent.Disconnected -> if (event.recoverable) {
                state.value.callId?.let { transition(CallEvent.NetworkLost(it)) }
            }
            is SignalingEvent.Incoming -> prepareIncoming(
                event.callId,
                event.from,
                "Friend",
                event.startedAt
            )
            is SignalingEvent.Accepted -> {
                // The offer has already been sent; wait for the remote answer.
            }
            is SignalingEvent.Declined -> if (event.callId == state.value.callId) {
                finishCall("Friend declined", false, true, true)
            }
            is SignalingEvent.Offer -> {
                if (event.callId != state.value.callId) return
                if (state.value.incoming && !incomingAccepted) pendingOffer = event.sdp
                else answerOffer(event.callId, event.from, event.sdp)
            }
            is SignalingEvent.Answer -> if (event.callId == state.value.callId) {
                runCatching { negotiationMutex.withLock { rtc.setRemoteAnswer(event.sdp) } }
                    .onFailure {
                        transition(CallEvent.Failed(event.callId, "Unable to secure call audio"))
                    }
            }
            is SignalingEvent.IceCandidateReceived -> if (event.callId == state.value.callId) {
                rtc.addRemoteIceCandidate(event.candidate)
            }
            is SignalingEvent.ReconnectRequested -> if (event.callId == state.value.callId) {
                // The remote peer follows with an ICE-restart offer.
            }
            is SignalingEvent.Ended -> if (event.callId == state.value.callId) {
                finishCall(event.reason ?: "Friend ended the call", false, true, true)
            }
            is SignalingEvent.Error -> if (event.code == "CALL_NOT_FOUND") {
                state.value.callId?.let { transition(CallEvent.Failed(it, event.message)) }
            }
        }
    }

    private fun answerOffer(callId: String, peerId: String, offer: String) {
        scope.launch {
            runCatching {
                val answer = negotiationMutex.withLock {
                    rtc.acceptOfferAndCreateAnswer(offer)
                }
                signaling.sendAnswer(callId, peerId, answer)
            }.onFailure {
                transition(CallEvent.Failed(callId, "Could not negotiate call audio"))
            }
        }
    }

    private suspend fun handleNetworkStatus(status: NetworkStatus) {
        val previous = lastNetwork
        lastNetwork = status
        lastNetworkLabel = when (status) {
            is NetworkStatus.Available -> status.label
            NetworkStatus.Unavailable -> "No internet"
        }
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        when (status) {
            NetworkStatus.Unavailable -> if (snapshot.hasLiveSession) {
                transition(CallEvent.NetworkLost(callId))
            }
            is NetworkStatus.Available -> {
                transition(CallEvent.NetworkAvailable(callId, status.label))
                val transportChanged = previous is NetworkStatus.Available &&
                    previous.type != status.type
                if (snapshot.stage == CallStage.RECONNECTING || transportChanged) {
                    runCatching { signaling.connect() }
                    attemptIceRestart(callId, snapshot.peerId.orEmpty())
                }
            }
        }
    }

    private suspend fun handleCellularCallState(cellularState: CellularCallState) {
        val snapshot = state.value
        val callId = snapshot.callId
        when (cellularState) {
            CellularCallState.OFFHOOK -> {
                val pendingStartedAt = customerCallStartedAt
                if (pendingStartedAt != null &&
                    System.currentTimeMillis() - pendingStartedAt <= 2 * 60_000
                ) {
                    customerCallObservedOffhook = true
                } else if (pendingStartedAt != null) {
                    customerCallStartedAt = null
                    customerDisplayName = "Customer"
                }
                if (callId != null && snapshot.stage == CallStage.CELLULAR_CALL_STARTED) {
                    transition(CallEvent.CellularCallActive(callId))
                }
            }
            CellularCallState.IDLE -> {
                if (customerCallObservedOffhook) recordCustomerCall()
                if (callId != null && snapshot.stage in setOf(
                        CallStage.CELLULAR_CALL_STARTED,
                        CallStage.CELLULAR_CALL_ACTIVE
                    )
                ) restoreAfterCustomerCall()
            }
            CellularCallState.RINGING -> Unit
            CellularCallState.PERMISSION_REQUIRED -> Unit
        }
    }

    private suspend fun recordCustomerCall() {
        val startedAt = customerCallStartedAt ?: return
        historyRepository.addRecord(
            CallRecord(
                id = "customer-${UUID.randomUUID()}",
                peerName = customerDisplayName,
                peerId = null,
                startTimeMillis = startedAt,
                durationSeconds = ((System.currentTimeMillis() - startedAt) / 1_000)
                    .coerceAtLeast(0),
                type = CallType.CELLULAR,
                reconnectionCount = 0,
                qualitySummary = "Mobile network"
            )
        )
        customerCallStartedAt = null
        customerCallObservedOffhook = false
        customerDisplayName = "Customer"
    }

    private suspend fun attemptIceRestart(callId: String, peerId: String) {
        if (peerId.isBlank()) return
        runCatching {
            signaling.connect()
            signaling.requestReconnect(callId, peerId)
            val offer = negotiationMutex.withLock { rtc.createOffer(iceRestart = true) }
            signaling.sendOffer(callId, peerId, offer)
        }.onFailure {
            transition(CallEvent.Failed(callId, "Unable to reconnect. Tap Retry."))
        }
    }

    private suspend fun restoreVoipAfterTelecomActivation() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        rtc.setRemoteAudioEnabled(true)
        rtc.setMicrophoneEnabled(!snapshot.mutedBeforeCellularCall)
        attemptIceRestart(callId, snapshot.peerId.orEmpty())
        if (state.value.stage == CallStage.RESTORING_VOIP) {
            transition(CallEvent.VoipRestored(callId))
            delay(1_500)
            transition(CallEvent.ResumeConnected(callId))
        }
    }

    private val telecomCallbacks = object : TelecomCallbacks {
        override suspend fun onAnswerRequested() {
            acceptIncomingInternal(fromTelecom = true)
        }

        override suspend fun onDisconnectRequested(reason: String) {
            finishCall(reason, notifyRemote = true, notifyTelecom = false, addToHistory = true)
        }

        override suspend fun onActiveRequested() {
            val snapshot = state.value
            if (snapshot.stage in setOf(
                    CallStage.CELLULAR_CALL_STARTED,
                    CallStage.CELLULAR_CALL_ACTIVE,
                    CallStage.CELLULAR_CALL_ENDED,
                    CallStage.RESTORING_VOIP
                )
            ) {
                if (snapshot.stage != CallStage.RESTORING_VOIP) {
                    snapshot.callId?.let { transition(CallEvent.CellularCallEnded(it)) }
                    snapshot.callId?.let { transition(CallEvent.RestoringVoip(it)) }
                }
                restoreVoipAfterTelecomActivation()
            }
        }

        override suspend fun onInactiveRequested() {
            val snapshot = state.value
            val callId = snapshot.callId ?: return
            rtc.setMicrophoneEnabled(false)
            rtc.setRemoteAudioEnabled(false)
            if (snapshot.canTalk) transition(CallEvent.CellularCallStarted(callId))
            transition(CallEvent.CellularCallActive(callId))
        }
    }

    private fun finishCall(
        reason: String,
        notifyRemote: Boolean,
        notifyTelecom: Boolean,
        addToHistory: Boolean
    ) {
        if (!ending.compareAndSet(false, true)) return
        scope.launch {
            val snapshot = state.value
            val callId = snapshot.callId
            val peerId = snapshot.peerId
            if (notifyRemote && callId != null && peerId != null) {
                val quality = snapshot.quality.takeUnless { it == CallQuality.NO_INTERNET }
                    ?.name?.lowercase() ?: "unknown"
                signaling.end(callId, peerId, quality)
            }
            if (notifyTelecom) telecom.disconnect()
            rtc.close()
            if (callId != null) transition(CallEvent.Ended(callId, reason))
            if (addToHistory && callId != null && snapshot.startedAtEpochMs != null) {
                val endedAt = System.currentTimeMillis()
                historyRepository.addRecord(
                    CallRecord(
                        id = callId,
                        peerName = snapshot.peerName,
                        peerId = peerId,
                        startTimeMillis = snapshot.startedAtEpochMs,
                        durationSeconds = ((endedAt - (snapshot.connectedAtEpochMs
                            ?: snapshot.startedAtEpochMs)) / 1_000).coerceAtLeast(0),
                        type = CallType.VOIP,
                        reconnectionCount = snapshot.diagnostics.reconnectionCount,
                        qualitySummary = snapshot.quality.name.lowercase()
                            .replaceFirstChar { it.uppercase() }
                    )
                )
            }
            sessionStore.clear()
            pendingOffer = null
            incomingAccepted = false
            if (presenceUserId != null) CallAvailabilityService.start(context)
            delay(1_000)
            machine.dispatch(CallEvent.Reset)
            notifications.cancel()
            ending.set(false)
        }
    }

    private suspend fun transition(event: CallEvent): CallState {
        val next = machine.dispatch(event)
        if (next.hasLiveSession) {
            sessionStore.save(next)
        } else if (next.stage in setOf(
                CallStage.IDLE,
                CallStage.VOIP_DISCONNECTED,
                CallStage.CALL_ENDED
            )
        ) {
            sessionStore.clear()
        }
        notifications.show(next)
        return next
    }

    private fun Throwable.userFacingMessage(): String = when (this) {
        is kotlinx.coroutines.TimeoutCancellationException -> "Call server timed out"
        else -> message?.takeIf { it.length <= 120 } ?: "Unable to start the call"
    }
}
