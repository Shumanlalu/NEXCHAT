package com.nexchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    val palette = LocalNexPalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.glass)
            .border(1.dp, palette.divider, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = (tint ?: palette.textPrimary).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(size * 0.47f),
        )
    }
}

@Composable
fun NexTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalNexPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null -> GlassIconButton(
                Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            leading != null -> leading()
        }
        Spacer(Modifier.width(if (onBack != null || leading != null) 12.dp else 2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalNexPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall.atSize(11.sp).copy(letterSpacing = 0.8.sp),
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.atSize(26.sp),
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        } else {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.atSize(11.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val MainTabs = listOf(
    BottomTab("chats", "Chats", Icons.Outlined.ChatBubbleOutline),
    BottomTab("contacts", "Contacts", Icons.Outlined.People),
    BottomTab("discover", "Discover", Icons.Outlined.Explore),
    BottomTab("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun NexBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNexPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(palette.navGlass),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.divider),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) palette.accentSoft else Color.Transparent)
                        .clickable { onSelect(tab.route) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) palette.accent else palette.textMuted,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall.atSize(10.sp),
                        color = if (selected) palette.accent else palette.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val palette = LocalNexPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.glassStrong,
        textContentColor = palette.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) palette.danger else palette.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = palette.textSecondary)
            }
        },
    )
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    val palette = LocalNexPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.divider),
    )
}
