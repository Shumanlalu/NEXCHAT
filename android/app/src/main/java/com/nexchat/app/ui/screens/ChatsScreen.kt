package com.nexchat.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.net.ConnectionState
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.EmptyState
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.GlassIconButton
import com.nexchat.app.ui.components.GlassSearchField
import com.nexchat.app.ui.components.ScreenTitle
import com.nexchat.app.ui.components.StatusPill
import com.nexchat.app.ui.formatListTime
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.ChatsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    onOpenConversation: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onOpenContacts: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel { ChatsViewModel(it.chat, it.auth, it.websocket) }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val archived by vm.archived.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<ConversationEntity?>(null) }
    var confirmClear by remember { mutableStateOf<ConversationEntity?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) {
        vm.errors.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                eyebrow = "Your space",
                title = "Chats",
                subtitle = profile?.displayName?.let { "Signed in as $it" },
                trailing = {
                    GlassIconButton(
                        Icons.Outlined.Archive,
                        contentDescription = "Archived chats",
                        onClick = onOpenArchived,
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(
                        Icons.Outlined.Add,
                        contentDescription = "New chat",
                        onClick = onOpenContacts,
                        tint = palette.accent,
                    )
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ConnectionPill(connection)
                if (archived.isNotEmpty()) {
                    TextButton(onClick = onOpenArchived) {
                        Text(
                            "Archived · ${archived.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            GlassSearchField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (conversations.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = if (query.isBlank()) "Your circle starts here" else "No chats found",
                    body = if (query.isBlank()) {
                        "Find someone by their NEX ID or username and start an end-to-end encrypted conversation."
                    } else {
                        "Try a different name or clear the search."
                    },
                    actionLabel = if (query.isBlank()) "Find people" else null,
                    onAction = if (query.isBlank()) onOpenContacts else null,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 100.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { onOpenConversation(conversation.id) },
                            onLongClick = { selected = conversation },
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

    val sheetTarget = selected
    if (sheetTarget != null) {
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            containerColor = palette.glassStrong,
            contentColor = palette.textPrimary,
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .size(width = 42.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.textMuted.copy(alpha = 0.4f)),
                )
            },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(
                    sheetTarget.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(12.dp))
                SheetAction(
                    icon = Icons.Outlined.PushPin,
                    label = if (sheetTarget.pinned) "Unpin chat" else "Pin chat",
                    onClick = {
                        vm.pin(sheetTarget, !sheetTarget.pinned)
                        selected = null
                    },
                )
                SheetAction(
                    icon = if (sheetTarget.muted) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    label = if (sheetTarget.muted) "Unmute notifications" else "Mute notifications",
                    onClick = {
                        vm.mute(sheetTarget, !sheetTarget.muted)
                        selected = null
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.MarkChatRead,
                    label = "Mark as read",
                    onClick = {
                        vm.markRead(sheetTarget)
                        selected = null
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.Archive,
                    label = "Archive chat",
                    onClick = {
                        vm.archive(sheetTarget, true)
                        selected = null
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.DeleteSweep,
                    label = "Clear messages",
                    destructive = true,
                    onClick = {
                        confirmClear = sheetTarget
                        selected = null
                    },
                )
            }
        }
    }

    val clearTarget = confirmClear
    if (clearTarget != null) {
        ConfirmDialog(
            title = "Clear this chat?",
            message = "Messages will be removed from this device. This cannot be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                vm.clear(clearTarget)
                confirmClear = null
            },
            onDismiss = { confirmClear = null },
        )
    }

}

@Composable
private fun ConnectionPill(connection: ConnectionState) {
    val palette = LocalNexPalette.current
    when (connection) {
        ConnectionState.CONNECTED -> StatusPill("Encrypted connection live", palette.success)
        ConnectionState.CONNECTING -> StatusPill("Connecting…", palette.accent)
        ConnectionState.RECONNECTING -> StatusPill("Reconnecting…", palette.accent)
        ConnectionState.STOPPED -> StatusPill("Offline", palette.textMuted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    GlassCard(
        corner = 22.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                name = conversation.title,
                avatar = conversation.avatar,
                size = 54.dp,
                isGroup = conversation.type == "GROUP",
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            tint = palette.textMuted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        conversation.draft.isNotBlank() -> "Draft: ${conversation.draft}"
                        conversation.lastMessagePreview.isNullOrBlank() -> "No messages yet"
                        else -> conversation.lastMessagePreview.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conversation.unreadCount > 0) palette.textPrimary else palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatListTime(conversation.lastMessageAt),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = if (conversation.unreadCount > 0) palette.accent else palette.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.muted) {
                        Icon(
                            Icons.Outlined.VolumeOff,
                            contentDescription = "Muted",
                            tint = palette.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    if (conversation.unreadCount > 0) {
                        Box(
                            Modifier
                                .defaultMinSize(20.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                conversation.unreadCount.coerceAtMost(99).toString(),
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) palette.danger else palette.textSecondary,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) palette.danger else palette.textPrimary,
        )
    }
}
