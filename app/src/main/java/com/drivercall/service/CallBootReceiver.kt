package com.drivercall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drivercall.data.security.TokenStorage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CallBootReceiver : BroadcastReceiver() {
    @Inject lateinit var tokenStorage: TokenStorage

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (tokenStorage.getToken() != null) CallAvailabilityService.start(context)
    }
}
