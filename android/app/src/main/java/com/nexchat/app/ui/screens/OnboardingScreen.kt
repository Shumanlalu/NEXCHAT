package com.nexchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexchat.app.ui.components.ChromeNLogo
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.components.SilverBackground
import com.nexchat.app.ui.theme.LocalNexPalette

@Composable
fun OnboardingScreen(
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
) {
    val palette = LocalNexPalette.current
    SilverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "NEXCHAT / PRIVATE MESSENGER",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.4.sp),
                    color = palette.textMuted,
                )
            }
            Spacer(Modifier.height(30.dp))
            ChromeNLogo(size = 168.dp)
            Spacer(Modifier.height(26.dp))
            Text(
                "Private by design.",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 43.sp),
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "A quieter place to talk. Messages are end-to-end encrypted, your NEX ID replaces a phone number, and the server only sees ciphertext.",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            FeatureRow(
                icon = Icons.Outlined.Lock,
                title = "Sealed on your device",
                body = "Every message is sealed with the Signal protocol and forward secrecy.",
            )
            Spacer(Modifier.height(12.dp))
            FeatureRow(
                icon = Icons.Outlined.Person,
                title = "No phone number required",
                body = "Share a randomly generated NEX ID instead of a personal phone number.",
            )
            Spacer(Modifier.height(12.dp))
            FeatureRow(
                icon = Icons.Outlined.Public,
                title = "Run your own server",
                body = "The backend is self-hostable and relays only opaque, encrypted envelopes.",
            )
            Spacer(Modifier.height(30.dp))
            PrimaryButton(
                text = "Create your NEX ID",
                icon = Icons.Outlined.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
                onClick = onCreateAccount,
            )
            TextButton(onClick = onSignIn) {
                Text(
                    "I already have an account",
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Encryption keys are generated on this device and protected by Android Keystore. NEXCHAT never stores plaintext messages on a server.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    val palette = LocalNexPalette.current
    GlassCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(palette.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}
