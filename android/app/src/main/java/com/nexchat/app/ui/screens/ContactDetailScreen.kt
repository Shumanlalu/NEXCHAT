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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VerifiedUser
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.data.SafetyNumber
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.GlassButton
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.GlassIconButton
import com.nexchat.app.ui.components.NexTopBar
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.components.SectionTitle
import com.nexchat.app.ui.components.StatusPill
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import com.nexchat.app.ui.viewmodel.ContactDetailViewModel
import kotlinx.coroutines.launch

@Composable
fun ContactDetailScreen(
    nexId: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val palette = LocalNexPalette.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val vm = nexViewModel(key = "contact-$nexId") { ContactDetailViewModel(it.contacts, it.chat, nexId) }
    val contact by vm.contact.collectAsStateWithLifecycle()
    val numbers by vm.numbers.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmBlock by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            NexTopBar(
                title = contact?.displayName ?: "Contact",
                subtitle = contact?.username?.let { "@$it" },
                onBack = onBack,
                trailing = {
                    if (contact?.verifiedAt != null) {
                        Icon(
                            Icons.Outlined.Verified,
                            contentDescription = "Verified",
                            tint = palette.success,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
            Column(Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(8.dp))
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
                            name = contact?.displayName ?: nexId,
                            avatar = contact?.avatar,
                            size = 94.dp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            contact?.displayName ?: "Contact",
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            contact?.username?.let { "@$it" } ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textMuted,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (contact?.verifiedAt != null) {
                            StatusPill("Safety number verified", palette.success, icon = Icons.Outlined.VerifiedUser)
                        } else {
                            StatusPill("Not verified yet", palette.textMuted)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.glass)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "NEX ID",
                                    style = MaterialTheme.typography.labelSmall.atSize(10.sp)
                                        .copy(letterSpacing = 0.4.sp),
                                    color = palette.textMuted,
                                    maxLines = 1,
                                )
                                Text(
                                    contact?.nexId ?: nexId,
                                    style = MaterialTheme.typography.titleMedium.atSize(16.sp),
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            GlassIconButton(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "Copy NEX ID",
                                size = 36.dp,
                                onClick = {
                                    clipboard.setText(AnnotatedString(contact?.nexId ?: nexId))
                                    scope.launch { snackbar.showSnackbar("NEX ID copied") }
                                },
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(
                            text = "Open encrypted chat",
                            icon = Icons.Outlined.ChatBubbleOutline,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.openConversation(onOpenConversation) },
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                SectionTitle("Verify safety number")
                GlassCard(
                    corner = 24.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(palette.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(
                            "Compare these digits with ${contact?.displayName ?: "your contact"} in person or over a trusted channel. If they match, mark this device as verified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (loading && numbers.isEmpty()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = palette.accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else if (numbers.isEmpty()) {
                        Text(
                            "Safety numbers are created after your first encrypted exchange with this contact.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textMuted,
                        )
                    } else {
                        numbers.forEachIndexed { index, number ->
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            SafetyNumberCard(
                                number = number,
                                onToggle = { vm.setVerified(!number.verified) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassButton(
                        text = "Refresh safety numbers",
                        icon = Icons.Outlined.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = vm::refreshNumbers,
                    )
                }
                Spacer(Modifier.height(22.dp))
                SectionTitle("Contact actions")
                GlassCard(
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    DetailAction(
                        icon = Icons.Outlined.Refresh,
                        title = "Reset security session",
                        onClick = { confirmReset = true },
                    )
                    DetailDivider()
                    DetailAction(
                        icon = Icons.Outlined.Block,
                        title = if (contact?.blocked == true) "Unblock contact" else "Block contact",
                        destructive = contact?.blocked != true,
                        onClick = {
                            if (contact?.blocked == true) {
                                vm.setBlocked(false)
                            } else {
                                confirmBlock = true
                            }
                        },
                    )
                    DetailDivider()
                    DetailAction(
                        icon = Icons.Outlined.Report,
                        title = "Report contact",
                        destructive = true,
                        onClick = { showReport = true },
                    )
                    DetailDivider()
                    DetailAction(
                        icon = Icons.Outlined.Delete,
                        title = "Remove contact",
                        destructive = true,
                        onClick = { confirmDelete = true },
                    )
                }
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

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset the security session?",
            message = "This deletes the local encryption session and queued ciphertext for this contact. Verify the new safety number before sending again. Use this only after the contact reinstalled NEXCHAT or their safety number changed.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = {
                confirmReset = false
                vm.resetSecuritySession()
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (confirmBlock) {
        ConfirmDialog(
            title = "Block this contact?",
            message = "They will not be able to start new conversations with you. Existing message history stays on this device.",
            confirmLabel = "Block",
            destructive = true,
            onConfirm = {
                confirmBlock = false
                vm.setBlocked(true)
            },
            onDismiss = { confirmBlock = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Remove this contact?",
            message = "The contact and their local safety-number verification will be removed from this device. Messages stay in your chats.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.deleteContact(onDeleted = onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (showReport) {
        val reasons = listOf("Spam", "Harassment", "Impersonation", "Other")
        AlertDialog(
            onDismissRequest = { showReport = false },
            containerColor = palette.glassStrong,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Report contact", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "Choose a reason. Reports are sent to the operator of your server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    reasons.forEach { reason ->
                        TextButton(
                            onClick = {
                                vm.report(reason)
                                showReport = false
                            },
                        ) {
                            Text(reason, color = palette.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReport = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SafetyNumberCard(number: SafetyNumber, onToggle: () -> Unit) {
    val palette = LocalNexPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.glass)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Device ${number.deviceId}",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (number.verified) {
                Icon(
                    Icons.Outlined.Verified,
                    contentDescription = null,
                    tint = palette.success,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Verified",
                    style = MaterialTheme.typography.labelSmall.atSize(11.sp),
                    color = palette.success,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            formatFingerprint(number.fingerprint),
            style = MaterialTheme.typography.bodyLarge.atSize(14.sp).copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.1.sp,
            ),
            color = palette.textPrimary,
            maxLines = 4,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onToggle) {
            Text(
                if (number.verified) "Mark as unverified" else "Mark as verified",
                color = if (number.verified) palette.textSecondary else palette.accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DetailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) palette.danger else palette.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(13.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) palette.danger else palette.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailDivider() {
    val palette = LocalNexPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 47.dp)
            .height(1.dp)
            .background(palette.divider),
    )
}

private fun formatFingerprint(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return raw
    return digits.chunked(5).joinToString(" ")
}
