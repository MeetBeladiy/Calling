package com.drivercall.data.rtc

import android.content.Context
import com.drivercall.BuildConfig
import com.drivercall.data.remote.ConfigApi
import com.drivercall.data.remote.IceServerDto
import com.drivercall.domain.call.CallDiagnostics
import com.drivercall.domain.call.CallQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class RtcIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
)

enum class RtcConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

interface WebRtcListener {
    fun onLocalIceCandidate(candidate: RtcIceCandidate)
    fun onConnectionStateChanged(state: RtcConnectionState)
    fun onRenegotiationNeeded()
}

interface WebRtcEngine {
    fun setListener(listener: WebRtcListener?)
    suspend fun createOffer(iceRestart: Boolean = false): String
    suspend fun acceptOfferAndCreateAnswer(remoteSdp: String): String
    suspend fun setRemoteAnswer(remoteSdp: String)
    fun addRemoteIceCandidate(candidate: RtcIceCandidate)
    fun setMicrophoneEnabled(enabled: Boolean)
    fun setRemoteAudioEnabled(enabled: Boolean)
    suspend fun collectDiagnostics(networkLabel: String, reconnections: Int): Pair<CallQuality, CallDiagnostics>
    fun close()
}

/**
 * Audio-only WebRTC transport. WebRTC supplies DTLS-SRTP media encryption,
 * Opus, jitter buffering and packet-loss concealment. Microphone processing is
 * always enabled: hardware AEC/NS where supported, with WebRTC software echo
 * cancellation, noise suppression, automatic gain and high-pass filtering.
 */
@Singleton
class AndroidWebRtcEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configApi: ConfigApi
) : WebRtcEngine {
    private var listener: WebRtcListener? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private val pendingRemoteCandidates = mutableListOf<RtcIceCandidate>()
    private val connectionMutex = Mutex()

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(BuildConfig.DEBUG)
                .createInitializationOptions()
        )
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
            .also { audioModule.release() }
    }

    override fun setListener(listener: WebRtcListener?) {
        this.listener = listener
    }

    private suspend fun ensurePeerConnection(): PeerConnection = connectionMutex.withLock {
        peerConnection?.let { return@withLock it }
        val servers = loadIceServers()
        createPeerConnection(servers)
    }

    private suspend fun loadIceServers(): List<PeerConnection.IceServer> {
        val fromBackend = runCatching {
            configApi.getIceConfig().iceServers.flatMap { it.toNative() }
        }.getOrDefault(emptyList())
        if (fromBackend.isNotEmpty()) return fromBackend

        return buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            if (BuildConfig.TURN_URL.isNotBlank()) {
                add(
                    PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
                        .setUsername(BuildConfig.TURN_USERNAME)
                        .setPassword(BuildConfig.TURN_CREDENTIAL)
                        .createIceServer()
                )
            }
        }
    }

    private fun IceServerDto.toNative(): List<PeerConnection.IceServer> = urls.map { url ->
        PeerConnection.IceServer.builder(url).apply {
            username?.takeIf(String::isNotBlank)?.let { setUsername(it) }
            credential?.takeIf(String::isNotBlank)?.let { setPassword(it) }
        }.createIceServer()
    }

    private fun createPeerConnection(servers: List<PeerConnection.IceServer>): PeerConnection {
        val configuration = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
        }
        val connection = requireNotNull(factory.createPeerConnection(configuration, observer)) {
            "WebRTC could not create a peer connection"
        }

        val processingConstraints = MediaConstraints().apply {
            // The software constraints remain enabled even when a device does not
            // provide working hardware effects, giving every call an NS/AEC path.
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        audioSource = factory.createAudioSource(processingConstraints)
        localAudioTrack = factory.createAudioTrack("calling-app-audio", audioSource).apply {
            setEnabled(true)
        }
        connection.addTrack(localAudioTrack, listOf("calling-app-stream"))
        peerConnection = connection
        return connection
    }

    override suspend fun createOffer(iceRestart: Boolean): String {
        val connection = ensurePeerConnection()
        val options = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
            if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }
        val description = connection.createDescription { observer ->
            connection.createOffer(observer, options)
        }
        connection.setDescription(description, local = true)
        return description.description
    }

    override suspend fun acceptOfferAndCreateAnswer(remoteSdp: String): String {
        val connection = ensurePeerConnection()
        // Both peers can notice a network handover simultaneously. Roll back a
        // local restart offer so the remote offer can win instead of deadlocking.
        if (connection.signalingState() != PeerConnection.SignalingState.STABLE) {
            connection.setDescription(
                SessionDescription(SessionDescription.Type.ROLLBACK, ""),
                local = true
            )
        }
        connection.setDescription(
            SessionDescription(SessionDescription.Type.OFFER, remoteSdp),
            local = false
        )
        flushPendingCandidates(connection)
        val options = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
        }
        val answer = connection.createDescription { observer ->
            connection.createAnswer(observer, options)
        }
        connection.setDescription(answer, local = true)
        return answer.description
    }

    override suspend fun setRemoteAnswer(remoteSdp: String) {
        val connection = ensurePeerConnection()
        connection.setDescription(
            SessionDescription(SessionDescription.Type.ANSWER, remoteSdp),
            local = false
        )
        flushPendingCandidates(connection)
    }

    override fun addRemoteIceCandidate(candidate: RtcIceCandidate) {
        val connection = peerConnection
        synchronized(pendingRemoteCandidates) {
            if (connection == null || connection.remoteDescription == null) {
                pendingRemoteCandidates += candidate
            } else {
                connection.addIceCandidate(candidate.toNative())
            }
        }
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    override fun setRemoteAudioEnabled(enabled: Boolean) {
        remoteAudioTrack?.setEnabled(enabled)
    }

    override suspend fun collectDiagnostics(
        networkLabel: String,
        reconnections: Int
    ): Pair<CallQuality, CallDiagnostics> = suspendCancellableCoroutine { continuation ->
        val connection = peerConnection
        if (connection == null) {
            continuation.resume(CallQuality.UNKNOWN to CallDiagnostics(networkLabel = networkLabel))
            return@suspendCancellableCoroutine
        }
        connection.getStats { report ->
            var latency: Int? = null
            var jitter: Int? = null
            var packetLoss: Double? = null
            var bitrate: Int? = null
            report.statsMap.values.forEach { stat ->
                when (stat.type) {
                    "candidate-pair" -> if (stat.members["state"] == "succeeded" ||
                        stat.members["nominated"] == true
                    ) {
                        (stat.members["currentRoundTripTime"] as? Number)?.toDouble()?.let {
                            latency = (it * 1_000).roundToInt()
                        }
                        (stat.members["availableOutgoingBitrate"] as? Number)?.toDouble()?.let {
                            bitrate = (it / 1_000).roundToInt()
                        }
                    }
                    "inbound-rtp" -> if (stat.members["kind"] == "audio" ||
                        stat.members["mediaType"] == "audio"
                    ) {
                        (stat.members["jitter"] as? Number)?.toDouble()?.let {
                            jitter = (it * 1_000).roundToInt()
                        }
                        val lost = (stat.members["packetsLost"] as? Number)?.toDouble() ?: 0.0
                        val received = (stat.members["packetsReceived"] as? Number)?.toDouble() ?: 0.0
                        if (lost + received > 0) packetLoss = lost.coerceAtLeast(0.0) * 100 / (lost + received)
                    }
                }
            }
            val quality = when {
                latency == null && packetLoss == null -> CallQuality.UNKNOWN
                (latency ?: 0) < 100 && (packetLoss ?: 0.0) < 1.0 -> CallQuality.EXCELLENT
                (latency ?: 0) < 250 && (packetLoss ?: 0.0) < 5.0 -> CallQuality.GOOD
                else -> CallQuality.WEAK
            }
            val diagnostics = CallDiagnostics(
                latencyMs = latency,
                jitterMs = jitter,
                packetLossPercent = packetLoss,
                availableOutgoingBitrateKbps = bitrate,
                networkLabel = networkLabel,
                reconnectionCount = reconnections
            )
            if (continuation.isActive) continuation.resume(quality to diagnostics)
        }
    }

    override fun close() {
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        localAudioTrack = null
        remoteAudioTrack = null
        audioSource = null
        peerConnection = null
        synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.clear() }
    }

    private fun flushPendingCandidates(connection: PeerConnection) {
        synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.forEach { connection.addIceCandidate(it.toNative()) }
            pendingRemoteCandidates.clear()
        }
    }

    private fun RtcIceCandidate.toNative() =
        IceCandidate(sdpMid, sdpMLineIndex, candidate)

    private suspend fun PeerConnection.createDescription(
        action: (SdpObserver) -> Unit
    ): SessionDescription = suspendCancellableCoroutine { continuation ->
        action(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }

            override fun onCreateFailure(error: String) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("SDP creation failed: $error"))
                }
            }
        })
    }

    private suspend fun PeerConnection.setDescription(
        description: SessionDescription,
        local: Boolean
    ) = suspendCancellableCoroutine { continuation ->
        val callback = object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("SDP apply failed: $error"))
                }
            }
        }
        if (local) setLocalDescription(callback, description)
        else setRemoteDescription(callback, description)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            val mapped = when (state) {
                PeerConnection.IceConnectionState.NEW -> RtcConnectionState.NEW
                PeerConnection.IceConnectionState.CHECKING -> RtcConnectionState.CONNECTING
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> RtcConnectionState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> RtcConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> RtcConnectionState.FAILED
                PeerConnection.IceConnectionState.CLOSED -> RtcConnectionState.CLOSED
            }
            listener?.onConnectionStateChanged(mapped)
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            listener?.onLocalIceCandidate(
                RtcIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() {
            listener?.onRenegotiationNeeded()
        }
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            (receiver.track() as? AudioTrack)?.let {
                remoteAudioTrack = it
                it.setEnabled(true)
            }
        }
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}
