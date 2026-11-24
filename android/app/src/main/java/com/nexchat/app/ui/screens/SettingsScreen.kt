package com.nexchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.BuildConfig
import com.nexchat.app.core.settings.ThemeMode
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.ConfirmDialog
import com.nexchat.app.ui.components.FilterChip
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.ScreenTitle
import com.nexchat.app.ui.components.SectionTitle
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import com.nexchat.app.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenProfile: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel(key = "settings") { SettingsViewModel(it.auth, it.settings, it.api) }
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp),
        ) {
            item {
                ScreenTitle(
                    eyebrow = "Control room",
                    title = "Settings",
                    subtitle = "Your privacy, your device, your rules",
                )
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    strong = true,
                    corner = 28.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                    onClick = onOpenProfile,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            name = profile?.displayName ?: "You",
                            avatar = profile?.avatar,
                            size = 60.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile?.displayName ?: "Your profile",
                                style = MaterialTheme.typography.titleLarge,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                profile?.nexId ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = palette.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("Account", Modifier.padding(horizontal = 24.dp))
            }
            item {
                SettingsGroup {
                    SettingsAction(
                        icon = Icons.Outlined.Person,
                        title = "Profile",
                        subtitle = "Display name, photo, NEX ID",
                        onClick = onOpenProfile,
                    )
                    SettingsDivider()
                    SettingsAction(
                        icon = Icons.Outlined.Devices,
                        title = "Devices",
                        subtitle = "Review and revoke signed-in devices",
                        onClick = onOpenDevices,
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("Privacy", Modifier.padding(horizontal = 24.dp))
            }
            item {
                SettingsGroup {
                    ToggleRow(
                        icon = Icons.Outlined.Shield,
                        title = "Read receipts",
                        subtitle = "Let contacts know when you read their messages",
                        checked = preferences.readReceipts,
                        onCheckedChange = vm::setReadReceipts,
                    )
                    SettingsDivider()
                    ToggleRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "Typing indicators",
                        subtitle = "Show when you are composing a message",
                        checked = preferences.typingIndicators,
                        onCheckedChange = vm::setTypingIndicators,
                    )
                    SettingsDivider()
                    ToggleRow(
                        icon = Icons.Outlined.Router,
                        title = "Share online status",
                        subtitle = "Contacts can see when you are online",
                        checked = preferences.sharePresence,
                        onCheckedChange = vm::setSharePresence,
                    )
                    SettingsDivider()
                    ToggleRow(
                        icon = Icons.Outlined.VisibilityOff,
                        title = "Hide notification previews",
                        subtitle = "Keep message text out of the notification shade",
                        checked = !preferences.notificationPreview,
                        onCheckedChange = { hidePreviews -> vm.setNotificationPreview(!hidePreviews) },
                    )
                    SettingsDivider()
                    ToggleRow(
                        icon = Icons.Outlined.Lock,
                        title = "Screenshot protection",
                        subtitle = "Block screenshots and screen recording in NEXCHAT",
                        checked = preferences.screenshotProtection,
                        onCheckedChange = vm::setScreenshotProtection,
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("Appearance", Modifier.padding(horizontal = 24.dp))
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                label = when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.DARK -> "Midnight"
                                    ThemeMode.LIGHT -> "Silver"
                                },
                                selected = preferences.themeMode == mode,
                                onClick = { vm.setTheme(mode) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Choose how Nexchat looks. Your selection applies across every screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        minLines = 1,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("Server", Modifier.padding(horizontal = 24.dp))
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(palette.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Router,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Connected server",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                preferences.serverUrl.ifBlank { "Not configured" },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "The server relays opaque encrypted envelopes. To use a different server, sign out and sign in against the new address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                SectionTitle("About", Modifier.padding(horizontal = 24.dp))
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    AboutLine("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Spacer(Modifier.height(8.dp))
                    AboutLine("Protocol", "Signal · X3DH/PQXDH · Double Ratchet")
                    Spacer(Modifier.height(8.dp))
                    AboutLine("Local storage", "SQLCipher · Android Keystore")
                    Spacer(Modifier.height(8.dp))
                    AboutLine(
                        "Calls & nearby",
                        "Voice and video calls · Bluetooth nearby relay available",
                    )
                    Spacer(Modifier.height(8.dp))
                    AboutLine("Attachments & transcription", "Encrypted files up to 10 MB · transcription not included")
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                GlassCard(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    corner = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                ) {
                    SettingsAction(
                        icon = Icons.Outlined.Logout,
                        title = "Sign out",
                        subtitle = "Wipes encrypted messages and keys from this device",
                        destructive = true,
                        trailing = {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = palette.danger,
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        onClick = { confirmLogout = true },
                    )
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

    if (confirmLogout) {
        ConfirmDialog(
            title = "Sign out of NEXCHAT?",
            message = "Your local messages, contacts, and encryption keys will be removed from this device. You can sign back in with your username and password.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                confirmLogout = false
                vm.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassCard(
        modifier = modifier.padding(horizontal = 18.dp),
        corner = 22.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun SettingsDivider() {
    val palette = LocalNexPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 64.dp)
            .height(1.dp)
            .background(palette.divider),
    )
}

@Composable
internal fun SettingsAction(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
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
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (destructive) palette.danger.copy(alpha = 0.12f) else palette.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) palette.danger else palette.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (destructive) palette.danger else palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (destructive) palette.danger.copy(alpha = 0.72f) else palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.atSize(15.sp),
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.atSize(11.sp),
                color = palette.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                checkedBorderColor = palette.accent,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.glassBorder,
                uncheckedBorderColor = palette.glassBorder,
            ),
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    val palette = LocalNexPalette.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
            modifier = Modifier.weight(0.35f),
            minLines = 1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.weight(0.65f),
            minLines = 1,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
