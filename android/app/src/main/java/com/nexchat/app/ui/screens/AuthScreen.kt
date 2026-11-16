package com.nexchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.ui.components.ChromeNLogo
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.GlassTextField
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.components.SilverBackground
import com.nexchat.app.ui.nexViewModel
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.viewmodel.AuthMode
import com.nexchat.app.ui.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    initialMode: AuthMode,
    onBack: (() -> Unit)? = null,
) {
    val palette = LocalNexPalette.current
    val vm = nexViewModel { AuthViewModel(it.auth) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(initialMode) { vm.setMode(initialMode) }

    SilverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Text("Back", color = palette.textSecondary)
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            ChromeNLogo(size = 118.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                if (state.mode == AuthMode.LOGIN) "WELCOME BACK" else "NEW IDENTITY",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.8.sp),
                color = palette.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.mode == AuthMode.LOGIN) "Sign in to NEXCHAT" else "Create your NEXCHAT",
                style = MaterialTheme.typography.headlineLarge,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.mode == AuthMode.LOGIN) {
                    "Your keys stay on this device."
                } else {
                    "A NEX ID is generated for you. No email or phone number is needed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            ModeSwitch(
                mode = state.mode,
                onSelect = vm::setMode,
            )
            Spacer(Modifier.height(22.dp))

            GlassTextField(
                value = state.username,
                onValueChange = vm::setUsername,
                placeholder = "your_username",
                label = "Username",
            )
            if (state.mode == AuthMode.REGISTER) {
                Spacer(Modifier.height(14.dp))
                GlassTextField(
                    value = state.displayName,
                    onValueChange = vm::setDisplayName,
                    placeholder = "How contacts see you",
                    label = "Display name",
                )
            }
            Spacer(Modifier.height(14.dp))
            GlassTextField(
                value = state.password,
                onValueChange = vm::setPassword,
                placeholder = if (state.mode == AuthMode.LOGIN) "Your password" else "At least 10 characters",
                label = "Password",
                password = !state.showPassword,
                trailing = {
                    Text(
                        if (state.showPassword) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                        modifier = Modifier.clickable { vm.togglePassword() },
                    )
                },
            )
            if (state.mode == AuthMode.REGISTER) {
                Spacer(Modifier.height(14.dp))
                GlassTextField(
                    value = state.confirmPassword,
                    onValueChange = vm::setConfirmPassword,
                    placeholder = "Repeat your password",
                    label = "Confirm password",
                    password = !state.showPassword,
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.danger.copy(alpha = 0.12f))
                        .border(1.dp, palette.danger.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = palette.danger,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            PrimaryButton(
                text = if (state.mode == AuthMode.LOGIN) "Sign in" else "Create account",
                icon = Icons.Outlined.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
                loading = state.loading,
                enabled = !state.loading,
                onClick = vm::submit,
            )
            Spacer(Modifier.height(18.dp))
            GlassCard(
                strong = false,
                corner = 18.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(palette.success),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Messages are encrypted before they leave this phone. The server stores ciphertext only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ModeSwitch(mode: AuthMode, onSelect: (AuthMode) -> Unit) {
    val palette = LocalNexPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(palette.glass)
            .border(1.dp, palette.glassBorder, RoundedCornerShape(50))
            .padding(5.dp),
    ) {
        ModePill(
            label = "Sign in",
            selected = mode == AuthMode.LOGIN,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(AuthMode.LOGIN) },
        )
        ModePill(
            label = "Create account",
            selected = mode == AuthMode.REGISTER,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(AuthMode.REGISTER) },
        )
    }
}

@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) androidx.compose.ui.graphics.Color.White else palette.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}
