package com.drivercall.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

enum class CellularCallState { IDLE, RINGING, OFFHOOK, PERMISSION_REQUIRED }

@Singleton
class CellularCallMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    val states: Flow<CellularCallState> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(CellularCallState.PERMISSION_REQUIRED)
            close()
            return@callbackFlow
        }

        fun emit(platformState: Int) {
            trySend(
                when (platformState) {
                    TelephonyManager.CALL_STATE_RINGING -> CellularCallState.RINGING
                    TelephonyManager.CALL_STATE_OFFHOOK -> CellularCallState.OFFHOOK
                    else -> CellularCallState.IDLE
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = emit(state)
            }
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
            emit(telephony.callState)
            awaitClose { telephony.unregisterTelephonyCallback(callback) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Legacy callback for API 26-30")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = emit(state)
            }
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            @Suppress("DEPRECATION")
            emit(telephony.callState)
            awaitClose {
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }.distinctUntilChanged()
}
