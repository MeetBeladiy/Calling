package com.drivercall.data.signaling

import com.drivercall.BuildConfig
import com.drivercall.data.rtc.RtcIceCandidate
import com.drivercall.data.security.TokenStorage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    data class Offer(val callId: String, val from: String, val sdp: String) : SignalingEvent
    data class Answer(val callId: String, val from: String, val sdp: String) : SignalingEvent
    data class IceCandidateReceived(
        val callId: String,
        val from: String,
        val candidate: RtcIceCandidate
    ) : SignalingEvent
    data class ReconnectRequested(val callId: String, val from: String) : SignalingEvent
    data class Ended(val callId: String, val reason: String?) : SignalingEvent
    data class Error(val code: String, val message: String) : SignalingEvent
}

interface SignalingClient {
    val events: SharedFlow<SignalingEvent>
    suspend fun connect()
    suspend fun createCall(to: String): String
    fun accept(callId: String)
    fun decline(callId: String)
    fun sendOffer(callId: String, to: String, sdp: String)
    fun sendAnswer(callId: String, to: String, sdp: String)
    fun sendIceCandidate(callId: String, to: String, candidate: RtcIceCandidate)
    fun requestReconnect(callId: String, to: String)
    fun end(callId: String, to: String, quality: String?)
    fun disconnect()
}

@Singleton
class OkHttpSignalingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenStorage: TokenStorage,
    @com.drivercall.di.ApplicationScope private val scope: CoroutineScope
) : SignalingClient {
    private val mutableEvents = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SignalingEvent> = mutableEvents.asSharedFlow()

    private val pendingCreates = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var shouldConnect = false
    @Volatile private var connected = false
    private var openSignal = CompletableDeferred<Unit>()
    private var reconnectAttempt = 0

    override suspend fun connect() {
        if (connected) return
        shouldConnect = true
        if (socket == null) openSocket()
        withTimeout(15_000) { openSignal.await() }
    }

    private fun openSocket() {
        val token = tokenStorage.getToken()
            ?: throw IllegalStateException("Sign in before starting a call")
        openSignal = CompletableDeferred()
        val url = signalingHttpUrl(BuildConfig.SIGNALING_URL).newBuilder()
            .addQueryParameter("token", token)
            .build()
        // Never log this request: its URL contains the short-lived access token.
        val request = Request.Builder().url(url).build()
        socket = okHttpClient.newWebSocket(request, listener)
    }

    override suspend fun createCall(to: String): String {
        connect()
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        pendingCreates[requestId] = result
        send(
            jsonMessage("call.invite").apply {
                addProperty("to", to)
                addProperty("requestId", requestId)
            }
        )
        return try {
            withTimeout(15_000) { result.await() }
        } finally {
            pendingCreates.remove(requestId)
        }
    }

    override fun accept(callId: String) = send(
        jsonMessage("call.accept").apply { addProperty("callId", callId) }
    )

    override fun decline(callId: String) = send(
        jsonMessage("call.decline").apply { addProperty("callId", callId) }
    )

    override fun sendOffer(callId: String, to: String, sdp: String) = send(
        jsonMessage("call.offer").apply {
            addProperty("callId", callId)
            addProperty("to", to)
            addProperty("sdp", sdp)
        }
    )

    override fun sendAnswer(callId: String, to: String, sdp: String) = send(
        jsonMessage("call.answer").apply {
            addProperty("callId", callId)
            addProperty("to", to)
            addProperty("sdp", sdp)
        }
    )

    override fun sendIceCandidate(
        callId: String,
        to: String,
        candidate: RtcIceCandidate
    ) = send(
        jsonMessage("ice.candidate").apply {
            addProperty("callId", callId)
            addProperty("to", to)
            add("candidate", JsonObject().apply {
                addProperty("candidate", candidate.candidate)
                candidate.sdpMid?.let { addProperty("sdpMid", it) }
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
    )

    override fun requestReconnect(callId: String, to: String) = send(
        jsonMessage("call.reconnect").apply {
            addProperty("callId", callId)
            addProperty("to", to)
        }
    )

    override fun end(callId: String, to: String, quality: String?) = send(
        jsonMessage("call.end").apply {
            addProperty("callId", callId)
            addProperty("to", to)
            quality?.let { addProperty("quality", it) }
        }
    )

    override fun disconnect() {
        shouldConnect = false
        connected = false
        socket?.close(1000, "Call finished")
        socket = null
    }

    private fun send(message: JsonObject) {
        if (socket?.send(message.toString()) != true) {
            mutableEvents.tryEmit(
                SignalingEvent.Error("SIGNALING_UNAVAILABLE", "Call server is reconnecting")
            )
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            reconnectAttempt = 0
            openSignal.complete(Unit)
            mutableEvents.tryEmit(SignalingEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleMessage(JsonParser.parseString(text).asJsonObject) }
                .onFailure {
                    mutableEvents.tryEmit(
                        SignalingEvent.Error("INVALID_SIGNAL", "The call server sent invalid data")
                    )
                }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleSocketLoss(recoverable = code != 1000)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            openSignal.completeExceptionally(t)
            handleSocketLoss(recoverable = true)
        }
    }

    private fun handleSocketLoss(recoverable: Boolean) {
        connected = false
        socket = null
        mutableEvents.tryEmit(SignalingEvent.Disconnected(recoverable && shouldConnect))
        if (!recoverable || !shouldConnect) return
        scope.launch {
            val attempt = reconnectAttempt++
            val base = min(30_000L, 1_000L shl min(attempt, 5))
            delay(base + Random.nextLong(0, 500))
            if (shouldConnect && socket == null) runCatching { openSocket() }
        }
    }

    private fun handleMessage(json: JsonObject) {
        val type = json.string("type") ?: return
        val callId = json.string("callId")
        when (type) {
            "call.created" -> {
                val requestId = json.string("requestId")
                if (requestId != null && callId != null) pendingCreates[requestId]?.complete(callId)
            }
            "call.incoming" -> if (callId != null) mutableEvents.tryEmit(
                SignalingEvent.Incoming(
                    callId,
                    json.string("from").orEmpty(),
                    json.number("startedAt")?.toLong() ?: System.currentTimeMillis()
                )
            )
            "call.accept" -> if (callId != null) mutableEvents.tryEmit(
                SignalingEvent.Accepted(callId, json.string("from").orEmpty())
            )
            "call.decline" -> if (callId != null) mutableEvents.tryEmit(SignalingEvent.Declined(callId))
            "call.offer" -> if (callId != null) mutableEvents.tryEmit(
                SignalingEvent.Offer(callId, json.string("from").orEmpty(), json.string("sdp").orEmpty())
            )
            "call.answer" -> if (callId != null) mutableEvents.tryEmit(
                SignalingEvent.Answer(callId, json.string("from").orEmpty(), json.string("sdp").orEmpty())
            )
            "ice.candidate" -> if (callId != null) {
                val candidate = json.getAsJsonObject("candidate")
                mutableEvents.tryEmit(
                    SignalingEvent.IceCandidateReceived(
                        callId,
                        json.string("from").orEmpty(),
                        RtcIceCandidate(
                            candidate?.string("sdpMid"),
                            candidate?.number("sdpMLineIndex")?.toInt() ?: 0,
                            candidate?.string("candidate").orEmpty()
                        )
                    )
                )
            }
            "call.reconnect" -> if (callId != null) mutableEvents.tryEmit(
                SignalingEvent.ReconnectRequested(callId, json.string("from").orEmpty())
            )
            "call.end" -> if (callId != null) mutableEvents.tryEmit(
                SignalingEvent.Ended(callId, json.string("reason"))
            )
            "error" -> mutableEvents.tryEmit(
                SignalingEvent.Error(json.string("code").orEmpty(), json.string("message").orEmpty())
            )
            "ping" -> send(jsonMessage("pong"))
        }
    }

    private fun jsonMessage(type: String) = JsonObject().apply { addProperty("type", type) }
    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.number(name: String): Number? =
        get(name)?.takeUnless { it.isJsonNull }?.asNumber
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
