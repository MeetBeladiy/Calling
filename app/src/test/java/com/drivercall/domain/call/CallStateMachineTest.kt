package com.drivercall.domain.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
