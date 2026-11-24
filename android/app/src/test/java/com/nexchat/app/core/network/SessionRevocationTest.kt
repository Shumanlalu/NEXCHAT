package com.nexchat.app.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRevocationTest {
    private val revocationCodes = listOf(
        "UNAUTHORIZED",
        "INVALID_TOKEN",
        "SESSION_REVOKED",
        "INVALID_REFRESH_TOKEN",
        "ACCOUNT_BANNED",
        "ACCOUNT_SUSPENDED",
    )

    @Test
    fun `revocation codes are recognized on 401 and 403`() {
        for (code in revocationCodes) {
            assertTrue("401 $code", SessionRevocation.isRevocation(401, code))
            assertTrue("403 $code", SessionRevocation.isRevocation(403, code))
            assertTrue("case insensitive $code", SessionRevocation.isRevocation(401, code.lowercase()))
        }
    }

    @Test
    fun `policy denials and login failures are not revocations`() {
        val nonRevocation = listOf(
            "NOT_A_GROUP_MEMBER",
            "CALL_NOT_YOURS",
            "CREATOR_REQUIRED",
            "ADMIN_REQUIRED",
            "INVALID_CREDENTIALS",
            "RATE_LIMITED",
            null,
        )
        for (code in nonRevocation) {
            assertFalse("401 $code", SessionRevocation.isRevocation(401, code))
            assertFalse("403 $code", SessionRevocation.isRevocation(403, code))
        }
    }

    @Test
    fun `other statuses are never revocations`() {
        for (status in listOf(200, 400, 404, 409, 429, 500)) {
            assertFalse("$status", SessionRevocation.isRevocation(status, "SESSION_REVOKED"))
        }
    }
}
