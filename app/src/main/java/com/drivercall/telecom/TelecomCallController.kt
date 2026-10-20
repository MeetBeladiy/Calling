package com.drivercall.telecom

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.drivercall.domain.call.CallAudioRoute
import com.drivercall.domain.call.CallAudioEndpoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface TelecomCallbacks {
    suspend fun onAnswerRequested()
    suspend fun onDisconnectRequested(reason: String)
    suspend fun onActiveRequested()
    suspend fun onInactiveRequested()
}

interface TelecomCallController {
    val endpoints: StateFlow<List<CallAudioEndpoint>>
    val currentEndpoint: StateFlow<CallAudioEndpoint?>
    val registered: StateFlow<Boolean>
    val isSupported: Boolean
    suspend fun registerCall(
        callId: String,
        displayName: String,
        incoming: Boolean,
        callbacks: TelecomCallbacks
    )
    fun answer()
    fun activate()
    fun hold()
    fun disconnect(reason: Int = DisconnectCause.LOCAL)
    fun selectEndpoint(id: String)
}

@Singleton
class AndroidTelecomCallController @Inject constructor(
    @ApplicationContext private val context: Context
) : TelecomCallController {
    private val callsManager = CallsManager(context)
    private val mutableEndpoints = MutableStateFlow<List<CallAudioEndpoint>>(emptyList())
    override val endpoints: StateFlow<List<CallAudioEndpoint>> = mutableEndpoints.asStateFlow()
    private val mutableCurrentEndpoint = MutableStateFlow<CallAudioEndpoint?>(null)
    override val currentEndpoint: StateFlow<CallAudioEndpoint?> = mutableCurrentEndpoint.asStateFlow()
    private val mutableRegistered = MutableStateFlow(false)
    override val registered: StateFlow<Boolean> = mutableRegistered.asStateFlow()
    override val isSupported: Boolean =
        context.packageManager.hasSystemFeature("android.software.telecom")

    @Volatile private var actionChannel: Channel<TelecomAction>? = null
    @Volatile private var platformEndpoints: List<CallEndpointCompat> = emptyList()

    init {
        if (isSupported) {
            callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        }
    }

    override suspend fun registerCall(
        callId: String,
        displayName: String,
        incoming: Boolean,
        callbacks: TelecomCallbacks
    ) {
        if (!isSupported) return
        check(actionChannel == null) { "Only one Calling App call can be registered at a time" }

        val attributes = CallAttributesCompat(
            displayName = displayName,
            address = Uri.parse("callingapp:${Uri.encode(callId)}"),
            direction = if (incoming) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE
        )
        val actions = Channel<TelecomAction>(Channel.BUFFERED)
        actionChannel = actions
        try {
            callsManager.addCall(
                attributes,
                onAnswer = {
                    callbacks.onAnswerRequested()
                    mutableRegistered.value = true
                },
                onDisconnect = { cause ->
                    callbacks.onDisconnectRequested(cause.toString())
                    mutableRegistered.value = false
                },
                onSetActive = {
                    callbacks.onActiveRequested()
                },
                onSetInactive = {
                    // This must stop microphone/playout before returning to Telecom.
                    callbacks.onInactiveRequested()
                }
            ) {
                mutableRegistered.value = true
                launch { processActions(actions, callbacks) }
                launch {
                    currentCallEndpoint.collect { endpoint ->
                        mutableCurrentEndpoint.value = endpoint?.toDomain()
                    }
                }
                launch {
                    availableEndpoints.collect { endpoints ->
                        platformEndpoints = endpoints
                        mutableEndpoints.value = endpoints.map { it.toDomain() }
                    }
                }
            }
        } finally {
            mutableRegistered.value = false
            mutableEndpoints.value = emptyList()
            mutableCurrentEndpoint.value = null
            platformEndpoints = emptyList()
            actions.close()
            actionChannel = null
        }
    }

    override fun answer() {
        actionChannel?.trySend(TelecomAction.Answer)
    }

    override fun activate() {
        actionChannel?.trySend(TelecomAction.Activate)
    }

    override fun hold() {
        actionChannel?.trySend(TelecomAction.Hold)
    }

    override fun disconnect(reason: Int) {
        actionChannel?.trySend(TelecomAction.Disconnect(reason))
    }

    override fun selectEndpoint(id: String) {
        actionChannel?.trySend(TelecomAction.SelectEndpoint(id))
    }

    private suspend fun CallControlScope.processActions(
        actions: Channel<TelecomAction>,
        callbacks: TelecomCallbacks
    ) {
        actions.consumeAsFlow().collect { action ->
            when (action) {
                TelecomAction.Answer -> when (answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)) {
                    is CallControlResult.Success -> callbacks.onAnswerRequested()
                    is CallControlResult.Error -> disconnect(DisconnectCause(DisconnectCause.BUSY))
                }
                TelecomAction.Activate -> if (setActive() is CallControlResult.Success) {
                    callbacks.onActiveRequested()
                }
                TelecomAction.Hold -> if (setInactive() is CallControlResult.Success) {
                    callbacks.onInactiveRequested()
                }
                is TelecomAction.Disconnect -> {
                    val cause = DisconnectCause(action.reason)
                    disconnect(cause)
                    callbacks.onDisconnectRequested(cause.toString())
                }
                is TelecomAction.SelectEndpoint -> {
                    platformEndpoints.firstOrNull {
                        it.identifier.toString() == action.id
                    }?.let { requestEndpointChange(it) }
                }
            }
        }
    }

    private fun CallEndpointCompat.toDomain() = CallAudioEndpoint(
        id = identifier.toString(),
        name = name.toString(),
        route = when (type) {
            CallEndpointCompat.TYPE_SPEAKER -> CallAudioRoute.SPEAKER
            CallEndpointCompat.TYPE_BLUETOOTH -> CallAudioRoute.BLUETOOTH
            CallEndpointCompat.TYPE_WIRED_HEADSET -> CallAudioRoute.WIRED_HEADSET
            else -> CallAudioRoute.EARPIECE
        }
    )

    private sealed interface TelecomAction {
        data object Answer : TelecomAction
        data object Activate : TelecomAction
        data object Hold : TelecomAction
        data class Disconnect(val reason: Int) : TelecomAction
        data class SelectEndpoint(val id: String) : TelecomAction
    }
}
