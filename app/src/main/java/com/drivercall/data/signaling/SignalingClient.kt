package com.drivercall.data.signaling

import com.drivercall.BuildConfig
import com.drivercall.data.rtc.RtcIceCandidate
import com.drivercall.data.security.TokenStorage
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.time.Instant
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

sealed interface SignalingEvent {
    data object Connected : SignalingEvent
    data class Disconnected(val recoverable: Boolean) : SignalingEvent
    data class Incoming(val callId: String, val from: String, val startedAt: Long) : SignalingEvent
    data class Accepted(val callId: String, val from: String) : SignalingEvent
    data class Declined(val callId: String) : SignalingEvent
    data class Offer(
        val callId: String,
        val from: String,
        val sdp: String,
        val negotiationId: String?
    ) : SignalingEvent
    data class Answer(
        val callId: String,
        val from: String,
        val sdp: String,
        val negotiationId: String?
    ) : SignalingEvent
    data class IceCandidateReceived(
        val callId: String,
        val from: String,
        val candidate: RtcIceCandidate,
        val negotiationId: String?
    ) : SignalingEvent
    data class ReconnectRequested(val callId: String, val from: String) : SignalingEvent
    data class Ended(val callId: String, val reason: String?) : SignalingEvent
    data class PeerUnavailable(val callId: String) : SignalingEvent
    data class Error(val code: String, val message: String) : SignalingEvent
}

enum class SignalingConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

interface SignalingClient {
    val events: Flow<SignalingEvent>
    val connectionState: StateFlow<SignalingConnectionState>
    suspend fun connect()
    suspend fun createCall(to: String): String
    suspend fun accept(callId: String)
    suspend fun decline(callId: String)
    suspend fun sendOffer(callId: String, to: String, sdp: String, negotiationId: String)
    suspend fun sendAnswer(callId: String, to: String, sdp: String, negotiationId: String)
    suspend fun sendIceCandidate(
        callId: String,
        to: String,
        candidate: RtcIceCandidate,
        negotiationId: String
    )
    suspend fun requestReconnect(callId: String, to: String)
    suspend fun end(callId: String, to: String, quality: String?)
    fun disconnect()
}

@Singleton
class OkHttpSignalingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenStorage: TokenStorage,
    @com.drivercall.di.ApplicationScope private val scope: CoroutineScope
) : SignalingClient {
    // WebSocket callbacks cannot suspend. An unlimited channel preserves protocol
    // order without silently dropping SDP/ICE when WebRTC work briefly blocks the
    // single coordinator consumer.
    private val eventChannel = Channel<SignalingEvent>(Channel.UNLIMITED)
    override val events: Flow<SignalingEvent> = eventChannel.receiveAsFlow()
    private val mutableConnectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<SignalingConnectionState> =
        mutableConnectionState.asStateFlow()

    private val pendingCreates = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingAcknowledgements =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val connectMutex = Mutex()
    private val socketGeneration = AtomicLong(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var shouldConnect = false
    @Volatile private var connected = false
    private var openSignal = CompletableDeferred<Unit>()
    private var reconnectAttempt = 0

    override suspend fun connect() {
        shouldConnect = true
        connectMutex.withLock {
            if (connected && socket != null) return@withLock
            val signal = if (socket == null) openSocket() else openSignal
            withTimeout(15_000) { signal.await() }
        }
    }

    private fun openSocket(): CompletableDeferred<Unit> {
        val token = tokenStorage.getToken()
            ?: throw IllegalStateException("Sign in before starting a call")
        val signal = CompletableDeferred<Unit>()
        openSignal = signal
        mutableConnectionState.value = SignalingConnectionState.CONNECTING
        val url = signalingHttpUrl(BuildConfig.SIGNALING_URL).newBuilder()
            .addQueryParameter("token", token)
            .build()
        // Never log this request: its URL contains the short-lived access token.
        val request = Request.Builder().url(url).build()
        val generation = socketGeneration.incrementAndGet()
        socket = okHttpClient.newWebSocket(request, listener(generation))
        return signal
    }

    override suspend fun createCall(to: String): String {
        connect()
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        pendingCreates[requestId] = result
        return try {
            sendOrThrow(
                jsonMessage("call.invite").apply {
                    addProperty("to", to)
                    addProperty("requestId", requestId)
                }
            )
            withTimeout(15_000) { result.await() }
        } finally {
            pendingCreates.remove(requestId)
        }
    }

    override suspend fun accept(callId: String) {
        connect()
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<Boolean>()
        pendingAcknowledgements[requestId] = result
        try {
            sendOrThrow(
                jsonMessage("call.accept").apply {
                    addProperty("callId", callId)
                    addProperty("requestId", requestId)
                }
            )
            val delivered = withTimeout(15_000) { result.await() }
            check(delivered) { "The caller is no longer available" }
        } finally {
            pendingAcknowledgements.remove(requestId)
        }
    }

    override suspend fun decline(callId: String) {
        sendAcknowledged(
            jsonMessage("call.decline").apply { addProperty("callId", callId) }
        )
    }

    override suspend fun sendOffer(
        callId: String,
        to: String,
        sdp: String,
        negotiationId: String
    ) {
        sendAcknowledged(
            jsonMessage("call.offer").apply {
                addProperty("callId", callId)
                addProperty("to", to)
                addProperty("sdp", sdp)
                addProperty("negotiationId", negotiationId)
            }
        )
    }

    override suspend fun sendAnswer(
        callId: String,
        to: String,
        sdp: String,
        negotiationId: String
    ) {
        sendAcknowledged(
            jsonMessage("call.answer").apply {
                addProperty("callId", callId)
                addProperty("to", to)
                addProperty("sdp", sdp)
                addProperty("negotiationId", negotiationId)
            }
        )
    }

    override suspend fun sendIceCandidate(
        callId: String,
        to: String,
        candidate: RtcIceCandidate,
        negotiationId: String
    ) {
        sendAcknowledged(
            jsonMessage("ice.candidate").apply {
                addProperty("callId", callId)
                addProperty("to", to)
                addProperty("negotiationId", negotiationId)
                add("candidate", JsonObject().apply {
                    addProperty("candidate", candidate.candidate)
                    candidate.sdpMid?.let { addProperty("sdpMid", it) }
                    addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                })
            }
        )
    }

    override suspend fun requestReconnect(callId: String, to: String) {
        sendAcknowledged(
            jsonMessage("call.reconnect").apply {
                addProperty("callId", callId)
                addProperty("to", to)
            }
        )
    }

    override suspend fun end(callId: String, to: String, quality: String?) {
        sendAcknowledged(
            jsonMessage("call.end").apply {
                addProperty("callId", callId)
                addProperty("to", to)
                quality?.let { addProperty("quality", it) }
            }
        )
    }

    override fun disconnect() {
        shouldConnect = false
        connected = false
        socketGeneration.incrementAndGet()
        val previous = socket
        socket = null
        mutableConnectionState.value = SignalingConnectionState.DISCONNECTED
        openSignal.completeExceptionally(IOException("Signaling disconnected"))
        failPending(IOException("Signaling disconnected"))
        previous?.close(1000, "Signed out")
    }

    private fun send(message: JsonObject) {
        if (socket?.send(message.toString()) != true) {
            eventChannel.trySend(
                SignalingEvent.Error("SIGNALING_UNAVAILABLE", "Call server is reconnecting")
            )
        }
    }

    private fun sendOrThrow(message: JsonObject) {
        if (socket?.send(message.toString()) != true) {
            throw IOException("Call server is reconnecting")
        }
    }

    private suspend fun sendAcknowledged(message: JsonObject) {
        connect()
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<Boolean>()
        pendingAcknowledgements[requestId] = result
        try {
            message.addProperty("requestId", requestId)
            sendOrThrow(message)
            check(withTimeout(15_000) { result.await() }) {
                "The other phone disconnected during call setup"
            }
        } finally {
            pendingAcknowledgements.remove(requestId)
        }
    }

    private fun listener(generation: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (generation != socketGeneration.get() || !shouldConnect) {
                webSocket.close(1000, "Superseded connection")
                return
            }
            connected = true
            reconnectAttempt = 0
            mutableConnectionState.value = SignalingConnectionState.CONNECTED
            openSignal.complete(Unit)
            eventChannel.trySend(SignalingEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation != socketGeneration.get()) return
            runCatching { handleMessage(JsonParser.parseString(text).asJsonObject) }
                .onFailure {
                    eventChannel.trySend(
                        SignalingEvent.Error("INVALID_SIGNAL", "The call server sent invalid data")
                    )
                }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (generation != socketGeneration.get()) return
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleSocketLoss(webSocket, generation, recoverable = code != 1000)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation != socketGeneration.get()) return
            openSignal.completeExceptionally(t)
            handleSocketLoss(webSocket, generation, recoverable = true)
        }
    }

    private fun handleSocketLoss(
        lostSocket: WebSocket,
        generation: Long,
        recoverable: Boolean
    ) {
        if (generation != socketGeneration.get() || socket !== lostSocket) return
        connected = false
        socket = null
        mutableConnectionState.value = SignalingConnectionState.DISCONNECTED
        failPending(IOException("Call server connection was lost"))
        eventChannel.trySend(SignalingEvent.Disconnected(recoverable && shouldConnect))
        if (!recoverable || !shouldConnect) return
        scope.launch {
            val attempt = reconnectAttempt++
            val base = min(30_000L, 1_000L shl min(attempt, 5))
            delay(base + Random.nextLong(0, 500))
            if (shouldConnect && socket == null) runCatching { connect() }
        }
    }

    private fun failPending(error: Throwable) {
        pendingCreates.values.forEach { it.completeExceptionally(error) }
        pendingCreates.clear()
        pendingAcknowledgements.values.forEach { it.completeExceptionally(error) }
        pendingAcknowledgements.clear()
    }

    private fun handleMessage(json: JsonObject) {
        val type = json.string("type") ?: return
        val callId = json.string("callId")
        when (type) {
            "call.created" -> {
                val requestId = json.string("requestId")
                if (requestId != null && callId != null) {
                    if (json.boolean("delivered") == false) {
                        pendingCreates[requestId]?.completeExceptionally(
                            IllegalStateException("Friend is offline")
                        )
                    } else {
                        pendingCreates[requestId]?.complete(callId)
                    }
                }
            }
            "call.incoming" -> if (callId != null) eventChannel.trySend(
                SignalingEvent.Incoming(
                    callId,
                    json.string("from").orEmpty(),
                    parseSignalTimestamp(json.get("startedAt"))
                )
            )
            "call.accept" -> if (callId != null) eventChannel.trySend(
                SignalingEvent.Accepted(callId, json.string("from").orEmpty())
            )
            "call.decline" -> if (callId != null) eventChannel.trySend(SignalingEvent.Declined(callId))
            "call.offer" -> if (callId != null) eventChannel.trySend(
                SignalingEvent.Offer(
                    callId,
                    json.string("from").orEmpty(),
                    json.string("sdp").orEmpty(),
                    json.string("negotiationId")
                )
            )
            "call.answer" -> if (callId != null) eventChannel.trySend(
                SignalingEvent.Answer(
                    callId,
                    json.string("from").orEmpty(),
                    json.string("sdp").orEmpty(),
                    json.string("negotiationId")
                )
            )
            "ice.candidate" -> if (callId != null) {
                val candidate = json.getAsJsonObject("candidate")
                eventChannel.trySend(
                    SignalingEvent.IceCandidateReceived(
                        callId,
                        json.string("from").orEmpty(),
                        RtcIceCandidate(
                            candidate?.string("sdpMid"),
                            candidate?.number("sdpMLineIndex")?.toInt() ?: 0,
                            candidate?.string("candidate").orEmpty()
                        ),
                        json.string("negotiationId")
                    )
                )
            }
            "call.reconnect" -> if (callId != null) eventChannel.trySend(
                SignalingEvent.ReconnectRequested(callId, json.string("from").orEmpty())
            )
            "call.end" -> if (callId != null) eventChannel.trySend(
                SignalingEvent.Ended(callId, json.string("reason"))
            )
            "peer.unavailable" -> if (callId != null) {
                eventChannel.trySend(SignalingEvent.PeerUnavailable(callId))
            }
            "ack" -> {
                val requestId = json.string("requestId")
                if (requestId != null) {
                    pendingAcknowledgements.remove(requestId)
                        ?.complete(json.boolean("delivered") != false)
                }
            }
            "error" -> {
                val code = json.string("code").orEmpty()
                val message = json.string("message").orEmpty()
                json.string("requestId")?.let { requestId ->
                    val error = IllegalStateException(message.ifBlank { code })
                    pendingCreates.remove(requestId)?.completeExceptionally(error)
                    pendingAcknowledgements.remove(requestId)?.completeExceptionally(error)
                }
                eventChannel.trySend(SignalingEvent.Error(code, message))
            }
            "ping" -> send(jsonMessage("pong"))
        }
    }

    private fun jsonMessage(type: String) = JsonObject().apply { addProperty("type", type) }
    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.number(name: String): Number? =
        get(name)?.takeUnless { it.isJsonNull }?.asNumber
    private fun JsonObject.boolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
}

/**
 * OkHttp performs the WebSocket upgrade from an HTTP(S) request. Its HttpUrl
 * parser intentionally rejects ws/wss schemes, so normalize the configured
 * WebSocket URL before constructing the request.
 */
internal fun signalingHttpUrl(configuredUrl: String): HttpUrl {
    val normalized = when {
        configuredUrl.startsWith("wss://", ignoreCase = true) ->
            "https://${configuredUrl.substring(6)}"
        configuredUrl.startsWith("ws://", ignoreCase = true) ->
            "http://${configuredUrl.substring(5)}"
        else -> configuredUrl
    }
    return normalized.toHttpUrl()
}

/** Accept both the current epoch-millisecond payload and older ISO-8601 servers. */
internal fun parseSignalTimestamp(
    value: JsonElement?,
    fallbackEpochMs: Long = System.currentTimeMillis()
): Long {
    if (value == null || value.isJsonNull || !value.isJsonPrimitive) return fallbackEpochMs
    val primitive = value.asJsonPrimitive
    if (primitive.isNumber) return runCatching { primitive.asLong }.getOrDefault(fallbackEpochMs)
    return runCatching { Instant.parse(primitive.asString).toEpochMilli() }
        .getOrDefault(fallbackEpochMs)
}
