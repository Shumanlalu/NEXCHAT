package com.nexchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.EmptyState
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.NexTopBar
import com.nexchat.app.ui.formatListTime
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.ChatsViewModel

@Composable
fun ArchivedScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "archived") { ChatsViewModel(it.chat, it.auth, it.websocket) }
    val archived by vm.archived.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        NexTopBar(
            title = "Archived",
            subtitle = "Hidden chats stay encrypted on this device",
            onBack = onBack,
        )
        if (archived.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Archive,
                title = "Nothing archived",
                body = "Archive a chat from its long-press menu to move it here.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(archived, key = { it.id }) { conversation ->
                    GlassCard(
                        corner = 22.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        onClick = { onOpenConversation(conversation.id) },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(
                                name = conversation.title,
                                avatar = conversation.avatar,
                                size = 48.dp,
                                isGroup = conversation.type == "GROUP",
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    formatListTime(conversation.lastMessageAt) +
                                        " · " + (conversation.lastMessagePreview ?: "No messages"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textMuted,
                                    maxLines = 1,
                                )
                            }
                            TextButton(onClick = {
                                vm.archive(conversation, false)
                            }) {
                                androidx.compose.material3.Icon(
                                    Icons.Outlined.Unarchive,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.width(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
