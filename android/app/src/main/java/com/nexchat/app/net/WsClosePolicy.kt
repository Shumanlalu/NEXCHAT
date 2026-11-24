package com.nexchat.app.net

/** Pure mapping of relay WebSocket close codes to session/reconnect behaviour. */
enum class WsCloseAction {
    INVALIDATE,
    REFRESH_THEN_INVALIDATE,
    RECONNECT,
}

object WsClosePolicy {
    fun action(code: Int): WsCloseAction = when (code) {
        4001, 4003, 4403 -> WsCloseAction.INVALIDATE
        4401 -> WsCloseAction.REFRESH_THEN_INVALIDATE
        else -> WsCloseAction.RECONNECT
    }
}
