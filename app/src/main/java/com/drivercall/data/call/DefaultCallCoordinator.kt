package com.drivercall.data.call

import android.content.Context
import android.telecom.DisconnectCause
import com.drivercall.data.network.NetworkMonitor
import com.drivercall.data.rtc.RtcConfigurationException
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
import com.drivercall.domain.model.NetworkType
import com.drivercall.domain.repository.CallHistoryRepository
import com.drivercall.domain.repository.AuthRepository
import com.drivercall.service.CallForegroundService
import com.drivercall.service.CallNotificationManager
import com.drivercall.telecom.CellularCallMonitor
import com.drivercall.telecom.CellularCallState
import com.drivercall.telecom.TelecomCallController
import com.drivercall.telecom.TelecomCallbacks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private data class PendingRemoteOffer(
    val sdp: String,
    val negotiationId: String?
)

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
            if (restored.incoming && restored.stage == CallStage.RINGING) {
                restored
            } else if (restored.shouldShowCallUi) {
                restored.copy(
                    stage = CallStage.VOIP_DISCONNECTED,
                    quality = CallQuality.UNKNOWN,
                    statusMessage = "Call was interrupted. Tap Retry to reconnect."
                )
            } else restored
        } ?: CallState()
    )
    override val state: StateFlow<CallState> = machine.state
    override val audioEndpoints: StateFlow<List<CallAudioEndpoint>> = telecom.endpoints
    override val currentAudioEndpoint: StateFlow<CallAudioEndpoint?> = telecom.currentEndpoint

    private val negotiationMutex = Mutex()
    private val recoveryMutex = Mutex()
    private val acceptMutex = Mutex()
    private val ending = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private var pendingOffer: PendingRemoteOffer? = null
    private var incomingAccepted = restoredSession?.let {
        it.incoming && it.stage != CallStage.RINGING
    } ?: false
    private var restoredUnacceptedOutgoing = restoredSession?.let {
        !it.incoming && it.stage in setOf(CallStage.OUTGOING_VOIP_CALL, CallStage.RINGING)
    } ?: false
    private var initialOfferSentCallId: String? = null
    private var localOfferCallId: String? = null
    @Volatile private var activeLocalNegotiationId: String? = null
    @Volatile private var activeLocalAllowsLegacySignals = false
    @Volatile private var activeRemoteNegotiationId: String? = null
    @Volatile private var localCandidateNegotiationId: String? = null
    @Volatile private var reconnectRequestedCallId: String? = null
    private val pendingLocalCandidates = mutableListOf<RtcIceCandidate>()
    @Volatile private var localDescriptionAnnounced = true
    private var negotiationTimeoutJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var iceDisconnectJob: Job? = null
    private var networkLossJob: Job? = null
    @Volatile private var rtcConnectionState = RtcConnectionState.NEW
    private var telecomRegistration: Job? = null
    private var telecomAnswerWaiter: Job? = null
    private var lastNetwork: NetworkStatus? = null
    private var lastNetworkLabel = "Checking connection"
    private var customerCallStartedAt: Long? = null
    private var customerCallObservedOffhook = false
    private var customerDisplayName = "Customer"
    private var presenceUserId: String? = null

    init {
        // A native PeerConnection cannot survive process death. Preserve the call
        // role and ringing state so receivers can still Accept; accepted calls are
        // restored as an explicit Retry flow with a fresh PeerConnection.
        rtc.setListener(this)
        // SDP and trickled ICE are an ordered protocol. collectLatest would cancel
        // an in-flight setRemoteDescription as soon as the next ICE event arrived.
        scope.launch {
            signaling.events.collect { event ->
                try {
                    handleSignalingEvent(event)
                } catch (timeout: TimeoutCancellationException) {
                    // A signaling/SDP timeout is a recoverable call failure. It must
                    // never cancel this long-lived collector and break later calls.
                    state.value.callId?.let {
                        failNegotiation(it, "Call setup timed out. Tap Retry.")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    state.value.callId?.let {
                        failNegotiation(it, "Unable to secure call audio. Tap Retry.")
                    }
                }
            }
        }
        scope.launch {
            authRepository.observeSession().collectLatest { session ->
                val nextUserId = session?.user?.id
                if (shouldDisconnectPresence(presenceUserId, nextUserId)) {
                    signaling.disconnect()
                }
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
                if (!snapshot.canTalk) continue
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
        state.value.takeIf { it.shouldShowCallUi }?.let { restored ->
            notifications.show(restored)
            restored.callId?.let { callId ->
                telecomRegistration = scope.launch {
                    runCatching {
                        telecom.registerCall(
                            callId,
                            restored.peerName,
                            restored.incoming,
                            telecomCallbacks
                        )
                    }
                }
            }
        }
    }

    override fun startOutgoing(peerId: String, peerName: String) {
        if (state.value.hasLiveSession || ending.get() ||
            !starting.compareAndSet(false, true)
        ) return
        scope.launch {
            try {
                val provisionalCallId = "pending-${UUID.randomUUID()}"
                initialOfferSentCallId = null
                localOfferCallId = null
                val started = transition(
                    CallEvent.StartOutgoing(
                        provisionalCallId,
                        peerId,
                        peerName,
                        System.currentTimeMillis()
                    )
                )
                if (started.callId != provisionalCallId ||
                    started.stage != CallStage.OUTGOING_VOIP_CALL
                ) return@launch
                signaling.connect()
                val callId = signaling.createCall(peerId)
                transition(CallEvent.AssignServerCallId(provisionalCallId, callId))
                startCallInfrastructure(callId, peerName, incoming = false)
                transition(CallEvent.RemoteRinging(callId))
                armRingTimeout(callId)
                // The receiver explicitly accepts before media negotiation starts.
                // This prevents an offer from being lost while their app reconnects.
            } catch (_: TimeoutCancellationException) {
                val callId = state.value.callId ?: return@launch
                transition(CallEvent.Failed(callId, "Call server timed out. Tap Retry."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val callId = state.value.callId ?: return@launch
                transition(CallEvent.Failed(callId, error.userFacingMessage()))
            } finally {
                starting.set(false)
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
            runCatching {
                withTimeout(END_SIGNAL_TIMEOUT_MS) { signaling.decline(callId) }
            }
            return
        }
        incomingAccepted = false
        pendingOffer = null
        val received = transition(CallEvent.ReceiveIncoming(callId, peerId, peerName, startedAt))
        if (received.callId != callId || !received.incoming) {
            runCatching {
                withTimeout(END_SIGNAL_TIMEOUT_MS) { signaling.decline(callId) }
            }
            return
        }
        startCallInfrastructure(callId, peerName, incoming = true)
        runCatching { signaling.connect() }
    }

    private fun startCallInfrastructure(callId: String, peerName: String, incoming: Boolean) {
        // Keep the availability FGS alive throughout the call. Stopping it before
        // launching another FGS can leave the app with no service on Android 12+.
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

    private suspend fun acceptIncomingInternal(fromTelecom: Boolean) = acceptMutex.withLock {
        val snapshot = state.value
        val callId = snapshot.callId ?: return@withLock
        if (!snapshot.incoming || snapshot.stage != CallStage.RINGING || incomingAccepted) {
            return@withLock
        }
        incomingAccepted = true
        transition(CallEvent.Negotiating(callId))
        armNegotiationTimeout(callId)
        CallForegroundService.enableMicrophone(context)
        try {
            signaling.connect()
            signaling.accept(callId)
        } catch (_: TimeoutCancellationException) {
            incomingAccepted = false
            cancelNegotiationTimeout()
            transition(CallEvent.AcceptFailed(callId, "Call server timed out. Tap Accept again."))
            return@withLock
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            incomingAccepted = false
            cancelNegotiationTimeout()
            if (error.message?.contains("no longer available", ignoreCase = true) == true) {
                finishCall(
                    "Caller is no longer available",
                    notifyRemote = false,
                    notifyTelecom = true,
                    addToHistory = false
                )
            } else {
                transition(CallEvent.AcceptFailed(callId, "Could not accept. Tap Accept again."))
            }
            return@withLock
        }
        if (state.value.callId != callId || state.value.stage != CallStage.NEGOTIATING) {
            return@withLock
        }
        if (!fromTelecom) answerTelecomWhenRegistered(callId)
        pendingOffer?.let { offer ->
            pendingOffer = null
            answerOffer(callId, snapshot.peerId.orEmpty(), offer.sdp, offer.negotiationId)
        }
    }

    override fun declineIncoming() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        scope.launch {
            runCatching {
                withTimeout(END_SIGNAL_TIMEOUT_MS) { signaling.decline(callId) }
            }
        }
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
        if (ending.get()) return
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        if (callId.startsWith("pending-") || restoredUnacceptedOutgoing) {
            val peerId = snapshot.peerId ?: return
            scope.launch {
                // An unaccepted outgoing ring cannot use call.reconnect because the
                // backend has not moved it to connected. End it and send a fresh invite.
                if (!callId.startsWith("pending-")) {
                    runCatching {
                        withTimeout(END_SIGNAL_TIMEOUT_MS) {
                            signaling.end(callId, peerId, "unknown")
                        }
                    }
                } else {
                    signaling.disconnect()
                }
                restoredUnacceptedOutgoing = false
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
            if (snapshot.stage == CallStage.VOIP_DISCONNECTED) {
                resetNegotiation(closeRtc = true)
            }
            transition(CallEvent.RetryStarted(callId))
            recoverMedia(callId, snapshot.peerId.orEmpty())
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
        if (ending.get()) return
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        val peerId = snapshot.peerId ?: return
        val negotiationId = localCandidateNegotiationId ?: return
        synchronized(pendingLocalCandidates) {
            if (!localDescriptionAnnounced) {
                pendingLocalCandidates += candidate
                return
            }
        }
        scope.launch {
            runCatching {
                signaling.sendIceCandidate(callId, peerId, candidate, negotiationId)
            }
        }
    }

    override fun onConnectionStateChanged(state: RtcConnectionState) {
        rtcConnectionState = state
        if (ending.get()) return
        val snapshot = this.state.value
        val callId = snapshot.callId ?: return
        if (!snapshot.hasLiveSession) return
        when (state) {
            RtcConnectionState.CONNECTED -> scope.launch {
                if (ending.get()) return@launch
                iceDisconnectJob?.cancel()
                iceDisconnectJob = null
                cancelNegotiationTimeout()
                localOfferCallId = null
                reconnectRequestedCallId = null
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
                if (ending.get()) return@launch
                iceDisconnectJob?.cancel()
                iceDisconnectJob = null
                val current = this@DefaultCallCoordinator.state.value
                if (current.callId != callId || !current.hasLiveSession) return@launch
                if (current.connectedAtEpochMs == null) {
                    failNegotiation(
                        callId,
                        "Unable to establish secure audio on this network. Tap Retry."
                    )
                } else {
                    resetNegotiation(closeRtc = false)
                    transition(CallEvent.ConnectionInterrupted(callId))
                    recoverMedia(callId, current.peerId.orEmpty())
                }
            }
            RtcConnectionState.DISCONNECTED -> {
                iceDisconnectJob?.cancel()
                iceDisconnectJob = scope.launch {
                // ICE "disconnected" is often transient; give it a short grace period.
                delay(ICE_DISCONNECT_GRACE_MS)
                val current = this@DefaultCallCoordinator.state.value
                if (!ending.get() && current.callId == callId &&
                    current.hasLiveSession &&
                    rtcConnectionState == RtcConnectionState.DISCONNECTED
                ) {
                    if (current.connectedAtEpochMs == null) {
                        failNegotiation(
                            callId,
                            "Unable to establish secure audio on this network. Tap Retry."
                        )
                    } else {
                        resetNegotiation(closeRtc = false)
                        transition(CallEvent.ConnectionInterrupted(callId))
                        recoverMedia(callId, current.peerId.orEmpty())
                    }
                }
                }
            }
            else -> Unit
        }
    }

    override fun onRenegotiationNeeded() = Unit

    private suspend fun handleSignalingEvent(event: SignalingEvent) {
        if (ending.get()) return
        when (event) {
            SignalingEvent.Connected -> {
                val snapshot = state.value
                if (snapshot.hasLiveSession && snapshot.stage == CallStage.RECONNECTING) {
                    snapshot.callId?.let { recoverMedia(it, snapshot.peerId.orEmpty()) }
                }
            }
            is SignalingEvent.Disconnected -> if (event.recoverable) {
                val snapshot = state.value
                val callId = snapshot.callId
                if (callId != null && (snapshot.stage == CallStage.NEGOTIATING ||
                        localOfferCallId == callId)
                ) {
                    if (snapshot.connectedAtEpochMs == null) {
                        failNegotiation(callId, "Call connection interrupted. Tap Retry.")
                    } else {
                        resetNegotiation(closeRtc = true)
                        transition(CallEvent.ConnectionInterrupted(callId))
                    }
                } else if (callId != null && snapshot.stage == CallStage.RECONNECTING) {
                    resetNegotiation(closeRtc = false)
                    transition(CallEvent.ConnectionInterrupted(callId))
                }
            }
            is SignalingEvent.Incoming -> prepareIncoming(
                event.callId,
                event.from,
                "Friend",
                event.startedAt
            )
            is SignalingEvent.Accepted -> {
                val snapshot = state.value
                if (event.callId == snapshot.callId && !snapshot.incoming &&
                    initialOfferSentCallId != event.callId
                ) {
                    cancelRingTimeout()
                    initialOfferSentCallId = event.callId
                    transition(CallEvent.Negotiating(event.callId))
                    armNegotiationTimeout(event.callId)
                    createAndSendOffer(
                        event.callId,
                        snapshot.peerId.orEmpty(),
                        iceRestart = false
                    )
                }
            }
            is SignalingEvent.Declined -> if (event.callId == state.value.callId) {
                finishCall("Friend declined", false, true, true)
            }
            is SignalingEvent.Offer -> {
                if (event.callId != state.value.callId) return
                if (state.value.incoming && !incomingAccepted) {
                    pendingOffer = PendingRemoteOffer(event.sdp, event.negotiationId)
                } else {
                    answerOffer(event.callId, event.from, event.sdp, event.negotiationId)
                }
            }
            is SignalingEvent.Answer -> if (event.callId == state.value.callId) {
                val expectedNegotiationId = activeLocalNegotiationId
                if (expectedNegotiationId == null ||
                    (event.negotiationId != expectedNegotiationId &&
                        !(event.negotiationId == null && activeLocalAllowsLegacySignals))
                ) return
                try {
                    withTimeout(NEGOTIATION_STEP_TIMEOUT_MS) {
                        negotiationMutex.withLock { rtc.setRemoteAnswer(event.sdp) }
                    }
                } catch (_: TimeoutCancellationException) {
                    failNegotiation(event.callId, "Call audio setup timed out. Tap Retry.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failNegotiation(event.callId, "Unable to secure call audio. Tap Retry.")
                }
            }
            is SignalingEvent.IceCandidateReceived -> if (event.callId == state.value.callId) {
                val expectedNegotiationId = if (state.value.incoming) {
                    activeRemoteNegotiationId
                } else {
                    activeLocalNegotiationId
                }
                if (expectedNegotiationId != null &&
                    (event.negotiationId == expectedNegotiationId ||
                        (event.negotiationId == null &&
                            !state.value.incoming && activeLocalAllowsLegacySignals))
                ) {
                    rtc.addRemoteIceCandidate(event.candidate)
                }
            }
            is SignalingEvent.ReconnectRequested -> if (event.callId == state.value.callId) {
                val snapshot = state.value
                if (!snapshot.incoming) {
                    restartAsCaller(
                        event.callId,
                        snapshot.peerId.orEmpty(),
                        notifyPeer = false
                    )
                }
            }
            is SignalingEvent.Ended -> if (event.callId == state.value.callId) {
                finishCall(event.reason ?: "Friend ended the call", false, true, true)
            }
            is SignalingEvent.PeerUnavailable -> if (event.callId == state.value.callId) {
                finishCall("Friend is offline", false, true, true)
            }
            is SignalingEvent.Error -> if (event.code == "CALL_NOT_FOUND" &&
                state.value.hasLiveSession
            ) {
                finishCall(event.message, false, true, true)
            }
        }
    }

    private suspend fun answerOffer(
        callId: String,
        peerId: String,
        offer: String,
        offeredNegotiationId: String?
    ) {
        if (ending.get() || !state.value.hasLiveSession) return
        val negotiationId = offeredNegotiationId ?: UUID.randomUUID().toString()
        try {
            withTimeout(NEGOTIATION_STEP_TIMEOUT_MS) {
                val answer = negotiationMutex.withLock {
                    if (state.value.callId != callId) return@withLock null
                    activeRemoteNegotiationId = negotiationId
                    activeLocalNegotiationId = null
                    beginLocalDescription(negotiationId)
                    rtc.acceptOfferAndCreateAnswer(offer).also {
                        applyDesiredAudioState()
                    }
                }
                if (answer == null) return@withTimeout
                signaling.sendAnswer(callId, peerId, answer, negotiationId)
                flushLocalCandidates(callId, peerId, negotiationId)
            }
            armNegotiationTimeout(callId)
        } catch (_: TimeoutCancellationException) {
            failNegotiation(callId, "Call audio setup timed out. Tap Retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RtcConfigurationException) {
            failNegotiation(
                callId,
                error.message ?: "Secure call relay is unavailable. Please try again shortly."
            )
        } catch (_: Throwable) {
            failNegotiation(callId, "Could not negotiate call audio. Tap Retry.")
        }
    }

    private suspend fun handleNetworkStatus(status: NetworkStatus) {
        val previous = lastNetwork
        lastNetwork = status
        lastNetworkLabel = when (status) {
            is NetworkStatus.Available -> status.label
            NetworkStatus.Unavailable -> "No internet"
        }
        when (status) {
            NetworkStatus.Unavailable -> {
                networkLossJob?.cancel()
                networkLossJob = scope.launch {
                    delay(NETWORK_LOSS_DEBOUNCE_MS)
                    if (ending.get() || lastNetwork != NetworkStatus.Unavailable) return@launch
                    val current = this@DefaultCallCoordinator.state.value
                    val callId = current.callId ?: return@launch
                    if (!current.hasLiveSession || current.stage in setOf(
                            CallStage.OUTGOING_VOIP_CALL,
                            CallStage.RINGING
                        )
                    ) return@launch
                    if (current.stage == CallStage.NEGOTIATING) {
                        resetNegotiation(closeRtc = true)
                    } else {
                        resetNegotiation(closeRtc = false)
                    }
                    transition(CallEvent.NetworkLost(callId))
                }
            }
            is NetworkStatus.Available -> {
                networkLossJob?.cancel()
                networkLossJob = null
                val snapshot = state.value
                val callId = snapshot.callId ?: return
                if (ending.get() || !snapshot.hasLiveSession) return
                transition(CallEvent.NetworkAvailable(callId, status.label))
                val restoredAfterLoss = previous == NetworkStatus.Unavailable &&
                    snapshot.stage == CallStage.RECONNECTING
                val transportChanged = shouldRestartIceForTransportChange(previous, status)
                if (transportChanged && snapshot.canTalk) {
                    transition(CallEvent.ConnectionInterrupted(callId))
                }
                if (restoredAfterLoss || transportChanged) {
                    recoverMedia(callId, snapshot.peerId.orEmpty())
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

    private suspend fun createAndSendOffer(
        callId: String,
        peerId: String,
        iceRestart: Boolean
    ) {
        if (peerId.isBlank() || ending.get() || !state.value.hasLiveSession) return
        val negotiationId = UUID.randomUUID().toString()
        var announced = false
        try {
            withTimeout(NEGOTIATION_STEP_TIMEOUT_MS) {
                val offer = negotiationMutex.withLock {
                    if (ending.get() || !state.value.hasLiveSession ||
                        state.value.callId != callId || localOfferCallId == callId
                    ) {
                        return@withLock null
                    }
                    localOfferCallId = callId
                    activeLocalNegotiationId = negotiationId
                    activeLocalAllowsLegacySignals = !iceRestart
                    activeRemoteNegotiationId = null
                    beginLocalDescription(negotiationId)
                    val createdOffer = rtc.createOffer(iceRestart)
                    applyDesiredAudioState()
                    if (state.value.callId != callId) {
                        clearNegotiationStateLocked(closeRtc = true)
                        return@withLock null
                    }
                    createdOffer
                }
                if (offer == null) return@withTimeout
                signaling.sendOffer(callId, peerId, offer, negotiationId)
                flushLocalCandidates(callId, peerId, negotiationId)
                announced = true
            }
            if (announced) armNegotiationTimeout(callId)
        } catch (_: TimeoutCancellationException) {
            failNegotiation(
                callId,
                if (iceRestart) "Reconnection timed out. Tap Retry."
                else "Call audio setup timed out. Tap Retry."
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RtcConfigurationException) {
            failNegotiation(
                callId,
                error.message ?: "Secure call relay is unavailable. Please try again shortly."
            )
        } catch (_: Throwable) {
            val reason = if (iceRestart) {
                "Unable to reconnect. Tap Retry."
            } else {
                "Could not start secure call audio. Tap Retry."
            }
            failNegotiation(callId, reason)
        }
    }

    private fun beginLocalDescription(negotiationId: String) {
        synchronized(pendingLocalCandidates) {
            localCandidateNegotiationId = negotiationId
            localDescriptionAnnounced = false
            pendingLocalCandidates.clear()
        }
    }

    private suspend fun flushLocalCandidates(
        callId: String,
        peerId: String,
        negotiationId: String
    ) {
        val candidates = synchronized(pendingLocalCandidates) {
            localDescriptionAnnounced = true
            pendingLocalCandidates.toList().also { pendingLocalCandidates.clear() }
        }
        candidates.forEach {
            signaling.sendIceCandidate(callId, peerId, it, negotiationId)
        }
    }

    private suspend fun restartAsCaller(
        callId: String,
        peerId: String,
        notifyPeer: Boolean
    ) {
        if (peerId.isBlank() || ending.get() || !state.value.hasLiveSession) return
        try {
            recoveryMutex.withLock {
                if (ending.get() || !state.value.hasLiveSession ||
                    state.value.callId != callId || localOfferCallId == callId
                ) return@withLock
                signaling.connect()
                if (notifyPeer) signaling.requestReconnect(callId, peerId)
                createAndSendOffer(callId, peerId, iceRestart = true)
            }
        } catch (_: TimeoutCancellationException) {
            failNegotiation(callId, "Reconnection timed out. Tap Retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failNegotiation(callId, "Unable to reconnect. Tap Retry.")
        }
    }

    private suspend fun recoverMedia(callId: String, peerId: String) {
        if (peerId.isBlank() || ending.get()) return
        val snapshot = state.value
        if (!snapshot.hasLiveSession || snapshot.callId != callId) return
        if (!snapshot.incoming) {
            restartAsCaller(callId, peerId, notifyPeer = true)
            return
        }
        try {
            recoveryMutex.withLock {
                if (ending.get() || !state.value.hasLiveSession ||
                    state.value.callId != callId
                ) return@withLock
                if (reconnectRequestedCallId == callId ||
                    (state.value.stage == CallStage.RECONNECTING &&
                        activeRemoteNegotiationId != null)
                ) return@withLock
                reconnectRequestedCallId = callId
                signaling.connect()
                // Only the original caller creates restart offers. This avoids
                // simultaneous-offer glare and stale answers on both phones.
                signaling.requestReconnect(callId, peerId)
            }
            armNegotiationTimeout(callId)
        } catch (_: TimeoutCancellationException) {
            failNegotiation(callId, "Reconnection timed out. Tap Retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failNegotiation(callId, "Unable to reconnect. Tap Retry.")
        }
    }

    private fun applyDesiredAudioState() {
        val snapshot = state.value
        rtc.setMicrophoneEnabled(!snapshot.muted && !snapshot.heldForCellularCall)
        rtc.setRemoteAudioEnabled(!snapshot.heldForCellularCall)
    }

    private fun armRingTimeout(callId: String) {
        cancelRingTimeout()
        ringTimeoutJob = scope.launch {
            delay(OUTGOING_RING_TIMEOUT_MS)
            val snapshot = state.value
            if (snapshot.callId == callId && !snapshot.incoming &&
                snapshot.stage == CallStage.RINGING
            ) {
                finishCall(
                    reason = "No answer",
                    notifyRemote = true,
                    notifyTelecom = true,
                    addToHistory = true
                )
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun armNegotiationTimeout(callId: String) {
        val stage = state.value.takeIf { it.callId == callId }?.stage ?: return
        if (stage !in setOf(
                CallStage.NEGOTIATING,
                CallStage.RECONNECTING,
                CallStage.RESTORING_VOIP
            )
        ) return
        negotiationTimeoutJob?.cancel()
        negotiationTimeoutJob = scope.launch {
            delay(NEGOTIATION_TIMEOUT_MS)
            val snapshot = state.value
            if (!ending.get() && snapshot.callId == callId && snapshot.stage in setOf(
                    CallStage.NEGOTIATING,
                    CallStage.RECONNECTING,
                    CallStage.RESTORING_VOIP
                )
            ) {
                negotiationTimeoutJob = null
                resetNegotiation(closeRtc = true, cancelTimeout = false)
                transition(CallEvent.Failed(callId, "Call audio timed out. Tap Retry."))
            }
        }
    }

    private fun cancelNegotiationTimeout() {
        negotiationTimeoutJob?.cancel()
        negotiationTimeoutJob = null
    }

    private suspend fun failNegotiation(callId: String, reason: String) {
        if (ending.get() || !state.value.hasLiveSession || state.value.callId != callId) return
        resetNegotiation(closeRtc = true)
        transition(CallEvent.Failed(callId, reason))
    }

    private suspend fun resetNegotiation(
        closeRtc: Boolean,
        cancelTimeout: Boolean = true
    ) {
        if (cancelTimeout) cancelNegotiationTimeout()
        negotiationMutex.withLock { clearNegotiationStateLocked(closeRtc) }
    }

    private fun clearNegotiationStateLocked(closeRtc: Boolean) {
        localOfferCallId = null
        activeLocalNegotiationId = null
        activeLocalAllowsLegacySignals = false
        activeRemoteNegotiationId = null
        reconnectRequestedCallId = null
        synchronized(pendingLocalCandidates) {
            pendingLocalCandidates.clear()
            localDescriptionAnnounced = true
            localCandidateNegotiationId = null
        }
        if (closeRtc) rtc.close()
    }

    private suspend fun restoreVoipAfterTelecomActivation() {
        val snapshot = state.value
        val callId = snapshot.callId ?: return
        rtc.setRemoteAudioEnabled(true)
        rtc.setMicrophoneEnabled(!snapshot.mutedBeforeCellularCall)
        recoverMedia(callId, snapshot.peerId.orEmpty())
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

    private fun answerTelecomWhenRegistered(callId: String) {
        if (!telecom.isSupported) return
        telecomAnswerWaiter?.cancel()
        telecomAnswerWaiter = scope.launch {
            telecom.registered.filter { it }.first()
            val snapshot = state.value
            if (incomingAccepted && snapshot.callId == callId && snapshot.incoming) {
                telecom.answer()
            }
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
            cancelRingTimeout()
            iceDisconnectJob?.cancel()
            iceDisconnectJob = null
            networkLossJob?.cancel()
            networkLossJob = null
            rtc.setMicrophoneEnabled(false)
            rtc.setRemoteAudioEnabled(false)
            try {
                if (callId != null) {
                    try {
                        transition(CallEvent.Ended(callId, reason))
                    } catch (_: Throwable) {
                        // Teardown and peer notification must still run even if a
                        // notification surface fails while rendering CALL_ENDED.
                    }
                }
                if (notifyTelecom) runCatching { telecom.disconnect() }

                // Deliver the terminal signal while the PeerConnection is still
                // alive. Closing ICE first can make the peer enter reconnect mode
                // before it receives call.end.
                if (notifyRemote && callId != null && peerId != null) {
                    val quality = snapshot.quality.takeUnless { it == CallQuality.NO_INTERNET }
                        ?.name?.lowercase() ?: "unknown"
                    try {
                        withTimeout(END_SIGNAL_TIMEOUT_MS) {
                            signaling.end(callId, peerId, quality)
                        }
                    } catch (_: Throwable) {
                        // Local hangup must always complete even if signaling is down.
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        try {
                            resetNegotiation(closeRtc = true)
                        } catch (_: Throwable) {
                            rtc.close()
                        }
                        if (addToHistory && callId != null && snapshot.startedAtEpochMs != null) {
                            try {
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
                            } catch (_: Throwable) {
                                // A local history failure cannot leave the call stuck.
                            }
                        }
                        sessionStore.clear()
                        pendingOffer = null
                        incomingAccepted = false
                        restoredUnacceptedOutgoing = false
                        initialOfferSentCallId = null
                        telecomAnswerWaiter?.cancel()
                        telecomAnswerWaiter = null
                        machine.dispatch(CallEvent.Reset)
                        notifications.cancel()
                    } finally {
                        ending.set(false)
                    }
                }
            }
        }
    }

    private suspend fun transition(event: CallEvent): CallState {
        val next = machine.dispatch(event)
        if (next.shouldShowCallUi && next.stage != CallStage.CALL_ENDED) {
            sessionStore.save(next)
        } else if (next.stage in setOf(CallStage.IDLE, CallStage.CALL_ENDED)) {
            sessionStore.clear()
        }
        notifications.show(next)
        return next
    }

    private fun Throwable.userFacingMessage(): String = when (this) {
        is TimeoutCancellationException -> "Call server timed out"
        else -> message?.takeIf { it.length <= 120 } ?: "Unable to start the call"
    }

    private companion object {
        const val END_SIGNAL_TIMEOUT_MS = 5_000L
        const val ICE_DISCONNECT_GRACE_MS = 3_000L
        const val NETWORK_LOSS_DEBOUNCE_MS = 3_000L
        const val NEGOTIATION_STEP_TIMEOUT_MS = 20_000L
        const val NEGOTIATION_TIMEOUT_MS = 30_000L
        const val OUTGOING_RING_TIMEOUT_MS = 45_000L
    }
}

/**
 * The availability service may establish the socket from its persisted token
 * before the repository restores the matching user. That initial null -> user
 * transition must keep the socket; logout and real account changes must close it.
 */
internal fun shouldDisconnectPresence(previousUserId: String?, nextUserId: String?): Boolean =
    previousUserId != null && previousUserId != nextUserId

/**
 * Radio labels can oscillate between LTE and NR during a single mobile-data
 * session. Only a real transport-family handover should force an ICE restart.
 */
internal fun shouldRestartIceForTransportChange(
    previous: NetworkStatus?,
    current: NetworkStatus.Available
): Boolean {
    val previousAvailable = previous as? NetworkStatus.Available ?: return false
    return previousAvailable.type.transportFamily() != current.type.transportFamily()
}

private fun NetworkType.transportFamily(): String = when (this) {
    NetworkType.CELLULAR,
    NetworkType.CELLULAR_4G,
    NetworkType.CELLULAR_5G -> "cellular"
    else -> name
}
