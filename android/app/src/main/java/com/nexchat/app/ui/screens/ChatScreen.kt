package com.nexchat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexchat.app.NexChatApp
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.MessageEntity
import com.nexchat.app.core.db.MessageKind
import com.nexchat.app.core.db.MessageStatus
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.GlassIconButton
import com.nexchat.app.ui.formatClock
import com.nexchat.app.ui.formatDayLabel
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.reactionSummary
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    val palette = LocalNexPalette.current
    val context = LocalContext.current
    val container = remember(context) { NexChatApp.from(context).container }
    val vm = nexViewModel(key = conversationId) { ChatViewModel(it.chat, it.contacts, conversationId) }
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val replyTo by vm.replyTo.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val contact by vm.contact.collectAsStateWithLifecycle()
    val members by vm.groupMembers.collectAsStateWithLifecycle()
    val paging = vm.messages.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingCall by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.errors.collect { snackbar.showSnackbar(it) }
    }

    val conv = conversation
    val blocked = contact?.blocked == true
    val subtitle = when {
        conv?.type == "GROUP" -> "${members.size} members · end-to-end encrypted"
        online -> "online · end-to-end encrypted"
        else -> "end-to-end encrypted"
    }

    fun beginVoiceCall() {
        val target = conv
        val peer = target?.peerNexId
        if (target?.type != "DM" || peer.isNullOrBlank()) {
            scope.launch {
                snackbar.showSnackbar("Voice calls are available only in one-to-one chats.")
            }
            return
        }
        if (blocked) {
            scope.launch { snackbar.showSnackbar("Unblock this contact to call them.") }
            return
        }
        runCatching {
            container.call.startOutgoing(target.id, peer, target.title, target.avatar)
        }.onFailure { error ->
            scope.launch {
                snackbar.showSnackbar(error.message ?: "The call could not be started")
            }
        }
    }

    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val startAfterGrant = pendingCall
        pendingCall = false
        if (granted && startAfterGrant) {
            beginVoiceCall()
        } else if (!granted) {
            scope.launch {
                snackbar.showSnackbar("Microphone permission is required for voice calls.")
            }
        }
    }

    fun requestVoiceCall() {
        if (conv?.type != "DM" || blocked) {
            scope.launch {
                snackbar.showSnackbar("Voice calls are available only in one-to-one chats.")
            }
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            beginVoiceCall()
        } else {
            pendingCall = true
            callPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                title = conv?.title ?: "Chat",
                subtitle = subtitle,
                avatar = conv?.avatar,
                isGroup = conv?.type == "GROUP",
                online = online,
                onBack = onBack,
                onCall = { requestVoiceCall() },
                callEnabled = conv?.type == "DM" && !blocked,
                onInfo = {
                    val target = conv
                    if (target?.type == "GROUP") {
                        target.groupId?.let(onOpenGroup)
                    } else {
                        target?.peerNexId?.let(onOpenContact)
                    }
                },
                onMore = { showMenu = true },
            )

            Box(Modifier.weight(1f)) {
                if (paging.itemCount == 0) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .background(palette.glass)
                                .border(1.dp, palette.glassBorder, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "No messages yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.textPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Messages in this chat are sealed on your device before they are sent.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 14.dp,
                            bottom = 14.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            count = paging.itemCount,
                            key = paging.itemKey { it.id },
                        ) { index ->
                            val message = paging[index]
                            if (message != null && !message.deletedLocally) {
                                val older = if (index + 1 < paging.itemCount) paging[index + 1] else null
                                val showDay = older == null ||
                                    dayOf(older.sentAt) != dayOf(message.sentAt)
                                Column(Modifier.fillMaxWidth()) {
                                    if (showDay) {
                                        DayDivider(message.sentAt)
                                    }
                                    MessageBubble(
                                        message = message,
                                        isGroup = conv?.type == "GROUP",
                                        onLongPress = { selectedMessage = message },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (typing) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "typing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                    )
                }
            }

            if (blocked) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.danger.copy(alpha = 0.1f))
                        .border(1.dp, palette.danger.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        "You blocked this contact",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { vm.blockPeer(false) } }) {
                        Text("Unblock", color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Composer(
                    value = input,
                    replyTo = replyTo,
                    onValueChange = vm::setInput,
                    onSend = vm::send,
                    onClearReply = { vm.replyTo(null) },
                    onAttach = {
                        scope.launch {
                            snackbar.showSnackbar("Encrypted attachments are not available in this build.")
                        }
                    },
                )
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
        )
    }

    if (showMenu) {
        val target = conversation
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            containerColor = palette.glassStrong,
            contentColor = palette.textPrimary,
            dragHandle = { SheetHandle() },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(
                    target?.title ?: "Conversation",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                SheetAction(
                    icon = Icons.Outlined.Info,
                    label = if (target?.type == "GROUP") "Group info" else "Contact info & safety number",
                    onClick = {
                        showMenu = false
                        if (target?.type == "GROUP") target.groupId?.let(onOpenGroup)
                        else target?.peerNexId?.let(onOpenContact)
                    },
                )
                SheetAction(
                    icon = if (target?.muted == true) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    label = if (target?.muted == true) "Unmute notifications" else "Mute notifications",
                    onClick = {
                        val muted = target?.muted == true
                        showMenu = false
                        scope.launch { vm.setMuted(!muted) }
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.Archive,
                    label = "Archive chat",
                    onClick = {
                        showMenu = false
                        scope.launch {
                            vm.setArchived(true)
                            onBack()
                        }
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.Delete,
                    label = "Clear messages",
                    destructive = true,
                    onClick = {
                        showMenu = false
                        confirmClear = true
                    },
                )
                if (target?.type == "DM" && contact?.blocked != true) {
                    SheetAction(
                        icon = Icons.Outlined.Block,
                        label = "Block contact",
                        destructive = true,
                        onClick = {
                            showMenu = false
                            scope.launch { vm.blockPeer(true) }
                        },
                    )
                }
            }
        }
    }

    val selected = selectedMessage
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedMessage = null },
            containerColor = palette.glassStrong,
            contentColor = palette.textPrimary,
            dragHandle = { SheetHandle() },
        ) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(palette.glass)
                                .border(1.dp, palette.glassBorder, CircleShape)
                                .clickable {
                                    vm.react(selected.id, emoji)
                                    selectedMessage = null
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 21.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SheetAction(
                    icon = Icons.Outlined.Reply,
                    label = "Reply",
                    onClick = {
                        vm.replyTo(selected)
                        selectedMessage = null
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy text",
                    onClick = {
                        clipboard.setText(AnnotatedString(selected.body))
                        selectedMessage = null
                    },
                )
                if (selected.outgoing && selected.kind == "TEXT" && !selected.deletedForEveryone) {
                    SheetAction(
                        icon = Icons.Outlined.Edit,
                        label = "Edit message",
                        onClick = {
                            editTarget = selected
                            selectedMessage = null
                        },
                    )
                }
                SheetAction(
                    icon = Icons.Outlined.Delete,
                    label = "Delete for me",
                    onClick = {
                        vm.deleteLocally(selected.id)
                        selectedMessage = null
                    },
                )
                if (selected.outgoing && !selected.deletedForEveryone) {
                    SheetAction(
                        icon = Icons.Outlined.Delete,
                        label = "Delete for everyone",
                        destructive = true,
                        onClick = {
                            confirmDeleteAll = selected
                            selectedMessage = null
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear this chat?",
            message = "Messages will be removed from this device. This cannot be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                confirmClear = false
                scope.launch { vm.clearChat() }
            },
            onDismiss = { confirmClear = false },
        )
    }

    val deleteTarget = confirmDeleteAll
    if (deleteTarget != null) {
        ConfirmDialog(
            title = "Delete for everyone?",
            message = "The message will be removed for every member of this conversation.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDeleteAll = null
                vm.deleteForEveryone(deleteTarget.id)
            },
            onDismiss = { confirmDeleteAll = null },
        )
    }

    val editing = editTarget
    if (editing != null) {
        var draft by remember(editing.id) { mutableStateOf(editing.body) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            containerColor = palette.glassStrong,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Edit message", style = MaterialTheme.typography.titleLarge) },
            text = {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                    ),
                    cursorBrush = Brush.verticalGradient(listOf(palette.accent, palette.accent)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.glass)
                        .border(1.dp, palette.glassBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty() && text != editing.body) vm.edit(editing.id, text)
                    editTarget = null
                }) {
                    Text("Save", color = palette.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }

    LaunchedEffect(paging.itemCount) { vm.markRead() }
}

@Composable
private fun ChatHeader(
    title: String,
    subtitle: String,
    avatar: String?,
    isGroup: Boolean,
    online: Boolean,
    onBack: () -> Unit,
    onCall: () -> Unit,
    callEnabled: Boolean,
    onInfo: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clickable(onClick = onInfo)) {
            Avatar(name = title, avatar = avatar, size = 42.dp, isGroup = isGroup)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clickable(onClick = onInfo)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isGroup) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (online) palette.success else palette.textMuted.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GlassIconButton(
            Icons.Outlined.Call,
            contentDescription = "Call",
            onClick = onCall,
            size = 40.dp,
            enabled = callEnabled,
        )
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Outlined.MoreVert, contentDescription = "More", onClick = onMore, size = 40.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    isGroup: Boolean,
    onLongPress: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val outgoing = message.outgoing
    val bubbleShape = if (outgoing) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 7.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 7.dp, bottomEnd = 20.dp)
    }
    val textColor = if (outgoing) Color.White else palette.textPrimary
    val secondary = if (outgoing) Color.White.copy(alpha = 0.66f) else palette.textSecondary
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(min = 84.dp, max = 340.dp)
                .clip(bubbleShape)
                .background(
                    if (outgoing) {
                        Brush.linearGradient(listOf(palette.bubbleOutStart, palette.bubbleOutEnd))
                    } else {
                        Brush.linearGradient(listOf(palette.bubbleIn, palette.bubbleIn))
                    },
                )
                .border(
                    1.dp,
                    if (outgoing) Color.White.copy(alpha = 0.12f) else palette.glassBorder,
                    bubbleShape,
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (isGroup && !outgoing) {
                Text(
                    message.senderDisplayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
            }
            if (!message.replyToPreview.isNullOrBlank()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = if (outgoing) 0.08f else 0.45f))
                        .padding(8.dp),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (outgoing) Color.White.copy(alpha = 0.65f) else palette.accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message.replyToPreview.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (message.deletedForEveryone) {
                Text(
                    "This message was deleted",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = secondary,
                )
            } else if (message.kind == MessageKind.CALL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Call,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                        color = textColor,
                    )
                }
            } else {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = textColor,
                )
            }
            val reactions = reactionSummary(message.reactionsJson)
            if (reactions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    reactions.forEach { (emoji, count) ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = if (outgoing) 0.12f else 0.55f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(emoji, fontSize = 12.sp)
                            if (count > 1) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                                    color = secondary,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.editedAt != null && !message.deletedForEveryone) {
                    Text(
                        "edited · ",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                        color = secondary,
                    )
                }
                Text(
                    formatClock(message.sentAt),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                    color = secondary,
                )
                if (outgoing) {
                    Spacer(Modifier.width(4.dp))
                    MessageStatusIcon(message.status, outgoing)
                }
            }
            if (message.status == MessageStatus.FAILED && message.error != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    message.error,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                    color = if (outgoing) Color(0xFFFFC2CD) else palette.danger,
                    modifier = Modifier.align(if (outgoing) Alignment.End else Alignment.Start),
                )
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(status: String, outgoing: Boolean) {
    val palette = LocalNexPalette.current
    val tint = if (outgoing) Color.White.copy(alpha = 0.78f) else palette.textMuted
    val icon: ImageVector = when (status) {
        MessageStatus.PENDING -> Icons.Outlined.Schedule
        MessageStatus.SENT -> Icons.Outlined.Check
        MessageStatus.DELIVERED -> Icons.Outlined.DoneAll
        MessageStatus.READ -> Icons.Outlined.DoneAll
        MessageStatus.FAILED -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Check
    }
    Icon(
        icon,
        contentDescription = status,
        tint = if (status == MessageStatus.READ) Color(0xFF9FE8FF) else tint,
        modifier = Modifier.size(13.dp),
    )
}

@Composable
private fun DayDivider(epochMillis: Long) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            formatDayLabel(epochMillis),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = palette.textMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.glass)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Composer(
    value: String,
    replyTo: MessageEntity?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearReply: () -> Unit,
    onAttach: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (replyTo != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.glass)
                    .border(1.dp, palette.glassBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.accent),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Replying to ${replyTo.senderDisplayName}",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                        color = palette.accent,
                    )
                    Text(
                        replyTo.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Cancel reply",
                    tint = palette.textMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onClearReply),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(palette.glassStrong)
                .border(1.dp, palette.glassBorder, RoundedCornerShape(28.dp))
                .padding(start = 8.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "Attach",
                    tint = palette.textMuted,
                    modifier = Modifier.size(21.dp),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        "Message",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                    ),
                    maxLines = 5,
                    cursorBrush = Brush.verticalGradient(listOf(palette.accent, palette.accent)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (value.isNotBlank()) palette.accent else palette.glassBorder,
                    )
                    .clickable(enabled = value.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (value.isNotBlank()) Color.White else palette.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    val palette = LocalNexPalette.current
    Box(
        Modifier
            .padding(top = 10.dp)
            .size(width = 42.dp, height = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(palette.textMuted.copy(alpha = 0.4f)),
    )
}

@Composable
private fun SheetAction(
    icon: ImageVector,
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

private fun dayOf(epochMillis: Long): Int =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt()
