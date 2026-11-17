package com.nexchat.app.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexchat.app.NexChatApp
import com.nexchat.app.ui.components.Avatar
import com.nexchat.app.ui.components.SilverBackground
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.NexChatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Full-screen call UI. It is deliberately a separate activity so an incoming
 * call can be shown over the lock screen from the notification's full-screen
 * intent without keeping a Compose navigation destination alive.
 */
class CallActivity : ComponentActivity() {
    private val answerRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        consumeIntent(intent)
        val container = NexChatApp.from(this).container
        setContent {
            val preferences by container.settings.state.collectAsStateWithLifecycle()
            val call by container.call.state.collectAsStateWithLifecycle()
            val requestedAnswer by answerRequest.collectAsStateWithLifecycle()
            NexChatTheme(mode = preferences.themeMode) {
                CallScreen(
                    call = call,
                    requestedAnswer = requestedAnswer,
                    onConsumedAnswerRequest = { answerRequest.value = null },
                    onAnswerRequest = { callId -> answerRequest.value = callId },
                    onHangup = container.call::hangup,
                    onReject = { container.call.reject(it) },
                    onToggleMute = container.call::toggleMute,
                    onToggleSpeaker = container.call::toggleSpeaker,
                )
                LaunchedEffect(call) {
                    if (call == null || call?.status == CallStatus.ENDED && call?.endReason == null) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        answerRequest.value = if (intent?.getBooleanExtra(EXTRA_AUTO_ACCEPT, false) == true) {
            intent.getStringExtra(EXTRA_CALL_ID)
        } else {
            null
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"
    }
}

@Composable
private fun CallScreen(
    call: CallUiState?,
    requestedAnswer: String?,
    onConsumedAnswerRequest: () -> Unit,
    onAnswerRequest: (String) -> Unit,
    onHangup: () -> Unit,
    onReject: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val context = LocalContext.current
    val container = remember(context) { NexChatApp.from(context).container }
    val hasMicrophone = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = requestedAnswer
        onConsumedAnswerRequest()
        if (pending != null) {
            if (granted) container.call.accept(pending) else container.call.declineForPermission(pending)
        }
    }

    LaunchedEffect(requestedAnswer, call?.callId) {
        val pending = requestedAnswer ?: return@LaunchedEffect
        val current = call ?: return@LaunchedEffect
        if (current.callId != pending || current.outgoing || current.status != CallStatus.RINGING) {
            onConsumedAnswerRequest()
            return@LaunchedEffect
        }
        if (hasMicrophone) {
            onConsumedAnswerRequest()
            container.call.accept(pending)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler {
        if (call == null) {
            onHangup()
        } else if (call.status == CallStatus.ENDED) {
            onHangup()
        } else if (call.outgoing) {
            onHangup()
        } else if (call.status == CallStatus.RINGING) {
            onReject(call.callId)
        } else {
            onHangup()
        }
    }

    SilverBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 26.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "NEXCHAT VOICE",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    color = palette.textMuted,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(34.dp))
                Avatar(
                    name = call?.peerName ?: "NEXCHAT",
                    avatar = call?.peerAvatar,
                    size = 126.dp,
                )
                Spacer(Modifier.height(26.dp))
                Text(
                    call?.peerName ?: "Call ended",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    statusLabel(call),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (call?.status == CallStatus.CONNECTED) palette.accent else palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                CallControls(
                    call = call,
                    onAnswer = { call?.callId?.let(onAnswerRequest) },
                    onHangup = onHangup,
                    onReject = { call?.callId?.let(onReject) },
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    "Signaling is end-to-end encrypted. Audio uses WebRTC DTLS-SRTP.",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = palette.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CallControls(
    call: CallUiState?,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onReject: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val palette = LocalNexPalette.current
    if (call == null || call.status == CallStatus.ENDED) {
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            CallControl(
                icon = Icons.Outlined.CallEnd,
                label = "Close",
                background = palette.danger,
                tint = Color.White,
                onClick = onHangup,
            )
        }
        return
    }
    if (!call.outgoing && call.status == CallStatus.RINGING) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallControl(
                icon = Icons.Outlined.CallEnd,
                label = "Decline",
                background = palette.danger,
                tint = Color.White,
                onClick = onReject,
            )
            CallControl(
                icon = Icons.Outlined.Call,
                label = "Answer",
                background = palette.success,
                tint = Color.White,
                onClick = onAnswer,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallControl(
            icon = if (call.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            label = if (call.muted) "Unmute" else "Mute",
            background = if (call.muted) palette.accent else palette.glassStrong,
            tint = if (call.muted) Color.White else palette.textPrimary,
            onClick = onToggleMute,
        )
        CallControl(
            icon = Icons.Outlined.CallEnd,
            label = if (call.outgoing && call.status == CallStatus.RINGING) "Cancel" else "End",
            background = palette.danger,
            tint = Color.White,
            onClick = onHangup,
        )
        CallControl(
            icon = if (call.speaker) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
            label = if (call.speaker) "Speaker on" else "Speaker",
            background = if (call.speaker) palette.accent else palette.glassStrong,
            tint = if (call.speaker) Color.White else palette.textPrimary,
            onClick = onToggleSpeaker,
        )
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(29.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun statusLabel(call: CallUiState?): String {
    if (call == null) return "Call ended"
    if (call.status == CallStatus.ENDED) return call.endReason ?: "Call ended"
    if (call.status == CallStatus.CONNECTED) {
        val connectedAt = call.connectedAt ?: return "Connecting…"
        var now by remember(connectedAt) { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(connectedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    return when {
        call.outgoing && call.status == CallStatus.RINGING -> "Calling…"
        call.outgoing -> "Connecting…"
        call.status == CallStatus.RINGING -> "Incoming end-to-end encrypted call"
        else -> "Connecting…"
    }
}
