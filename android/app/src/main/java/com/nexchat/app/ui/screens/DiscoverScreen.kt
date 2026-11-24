package com.nexchat.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nexchat.app.core.nearby.NearbyPeer
import com.nexchat.app.net.ConnectionState
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.GlassButton
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.ScreenTitle
import com.nexchat.app.ui.components.SectionTitle
import com.nexchat.app.ui.components.StatusPill
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import com.nexchat.app.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.launch

@Composable
fun DiscoverScreen(
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "discover") {
        DiscoverViewModel(it.api, it.auth, it.websocket, it.settings, it.chat, it.nearby)
    }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val reachable by vm.reachable.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val nearbyEnabled by vm.nearbyEnabled.collectAsStateWithLifecycle()
    val nearbyPeers by vm.nearbyPeers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val nearbyPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.isNotEmpty() && results.values.all { granted -> granted }) {
            vm.enableNearby()
        } else {
            scope.launch {
                snackbar.showSnackbar("Nearby needs the requested permissions to find local peers.")
            }
        }
    }

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
                                style = MaterialTheme.typography.headlineMedium,
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
                                style = MaterialTheme.typography.labelSmall.atSize(10.sp)
                                    .copy(letterSpacing = 0.4.sp),
                                color = palette.textMuted,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                profile?.nexId ?: "—",
                                style = MaterialTheme.typography.titleMedium.atSize(17.sp),
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                NearbyMessagingCard(
                    enabled = nearbyEnabled,
                    peers = nearbyPeers,
                    onEnable = {
                        val required = vm.nearbyPermissions()
                        if (required.isEmpty()) {
                            vm.enableNearby()
                        } else {
                            nearbyPermissions.launch(required)
                        }
                    },
                    onDisable = vm::disableNearby,
                    onSync = vm::syncNearby,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Spacer(Modifier.height(10.dp))
                CapabilityCard(
                    icon = Icons.Outlined.Call,
                    title = "Encrypted calls",
                    body = "One-to-one voice and video calls are end-to-end encrypted.",
                    state = "Available",
                    available = true,
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
private fun NearbyMessagingCard(
    enabled: Boolean,
    peers: List<NearbyPeer>,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNexPalette.current
    GlassCard(
        modifier = modifier,
        corner = 22.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
                    Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Nearby messaging",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    "Bluetooth relay for peers in range, without a server round trip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            StatusPill(
                if (enabled) "On" else "Off",
                if (enabled) palette.success else palette.textMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${peers.size} nearby peer${if (peers.size == 1) "" else "s"} in range",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        if (peers.isEmpty()) {
            Text(
                "Enable nearby to discover peers. NEX IDs shown here are shortened for privacy.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        } else {
            peers.take(3).forEachIndexed { index, peer ->
                Text(
                    "Peer ${index + 1} · NEX ${redactNexId(peer.nexId)}",
                    style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (peers.size > 3) {
                Text(
                    "+${peers.size - 3} more nearby peers",
                    style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                text = if (enabled) "Stop nearby" else "Enable nearby",
                icon = Icons.Outlined.Bluetooth,
                modifier = Modifier.weight(1f),
                onClick = if (enabled) onDisable else onEnable,
            )
            GlassButton(
                text = "Sync now",
                icon = Icons.Outlined.Sync,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = onSync,
            )
        }
    }
}

/** Only the public NEX ID is displayed; Bluetooth addresses are never rendered. */
private fun redactNexId(value: String): String {
    val id = value.trim()
    if (id.length <= 8) return "NEX ••••"
    return "${id.take(4)}••••${id.takeLast(4)}"
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
