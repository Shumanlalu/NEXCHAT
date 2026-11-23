package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.GroupMemberEntity
import com.nexchat.app.core.db.MessageEntity
import com.nexchat.app.data.AttachmentDraft
import com.nexchat.app.data.ChatEvent
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.data.ContactRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(
    private val chat: ChatRepository,
    private val contacts: ContactRepository,
    val conversationId: String,
) : ViewModel() {
    val conversation: StateFlow<ConversationEntity?> = chat.conversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: Flow<PagingData<MessageEntity>> = chat.messages(conversationId).cachedIn(viewModelScope)

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _replyTo = MutableStateFlow<MessageEntity?>(null)
    val replyTo: StateFlow<MessageEntity?> = _replyTo.asStateFlow()

    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _contact = MutableStateFlow<ContactEntity?>(null)
    val contact: StateFlow<ContactEntity?> = _contact.asStateFlow()

    private val _groupMembers = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    val groupMembers: StateFlow<List<GroupMemberEntity>> = _groupMembers.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    private var peerNexId: String? = null
    private var groupMembersJob: Job? = null
    private var remoteTypingJob: Job? = null
    private var localTypingJob: Job? = null
    private var localTypingActive = false
    private var draftJob: Job? = null

    init {
        chat.setActiveConversation(conversationId)
        viewModelScope.launch { runCatching { chat.markConversationRead(conversationId) } }
        viewModelScope.launch {
            chat.getConversation(conversationId)?.draft?.let { draft ->
                if (draft.isNotBlank() && _input.value.isBlank()) _input.value = draft
            }
        }
        viewModelScope.launch {
            chat.conversation(conversationId).collectLatest { conversation ->
                if (conversation == null) return@collectLatest
                if (conversation.type == "DM") {
                    peerNexId = conversation.peerNexId
                    _contact.value = conversation.peerNexId?.let { contacts.get(it) }
                    if (groupMembersJob != null) {
                        groupMembersJob?.cancel()
                        groupMembersJob = null
                        _groupMembers.value = emptyList()
                    }
                } else if (conversation.groupId != null) {
                    trackGroupMembers(conversation.groupId)
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val peer = peerNexId
                if (peer != null) {
                    _online.value = contacts.presence(peer)
                }
                delay(30_000)
            }
        }
        viewModelScope.launch {
            chat.events.collect { event ->
                when (event) {
                    is ChatEvent.Typing -> {
                        if (event.event.conversationId == conversationId) {
                            showRemoteTyping(event.event.active)
                        }
                    }
                    is ChatEvent.Incoming -> {
                        if (event.event.conversation.id == conversationId) {
                            runCatching { chat.markConversationRead(conversationId) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun trackGroupMembers(groupId: String) {
        if (groupMembersJob != null) return
        groupMembersJob = viewModelScope.launch {
            chat.groupMembers(groupId).collect { members ->
                _groupMembers.value = members
            }
        }
    }

    fun setInput(value: String) {
        _input.value = value
        if (value.isBlank()) {
            localTypingJob?.cancel()
            if (localTypingActive) {
                localTypingActive = false
                viewModelScope.launch { runCatching { chat.setTyping(conversationId, false) } }
            }
        } else {
            if (!localTypingActive) {
                localTypingActive = true
                viewModelScope.launch { runCatching { chat.setTyping(conversationId, true) } }
            }
            localTypingJob?.cancel()
            localTypingJob = viewModelScope.launch {
                delay(1_800)
                localTypingActive = false
                runCatching { chat.setTyping(conversationId, false) }
            }
        }
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(500)
            runCatching { chat.saveDraft(conversationId, value) }
        }
    }

    private fun showRemoteTyping(active: Boolean) {
        remoteTypingJob?.cancel()
        if (active) {
            _typing.value = true
            remoteTypingJob = viewModelScope.launch {
                delay(5_000)
                _typing.value = false
            }
        } else {
            _typing.value = false
        }
    }

    fun replyTo(message: MessageEntity?) {
        _replyTo.value = message
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        val reply = _replyTo.value
        _input.value = ""
        _replyTo.value = null
        localTypingJob?.cancel()
        localTypingActive = false
        viewModelScope.launch {
            runCatching { chat.setTyping(conversationId, false) }
            runCatching { chat.sendText(conversationId, text, reply) }
                .onFailure { error ->
                    _errors.tryEmit(error.message ?: "The message could not be sent")
                    if (_input.value.isBlank()) _input.value = text
                }
        }
    }

    fun sendAttachment(draft: AttachmentDraft) {
        val reply = _replyTo.value
        _replyTo.value = null
        localTypingJob?.cancel()
        localTypingActive = false
        viewModelScope.launch {
            runCatching { chat.setTyping(conversationId, false) }
            runCatching { chat.sendAttachment(conversationId, draft, reply) }
                .onFailure { error ->
                    _errors.tryEmit(error.message ?: "The attachment could not be sent")
                }
        }
    }

    fun edit(messageId: String, text: String) = viewModelScope.launch {
        runCatching { chat.editMessage(conversationId, messageId, text) }
            .onFailure { _errors.tryEmit(it.message ?: "Could not edit the message") }
    }

    fun deleteForEveryone(messageId: String) = viewModelScope.launch {
        runCatching { chat.deleteMessage(conversationId, messageId) }
            .onFailure { _errors.tryEmit(it.message ?: "Could not delete the message") }
    }

    fun deleteLocally(messageId: String) = viewModelScope.launch {
        runCatching { chat.deleteLocalMessage(messageId) }
            .onFailure { _errors.tryEmit(it.message ?: "Could not remove the message") }
    }

    fun react(messageId: String, emoji: String) = viewModelScope.launch {
        runCatching { chat.react(conversationId, messageId, emoji) }
            .onFailure { _errors.tryEmit(it.message ?: "Could not add the reaction") }
    }

    fun markRead() = viewModelScope.launch {
        runCatching { chat.markConversationRead(conversationId) }
    }

    suspend fun setMuted(muted: Boolean) {
        chat.markConversationMuted(conversationId, muted)
    }

    suspend fun setArchived(archived: Boolean) {
        chat.markConversationArchived(conversationId, archived)
    }

    suspend fun clearChat() {
        chat.clearConversation(conversationId)
    }

    suspend fun blockPeer(blocked: Boolean) {
        val peer = peerNexId ?: return
        contacts.setBlocked(peer, blocked)
        _contact.value = contacts.get(peer)
    }

    fun reportPeer(reason: String) = viewModelScope.launch {
        val peer = peerNexId ?: return@launch
        runCatching { contacts.report(peer, reason) }
            .onSuccess { _errors.tryEmit("Report submitted") }
            .onFailure { _errors.tryEmit(it.message ?: "Could not submit the report") }
    }

    override fun onCleared() {
        if (localTypingActive) {
            viewModelScope.launch { runCatching { chat.setTyping(conversationId, false) } }
        }
        chat.setActiveConversation(null)
        super.onCleared()
    }
}
