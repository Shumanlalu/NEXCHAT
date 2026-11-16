package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.network.AuthUser
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.AuthState
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.data.ContactRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val contacts: ContactRepository,
    private val chat: ChatRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val myNexId: String? = (auth.state.value as? AuthState.LoggedIn)?.profile?.nexId

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val all: StateFlow<List<ContactEntity>> = contacts.contacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filtered: StateFlow<List<ContactEntity>> = combine(all, _query) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            val needle = query.trim()
            list.filter {
                it.displayName.contains(needle, ignoreCase = true) ||
                    it.username.contains(needle, ignoreCase = true) ||
                    it.nexId.contains(needle, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blocked: StateFlow<List<ContactEntity>> = contacts.blocked()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _globalResults = MutableStateFlow<List<AuthUser>>(emptyList())
    val globalResults: StateFlow<List<AuthUser>> = _globalResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    private var searchJob: Job? = null

    fun setQuery(value: String) {
        _query.value = value
    }

    fun searchPeople(raw: String) {
        val query = raw.trim()
        searchJob?.cancel()
        if (query.length < 2) {
            _globalResults.value = emptyList()
            _searchError.value = null
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _searching.value = true
            _searchError.value = null
            runCatching { contacts.search(query) }
                .onSuccess { _globalResults.value = it.filter { user -> user.nexId != myNexId } }
                .onFailure {
                    _searchError.value = it.message ?: "Search failed"
                    _globalResults.value = emptyList()
                }
            _searching.value = false
        }
    }

    fun clearPeopleSearch() {
        searchJob?.cancel()
        _globalResults.value = emptyList()
        _searchError.value = null
        _searching.value = false
    }

    suspend fun startConversation(user: AuthUser): ConversationEntity {
        val contact = contacts.add(user)
        return chat.openDirectConversation(contact.nexId, contact.displayName)
    }

    suspend fun startConversation(contact: ContactEntity): ConversationEntity =
        chat.openDirectConversation(contact.nexId, contact.displayName)

    fun setBlocked(nexId: String, blocked: Boolean) = viewModelScope.launch {
        runCatching { contacts.setBlocked(nexId, blocked) }
            .onFailure { _errors.tryEmit(it.message ?: "Could not update the contact") }
    }

    fun removeContact(nexId: String) = viewModelScope.launch {
        runCatching { contacts.delete(nexId) }
            .onFailure { _errors.tryEmit(it.message ?: "Could not remove the contact") }
    }
}

class NewGroupViewModel(
    private val chat: ChatRepository,
    contacts: ContactRepository,
) : ViewModel() {
    val contacts: StateFlow<List<ContactEntity>> = contacts.contacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _created = MutableSharedFlow<ConversationEntity>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    fun setTitle(value: String) {
        _title.value = value.take(60)
    }

    fun toggle(nexId: String) {
        _selected.value = if (_selected.value.contains(nexId)) {
            _selected.value - nexId
        } else {
            _selected.value + nexId
        }
    }

    fun create() {
        if (_creating.value) return
        val chosen = contacts.value.filter { _selected.value.contains(it.nexId) }
        if (chosen.isEmpty()) {
            _error.value = "Choose at least one contact."
            return
        }
        _creating.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { chat.createGroup(_title.value, chosen) }
                .onSuccess { _created.tryEmit(it) }
                .onFailure { _error.value = it.message ?: "Could not create the group" }
            _creating.value = false
        }
    }
}
