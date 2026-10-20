package com.drivercall.data.call

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.drivercall.domain.call.CallStage
import com.drivercall.domain.call.CallState
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedCallSession(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val startedAtEpochMs: Long,
    val connectedAtEpochMs: Long?,
    val muted: Boolean,
    val reconnectionCount: Int
)

/** Saves only restoration metadata, never media or a customer's phone number. */
@Singleton
class CallSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val gson = Gson()
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "calling_app_call_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(state: CallState) {
        val callId = state.callId ?: return
        val peerId = state.peerId ?: return
        val startedAt = state.startedAtEpochMs ?: return
        val persisted = PersistedCallSession(
            callId,
            peerId,
            state.peerName,
            startedAt,
            state.connectedAtEpochMs,
            state.muted,
            state.diagnostics.reconnectionCount
        )
        preferences.edit().putString(KEY_SESSION, gson.toJson(persisted)).apply()
    }

    fun restore(): CallState? = preferences.getString(KEY_SESSION, null)?.let { json ->
        runCatching { gson.fromJson(json, PersistedCallSession::class.java) }.getOrNull()
    }?.let {
        CallState(
            stage = CallStage.VOIP_DISCONNECTED,
            callId = it.callId,
            peerId = it.peerId,
            peerName = it.peerName,
            startedAtEpochMs = it.startedAtEpochMs,
            connectedAtEpochMs = it.connectedAtEpochMs,
            muted = it.muted,
            diagnostics = com.drivercall.domain.call.CallDiagnostics(
                reconnectionCount = it.reconnectionCount
            ),
            statusMessage = "Previous call was interrupted. Start a new call to reconnect."
        )
    }

    fun clear() {
        preferences.edit().remove(KEY_SESSION).apply()
    }

    private companion object {
        const val KEY_SESSION = "active_call"
    }
}

