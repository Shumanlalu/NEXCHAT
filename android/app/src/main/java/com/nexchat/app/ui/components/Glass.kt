package com.nexchat.app.ui.components

import android.util.Base64
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.absoluteValue

@Composable
fun SilverBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalNexPalette.current
    Box(modifier.fillMaxSize().background(palette.backgroundBottom)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(palette.backgroundTop, palette.backgroundBottom),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.glowBlue, Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.05f),
                    radius = size.width * 0.85f,
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width * 0.15f, size.height * 0.05f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.glowViolet, Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.75f),
                    radius = size.width * 0.8f,
                ),
                radius = size.width * 0.8f,
                center = Offset(size.width * 0.95f, size.height * 0.75f),
            )
            val arcColor = if (palette.dark) Color.White.copy(alpha = 0.055f) else Color.White.copy(alpha = 0.34f)
            drawArc(
                color = arcColor,
                startAngle = 195f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(-size.width * 0.25f, size.height * 0.08f),
                size = Size(size.width * 1.2f, size.height * 0.75f),
                style = Stroke(width = size.width * 0.075f),
            )
            drawArc(
                color = if (palette.dark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.22f),
                startAngle = 12f,
                sweepAngle = 45f,
                useCenter = false,
                topLeft = Offset(size.width * 0.42f, size.height * 0.64f),
                size = Size(size.width * 1.05f, size.height * 0.55f),
                style = Stroke(width = size.width * 0.04f),
            )
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier
        .shadow(if (palette.dark) 6.dp else 14.dp, shape, clip = false)
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = if (strong) {
                    listOf(palette.glassStrong, palette.glass)
                } else {
                    listOf(palette.glass, palette.glass.copy(alpha = palette.glass.alpha * 0.72f))
                },
            ),
        )
        .border(1.dp, palette.glassBorder, shape)
        .then(
            if (onClick != null) {
                Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            } else {
                Modifier
            },
        )
    Box(base.padding(contentPadding)) { content() }
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
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(58.dp)
            .shadow(18.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        if (enabled) palette.accent else palette.accent.copy(alpha = 0.45f),
                        if (enabled) palette.accentDeep else palette.accentDeep.copy(alpha = 0.45f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
            if (icon != null) {
                Spacer(Modifier.width(10.dp))
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(50.dp)
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(palette.glassStrong)
            .border(1.dp, palette.glassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = palette.textPrimary, style = MaterialTheme.typography.labelLarge)
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
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(palette.glass)
            .border(1.dp, palette.glassBorder, shape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(21.dp))
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
    val shape = RoundedCornerShape(18.dp)
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
                .height(56.dp)
                .clip(shape)
                .background(palette.glass)
                .border(1.dp, palette.glassBorder, shape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
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
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(42.dp)
            .clip(shape)
            .background(if (selected) palette.accent else palette.glass)
            .border(1.dp, if (selected) Color.Transparent else palette.glassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Color.White else palette.textSecondary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            label,
            color = if (selected) Color.White else palette.textSecondary,
            style = MaterialTheme.typography.labelMedium,
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
    val colors = remember(name, palette.dark) {
        val options = if (palette.dark) {
            listOf(
                Color(0xFF4E60F5) to Color(0xFF7A4DFF),
                Color(0xFF1E8E9E) to Color(0xFF2854C7),
                Color(0xFF8B4FC4) to Color(0xFF4652C8),
                Color(0xFFB6673C) to Color(0xFF6E3C96),
                Color(0xFF287A67) to Color(0xFF2E4AA8),
            )
        } else {
            listOf(
                Color(0xFF6372F4) to Color(0xFF9E7BFF),
                Color(0xFF35A7B4) to Color(0xFF4F72E8),
                Color(0xFF9E69D4) to Color(0xFF6474E6),
                Color(0xFFC58B5C) to Color(0xFF8D64C7),
                Color(0xFF4A9B84) to Color(0xFF5C70DB),
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
    Box(
        modifier = Modifier
            .size(size)
            .shadow(8.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.first, colors.second)))
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
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
            Text("◎", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.44f).sp)
        } else {
            Text(
                initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.32f).sp,
                letterSpacing = 0.3.sp,
            )
        }
    }
}

@Composable
fun Wordmark(fontSize: Int = 34) {
    val palette = LocalNexPalette.current
    Text(
        "NexChat",
        style = MaterialTheme.typography.displayMedium.copy(fontSize = fontSize.sp),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun ChromeNLogo(size: Dp = 132.dp) {
    val palette = LocalNexPalette.current
    val transition = rememberInfiniteTransition(label = "logo")
    val shimmer by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Reverse),
        label = "shimmer",
    )
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.glowBlue, Color.Transparent),
                center = Offset(w / 2, h / 2),
                radius = w * 0.6f,
            ),
            radius = w * 0.6f,
            center = Offset(w / 2, h / 2),
        )
        rotate(-20f, pivot = Offset(w / 2, h / 2)) {
            drawOval(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFF7C879E),
                        Color(0xFFF8FAFF),
                        Color(0xFFA7B4C9),
                        Color(0xFF6B7690),
                        Color(0xFFE8EEFA),
                        Color(0xFF7C879E),
                    ),
                ),
                topLeft = Offset(w * 0.08f, h * 0.25f),
                size = Size(w * 0.84f, h * 0.5f),
                style = Stroke(width = w * 0.035f),
            )
            drawOval(
                color = Color.White.copy(alpha = 0.32f * shimmer),
                topLeft = Offset(w * 0.08f, h * 0.25f),
                size = Size(w * 0.84f, h * 0.5f),
                style = Stroke(width = w * 0.008f),
            )
        }
        val n = Path().apply {
            moveTo(w * 0.33f, h * 0.26f)
            lineTo(w * 0.33f, h * 0.75f)
            lineTo(w * 0.67f, h * 0.25f)
            lineTo(w * 0.67f, h * 0.74f)
        }
        drawPath(
            n,
            color = Color(0xFF0A0E18).copy(alpha = 0.35f),
            style = Stroke(width = w * 0.145f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            n,
            brush = Brush.linearGradient(
                listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFDDE4F1),
                    Color(0xFF8E9AB2),
                    Color(0xFFF6F9FF),
                ),
                start = Offset(w * 0.3f, h * 0.2f),
                end = Offset(w * 0.7f, h * 0.8f),
            ),
            style = Stroke(width = w * 0.145f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            n,
            color = Color.White.copy(alpha = 0.5f * shimmer),
            style = Stroke(width = w * 0.012f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        rotate(-20f, pivot = Offset(w / 2, h / 2)) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.8f), Color.Transparent),
                ),
                startAngle = 200f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(w * 0.08f, h * 0.25f),
                size = Size(w * 0.84f, h * 0.5f),
                style = Stroke(width = w * 0.035f),
            )
        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(palette.glass)
                .border(1.dp, palette.glassBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(22.dp))
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
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp, fontSize = 11.sp),
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp),
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
            .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape),
    )
}
