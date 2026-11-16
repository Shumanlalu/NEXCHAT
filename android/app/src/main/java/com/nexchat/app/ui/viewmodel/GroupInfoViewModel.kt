package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.GroupEntity
import com.nexchat.app.core.db.GroupMemberEntity
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.AuthState
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.data.ContactRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupInfoViewModel(
    private val chat: ChatRepository,
    contacts: ContactRepository,
    auth: AuthRepository,
    val groupId: String,
) : ViewModel() {
    val myNexId: String? = (auth.state.value as? AuthState.LoggedIn)?.profile?.nexId

    private val _group = MutableStateFlow<GroupEntity?>(null)
    val group: StateFlow<GroupEntity?> = _group.asStateFlow()

    val members: StateFlow<List<GroupMemberEntity>> = chat.groupMembers(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contacts: StateFlow<List<ContactEntity>> = contacts.contacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        reload()
    }

    fun reload() = viewModelScope.launch {
        runCatching {
            var group = chat.group(groupId)
            if (group == null || !group.metadataKnown) {
                runCatching { chat.refreshGroup(groupId) }
            }
            group = chat.group(groupId)
            _group.value = group
        }.onFailure { _messages.tryEmit(it.message ?: "Could not load the group") }
    }

    fun rename(title: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching {
            chat.renameGroup(groupId, title)
            _group.value = chat.group(groupId)
        }.onSuccess { onDone() }
            .onFailure { _messages.tryEmit(it.message ?: "Could not rename the group") }
    }

    fun addMembers(chosen: List<ContactEntity>) = viewModelScope.launch {
        if (chosen.isEmpty()) return@launch
        runCatching {
            chat.addGroupMembers(groupId, chosen)
            _group.value = chat.group(groupId)
        }.onSuccess { _messages.tryEmit("Members added") }
            .onFailure { _messages.tryEmit(it.message ?: "Could not add members") }
    }

    fun removeMember(nexId: String) = viewModelScope.launch {
        runCatching {
            chat.removeGroupMember(groupId, nexId)
            _group.value = chat.group(groupId)
        }.onFailure { _messages.tryEmit(it.message ?: "Could not remove the member") }
    }

    fun leave(onLeft: () -> Unit = {}) = viewModelScope.launch {
        runCatching { chat.leaveGroup(groupId) }
            .onSuccess { onLeft() }
            .onFailure { _messages.tryEmit(it.message ?: "Could not leave the group") }
    }
}
