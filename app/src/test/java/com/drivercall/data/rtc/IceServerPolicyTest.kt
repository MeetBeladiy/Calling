package com.drivercall.data.rtc

import com.drivercall.data.remote.IceServerDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceServerPolicyTest {
    @Test
    fun `stun only configuration is not a production relay`() {
        val servers = listOf(
            IceServerDto(urls = listOf("stun:stun.example.com:3478"))
        )

        assertFalse(servers.hasAuthenticatedTurn())
    }

    @Test
    fun `turn requires both username and credential`() {
        val missingCredential = listOf(
            IceServerDto(
                urls = listOf("turn:relay.example.com:3478?transport=udp"),
                username = "driver"
            )
        )

        assertFalse(missingCredential.hasAuthenticatedTurn())
    }

    @Test
    fun `authenticated turn and turns transports satisfy relay policy`() {
        val servers = listOf(
            IceServerDto(
                urls = listOf(
                    "turn:relay.example.com:3478?transport=udp",
                    "turns:relay.example.com:443?transport=tcp"
                ),
                username = "driver",
                credential = "temporary-secret"
            )
        )

        assertTrue(servers.hasAuthenticatedTurn())
    }

    @Test
    fun `normalization removes blanks duplicates and unsupported schemes`() {
        val normalized = IceServerDto(
            urls = listOf(
                "  turns:relay.example.com:443?transport=tcp  ",
                "turns:relay.example.com:443?transport=tcp",
                "https://not-an-ice-server.example.com",
                " "
            ),
            username = " driver ",
            credential = " secret "
        ).normalized()

        requireNotNull(normalized)
        assertEquals(listOf("turns:relay.example.com:443?transport=tcp"), normalized.urls)
        assertEquals("driver", normalized.username)
        assertEquals("secret", normalized.credential)
    }

    @Test
    fun `normalization rejects a server with no supported URL`() {
        assertNull(IceServerDto(urls = listOf("https://example.com", " ")).normalized())
    }
}
