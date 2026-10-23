package com.drivercall.data.signaling

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingTimestampTest {
    @Test
    fun `epoch millisecond timestamp is accepted`() {
        val value = JsonParser.parseString("1761181200123")

        assertEquals(1761181200123L, parseSignalTimestamp(value, fallbackEpochMs = 1L))
    }

    @Test
    fun `ISO timestamp from older server is accepted`() {
        val value = JsonParser.parseString("\"2026-10-23T01:40:00.123Z\"")

        assertEquals(1792719600123L, parseSignalTimestamp(value, fallbackEpochMs = 1L))
    }

    @Test
    fun `invalid timestamp uses fallback`() {
        val value = JsonParser.parseString("\"not-a-date\"")

        assertEquals(42L, parseSignalTimestamp(value, fallbackEpochMs = 42L))
    }
}
