package com.nexchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.core.network.AuthUser
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.EmptyState
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.GlassSearchField
import com.nexchat.app.ui.components.GlassTextField
import com.nexchat.app.ui.components.NexTopBar
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.ContactsViewModel
import com.nexchat.app.ui.viewmodel.NewGroupViewModel
import kotlinx.coroutines.launch

@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "add-contact") { ContactsViewModel(it.contacts, it.chat, it.auth) }
    val results by vm.globalResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val searchError by vm.searchError.collectAsStateWithLifecycle()
    val allContacts by vm.all.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.errors.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NexTopBar(
                title = "Find people",
                subtitle = "Search by username or NEX ID",
                onBack = onBack,
            )
            GlassSearchField(
                value = query,
                onValueChange = {
                    query = it
                    vm.searchPeople(it)
                },
                placeholder = "Search NEXCHAT…",
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(14.dp))
            when {
                searching -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(30.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = palette.accent,
                        strokeWidth = 2.dp,
                    )
                }
                searchError != null -> GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    Text(
                        searchError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.danger,
                    )
                }
                query.trim().length < 2 -> EmptyState(
                    icon = Icons.Outlined.PersonSearch,
                    title = "Find your people",
                    body = "Type at least two characters. Usernames and display names are searchable; a full NEX ID finds one person exactly.",
                )
                results.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "No one found",
                    body = "Check the spelling, or ask your contact for their exact NEX ID.",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 100.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.id }) { user ->
                        PersonRow(
                            user = user,
                            added = allContacts.any { it.nexId == user.nexId },
                            onClick = {
                                scope.launch {
                                    runCatching { vm.startConversation(user) }
                                        .onSuccess { onOpenConversation(it.id) }
                                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not open the chat") }
                                }
                            },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
        )
    }
}

@Composable
private fun PersonRow(user: AuthUser, added: Boolean, onClick: () -> Unit) {
    val palette = LocalNexPalette.current
    GlassCard(
        corner = 22.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = user.displayName, avatar = user.avatar, size = 50.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "@${user.username} · ${user.nexId}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = palette.textMuted,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onClick) {
                Text(
                    if (added) "Message" else "Add",
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "new-group") { NewGroupViewModel(it.chat, it.contacts) }
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.created.collect { conversation -> onCreated(conversation.id) }
    }

    Column(Modifier.fillMaxSize()) {
        NexTopBar(
            title = "New group",
            subtitle = if (selected.isEmpty()) "Choose members" else "${selected.size} selected",
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 18.dp)) {
            GlassTextField(
                value = title,
                onValueChange = vm::setTitle,
                placeholder = "Group name",
                label = "Name",
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (contacts.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.PersonSearch,
                title = "No contacts to add",
                body = "Add a contact first, then create a group.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(contacts, key = { it.nexId }) { contact ->
                    val isSelected = selected.contains(contact.nexId)
                    GlassCard(
                        corner = 20.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(11.dp),
                        onClick = { vm.toggle(contact.nexId) },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name = contact.displayName, avatar = contact.avatar, size = 46.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    contact.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "@${contact.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textMuted,
                                )
                            }
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) palette.accent else palette.glassBorder),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        PrimaryButton(
            text = if (selected.isEmpty()) "Create group" else "Create group · ${selected.size}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            loading = creating,
            enabled = selected.isNotEmpty() && !creating,
            onClick = vm::create,
        )
    }
}
