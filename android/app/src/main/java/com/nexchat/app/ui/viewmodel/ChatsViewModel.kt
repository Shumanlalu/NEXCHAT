package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.ProfileEntity
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.AuthState
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.net.ConnectionState
import com.nexchat.app.net.WebSocketManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatsViewModel(
    private val chat: ChatRepository,
    auth: AuthRepository,
    websocket: WebSocketManager,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val profile: StateFlow<ProfileEntity?> = auth.state
        .map { (it as? AuthState.LoggedIn)?.profile }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val conversations: StateFlow<List<ConversationEntity>> = combine(
        chat.conversations(archived = false),
        _query,
    ) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            val needle = query.trim()
            list.filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.lastMessagePreview.orEmpty().contains(needle, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archived: StateFlow<List<ConversationEntity>> = chat.conversations(archived = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connection: StateFlow<ConnectionState> = websocket.state

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    private var refreshedOnce = false

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() {
        if (refreshedOnce) return
        refreshedOnce = true
        viewModelScope.launch {
            runCatching { chat.onConnected() }
                .onFailure { _errors.tryEmit(it.message ?: "Could not refresh messages") }
        }
    }

    fun pin(conversation: ConversationEntity, pinned: Boolean) = viewModelScope.launch {
        chat.pinConversation(conversation.id, pinned)
    }

    fun mute(conversation: ConversationEntity, muted: Boolean) = viewModelScope.launch {
        chat.markConversationMuted(conversation.id, muted)
    }

    fun archive(conversation: ConversationEntity, archived: Boolean) = viewModelScope.launch {
        chat.markConversationArchived(conversation.id, archived)
    }

    fun clear(conversation: ConversationEntity) = viewModelScope.launch {
        chat.clearConversation(conversation.id)
    }

    fun markRead(conversation: ConversationEntity) = viewModelScope.launch {
        chat.markConversationRead(conversation.id)
    }
}
