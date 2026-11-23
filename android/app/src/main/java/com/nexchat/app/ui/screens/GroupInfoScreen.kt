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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
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
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.GroupMemberEntity
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.GlassButton
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.GlassIconButton
import com.nexchat.app.ui.components.GlassTextField
import com.nexchat.app.ui.components.NexTopBar
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.components.SectionTitle
import com.nexchat.app.ui.components.StatusPill
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import com.nexchat.app.ui.viewmodel.GroupInfoViewModel

@Composable
fun GroupInfoScreen(
    groupId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "group-$groupId") {
        GroupInfoViewModel(it.chat, it.contacts, it.auth, groupId)
    }
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<GroupMemberEntity?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val isAdmin = group?.myRole == "admin"
    val available = contacts.filterNot { contact ->
        members.any { it.nexId == contact.nexId }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            NexTopBar(
                title = "Group info",
                subtitle = "Encrypted for every member device",
                onBack = onBack,
                trailing = {
                    if (isAdmin) {
                        GlassIconButton(
                            Icons.Outlined.Edit,
                            contentDescription = "Rename group",
                            size = 38.dp,
                            onClick = { showRename = true },
                        )
                    }
                },
            )
            Column(Modifier.padding(horizontal = 18.dp)) {
                GlassCard(
                    strong = true,
                    corner = 28.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(
                            name = group?.title ?: "Group",
                            avatar = group?.avatar,
                            size = 92.dp,
                            isGroup = true,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            group?.title ?: "Group chat",
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${members.size} member${if (members.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            StatusPill("End-to-end encrypted", palette.success, icon = Icons.Outlined.Lock)
                            if (isAdmin) StatusPill("You are admin", palette.accent)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                SectionTitle("Members")
                GlassCard(
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    if (members.isEmpty()) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = palette.accent,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Loading the encrypted member list…",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                            )
                        }
                    } else {
                        members.forEachIndexed { index, member ->
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 58.dp)
                                        .height(1.dp)
                                        .background(palette.divider),
                                )
                            }
                            MemberRow(
                                member = member,
                                isSelf = member.nexId == vm.myNexId,
                                canRemove = isAdmin && member.nexId != vm.myNexId,
                                onRemove = { removeTarget = member },
                            )
                        }
                    }
                    if (isAdmin) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 58.dp)
                                .height(1.dp)
                                .background(palette.divider),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = available.isNotEmpty()) { showAdd = true }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                tint = if (available.isEmpty()) palette.textMuted else palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (available.isEmpty()) {
                                    "No contacts left to add"
                                } else {
                                    "Add members"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (available.isEmpty()) palette.textMuted else palette.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                GlassCard(
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Group messages are sealed separately for each member device using pairwise Signal sessions. The server relays ciphertext and group metadata only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                GlassButton(
                    text = "Leave group",
                    icon = Icons.Outlined.ExitToApp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmLeave = true },
                )
                Spacer(Modifier.height(26.dp))
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
        )
    }

    if (showRename) {
        var title by remember(group?.title) { mutableStateOf(group?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = palette.glassStrong,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Rename group", style = MaterialTheme.typography.titleLarge) },
            text = {
                GlassTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    placeholder = "Group name",
                    label = "Name",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = title.trim().isNotEmpty(),
                    onClick = {
                        vm.rename(title) { showRename = false }
                    },
                ) {
                    Text("Save", color = palette.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }

    if (showAdd) {
        var chosen by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = palette.glassStrong,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Add members", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    available.forEach { contact ->
                        val selected = contact.nexId in chosen
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    chosen = if (selected) chosen - contact.nexId else chosen + contact.nexId
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = contact.displayName,
                                avatar = contact.avatar,
                                size = 38.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    contact.displayName,
                                    style = MaterialTheme.typography.titleMedium.atSize(14.sp),
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "@${contact.username}",
                                    style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                                    color = palette.textMuted,
                                )
                            }
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) palette.accent else palette.glassBorder),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = palette.onAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        val selectedContacts = available.filter { it.nexId in chosen }
                        vm.addMembers(selectedContacts)
                        showAdd = false
                    },
                ) {
                    Text(
                        if (chosen.isEmpty()) "Add" else "Add ${chosen.size}",
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }

    val target = removeTarget
    if (target != null) {
        ConfirmDialog(
            title = "Remove ${target.displayName}?",
            message = "They will stop receiving new encrypted group messages. Their local history will not be deleted.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                removeTarget = null
                vm.removeMember(target.nexId)
            },
            onDismiss = { removeTarget = null },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave this group?",
            message = "The group and its local message history will be removed from this device.",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                vm.leave(onLeft)
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun MemberRow(
    member: GroupMemberEntity,
    isSelf: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = member.displayName, avatar = member.avatar, size = 38.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName + if (isSelf) " (you)" else "",
                    style = MaterialTheme.typography.titleMedium.atSize(14.sp),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.role == "admin") {
                    Spacer(Modifier.width(6.dp))
                    StatusPill("Admin", palette.accent)
                }
            }
            Text(
                "@${member.username}",
                style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                color = palette.textMuted,
            )
        }
        if (canRemove) {
            GlassIconButton(
                Icons.Outlined.PersonRemove,
                contentDescription = "Remove member",
                size = 34.dp,
                tint = palette.danger,
                onClick = onRemove,
            )
        }
    }
}
