package com.nexchat.app.core.security

/**
 * Pure, dependency-free redaction for anything that may reach a log, a crash
 * record or telemetry. Applied on debug logs as defence in depth and on every
 * release-path payload.
 */
object LogRedactor {
    private const val REDACTED = "<redacted>"

    private val NEX_ID = Regex("""\bNX-[A-Z2-9]{4}-[A-Z2-9]{4}\b""", RegexOption.IGNORE_CASE)
    private val UUID = Regex(
        """\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""",
        RegexOption.IGNORE_CASE,
    )
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\b""")
    private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val URL_QUERY = Regex("""\?[^\s"'<>]+""")
    private val CREDENTIAL_ASSIGNMENT = Regex(
        """(?i)\b((token|refreshToken|accessToken|password|secret|apiKey|api_key|authorization|credential|ciphertext))\s*[=:]\s*[^\s,;)]+""",
    )
    private val PHONE = Regex("""(?<![A-Za-z0-9])\+?\d(?:[\d\s().-]{7,}\d)(?![A-Za-z0-9])""")
    private val BLOB = Regex("""\b[A-Za-z0-9+/_-]{32,}={0,2}\b""")

    fun redact(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        var result = value
        result = URL_QUERY.replace(result, "?$REDACTED")
        result = JWT.replace(result, REDACTED)
        result = BEARER.replace(result, "Bearer $REDACTED")
        result = CREDENTIAL_ASSIGNMENT.replace(result) { match ->
            "${match.groupValues[2]}=$REDACTED"
        }
        result = NEX_ID.replace(result, REDACTED)
        result = UUID.replace(result, REDACTED)
        result = PHONE.replace(result, REDACTED)
        result = BLOB.replace(result, REDACTED)
        return result
    }

    fun redactMap(values: Map<String, String>?): Map<String, String>? {
        if (values == null) return null
        val result = LinkedHashMap<String, String>(values.size)
        for ((key, value) in values) {
            result[redact(key)] = redact(value)
        }
        return result
    }
}
