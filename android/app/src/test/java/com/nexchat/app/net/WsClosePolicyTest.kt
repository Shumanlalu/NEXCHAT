package com.nexchat.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class WsClosePolicyTest {
    @Test
    fun `revocation close codes invalidate immediately`() {
        for (code in listOf(4001, 4003, 4403)) {
            assertEquals("$code", WsCloseAction.INVALIDATE, WsClosePolicy.action(code))
        }
    }

    @Test
    fun `4401 refreshes once before invalidating`() {
        assertEquals(WsCloseAction.REFRESH_THEN_INVALIDATE, WsClosePolicy.action(4401))
    }

    @Test
    fun `all other close codes keep reconnect behaviour`() {
        for (code in listOf(1000, 1001, 1006, 4000, 4002, 4400, 4402, 4500)) {
            assertEquals("$code", WsCloseAction.RECONNECT, WsClosePolicy.action(code))
        }
    }
}
