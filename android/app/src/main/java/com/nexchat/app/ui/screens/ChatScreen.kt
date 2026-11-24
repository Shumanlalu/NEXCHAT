package com.nexchat.app.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Videocam
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexchat.app.NexChatApp
import com.nexchat.app.core.db.AttachmentEntity
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.MessageEntity
import com.nexchat.app.core.db.MessageKind
import com.nexchat.app.core.db.MessageStatus
import com.nexchat.app.data.AttachmentDraft
import com.nexchat.app.data.MAX_ATTACHMENT_BYTES
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.GlassIconButton
import com.nexchat.app.ui.formatClock
import com.nexchat.app.ui.formatDayLabel
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.reactionSummary
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import com.nexchat.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
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
    val remote by container.remoteConfig.state.collectAsStateWithLifecycle()
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

    // The list uses reverseLayout, so index 0 is the newest message.
    val listState = rememberLazyListState()
    val nearLatest by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }
    var lastSeenLatestId by remember(conversationId) { mutableStateOf<String?>(null) }
    var lastSeenCount by remember(conversationId) { mutableStateOf(0) }
    var newBelowCount by remember(conversationId) { mutableStateOf(0) }
    val latestMessageId = if (paging.itemCount > 0) paging[0]?.id else null
    val latestIsMine = if (paging.itemCount > 0) paging[0]?.outgoing == true else false

    LaunchedEffect(latestMessageId) {
        val latestId = latestMessageId ?: return@LaunchedEffect
        val previousId = lastSeenLatestId
        val added = (paging.itemCount - lastSeenCount).coerceAtLeast(0)
        lastSeenLatestId = latestId
        lastSeenCount = paging.itemCount
        if (previousId == null || previousId == latestId) return@LaunchedEffect
        if (nearLatest || latestIsMine) {
            newBelowCount = 0
            listState.animateScrollToItem(0)
        } else {
            newBelowCount += if (added > 0) added else 1
        }
    }
    LaunchedEffect(nearLatest) {
        if (nearLatest) newBelowCount = 0
    }

    var showMenu by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }

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

    fun beginCall(video: Boolean) {
        if (!remote.callsEnabled) {
            scope.launch {
                snackbar.showSnackbar("Calling is temporarily unavailable.")
            }
            return
        }
        if (video && !container.remoteConfig.videoCallsEnabled()) {
            scope.launch {
                snackbar.showSnackbar("Video calls are temporarily unavailable.")
            }
            return
        }
        val target = conv
        val peer = target?.peerNexId
        if (target?.type != "DM" || peer.isNullOrBlank()) {
            scope.launch {
                snackbar.showSnackbar("Calls are available only in one-to-one chats.")
            }
            return
        }
        if (blocked) {
            scope.launch { snackbar.showSnackbar("Unblock this contact to call them.") }
            return
        }
        runCatching {
            container.call.startOutgoing(
                conversationId = target.id,
                peerNexId = peer,
                peerName = target.title,
                peerAvatar = target.avatar,
                video = video,
            )
        }.onFailure { error ->
            scope.launch {
                snackbar.showSnackbar(error.message ?: "The call could not be started")
            }
        }
    }

    val callPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val video = pendingCallVideo ?: false
        pendingCallVideo = null
        val microphone = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        val camera = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        when {
            !microphone -> scope.launch {
                snackbar.showSnackbar("Microphone permission is required for calls.")
            }
            video && !camera -> scope.launch {
                snackbar.showSnackbar("Camera permission is required for video calls.")
            }
            else -> beginCall(video)
        }
    }

    fun requestCall(video: Boolean) {
        if (!remote.callsEnabled) {
            scope.launch {
                snackbar.showSnackbar("Calling is temporarily unavailable.")
            }
            return
        }
        if (video && !container.remoteConfig.videoCallsEnabled()) {
            scope.launch {
                snackbar.showSnackbar("Video calls are temporarily unavailable.")
            }
            return
        }
        if (conv?.type != "DM" || blocked) {
            scope.launch {
                snackbar.showSnackbar("Calls are available only in one-to-one chats.")
            }
            return
        }
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (video && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (missing.isEmpty()) {
            beginCall(video)
        } else {
            pendingCallVideo = video
            callPermissions.launch(missing.toTypedArray())
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readAttachmentDraft(context, uri) }
                .onSuccess { draft -> vm.sendAttachment(draft) }
                .onFailure { error ->
                    snackbar.showSnackbar(
                        error.message ?: "That file could not be read. Try a different file.",
                    )
                }
        }
    }

    suspend fun openAttachment(attachment: AttachmentEntity, bytes: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
            File(dir, safeAttachmentName(attachment.fileName)).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Open attachment")) }
            .onFailure { snackbar.showSnackbar("No app can open this file type") }
    }

    fun saveAttachment(attachment: AttachmentEntity, bytes: ByteArray) {
        scope.launch {
            runCatching {
                val name = safeAttachmentName(attachment.fileName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, attachment.mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create the Downloads entry")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Could not write the downloaded file")
                    "Saved to Downloads"
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("Downloads folder is unavailable")
                    File(dir, name).writeBytes(bytes)
                    "Saved to app Downloads folder"
                }
            }.onSuccess { snackbar.showSnackbar(it) }
                .onFailure { snackbar.showSnackbar(it.message ?: "The file could not be saved") }
        }
    }

    suspend fun openAttachmentMessage(message: MessageEntity) {
        val loaded = container.chat.loadAttachment(message.id)
        if (loaded == null) {
            snackbar.showSnackbar("This attachment is still transferring or could not be verified")
            return
        }
        openAttachment(loaded.first, loaded.second)
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
                onCall = { requestCall(video = false) },
                onVideoCall = { requestCall(video = true) },
                callEnabled = conv?.type == "DM" && !blocked && remote.callsEnabled,
                videoCallEnabled = conv?.type == "DM" && !blocked &&
                    remote.callsEnabled && remote.videoCallsEnabled,
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
                        state = listState,
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
                                        onOpenAttachment = { scope.launch { openAttachmentMessage(it) } },
                                    )
                                }
                            }
                        }
                    }
                }
                if (paging.itemCount > 0 && newBelowCount > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.accent)
                            .clickable {
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                    newBelowCount = 0
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.KeyboardArrowUp,
                                contentDescription = null,
                                tint = palette.onAccent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$newBelowCount new message${if (newBelowCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.onAccent,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
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
                    onAttach = { attachmentPicker.launch("*/*") },
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
                if (selected.kind == MessageKind.ATTACHMENT) {
                    SheetAction(
                        icon = Icons.Outlined.Download,
                        label = "Save to Downloads",
                        onClick = {
                            scope.launch {
                                val loaded = container.chat.loadAttachment(selected.id)
                                if (loaded == null) {
                                    snackbar.showSnackbar("This attachment is not ready yet")
                                } else {
                                    saveAttachment(loaded.first, loaded.second)
                                }
                            }
                            selectedMessage = null
                        },
                    )
                }
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
    onVideoCall: () -> Unit,
    videoCallEnabled: Boolean,
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
                    style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GlassIconButton(
            Icons.Outlined.Videocam,
            contentDescription = "Video call",
            onClick = onVideoCall,
            size = 40.dp,
            enabled = videoCallEnabled,
        )
        Spacer(Modifier.width(6.dp))
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
    onOpenAttachment: (MessageEntity) -> Unit,
) {
    val palette = LocalNexPalette.current
    val outgoing = message.outgoing
    val bubbleShape = if (outgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 6.dp, bottomEnd = 16.dp)
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
                .background(if (outgoing) palette.bubbleOutStart else palette.bubbleIn)
                .border(
                    1.dp,
                    if (outgoing) Color.Transparent else palette.divider,
                    bubbleShape,
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 13.dp, vertical = 8.dp),
        ) {
            if (isGroup && !outgoing) {
                Text(
                    message.senderDisplayName,
                    style = MaterialTheme.typography.labelSmall.atSize(11.sp),
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
                        style = MaterialTheme.typography.bodyLarge.atSize(15.sp),
                        color = textColor,
                    )
                }
            } else if (message.kind == MessageKind.ATTACHMENT) {
                val details = message.body.removePrefix("📎 ").split(" · ", limit = 2)
                val name = details.firstOrNull().orEmpty().ifBlank { "Attachment" }
                val status = details.getOrNull(1).orEmpty()
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = if (outgoing) 0.1f else 0.5f))
                        .clickable { onOpenAttachment(message) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = if (outgoing) Color.White else palette.accent,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium.atSize(14.sp),
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (status.isNotBlank()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall.atSize(10.sp),
                                color = secondary,
                            )
                        }
                    }
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = "Open attachment",
                        tint = secondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyLarge.atSize(15.sp),
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
                                    style = MaterialTheme.typography.labelSmall.atSize(11.sp),
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
                        style = MaterialTheme.typography.labelSmall.atSize(10.sp),
                        color = secondary,
                    )
                }
                Text(
                    formatClock(message.sentAt),
                    style = MaterialTheme.typography.labelSmall.atSize(10.sp),
                    color = secondary,
                )
                if (outgoing) {
                    Spacer(Modifier.width(4.dp))
                    MessageStatusIcon(message.status, outgoing)
                }
            }
            if (
                (message.status == MessageStatus.FAILED ||
                    message.status == MessageStatus.PENDING) &&
                message.error != null
            ) {
                Spacer(Modifier.height(2.dp))
                Text(
                    message.error,
                    style = MaterialTheme.typography.labelSmall.atSize(10.sp),
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
            style = MaterialTheme.typography.labelSmall.atSize(11.sp),
            color = palette.textMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.glass)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private fun safeAttachmentName(name: String): String =
    name.replace(Regex("[/\\\\]"), "_").trim().take(120).ifBlank { "attachment" }

private suspend fun readAttachmentDraft(context: Context, uri: Uri): AttachmentDraft =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }?.let(::safeAttachmentName) ?: "attachment"
        val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } else {
                -1L
            }
        } ?: -1L
        if (declaredSize > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("Attachments must be ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB or smaller")
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("That file could not be opened")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("Attachments must be ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB or smaller")
                }
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) throw IllegalArgumentException("The selected file is empty")
        AttachmentDraft(fileName = name, mimeType = mime, bytes = bytes)
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
                        style = MaterialTheme.typography.labelSmall.atSize(11.sp),
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
                .clip(RoundedCornerShape(16.dp))
                .background(palette.glassStrong)
                .border(1.dp, palette.divider, RoundedCornerShape(16.dp))
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
                    tint = if (value.isNotBlank()) palette.onAccent else palette.textMuted,
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
