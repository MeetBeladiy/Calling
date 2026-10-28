package com.drivercall.data.call

import com.drivercall.domain.call.CallStage
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionStoreTest {
    private val gson = Gson()

    @Test
    fun `incoming ringing role and stage survive JSON round trip`() {
        val state = roundTrip(incoming = true, stage = CallStage.RINGING).toCallState()

        assertTrue(state.incoming)
        assertEquals(CallStage.RINGING, state.stage)
        assertEquals("Incoming call", state.statusMessage)
    }

    @Test
    fun `accepted incoming call keeps receiver role`() {
        val state = roundTrip(incoming = true, stage = CallStage.CONNECTED).toCallState()

        assertTrue(state.incoming)
        assertEquals(CallStage.CONNECTED, state.stage)
    }

    @Test
    fun `outgoing ringing call keeps caller role`() {
        val state = roundTrip(incoming = false, stage = CallStage.RINGING).toCallState()

        assertFalse(state.incoming)
        assertEquals(CallStage.RINGING, state.stage)
    }

    private fun roundTrip(incoming: Boolean, stage: CallStage): PersistedCallSession {
        val original = PersistedCallSession(
            callId = "call-1",
            peerId = "peer-1",
            peerName = "Friend",
            startedAtEpochMs = 1_000L,
            connectedAtEpochMs = if (stage == CallStage.CONNECTED) 2_000L else null,
            muted = false,
            reconnectionCount = 0,
            incoming = incoming,
            stage = stage
        )
        return gson.fromJson(gson.toJson(original), PersistedCallSession::class.java)
    }
}
