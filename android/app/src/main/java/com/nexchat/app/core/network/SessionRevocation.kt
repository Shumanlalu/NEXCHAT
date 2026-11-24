package com.nexchat.app.core.network

/**
 * Pure classification of relay error codes that mean the session itself is no
 * longer valid. Policy denials and failed logins must never land here.
 */
object SessionRevocation {
    private const val UNAUTHORIZED = "UNAUTHORIZED"
    private const val INVALID_TOKEN = "INVALID_TOKEN"
    private const val SESSION_REVOKED = "SESSION_REVOKED"
    private const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    private const val ACCOUNT_BANNED = "ACCOUNT_BANNED"
    private const val ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED"

    private val REVOCATION_CODES = setOf(
        UNAUTHORIZED,
        INVALID_TOKEN,
        SESSION_REVOKED,
        INVALID_REFRESH_TOKEN,
        ACCOUNT_BANNED,
        ACCOUNT_SUSPENDED,
    )

    private val REVOCATION_STATUSES = setOf(401, 403)

    fun isRevocation(statusCode: Int, code: String?): Boolean =
        statusCode in REVOCATION_STATUSES && code?.uppercase() in REVOCATION_CODES
}
