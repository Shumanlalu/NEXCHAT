package com.nexchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexchat.app.core.network.AnnouncementDto
import com.nexchat.app.ui.theme.LocalNexPalette

@Composable
fun AnnouncementBanner(
    announcement: AnnouncementDto,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNexPalette.current
    val accent = when (announcement.severity) {
        "critical" -> palette.danger
        "warning" -> Color(0xFFD69A32)
        else -> palette.accent
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.4f), shape)
            .padding(start = 13.dp, end = 9.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Campaign,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                announcement.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (announcement.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    announcement.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDismiss != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss announcement",
                    tint = palette.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun MaintenanceBanner(message: String, modifier: Modifier = Modifier) {
    val palette = LocalNexPalette.current
    val accent = Color(0xFFD69A32)
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.4f), shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Maintenance",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun UpdateRequiredScreen(
    currentVersion: String,
    minimumVersion: String,
    onRetry: () -> Unit,
) {
    val palette = LocalNexPalette.current
    SilverBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GlassCard(
                    strong = true,
                    corner = 28.dp,
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(46.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Update NEXCHAT",
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "This build (v$currentVersion) is older than the minimum supported version v$minimumVersion. Install the latest APK from your official NEXCHAT source, then open it again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton(
                            text = "Check again",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onRetry,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Your messages and encryption keys stay on this device. Updating the app does not move them to a server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
