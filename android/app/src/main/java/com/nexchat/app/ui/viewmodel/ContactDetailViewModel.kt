package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.data.ContactRepository
import com.nexchat.app.data.SafetyNumber
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactDetailViewModel(
    private val contacts: ContactRepository,
    private val chat: ChatRepository,
    val nexId: String,
) : ViewModel() {
    val contact: StateFlow<ContactEntity?> = contacts.contact(nexId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _numbers = MutableStateFlow<List<SafetyNumber>>(emptyList())
    val numbers: StateFlow<List<SafetyNumber>> = _numbers.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        refreshNumbers()
    }

    fun refreshNumbers() {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            runCatching { contacts.safetyNumbers(nexId) }
                .onSuccess { _numbers.value = it }
                .onFailure { _messages.tryEmit(it.message ?: "Could not load safety numbers") }
            _loading.value = false
        }
    }

    fun setVerified(verified: Boolean) = viewModelScope.launch {
        runCatching {
            contacts.setVerified(nexId, verified)
            refreshNumbers()
        }.onFailure { _messages.tryEmit(it.message ?: "Could not update verification") }
    }

    fun setBlocked(blocked: Boolean) = viewModelScope.launch {
        runCatching { contacts.setBlocked(nexId, blocked) }
            .onFailure { _messages.tryEmit(it.message ?: "Could not update the contact") }
    }

    fun report(reason: String) = viewModelScope.launch {
        runCatching { contacts.report(nexId, reason) }
            .onSuccess { _messages.tryEmit("Report submitted") }
            .onFailure { _messages.tryEmit(it.message ?: "Could not submit the report") }
    }

    fun deleteContact(onDeleted: () -> Unit) = viewModelScope.launch {
        runCatching { contacts.delete(nexId) }
            .onSuccess { onDeleted() }
            .onFailure { _messages.tryEmit(it.message ?: "Could not remove the contact") }
    }

    fun openConversation(onOpened: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            chat.openDirectConversation(nexId, contact.value?.displayName)
        }.onSuccess { onOpened(it.id) }
            .onFailure { _messages.tryEmit(it.message ?: "Could not open the chat") }
    }
}
