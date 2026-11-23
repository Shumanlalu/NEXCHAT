package com.nexchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexchat.app.ui.theme.LocalNexPalette

/**
 * A single contact entry with no card container: no background fill, border,
 * shadow or rectangular press highlight. The avatar, name and identifiers are
 * laid out directly on the screen background so the list reads as one
 * continuous surface. Used for saved contacts, search results, blocked
 * contacts and group-member selection so every contact list looks identical.
 */
@Composable
fun ContactListRow(
    name: String,
    subtitle: String?,
    avatar: String?,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 52.dp,
    verified: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val palette = LocalNexPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .then(
                    if (onClick != null) {
                        // indication = null removes the default rectangular
                        // ripple/foreground that made names look boxed in.
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name = name, avatar = avatar, size = avatarSize)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (verified) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Outlined.Verified,
                            contentDescription = "Safety number verified",
                            tint = palette.success,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = avatarSize + 22.dp, end = 8.dp)
                    .height(1.dp)
                    .background(palette.divider),
            )
        }
    }
}
