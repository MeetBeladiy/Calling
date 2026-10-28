package com.drivercall.domain.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {
    @Test
    fun `customer call preserves and restores logical VoIP session`() {
        val initial = CallStateMachine.reduce(
            CallState(),
            CallEvent.StartOutgoing("call-1", "friend-1", "Sarah", 100)
        )
        val connected = CallStateMachine.reduce(initial, CallEvent.Connected("call-1", 200))
        val cellularStarted = CallStateMachine.reduce(
            connected,
            CallEvent.CellularCallStarted("call-1")
        )
        val cellularActive = CallStateMachine.reduce(
            cellularStarted,
            CallEvent.CellularCallActive("call-1")
        )
        val cellularEnded = CallStateMachine.reduce(
            cellularActive,
            CallEvent.CellularCallEnded("call-1")
        )
        val restoring = CallStateMachine.reduce(
            cellularEnded,
            CallEvent.RestoringVoip("call-1")
        )
        val restored = CallStateMachine.reduce(restoring, CallEvent.VoipRestored("call-1"))

        assertEquals(CallStage.VOIP_RESTORED, restored.stage)
        assertEquals("call-1", restored.callId)
        assertEquals("Sarah", restored.peerName)
        assertFalse(restored.heldForCellularCall)
    }

    @Test
    fun `network loss and ICE restart retain call identity`() {
        val connected = CallState(
            stage = CallStage.CONNECTED,
            callId = "active",
            peerId = "peer",
            peerName = "Meet"
        )
        val reconnecting = CallStateMachine.reduce(
            connected,
            CallEvent.NetworkLost("active")
        )
        val restored = CallStateMachine.reduce(
            reconnecting,
            CallEvent.IceRestartSucceeded("active")
        )

        assertEquals(CallStage.CONNECTED, restored.stage)
        assertEquals("active", restored.callId)
        assertEquals(1, restored.diagnostics.reconnectionCount)
    }

    @Test
    fun `late callback from old call is ignored`() {
        val active = CallState(
            stage = CallStage.CONNECTED,
            callId = "new-call",
            peerName = "Current friend"
        )
        val result = CallStateMachine.reduce(active, CallEvent.Ended("old-call"))
        assertEquals(active, result)
    }

    @Test
    fun `server call id atomically replaces provisional outgoing id`() {
        val provisional = CallStateMachine.reduce(
            CallState(),
            CallEvent.StartOutgoing("pending-1", "friend-1", "Sarah", 100)
        )

        val assigned = CallStateMachine.reduce(
            provisional,
            CallEvent.AssignServerCallId("pending-1", "server-call-1")
        )

        assertEquals(CallStage.OUTGOING_VOIP_CALL, assigned.stage)
        assertEquals("server-call-1", assigned.callId)
        assertEquals("friend-1", assigned.peerId)
    }

    @Test
    fun `recoverable call failure keeps call UI visible for retry`() {
        val outgoing = CallStateMachine.reduce(
            CallState(),
            CallEvent.StartOutgoing("pending-1", "friend-1", "Sarah", 100)
        )
        val failed = CallStateMachine.reduce(
            outgoing,
            CallEvent.Failed("pending-1", "Call server timed out")
        )

        assertEquals(CallStage.VOIP_DISCONNECTED, failed.stage)
        assertEquals(CallQuality.UNKNOWN, failed.quality)
        assertFalse(failed.hasLiveSession)
        assertTrue(failed.shouldShowCallUi)
    }

    @Test
    fun `accept immediately leaves ringing UI and shows secure negotiation`() {
        val ringing = CallStateMachine.reduce(
            CallState(),
            CallEvent.ReceiveIncoming("call-1", "friend-1", "Meet", 100)
        )

        val accepting = CallStateMachine.reduce(ringing, CallEvent.Negotiating("call-1"))

        assertEquals(CallStage.NEGOTIATING, accepting.stage)
        assertTrue(accepting.incoming)
        assertEquals("Connecting secure audio…", accepting.statusMessage)
    }

    @Test
    fun `failed accept returns to actionable ringing state`() {
        val ringing = CallStateMachine.reduce(
            CallState(),
            CallEvent.ReceiveIncoming("call-1", "friend-1", "Meet", 100)
        )
        val accepting = CallStateMachine.reduce(ringing, CallEvent.Negotiating("call-1"))

        val retryable = CallStateMachine.reduce(
            accepting,
            CallEvent.AcceptFailed("call-1", "Could not accept. Tap Accept again.")
        )

        assertEquals(CallStage.RINGING, retryable.stage)
        assertEquals("Could not accept. Tap Accept again.", retryable.statusMessage)
    }

    @Test
    fun `only actual network loss is labelled no internet`() {
        val active = CallState(
            stage = CallStage.NEGOTIATING,
            callId = "call-1",
            diagnostics = CallDiagnostics(networkLabel = "Wi-Fi")
        )

        val negotiationFailure = CallStateMachine.reduce(
            active,
            CallEvent.Failed("call-1", "Unable to secure call audio")
        )
        val networkFailure = CallStateMachine.reduce(active, CallEvent.NetworkLost("call-1"))

        assertEquals(CallQuality.UNKNOWN, negotiationFailure.quality)
        assertEquals(CallQuality.NO_INTERNET, networkFailure.quality)
    }

    @Test
    fun `manual retry moves failed call back to reconnecting`() {
        val failed = CallState(
            stage = CallStage.VOIP_DISCONNECTED,
            callId = "call-1",
            peerId = "friend-1",
            statusMessage = "Unable to secure call audio"
        )

        val retrying = CallStateMachine.reduce(failed, CallEvent.RetryStarted("call-1"))

        assertEquals(CallStage.RECONNECTING, retrying.stage)
        assertTrue(retrying.hasLiveSession)
        assertEquals("Reconnecting secure audio…", retrying.statusMessage)
    }

    @Test
    fun `reset removes completed call UI`() {
        val ended = CallState(
            stage = CallStage.CALL_ENDED,
            callId = "call-1"
        )

        assertTrue(ended.shouldShowCallUi)
        assertFalse(CallStateMachine.reduce(ended, CallEvent.Reset).shouldShowCallUi)
    }
}
