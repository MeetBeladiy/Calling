package com.drivercall.data.signaling

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingUrlTest {
    @Test
    fun `secure websocket URL becomes HTTPS upgrade request`() {
        val url = signalingHttpUrl("wss://calling.example.com/ws")

        assertEquals("https", url.scheme)
        assertEquals("calling.example.com", url.host)
        assertEquals("/ws", url.encodedPath)
    }

    @Test
    fun `local websocket URL becomes HTTP upgrade request`() {
        val url = signalingHttpUrl("ws://10.0.2.2:8080/ws")

        assertEquals("http", url.scheme)
        assertEquals("10.0.2.2", url.host)
        assertEquals(8080, url.port)
        assertEquals("/ws", url.encodedPath)
    }
}
