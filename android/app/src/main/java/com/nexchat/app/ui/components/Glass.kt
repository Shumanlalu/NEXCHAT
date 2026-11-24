package com.nexchat.app.ui.components

import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.atSize
import kotlin.math.absoluteValue

@Composable
fun SilverBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalNexPalette.current
    Box(modifier.fillMaxSize().background(palette.backgroundTop)) {
        // One restrained vertical wash. Decorative glows, arcs and diagonal
        // sheens made the interface read as decorative rather than useful.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(palette.backgroundTop, palette.backgroundBottom),
                ),
            )
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier
        .clip(shape)
        .background(if (strong) palette.glassStrong else palette.glass)
        .border(1.dp, palette.divider, shape)
        .then(
            if (onClick != null) {
                Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            } else {
                Modifier
            },
        )
    Box(base.padding(contentPadding).then(modifier)) { content() }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (enabled) palette.accent else palette.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                color = palette.onAccent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text,
                color = palette.onAccent,
                style = MaterialTheme.typography.labelLarge.atSize(15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (icon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(icon, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(12.dp)
    val contentColor = palette.textPrimary.copy(alpha = if (enabled) 1f else 0.45f)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (enabled) palette.glassStrong else palette.glassStrong.copy(alpha = 0.5f))
            .border(1.dp, palette.divider, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search chats...",
    onSearch: (() -> Unit)? = null,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(palette.glass)
            .border(1.dp, palette.divider, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, color = palette.textMuted, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                ),
                cursorBrush = Brush.verticalGradient(listOf(palette.accent, palette.accent)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                color = palette.textSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(palette.glass)
                .border(1.dp, palette.divider, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, color = palette.textMuted, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                    ),
                    cursorBrush = Brush.verticalGradient(listOf(palette.accent, palette.accent)),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                    ),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(if (selected) palette.accentSoft else palette.glass)
            .border(1.dp, if (selected) palette.accent else palette.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) palette.accent else palette.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (selected) palette.accent else palette.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Avatar(
    name: String,
    avatar: String? = null,
    size: Dp = 52.dp,
    isGroup: Boolean = false,
) {
    val palette = LocalNexPalette.current
    val initials = remember(name) {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts.first().take(2).uppercase()
            else -> (parts.first().first().toString() + parts.last().first().toString()).uppercase()
        }
    }
    val avatarColor = remember(name, palette.dark) {
        val options = if (palette.dark) {
            listOf(
                Color(0xFF39786C), Color(0xFF436C85), Color(0xFF6A5C86),
                Color(0xFF87674F), Color(0xFF3D7866),
            )
        } else {
            listOf(
                Color(0xFF2E8B7A), Color(0xFF3E7391), Color(0xFF78639B),
                Color(0xFFA87A55), Color(0xFF3E8A70),
            )
        }
        options[name.hashCode().absoluteValue % options.size]
    }
    val model = remember(avatar) {
        if (avatar.isNullOrBlank()) null else if (avatar.startsWith("http") || avatar.startsWith("content:")) {
            avatar
        } else {
            val payload = if (avatar.startsWith("data:")) avatar.substringAfter(',') else avatar
            runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
        }
    }
    val initialsSize = when {
        size >= 80.dp -> 24.sp
        size >= 56.dp -> 18.sp
        size >= 44.dp -> 15.sp
        else -> 13.sp
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor)
            .border(1.dp, palette.divider, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else if (isGroup) {
            Text("◎", color = Color.White, style = MaterialTheme.typography.titleMedium.atSize(initialsSize))
        } else {
            Text(
                initials,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge.atSize(initialsSize),
            )
        }
    }
}

@Composable
fun Wordmark(fontSize: Int = 30) {
    val palette = LocalNexPalette.current
    Text(
        "NexChat",
        style = MaterialTheme.typography.displayMedium.atSize(fontSize.sp),
        color = palette.textPrimary,
        maxLines = 1,
    )
}

@Composable
fun ChromeNLogo(size: Dp = 132.dp) {
    val palette = LocalNexPalette.current
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(
            color = palette.accentSoft,
            radius = w * 0.48f,
            center = Offset(w / 2, h / 2),
        )
        drawCircle(
            color = palette.accent.copy(alpha = 0.4f),
            radius = w * 0.47f,
            center = Offset(w / 2, h / 2),
            style = Stroke(width = w * 0.012f),
        )
        val n = Path().apply {
            moveTo(w * 0.33f, h * 0.26f)
            lineTo(w * 0.33f, h * 0.75f)
            lineTo(w * 0.67f, h * 0.25f)
            lineTo(w * 0.67f, h * 0.74f)
        }
        drawPath(
            n,
            color = palette.accent,
            style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun LoadingOrb(size: Dp = 34.dp) {
    val palette = LocalNexPalette.current
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        color = palette.accent,
        trackColor = palette.glassBorder,
        strokeWidth = 2.5.dp,
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalNexPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(palette.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            GlassButton(actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val palette = LocalNexPalette.current
    Text(
        text.uppercase(),
        color = palette.textMuted,
        style = MaterialTheme.typography.labelMedium.atSize(11.sp).copy(letterSpacing = 0.4.sp),
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun OnlineDot(online: Boolean, size: Dp = 9.dp) {
    val palette = LocalNexPalette.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (online) palette.success else palette.textMuted.copy(alpha = 0.45f))
            .border(1.dp, palette.glass, CircleShape),
    )
}
