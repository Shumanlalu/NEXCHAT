package com.nexchat.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    @Test
    fun `redacts nex ids and uuids`() {
        val redacted = LogRedactor.redact(
            "peer=NX-ABCD-EFGH call=550e8400-e29b-41d4-a716-446655440000",
        )
        assertFalse(redacted.contains("NX-ABCD-EFGH"))
        assertFalse(redacted.contains("550e8400"))
        assertEquals("peer=<redacted> call=<redacted>", redacted)
    }

    @Test
    fun `redacts jwt bearer tokens and credential assignments`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val redacted = LogRedactor.redact("Authorization: Bearer $jwt refreshToken=$jwt password=hunter2")
        assertFalse(redacted.contains("eyJ"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("SflKxw"))
    }

    @Test
    fun `redacts url query strings`() {
        val redacted = LogRedactor.redact(
            "GET https://example.com/v1/users?nexId=NX-ABCD-EFGH&token=abc def",
        )
        assertFalse(redacted.contains("nexId="))
        assertFalse(redacted.contains("NX-ABCD-EFGH"))
        assertEquals("GET https://example.com/v1/users?<redacted> def", redacted)
    }

    @Test
    fun `redacts phone like numbers and long blobs`() {
        val redacted = LogRedactor.redact("to +1 (415) 555-2671 blob=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXpBQkNERUZH")
        assertFalse(redacted.contains("555-2671"))
        assertFalse(redacted.contains("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXpBQkNERUZH"))
    }

    @Test
    fun `redacts metadata keys as well as values`() {
        val redacted = LogRedactor.redactMap(
            mapOf("NX-ABCD-EFGH" to "call 550e8400-e29b-41d4-a716-446655440000"),
        )!!
        assertEquals(1, redacted.size)
        val (key, value) = redacted.entries.first()
        assertEquals("<redacted>", key)
        assertEquals("call <redacted>", value)
    }

    @Test
    fun `handles null and empty input`() {
        assertEquals("", LogRedactor.redact(null))
        assertEquals("", LogRedactor.redact(""))
        assertEquals(null, LogRedactor.redactMap(null))
    }
}
