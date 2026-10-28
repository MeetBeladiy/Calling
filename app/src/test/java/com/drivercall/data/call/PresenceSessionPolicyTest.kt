package com.drivercall.data.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceSessionPolicyTest {
    @Test
    fun `initial restored session keeps token-established socket`() {
        assertFalse(shouldDisconnectPresence(previousUserId = null, nextUserId = "user-a"))
    }

    @Test
    fun `logout and account change disconnect existing socket`() {
        assertTrue(shouldDisconnectPresence(previousUserId = "user-a", nextUserId = null))
        assertTrue(shouldDisconnectPresence(previousUserId = "user-a", nextUserId = "user-b"))
    }

    @Test
    fun `same user session refresh keeps existing socket`() {
        assertFalse(shouldDisconnectPresence(previousUserId = "user-a", nextUserId = "user-a"))
    }
}
