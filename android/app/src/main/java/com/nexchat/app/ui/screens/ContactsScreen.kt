package com.nexchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.ui.components.ContactListRow
import com.nexchat.app.ui.components.EmptyState
import com.nexchat.app.ui.components.GlassIconButton
import com.nexchat.app.ui.components.GlassSearchField
import com.nexchat.app.ui.components.ScreenTitle
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.ContactsViewModel
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(
    onOpenConversation: (String) -> Unit,
    onAddContact: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenBlocked: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel { ContactsViewModel(it.contacts, it.chat, it.auth) }
    val contacts by vm.filtered.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.errors.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                eyebrow = "People",
                title = "Contacts",
                subtitle = "${all.size} encrypted contact${if (all.size == 1) "" else "s"}",
                trailing = {
                    GlassIconButton(
                        Icons.Outlined.GroupAdd,
                        contentDescription = "New group",
                        onClick = onNewGroup,
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(
                        Icons.Outlined.PersonAdd,
                        contentDescription = "Add contact",
                        onClick = onAddContact,
                        tint = palette.accent,
                    )
                },
            )
            GlassSearchField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = "Search your contacts…",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (blocked.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenBlocked,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Block,
                        contentDescription = null,
                        tint = palette.danger,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Blocked contacts · ${blocked.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = palette.danger,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (all.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.People,
                    title = "No contacts yet",
                    body = "Search for someone by username or NEX ID to start a private conversation.",
                    actionLabel = "Find people",
                    onAction = onAddContact,
                )
            } else if (contacts.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "No matches",
                    body = "No contact matches “$query”.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 100.dp,
                    ),
                ) {
                    items(contacts, key = { it.nexId }) { contact ->
                        ContactRow(
                            contact = contact,
                            onClick = {
                                scope.launch {
                                    runCatching { vm.startConversation(contact) }
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
                .padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
        )
    }
}

@Composable
fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    val palette = LocalNexPalette.current
    ContactListRow(
        name = contact.displayName,
        subtitle = "@${contact.username} · ${contact.nexId}",
        avatar = contact.avatar,
        verified = contact.verifiedAt != null,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(19.dp),
            )
        },
    )
}

@Composable
fun BlockedScreen(onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "blocked") { ContactsViewModel(it.contacts, it.chat, it.auth) }
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.errors.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                eyebrow = "Privacy",
                title = "Blocked",
                subtitle = "Blocked contacts cannot start new conversations with you.",
                trailing = {
                    TextButton(onClick = onBack) {
                        Text("Done", color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                },
            )
            if (blocked.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Block,
                    title = "Nobody is blocked",
                    body = "Blocked contacts will appear here. Blocking does not delete your message history.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    items(blocked, key = { it.nexId }) { contact ->
                        ContactListRow(
                            name = contact.displayName,
                            subtitle = "@${contact.username} · ${contact.nexId}",
                            avatar = contact.avatar,
                            avatarSize = 48.dp,
                            trailing = {
                                TextButton(onClick = { vm.setBlocked(contact.nexId, false) }) {
                                    Text("Unblock", color = palette.accent, fontWeight = FontWeight.Bold)
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
