package com.drivercall.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallForegroundServicePolicyTest {
    @Test
    fun `sticky restart never requests microphone foreground type`() {
        assertFalse(callActionNeedsMicrophone(action = null, incoming = false))
        assertFalse(callActionNeedsMicrophone(action = null, incoming = true))
    }

    @Test
    fun `explicit call actions request microphone only when appropriate`() {
        assertTrue(
            callActionNeedsMicrophone(
                action = CallForegroundService.ACTION_ACCEPT,
                incoming = true
            )
        )
        assertTrue(
            callActionNeedsMicrophone(
                action = CallForegroundService.ACTION_ENABLE_AUDIO,
                incoming = true
            )
        )
        assertTrue(
            callActionNeedsMicrophone(
                action = CallForegroundService.ACTION_SYNC,
                incoming = false
            )
        )
        assertFalse(
            callActionNeedsMicrophone(
                action = CallForegroundService.ACTION_SYNC,
                incoming = true
            )
        )
        assertFalse(
            callActionNeedsMicrophone(
                action = CallForegroundService.ACTION_END,
                incoming = false
            )
        )
    }
}
