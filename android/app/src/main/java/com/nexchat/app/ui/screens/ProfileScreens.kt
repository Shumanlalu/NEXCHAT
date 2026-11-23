package com.nexchat.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.EmptyState
import com.nexchat.app.ui.components.GlassButton
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.GlassTextField
import com.nexchat.app.ui.components.NexTopBar
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.components.SectionTitle
import com.nexchat.app.ui.components.StatusPill
import com.nexchat.app.ui.encodeAvatar
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import com.nexchat.app.ui.viewmodel.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val palette = LocalNexPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val vm = nexViewModel(key = "profile") { SettingsViewModel(it.auth, it.settings, it.api) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var displayName by remember(profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val encoded = encodeAvatar(context, uri)
        if (encoded == null) {
            scope.launch { snackbar.showSnackbar("That image could not be read. Try a smaller photo.") }
        } else {
            vm.updateAvatar(encoded)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            NexTopBar(
                title = "Profile",
                subtitle = "Your public identity",
                onBack = onBack,
            )
            Column(
                Modifier.padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))
                Avatar(
                    name = profile?.displayName ?: "You",
                    avatar = profile?.avatar,
                    size = 110.dp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        text = "Change photo",
                        icon = Icons.Outlined.PhotoCamera,
                        onClick = { picker.launch("image/*") },
                    )
                    if (!profile?.avatar.isNullOrBlank()) {
                        GlassButton(
                            text = "Remove",
                            onClick = { vm.updateAvatar(null) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    corner = 24.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    GlassTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(40) },
                        placeholder = "Display name",
                        label = "Display name",
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = "Save name",
                        modifier = Modifier.fillMaxWidth(),
                        loading = busy,
                        enabled = displayName.trim().isNotEmpty() &&
                            displayName.trim() != profile?.displayName && !busy,
                        onClick = { vm.updateDisplayName(displayName) },
                    )
                }
                Spacer(Modifier.height(18.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    corner = 24.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    Text(
                        "NEX ID",
                        style = MaterialTheme.typography.labelSmall.atSize(11.sp).copy(letterSpacing = 0.4.sp),
                        color = palette.textMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.nexId ?: "—",
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy NEX ID",
                            tint = palette.accent,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(palette.accentSoft)
                                .padding(3.dp)
                                .let { it },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            profile?.nexId?.let {
                                clipboard.setText(AnnotatedString(it))
                                scope.launch { snackbar.showSnackbar("NEX ID copied") }
                            }
                        },
                    ) {
                        Text("Copy NEX ID", color = palette.accent)
                    }
                    Spacer(Modifier.height(4.dp))
                    ProfileFact("Username", profile?.username?.let { "@$it" } ?: "—")
                    Spacer(Modifier.height(7.dp))
                    ProfileFact("Device ID", profile?.deviceId ?: "—")
                    Spacer(Modifier.height(7.dp))
                    ProfileFact(
                        "Signal device",
                        profile?.signalDeviceId?.toString() ?: "—",
                    )
                }
                Spacer(Modifier.height(22.dp))
                SectionTitle("Danger zone", Modifier.fillMaxWidth())
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(palette.danger.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = null,
                                tint = palette.danger,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Delete account",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.danger,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Removes your account and registered device keys. This cannot be undone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassButton(
                        text = "Delete my account",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { confirmDelete = true },
                    )
                }
                Spacer(Modifier.height(30.dp))
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
        )
    }

    if (confirmDelete) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = palette.glassStrong,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Delete your account?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "This permanently removes your NEXCHAT account, profile, devices, and queued ciphertext. Messages already delivered to other devices cannot be recalled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    GlassTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Confirm your password",
                        label = "Password",
                        password = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty() && !busy,
                    onClick = {
                        vm.deleteAccount(password)
                        confirmDelete = false
                    },
                ) {
                    Text("Delete", color = palette.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun ProfileFact(label: String, value: String) {
    val palette = LocalNexPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
            modifier = Modifier.weight(0.4f),
            maxLines = 2,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.weight(0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "devices") { SettingsViewModel(it.auth, it.settings, it.api) }
    val devices by vm.devices.collectAsStateWithLifecycle()
    val loading by vm.loadingDevices.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var revokeTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.loadDevices()
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NexTopBar(
                title = "Devices",
                subtitle = "Every device has its own encryption keys",
                onBack = onBack,
                trailing = {
                    TextButton(onClick = vm::loadDevices) {
                        Text("Refresh", color = palette.accent)
                    }
                },
            )
            if (loading && devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp)
                }
            } else if (devices.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Devices,
                    title = "No devices listed",
                    body = "Pull a refresh from the server. If this keeps happening, check your connection.",
                    actionLabel = "Refresh",
                    onAction = vm::loadDevices,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
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
                                "Each device receives messages encrypted to its own keys. Revoking a device removes its access, but does not wipe its local database.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    devices.forEach { device ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            corner = 22.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(palette.accentSoft),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.PhoneAndroid,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${device.platform} · added ${formatDeviceDate(device.createdAt)}",
                                        style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                                        color = palette.textMuted,
                                    )
                                }
                                if (device.current) {
                                    StatusPill("This device", palette.success)
                                } else {
                                    TextButton(onClick = { revokeTarget = device.id }) {
                                        Text("Revoke", color = palette.danger)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        "Signing in on another device adds it here. There is no QR device-linking flow in this build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    )
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

    val target = revokeTarget
    if (target != null) {
        ConfirmDialog(
            title = "Revoke this device?",
            message = "The device will no longer receive new message envelopes. Its local encrypted data remains on that device until the app is signed out or uninstalled.",
            confirmLabel = "Revoke",
            destructive = true,
            onConfirm = {
                revokeTarget = null
                vm.revokeDevice(target)
            },
            onDismiss = { revokeTarget = null },
        )
    }
}

private fun formatDeviceDate(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown"
    return runCatching {
        val instant = Instant.parse(raw)
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault("unknown")
}
