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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.net.ConnectionState
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.GlassButton
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.ScreenTitle
import com.nexchat.app.ui.components.SectionTitle
import com.nexchat.app.ui.components.StatusPill
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.launch

@Composable
fun DiscoverScreen(
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "discover") { DiscoverViewModel(it.api, it.auth, it.websocket, it.settings, it.chat) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val reachable by vm.reachable.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 110.dp),
        ) {
            item {
                ScreenTitle(
                    eyebrow = "Your network",
                    title = "Discover",
                    subtitle = "A private space that belongs to you",
                )
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    strong = true,
                    corner = 28.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    onClick = onOpenProfile,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            name = profile?.displayName ?: "You",
                            avatar = profile?.avatar,
                            size = 64.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile?.displayName ?: "Your profile",
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                profile?.username?.let { "@$it" } ?: "Not signed in",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                            )
                        }
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.glass)
                            .clickable {
                                profile?.nexId?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    scope.launch { snackbar.showSnackbar("NEX ID copied") }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "YOUR NEX ID",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    letterSpacing = 1.8.sp,
                                    fontSize = 9.sp,
                                ),
                                color = palette.textMuted,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                profile?.nexId ?: "—",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 17.sp,
                                    letterSpacing = 1.2.sp,
                                ),
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy NEX ID",
                            tint = palette.accent,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Share this ID instead of your phone number. Contacts can also find you by username.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("Connection", Modifier.padding(horizontal = 24.dp))
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    corner = 24.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Encrypted relay",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                vm.serverUrl.ifBlank { "Server address not configured" },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                            )
                        }
                        ConnectionBadge(connection)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            text = if (checking) "Checking…" else "Test server",
                            icon = Icons.Outlined.Refresh,
                            modifier = Modifier.weight(1f),
                            onClick = { if (!checking) vm.checkServer() },
                        )
                        GlassButton(
                            text = "Sync groups",
                            icon = Icons.Outlined.Sync,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.syncGroups() },
                        )
                    }
                    if (reachable != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (reachable == true) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = if (reachable == true) palette.success else palette.danger,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (reachable == true) {
                                    "The server answered. Messages will flow through its encrypted relay."
                                } else {
                                    "The server could not be reached. Check the address or your connection."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reachable == true) palette.success else palette.danger,
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("What is live in this build", Modifier.padding(horizontal = 24.dp))
            }
            item {
                CapabilityCard(
                    icon = Icons.Outlined.Lock,
                    title = "End-to-end encrypted messaging",
                    body = "One-to-one and group messages are sealed with the Signal protocol before they reach the server.",
                    state = "Available",
                    available = true,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Spacer(Modifier.height(10.dp))
                CapabilityCard(
                    icon = Icons.Outlined.Groups,
                    title = "Private groups",
                    body = "Group messages are encrypted separately for every member device. The server only sees opaque envelopes.",
                    state = "Available",
                    available = true,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Spacer(Modifier.height(10.dp))
                CapabilityCard(
                    icon = Icons.Outlined.Bluetooth,
                    title = "Nearby messaging",
                    body = "Bluetooth LE offline delivery is not implemented in this build. No nearby permissions are requested.",
                    state = "Not in this build",
                    available = false,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Spacer(Modifier.height(10.dp))
                CapabilityCard(
                    icon = Icons.Outlined.Call,
                    title = "Encrypted calls",
                    body = "Voice and video calling is not implemented in this build. No microphone access is requested.",
                    state = "Not in this build",
                    available = false,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Spacer(Modifier.height(22.dp))
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    onClick = onOpenSettings,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(palette.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Person,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Privacy & appearance",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Receipts, sharing, notifications, screenshot protection",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                            )
                        }
                        Text(
                            "Open",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
private fun ConnectionBadge(connection: ConnectionState) {
    val palette = LocalNexPalette.current
    when (connection) {
        ConnectionState.CONNECTED -> StatusPill("Live", palette.success)
        ConnectionState.CONNECTING -> StatusPill("Connecting", palette.accent)
        ConnectionState.RECONNECTING -> StatusPill("Retrying", palette.accent)
        ConnectionState.STOPPED -> StatusPill("Offline", palette.textMuted)
    }
}

@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    body: String,
    state: String,
    available: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNexPalette.current
    GlassCard(
        modifier = modifier,
        corner = 22.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (available) palette.accentSoft else palette.glassBorder),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (available) palette.accent else palette.textMuted,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        state,
                        if (available) palette.success else palette.textMuted,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}
