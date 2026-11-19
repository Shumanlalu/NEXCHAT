package com.nexchat.app.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalDeviceIdTest {
    @Test
    fun `the production crash id becomes a libsignal-compatible id`() {
        val mapped = canonicalSignalDeviceId(1_708_410_131)

        assertTrue("libsignal only accepts 1..127, got $mapped", mapped in 1..127)
        assertEquals(36, mapped)
    }

    @Test
    fun `valid ids are never rewritten`() {
        for (id in MIN_SIGNAL_DEVICE_ID..MAX_SIGNAL_DEVICE_ID) {
            assertEquals(id, canonicalSignalDeviceId(id))
        }
    }

    @Test
    fun `every 32-bit id maps into the supported range`() {
        val values = listOf(
            Int.MIN_VALUE,
            -1,
            0,
            Int.MAX_VALUE,
            1_708_410_131,
            2_000_000_000,
        )
        for (value in values) {
            val mapped = canonicalSignalDeviceId(value)
            assertTrue("$value mapped to $mapped", mapped in MIN_SIGNAL_DEVICE_ID..MAX_SIGNAL_DEVICE_ID)
        }
    }
}
