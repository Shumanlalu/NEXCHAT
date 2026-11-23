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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

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
            val video by container.call.videoState.collectAsStateWithLifecycle()
            val requestedAnswer by answerRequest.collectAsStateWithLifecycle()
            NexChatTheme(mode = preferences.themeMode) {
                CallScreen(
                    call = call,
                    video = video,
                    requestedAnswer = requestedAnswer,
                    onConsumedAnswerRequest = { answerRequest.value = null },
                    onAnswerRequest = { callId -> answerRequest.value = callId },
                    onHangup = container.call::hangup,
                    onReject = { container.call.reject(it) },
                    onToggleMute = container.call::toggleMute,
                    onToggleSpeaker = container.call::toggleSpeaker,
                    onToggleCamera = container.call::toggleCamera,
                )
                LaunchedEffect(call) {
                    // The manager keeps the ended state briefly so the user can
                    // see why the call stopped; once it is cleared, close.
                    if (call == null) finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    override fun onDestroy() {
        // If the user dismisses the ended-call screen, drop the stale state so
        // the next call can start immediately.
        if (isFinishing) {
            runCatching {
                val container = NexChatApp.from(this).container
                val call = container.call.state.value
                if (call?.status == CallStatus.ENDED) container.call.clearEnded(call.callId)
            }
        }
        super.onDestroy()
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
    video: CallVideoState,
    requestedAnswer: String?,
    onConsumedAnswerRequest: () -> Unit,
    onAnswerRequest: (String) -> Unit,
    onHangup: () -> Unit,
    onReject: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
) {
    val palette = LocalNexPalette.current
    val context = LocalContext.current
    val container = remember(context) { NexChatApp.from(context).container }

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val pending = requestedAnswer
        onConsumedAnswerRequest()
        val microphone = results[Manifest.permission.RECORD_AUDIO] == true ||
            granted(Manifest.permission.RECORD_AUDIO)
        val camera = results[Manifest.permission.CAMERA] == true ||
            granted(Manifest.permission.CAMERA)
        if (pending != null) {
            // Camera denial must not block the audio portion of a video call.
            if (microphone) {
                container.call.accept(pending, cameraEnabled = camera)
            } else {
                container.call.declineForPermission(pending)
            }
        }
    }

    var pendingCameraToggle by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        val shouldToggle = pendingCameraToggle
        pendingCameraToggle = false
        if (allowed && shouldToggle) onToggleCamera()
    }

    fun requestCameraToggle() {
        if (granted(Manifest.permission.CAMERA)) {
            onToggleCamera()
        } else {
            pendingCameraToggle = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(requestedAnswer, call?.callId) {
        val pending = requestedAnswer ?: return@LaunchedEffect
        val current = call ?: return@LaunchedEffect
        if (current.callId != pending || current.outgoing || current.status != CallStatus.RINGING) {
            onConsumedAnswerRequest()
            return@LaunchedEffect
        }
        val permissions = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (current.video && !granted(Manifest.permission.CAMERA)) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (permissions.isEmpty()) {
            onConsumedAnswerRequest()
            container.call.accept(
                pending,
                cameraEnabled = granted(Manifest.permission.CAMERA),
            )
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
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

    val videoMode = call?.video == true && call.status != CallStatus.ENDED
    val hasRemoteVideo = videoMode && video.remoteTrack != null

    SilverBackground {
        Box(Modifier.fillMaxSize()) {
            if (videoMode) {
                video.remoteTrack?.let { track ->
                    video.eglContext?.let { egl ->
                        key(track, egl) {
                            VideoSurface(
                                track = track,
                                eglContext = egl,
                                mirror = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.26f)),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 26.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!hasRemoteVideo) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        if (call?.video == true) "NEXCHAT VIDEO" else "NEXCHAT VOICE",
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
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = palette.glass.copy(alpha = 0.88f),
                                shape = RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            name = call?.peerName ?: "NEXCHAT",
                            avatar = call?.peerAvatar,
                            size = 34.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                call?.peerName ?: "NEXCHAT",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                statusLabel(call),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
                if (!hasRemoteVideo) {
                    Text(
                        statusLabel(call),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (call?.status == CallStatus.CONNECTED) {
                            palette.accent
                        } else {
                            palette.textSecondary
                        },
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f))
                CallControls(
                    call = call,
                    video = call?.video == true,
                    cameraAvailable = video.localTrack != null,
                    onAnswer = { call?.callId?.let(onAnswerRequest) },
                    onHangup = onHangup,
                    onReject = { call?.callId?.let(onReject) },
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onToggleCamera = ::requestCameraToggle,
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    if (call?.video == true) {
                        "Signaling is end-to-end encrypted. Video uses WebRTC DTLS-SRTP."
                    } else {
                        "Signaling is end-to-end encrypted. Audio uses WebRTC DTLS-SRTP."
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = palette.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            if (videoMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 96.dp, end = 18.dp)
                        .size(width = 108.dp, height = 150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val local = video.localTrack
                    if (local != null && call?.cameraOn == true) {
                        video.eglContext?.let { egl ->
                            key(local, egl) {
                                VideoSurface(
                                    track = local,
                                    eglContext = egl,
                                    mirror = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else {
                        Text(
                            "Camera off",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(
    track: VideoTrack,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { androidContext ->
            SurfaceViewRenderer(androidContext).apply {
                init(eglContext, null)
                setMirror(mirror)
                setZOrderMediaOverlay(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                track.addSink(this)
            }
        },
        update = { renderer -> renderer.setMirror(mirror) },
        onRelease = { renderer ->
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        },
    )
}

@Composable
private fun CallControls(
    call: CallUiState?,
    video: Boolean,
    cameraAvailable: Boolean,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onReject: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
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
        if (video) {
            CallControl(
                icon = if (call.cameraOn) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
                label = if (call.cameraOn) "Camera on" else "Camera off",
                background = if (call.cameraOn) palette.accent else palette.glassStrong,
                tint = if (call.cameraOn) Color.White else palette.textPrimary,
                enabled = cameraAvailable,
                onClick = onToggleCamera,
            )
        }
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalNexPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, onClick = onClick),
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
        call.status == CallStatus.RINGING ->
            if (call.video) "Incoming end-to-end encrypted video call"
            else "Incoming end-to-end encrypted call"
        else -> "Connecting…"
    }
}
