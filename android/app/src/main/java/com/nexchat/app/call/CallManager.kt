package com.nexchat.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nexchat.app.BuildConfig
import com.nexchat.app.core.config.RemoteConfigStore
import com.nexchat.app.core.crypto.ContentKind
import com.nexchat.app.core.crypto.SecureContent
import com.nexchat.app.core.db.CallHistoryEntity
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.IceServerDto
import com.nexchat.app.core.security.LogRedactor
import com.nexchat.app.core.telemetry.Telemetry
import com.nexchat.app.data.CallSignalEvent
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.service.CallAudioService
import com.nexchat.app.service.NotificationHelper
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class CallStatus { RINGING, CONNECTING, CONNECTED, ENDED }

data class CallUiState(
    val callId: String,
    val conversationId: String,
    val peerNexId: String,
    val peerName: String,
    val peerAvatar: String?,
    val outgoing: Boolean,
    val status: CallStatus,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val video: Boolean = false,
    val cameraOn: Boolean = false,
    val endReason: String? = null,
)

/** Native video objects owned by the call and exposed to the call UI. */
data class CallVideoState(
    val eglContext: EglBase.Context? = null,
    val localTrack: VideoTrack? = null,
    val remoteTrack: VideoTrack? = null,
)

private fun CallUiState.callMode(): String = if (video) "video" else "audio"

/**
 * Owns the single active 1:1 call. SDP and ICE are carried inside the normal
 * end-to-end encrypted Signal envelopes, so the relay never sees call metadata
 * or media. WebRTC media itself is SRTP encrypted peer-to-peer (or via TURN).
 */
class CallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chat: ChatRepository,
    private val api: ApiClient,
    private val notifications: NotificationHelper,
    private val remoteConfig: RemoteConfigStore,
    private val telemetry: Telemetry,
) {
    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    private val _videoState = MutableStateFlow(CallVideoState())
    val videoState: StateFlow<CallVideoState> = _videoState.asStateFlow()

    private val lock = Any()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var audioModule: JavaAudioDeviceModule? = null
    @Volatile private var connection: PeerConnection? = null
    @Volatile private var audioSource: AudioSource? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var eglBase: EglBase? = null
    @Volatile private var cameraCapturer: CameraVideoCapturer? = null
    @Volatile private var cameraTextureHelper: SurfaceTextureHelper? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var incomingOffer: String? = null
    // Once a particular peer device answers, signals from the user's other
    // devices (for example a missed-call timeout on a tablet that kept
    // ringing) must not be allowed to tear down the connected call.
    @Volatile private var remoteDeviceId: Int? = null
    @Volatile private var speakerOn = false
    @Volatile private var historyRecorded = false
    @Volatile private var answering = false
    @Volatile private var localDescriptionSent = false
    @Volatile private var audioServiceStarted = false

    private var ringJob: Job? = null
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private var durationJob: Job? = null
    private var signalPollJob: Job? = null
    private val remoteCandidates =
        mutableMapOf<String, MutableList<Pair<Int, IceCandidate>>>()
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()
    private val recentCalls = LinkedHashSet<String>()
    private var cachedIce: Pair<Long, List<PeerConnection.IceServer>>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Signaling must be processed in arrival order. A parallel `launch` per
    // event allowed a cancel/answer to be handled before the offer created the
    // call state, which left ghost calls ringing and broke Accept/Decline.
    private val signalChannel = Channel<CallSignalEvent>(Channel.UNLIMITED)
    private var signalConsumerJob: Job? = null
    private val pendingTerminal = LinkedHashMap<String, CallSignalEvent>()

    init {
        startSignalConsumer()
    }

    fun startOutgoing(
        conversationId: String,
        peerNexId: String,
        peerName: String,
        peerAvatar: String?,
        video: Boolean = false,
    ) {
        if (!remoteConfig.callsEnabled()) {
            throw IllegalStateException("Calling is temporarily unavailable")
        }
        if (video && !remoteConfig.videoCallsEnabled()) {
            throw IllegalStateException("Video calls are temporarily unavailable")
        }
        if (!hasMicrophonePermission()) {
            throw IllegalStateException("Microphone permission is required for calls")
        }
        if (video && !hasCameraPermission()) {
            throw IllegalStateException("Camera permission is required for video calls")
        }
        val callId = UUID.randomUUID().toString()
        val call = CallUiState(
            callId = callId,
            conversationId = conversationId,
            peerNexId = peerNexId,
            peerName = peerName.ifBlank { peerNexId },
            peerAvatar = peerAvatar,
            outgoing = true,
            status = CallStatus.RINGING,
            startedAt = System.currentTimeMillis(),
            video = video,
            cameraOn = video && hasCameraPermission(),
        )
        synchronized(lock) {
            if (_state.value != null) {
                throw IllegalStateException("Finish the current call first")
            }
            historyRecorded = false
            incomingOffer = null
            remoteDescriptionSet = false
            remoteDeviceId = null
            remoteCandidates.clear()
            pendingLocalCandidates.clear()
            localDescriptionSent = false
            answering = false
            _state.value = call
            _videoState.value = CallVideoState()
        }
        cancelTimers()
        log(
            "outgoing start call=${short(callId)} peer=${call.peerNexId} " +
                "video=${call.video}",
        )
        telemetry.reportCall(
            callId = call.callId,
            role = "caller",
            peerNexId = call.peerNexId,
            mode = call.callMode(),
            status = "ringing",
            startedAt = call.startedAt,
        )
        notifications.cancelIncomingCall()
        startSignalPolling()
        configureAudio()
        // Start the ring timer before any network work. Previously it only
        // started after the blocking offer send, so a stalled POST left the
        // caller on "Calling..." indefinitely.
        startRingTimeout(call.callId)
        launchCallActivity(call.callId, autoAccept = false)
        scope.launch { sendOffer(call) }
    }

    fun accept(callId: String, cameraEnabled: Boolean = hasCameraPermission()) {
        if (!remoteConfig.callsEnabled()) {
            reject(callId)
            return
        }
        val call = synchronized(lock) {
            val current = _state.value
            if (current == null || current.callId != callId || current.outgoing ||
                current.status != CallStatus.RINGING
            ) {
                log("accept ignored call=${short(callId)} state=${current?.status}")
                return
            }
            if (answering) return
            answering = true
            val updated = current.copy(
                status = CallStatus.CONNECTING,
                cameraOn = current.video && cameraEnabled && hasCameraPermission(),
            )
            _state.value = updated
            updated
        }
        ringJob?.cancel()
        log("accept call=${short(callId)}")
        notifications.cancelIncomingCall()
        if (!hasMicrophonePermission()) {
            declineForPermission(callId)
            return
        }
        // Start before SDP/ICE work so a stalled send cannot hide a failed
        // negotiation behind an unbounded "Connecting..." state.
        startConnectTimeout(callId)
        scope.launch { sendAnswer(call) }
    }

    fun reject(callId: String? = null) {
        val call = _state.value ?: return
        if (callId != null && call.callId != callId) return
        if (call.status == CallStatus.CONNECTED) {
            hangup()
            return
        }
        val tag = if (call.outgoing) "canceled" else "declined"
        val label = if (call.outgoing) "Call canceled" else "Call declined"
        log("reject call=${short(call.callId)} reason=$tag")
        notifications.cancelIncomingCall()
        sendTerminalSignal(call, ContentKind.CALL_REJECT, tag)
        finishCall(call.callId, label, tag)
    }

    fun declineForPermission(callId: String) {
        val call = _state.value ?: return
        if (call.callId != callId) return
        log("reject call=${short(callId)} reason=permission")
        notifications.cancelIncomingCall()
        sendTerminalSignal(call, ContentKind.CALL_REJECT, "declined")
        finishCall(callId, "Microphone permission is required for calls", "declined")
    }

    fun hangup() {
        val call = _state.value ?: return
        if (call.status == CallStatus.ENDED) {
            // Close/Back on the ended screen must dismiss it immediately
            // instead of waiting for the automatic clear delay.
            clearEnded(call.callId)
            return
        }
        val connected = call.connectedAt != null
        val tag = when {
            connected -> "completed"
            call.outgoing -> "canceled"
            else -> "declined"
        }
        val label = when {
            connected -> "Call ended"
            call.outgoing -> "Call canceled"
            else -> "Call declined"
        }
        notifications.cancelIncomingCall()
        log("hangup call=${short(call.callId)} reason=$tag connected=$connected")
        sendTerminalSignal(call, ContentKind.CALL_HANGUP, tag)
        finishCall(call.callId, label, tag)
    }

    fun toggleMute() {
        val updated = synchronized(lock) {
            val current = _state.value ?: return
            if (current.status != CallStatus.CONNECTED) return
            current.copy(muted = !current.muted).also { _state.value = it }
        }
        runCatching { audioModule?.setMicrophoneMute(updated.muted) }
    }

    fun toggleSpeaker() {
        val updated = synchronized(lock) {
            val current = _state.value ?: return
            if (current.status != CallStatus.CONNECTED) return
            current.copy(speaker = !current.speaker).also { _state.value = it }
        }
        speakerOn = updated.speaker
        runCatching { audioManager.isSpeakerphoneOn = speakerOn }
        updateProximity()
    }

    fun toggleCamera() {
        val call = _state.value ?: return
        if (!call.video || call.status == CallStatus.ENDED) return
        setCameraEnabled(!call.cameraOn)
    }

    /** Enable or disable the local camera without renegotiating the call. */
    fun setCameraEnabled(enabled: Boolean) {
        if (enabled && !remoteConfig.videoCallsEnabled()) return
        val updated = synchronized(lock) {
            val current = _state.value ?: return
            if (!current.video || current.status == CallStatus.ENDED ||
                current.cameraOn == enabled
            ) {
                return
            }
            if (enabled && (!hasCameraPermission() || videoTrack == null)) return
            current.copy(cameraOn = enabled).also { _state.value = it }
        }
        log("camera toggled call=${short(updated.callId)} on=${updated.cameraOn}")
        scope.launch(Dispatchers.Default) {
            if (updated.cameraOn) {
                startLocalCamera()
            } else {
                stopLocalCamera()
            }
        }
    }

    fun clearEnded(callId: String) {
        synchronized(lock) {
            if (_state.value?.callId == callId && _state.value?.status == CallStatus.ENDED) {
                _state.value = null
            }
        }
        if (_state.value == null) stopSignalPolling()
    }

    /** Release every call resource when the account session goes away. */
    fun teardown() {
        val active = _state.value
        if (active != null && active.status != CallStatus.ENDED) {
            sendTerminalSignal(active, ContentKind.CALL_HANGUP, "session_ended")
            finishCall(active.callId, "Call ended", "completed")
        }
        runCatching { notifications.cancelIncomingCall() }
        cleanupMedia()
        val egl = eglBase
        eglBase = null
        _videoState.value = CallVideoState()
        runCatching { egl?.release() }
        endAudio()
        stopSignalPolling()
        synchronized(lock) { pendingTerminal.clear() }
    }

    /**
     * Serverless relays may deliver a WebSocket push on a different function
     * instance than the one holding this socket. While a call exists, poll the
     * encrypted queue quickly so signaling does not wait for the slower
     * background receive poll.
     */
    private fun startSignalPolling() {
        if (signalPollJob?.isActive == true) return
        signalPollJob = scope.launch {
            while (isActive) {
                delay(CALL_SIGNAL_POLL_MILLIS)
                val current = _state.value
                if (current == null || current.status == CallStatus.ENDED) break
                runCatching { chat.pollIncoming() }
            }
            signalPollJob = null
        }
    }

    private fun stopSignalPolling() {
        signalPollJob?.cancel()
        signalPollJob = null
    }

    private fun startSignalConsumer() {
        if (signalConsumerJob?.isActive == true) return
        signalConsumerJob = scope.launch {
            for (event in signalChannel) {
                runCatching {
                    when (event) {
                        is CallSignalEvent.Offer -> handleOffer(event)
                        is CallSignalEvent.Answer -> handleAnswer(event)
                        is CallSignalEvent.Ice -> handleIce(event)
                        is CallSignalEvent.Ended -> handleRemoteEnded(event)
                        is CallSignalEvent.Rejected -> handleRejected(event)
                    }
                }.onFailure {
                    log("signal failed call=${short(event.callId)} type=${event::class.simpleName} ${it.message}")
                }
            }
        }
    }

    fun onSignal(event: CallSignalEvent) {
        if (!signalChannel.trySend(event).isSuccess) {
            log("signal queue full call=${short(event.callId)}")
        }
    }

    /** Replay an end/reject that arrived before the offer finished decrypting. */
    private fun replayPendingTerminal(callId: String) {
        val events = synchronized(lock) {
            val keys = pendingTerminal.keys.filter { it == callId }
            keys.mapNotNull { pendingTerminal.remove(it) }
        }
        events.forEach { event ->
            when (event) {
                is CallSignalEvent.Ended -> handleRemoteEnded(event)
                is CallSignalEvent.Rejected -> handleRejected(event)
                else -> Unit
            }
        }
    }

    private fun rememberTerminalIfNoCall(event: CallSignalEvent) {
        synchronized(lock) {
            if (_state.value != null) return
            if (pendingTerminal.size >= 8) pendingTerminal.remove(pendingTerminal.keys.first())
            pendingTerminal[event.callId] = event
        }
    }

    private suspend fun sendOffer(call: CallUiState) = withContext(Dispatchers.IO) {
        try {
            val peer = createPeerConnection(call.callId)
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
                if (call.video) {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
            }
            val offer = peer.createOfferSuspended(constraints)
            peer.setLocalDescriptionSuspended(offer)
            if (_state.value?.callId != call.callId) {
                // The call was ended while the connection was being created.
                // Close the native resources created after that cleanup ran.
                runCatching { peer.close() }
                runCatching { peer.dispose() }
                return@withContext
            }
            log(
                "offer local sdp chars=${offer.description.length} " +
                    "audio=${offer.description.contains("m=audio")} " +
                    "video=${offer.description.contains("m=video")}",
            )
            // Send the offer before releasing locally gathered ICE. Candidates
            // that arrive first are buffered, so the peer can never receive
            // an answer or candidate for an SDP it has not seen.
            chat.sendCallSignalFast(
                call.conversationId,
                SecureContent(
                    kind = ContentKind.CALL_OFFER,
                    clientId = UUID.randomUUID().toString(),
                    callId = call.callId,
                    sdp = offer.description,
                    sdpType = "offer",
                    video = call.video,
                ),
            )
            localDescriptionSent = true
            flushLocalCandidates(call.callId)
            log("offer queued call=${short(call.callId)}")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log("offer failed call=${short(call.callId)} ${error.message}")
            failCall(call.callId, error.message ?: "Could not start the call")
        }
    }

    private suspend fun sendAnswer(call: CallUiState) = withContext(Dispatchers.IO) {
        try {
            configureAudio()
            val peer = createPeerConnection(call.callId)
            val offer = incomingOffer ?: throw IllegalStateException("This call offer has expired")
            peer.setRemoteDescriptionSuspended(
                SessionDescription(SessionDescription.Type.OFFER, offer),
            )
            synchronized(lock) {
                remoteDescriptionSet = true
                pendingLocalCandidates.clear()
                localDescriptionSent = false
            }
            flushRemoteCandidates(call.callId)
            val answer = peer.createAnswerSuspended(MediaConstraints())
            peer.setLocalDescriptionSuspended(answer)
            log(
                "answer local sdp chars=${answer.description.length} " +
                    "audio=${answer.description.contains("m=audio")} " +
                    "video=${answer.description.contains("m=video")}",
            )
            // Target the exact device whose offer we accepted. Sending the
            // answer to every signed-in device can wake a stale installation
            // and is a common source of duplicate/ghost call state.
            chat.sendCallSignalFast(
                call.conversationId,
                SecureContent(
                    kind = ContentKind.CALL_ANSWER,
                    clientId = UUID.randomUUID().toString(),
                    callId = call.callId,
                    sdp = answer.description,
                    sdpType = "answer",
                    video = call.video,
                ),
                targetSignalDeviceId = remoteDeviceId,
            )
            localDescriptionSent = true
            flushLocalCandidates(call.callId)
            log("answer queued call=${short(call.callId)} target=${remoteDeviceId}")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log("answer failed call=${short(call.callId)} ${error.message}")
            failCall(call.callId, error.message ?: "Could not answer the call")
        }
    }

    private suspend fun handleOffer(event: CallSignalEvent.Offer) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val age = if (event.sentAt > 0) now - event.sentAt else 0
        if (age > MAX_OFFER_AGE_MILLIS) {
            log("offer ignored call=${short(event.callId)} stale ageMs=$age")
            return@withContext
        }
        if (!remoteConfig.callsEnabled()) {
            log("offer rejected call=${short(event.callId)} calls disabled")
            sendCallsDisabledReject(event)
            return@withContext
        }
        val videoCall = event.video && event.sdp.contains("m=video")
        if (videoCall && !remoteConfig.videoCallsEnabled()) {
            log("offer rejected call=${short(event.callId)} video calls disabled")
            sendCallsDisabledReject(event)
            return@withContext
        }
        if (!event.sdp.startsWith("v=") || !event.sdp.contains("m=audio")) {
            log("offer ignored call=${short(event.callId)} malformed sdp")
            return@withContext
        }
        synchronized(lock) {
            if (recentCalls.contains(event.callId)) {
                log("offer ignored call=${short(event.callId)} recently ended")
                return@withContext
            }
        }
        val current = _state.value
        if (current != null) {
            if (current.callId != event.callId) sendBusyReject(event)
            return@withContext
        }
        if (chat.isBlocked(event.fromNexId)) {
            log("offer ignored call=${short(event.callId)} blocked peer")
            return@withContext
        }

        val conversation = runCatching {
            chat.openDirectConversation(event.fromNexId, event.displayName)
        }.getOrNull()
        val peerName = conversation?.title?.takeIf { it.isNotBlank() }
            ?: event.displayName?.takeIf { it.isNotBlank() }
            ?: event.fromNexId
        val call = CallUiState(
            callId = event.callId,
            conversationId = conversation?.id ?: event.conversationId,
            peerNexId = event.fromNexId,
            peerName = peerName,
            peerAvatar = conversation?.avatar,
            outgoing = false,
            status = CallStatus.RINGING,
            startedAt = now,
            video = videoCall,
            cameraOn = false,
        )
        synchronized(lock) {
            if (_state.value != null || recentCalls.contains(event.callId)) return@withContext
            historyRecorded = false
            incomingOffer = event.sdp
            remoteDescriptionSet = false
            remoteDeviceId = event.fromSignalDeviceId
            localDescriptionSent = false
            answering = false
            pendingLocalCandidates.clear()
            // Drop candidates buffered for call IDs that never produced an
            // offer we accepted, so they cannot occupy the pending-call cap.
            remoteCandidates.keys.retainAll(setOf(event.callId))
            remoteCandidates[event.callId] = remoteCandidates[event.callId] ?: mutableListOf()
            _state.value = call
            _videoState.value = CallVideoState()
        }
        log(
            "incoming offer call=${short(event.callId)} peer=${event.fromNexId} " +
                "device=${event.fromSignalDeviceId} ageMs=$age sdpChars=${event.sdp.length} " +
                "video=${call.video}",
        )
        telemetry.reportCall(
            callId = call.callId,
            role = "callee",
            peerNexId = call.peerNexId,
            mode = call.callMode(),
            status = "ringing",
            startedAt = call.startedAt,
        )
        cancelTimers()
        startSignalPolling()
        notifications.showIncomingCall(call.callId, call.peerName, call.video)
        launchCallActivity(call.callId, autoAccept = false)
        startIncomingRingTimeout(call.callId)
        // A fast cancel/decline can be decrypted before the offer finishes
        // opening the conversation. Apply it now instead of ringing a call the
        // other party already ended.
        replayPendingTerminal(call.callId)
    }

    private suspend fun handleAnswer(event: CallSignalEvent.Answer) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = _state.value
            if (current == null) {
                log("answer ignored call=${short(event.callId)} no active call")
                return@withContext
            }
            if (!current.outgoing || current.callId != event.callId ||
                current.peerNexId != event.fromNexId
            ) {
                log("answer ignored call=${short(event.callId)} state mismatch")
                return@withContext
            }
            if (current.status != CallStatus.RINGING && current.status != CallStatus.CONNECTING) {
                log("answer ignored call=${short(event.callId)} status=${current.status}")
                return@withContext
            }
            if (remoteDescriptionSet) {
                log("answer ignored call=${short(event.callId)} duplicate")
                return@withContext
            }
            val answeredDevice = remoteDeviceId
            if (answeredDevice != null && answeredDevice != event.fromSignalDeviceId) {
                log(
                    "answer ignored call=${short(event.callId)} device=${
                        event.fromSignalDeviceId
                    } expected=$answeredDevice",
                )
                return@withContext
            }
            remoteDeviceId = event.fromSignalDeviceId
        }
        // Apply the answer on the sequential signal consumer so a following
        // ICE/cancel cannot be processed before the remote description exists.
        startConnectTimeout(event.callId)
        try {
            val peer = connection ?: throw IllegalStateException("The call connection is gone")
            peer.setRemoteDescriptionSuspended(
                SessionDescription(SessionDescription.Type.ANSWER, event.sdp),
            )
            synchronized(lock) { remoteDescriptionSet = true }
            synchronized(lock) {
                val current = _state.value
                if (current?.callId == event.callId) {
                    _state.value = current.copy(status = CallStatus.CONNECTING)
                }
            }
            ringJob?.cancel()
            flushRemoteCandidates(event.callId)
            log(
                "answer received call=${short(event.callId)} " +
                    "device=${event.fromSignalDeviceId} sdpChars=${event.sdp.length}",
            )
            configureAudio()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log("answer apply failed call=${short(event.callId)} ${error.message}")
            failCall(event.callId, error.message ?: "Could not connect the call")
        }
    }

    private fun handleIce(event: CallSignalEvent.Ice) {
        if (event.candidate.isBlank() || event.candidate.length > MAX_CANDIDATE_CHARS) return
        val candidate = IceCandidate(
            event.sdpMid ?: "0",
            event.index ?: 0,
            event.candidate,
        )
        val call = _state.value
        if (call == null) {
            // Candidates can legitimately arrive just before the offer
            // envelope is decrypted, so buffer them briefly. Bound both the
            // number of pending call IDs and the candidates per call so a
            // malicious peer cannot grow this map without limit.
            synchronized(lock) {
                if (remoteCandidates.size >= MAX_PENDING_CALL_IDS &&
                    !remoteCandidates.containsKey(event.callId)
                ) {
                    return
                }
                val list = remoteCandidates.getOrPut(event.callId) { mutableListOf() }
                if (list.size < MAX_BUFFERED_CANDIDATES) {
                    list.add(event.fromSignalDeviceId to candidate)
                }
            }
            return
        }
        if (call.callId != event.callId || call.peerNexId != event.fromNexId) return
        val activeDevice = remoteDeviceId
        if (activeDevice != null && activeDevice != event.fromSignalDeviceId) {
            log(
                "ice ignored call=${short(event.callId)} device=${event.fromSignalDeviceId} " +
                    "expected=$activeDevice",
            )
            return
        }
        val peer = synchronized(lock) {
            if (!remoteDescriptionSet) {
                val list = remoteCandidates.getOrPut(event.callId) { mutableListOf() }
                if (list.size < MAX_BUFFERED_CANDIDATES) {
                    list.add(event.fromSignalDeviceId to candidate)
                }
                null
            } else {
                connection
            }
        }
        if (peer != null) {
            runCatching { peer.addIceCandidate(candidate) }
                .onFailure { log("ice add failed call=${short(event.callId)} ${it.message}") }
        }
    }

    private fun handleRemoteEnded(event: CallSignalEvent.Ended) {
        val call = _state.value
        if (call == null) {
            rememberTerminalIfNoCall(event)
            return
        }
        if (call.callId != event.callId || call.peerNexId != event.fromNexId) return
        val activeDevice = remoteDeviceId
        if (activeDevice != null && activeDevice != event.fromSignalDeviceId) return
        val connected = call.connectedAt != null
        val label: String
        val tag: String
        when {
            connected && event.reason == "time_limit" -> {
                label = "Call ended at the time limit"
                tag = "time_limit"
            }
            connected -> {
                label = "Call ended"
                tag = "completed"
            }
            call.outgoing && (event.reason == "missed" || event.reason == "no_answer") -> {
                label = "No answer"
                tag = "no_answer"
            }
            call.outgoing || event.reason == "canceled" -> {
                label = "Call canceled"
                tag = "canceled"
            }
            else -> {
                label = "Missed call"
                tag = "missed"
            }
        }
        finishCall(call.callId, label, tag)
    }

    private fun handleRejected(event: CallSignalEvent.Rejected) {
        val call = _state.value
        if (call == null) {
            rememberTerminalIfNoCall(event)
            return
        }
        if (call.callId != event.callId || call.peerNexId != event.fromNexId) return
        val activeDevice = remoteDeviceId
        if (activeDevice != null && activeDevice != event.fromSignalDeviceId) return
        val (label, tag) = when (event.reason) {
            "busy" -> "Contact is on another call" to "busy"
            "disabled" -> "Voice calling is temporarily unavailable" to "disabled"
            "declined" -> "Call declined" to "declined"
            "canceled" -> "Call canceled" to "canceled"
            else -> "Call declined" to "declined"
        }
        finishCall(call.callId, label, tag)
    }

    private fun sendBusyReject(event: CallSignalEvent.Offer) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val conversation = chat.openDirectConversation(event.fromNexId, event.displayName)
                chat.sendCallSignalFast(
                    conversation.id,
                    callContent(ContentKind.CALL_REJECT, event.callId, reason = "busy"),
                    targetSignalDeviceId = event.fromSignalDeviceId,
                )
            }
        }
    }

    private fun sendCallsDisabledReject(event: CallSignalEvent.Offer) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val conversation = chat.openDirectConversation(event.fromNexId, event.displayName)
                chat.sendCallSignalFast(
                    conversation.id,
                    callContent(ContentKind.CALL_REJECT, event.callId, reason = "disabled"),
                    targetSignalDeviceId = event.fromSignalDeviceId,
                )
            }.onFailure { log("disabled reject failed call=${short(event.callId)} ${it.message}") }
        }
    }

    private fun callContent(kind: String, callId: String, reason: String? = null): SecureContent =
        SecureContent(
            kind = kind,
            clientId = UUID.randomUUID().toString(),
            callId = callId,
            callEndReason = reason,
        )

    private fun sendTerminalSignal(
        call: CallUiState,
        kind: String,
        reason: String,
        targetSignalDeviceId: Int? = null,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                chat.sendCallSignalFast(
                    call.conversationId,
                    callContent(kind, call.callId, reason),
                    targetSignalDeviceId,
                )
            }.onFailure {
                log("terminal signal failed call=${short(call.callId)} kind=$kind ${it.message}")
            }
        }
    }

    private fun startRingTimeout(callId: String) {
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            val call = _state.value
            if (call?.callId == callId && call.outgoing &&
                call.status != CallStatus.CONNECTED && call.status != CallStatus.ENDED
            ) {
                log("ring timeout call=${short(callId)}")
                sendTerminalSignal(call, ContentKind.CALL_HANGUP, "no_answer")
                finishCall(callId, "No answer", "no_answer")
            }
        }
    }

    private fun startIncomingRingTimeout(callId: String) {
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            val call = _state.value
            if (call?.callId == callId && !call.outgoing && call.status == CallStatus.RINGING) {
                log("incoming ring timeout call=${short(callId)}")
                sendTerminalSignal(call, ContentKind.CALL_HANGUP, "missed")
                finishCall(callId, "Missed call", "missed")
            }
        }
    }

    private fun startConnectTimeout(callId: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(CONNECT_TIMEOUT_MILLIS)
            val call = _state.value
            if (call?.callId == callId && call.status != CallStatus.CONNECTED &&
                call.status != CallStatus.ENDED
            ) {
                log("connect timeout call=${short(callId)} status=${call.status}")
                failCall(callId, "The call could not connect")
            }
        }
    }

    private fun scheduleDisconnectFailure(callId: String) {
        disconnectJob?.cancel()
        disconnectJob = scope.launch {
            delay(DISCONNECT_TIMEOUT_MILLIS)
            val call = _state.value
            if (call?.callId == callId && call.status == CallStatus.CONNECTED) {
                failCall(callId, "The call connection was lost")
            }
        }
    }

    private fun markConnected(callId: String) {
        var newlyConnected = false
        val updated = synchronized(lock) {
            val current = _state.value
            if (current == null || current.callId != callId ||
                current.status == CallStatus.ENDED
            ) {
                return
            }
            if (current.connectedAt != null) {
                // Reconnected after a transient ICE disconnect. The media is
                // flowing again, so the pending failure timer must not fire.
                current
            } else {
                newlyConnected = true
                current.copy(
                    status = CallStatus.CONNECTED,
                    connectedAt = System.currentTimeMillis(),
                ).also { _state.value = it }
            }
        }
        ringJob?.cancel()
        connectJob?.cancel()
        disconnectJob?.cancel()
        configureAudio()
        updateProximity()
        if (updated.muted) runCatching { audioModule?.setMicrophoneMute(true) }
        if (newlyConnected) {
            telemetry.reportCall(
                callId = updated.callId,
                role = if (updated.outgoing) "caller" else "callee",
                peerNexId = updated.peerNexId,
                mode = updated.callMode(),
                status = "connected",
                startedAt = updated.startedAt,
                connectedAt = updated.connectedAt,
            )
            startDurationLimit(callId)
        }
    }

    /**
     * Remote config can cap connected-call time. Reading the limit at connect
     * keeps a single call consistent even if the administrator changes it
     * while the call is in progress.
     */
    private fun startDurationLimit(callId: String) {
        durationJob?.cancel()
        val limitSeconds = remoteConfig.maxCallDurationSeconds()
        durationJob = scope.launch {
            delay(limitSeconds.toLong() * 1000L)
            val call = _state.value
            if (call?.callId == callId && call.status == CallStatus.CONNECTED) {
                log("duration limit call=${short(callId)} seconds=$limitSeconds")
                sendTerminalSignal(call, ContentKind.CALL_HANGUP, "time_limit")
                finishCall(callId, "Call ended at the time limit", "time_limit")
            }
        }
    }

    private fun failCall(callId: String, message: String) {
        val call = _state.value
        if (call?.callId == callId && call.status != CallStatus.ENDED) {
            log("call failed call=${short(callId)} reason=$message")
            sendTerminalSignal(call, ContentKind.CALL_HANGUP, "failed")
        }
        finishCall(callId, message, "failed")
    }

    private fun finishCall(callId: String, label: String, tag: String) {
        val call = synchronized(lock) {
            val current = _state.value
            if (current == null || current.callId != callId ||
                current.status == CallStatus.ENDED
            ) {
                return
            }
            recentCalls.add(callId)
            while (recentCalls.size > 32) recentCalls.remove(recentCalls.first())
            answering = false
            localDescriptionSent = false
            pendingLocalCandidates.clear()
            current.copy(status = CallStatus.ENDED, endReason = label, muted = false)
                .also { _state.value = it }
        }
        log(
            "call ended call=${short(callId)} tag=$tag label=\"$label\" " +
                "connectedAt=${call.connectedAt}",
        )
        val endedAt = System.currentTimeMillis()
        val durationSeconds = call.connectedAt?.let {
            ((endedAt - it) / 1000).coerceAtLeast(0).toInt()
        }
        telemetry.reportCall(
            callId = call.callId,
            role = if (call.outgoing) "caller" else "callee",
            peerNexId = call.peerNexId,
            mode = call.callMode(),
            status = when {
                call.connectedAt != null -> "ended"
                tag == "failed" -> "failed"
                tag == "declined" || tag == "busy" || tag == "disabled" -> "declined"
                else -> "missed"
            },
            startedAt = call.startedAt,
            connectedAt = call.connectedAt,
            endedAt = endedAt,
            durationSeconds = durationSeconds,
            failureReason = if (call.connectedAt == null) tag else null,
        )
        cancelTimers()
        stopSignalPolling()
        notifications.cancelIncomingCall()
        synchronized(lock) { pendingTerminal.remove(callId) }
        scope.launch(Dispatchers.Default) {
            cleanupMedia()
            endAudio()
            // Discard any un-sent offer/ICE rows for this call. Without this a
            // late retry could ring the peer after both sides hung up.
            runCatching { chat.clearCallSignals(callId) }
                .onFailure { log("clear call signals failed call=${short(callId)} ${it.message}") }
            if (!historyRecorded) {
                historyRecorded = true
                recordHistory(call, tag)
            }
            delay(END_SCREEN_MILLIS)
            synchronized(lock) {
                if (_state.value?.callId == callId && _state.value?.status == CallStatus.ENDED) {
                    _state.value = null
                }
            }
        }
    }

    private suspend fun recordHistory(call: CallUiState, tag: String) {
        val endedAt = System.currentTimeMillis()
        val connected = call.connectedAt != null
        val seconds = if (connected) {
            ((endedAt - (call.connectedAt ?: endedAt)) / 1000).coerceAtLeast(0)
        } else {
            0
        }
        val kindLabel = if (call.video) "Video call" else "Voice call"
        val body = when {
            connected -> "$kindLabel · ${durationLabel(seconds)}"
            call.outgoing && tag == "declined" -> "Call declined"
            call.outgoing && tag == "busy" -> "Contact was on another call"
            call.outgoing && tag == "no_answer" -> "No answer"
            call.outgoing && tag == "failed" -> "Call failed"
            call.outgoing && tag == "canceled" -> "Outgoing call"
            !call.outgoing && tag == "declined" -> "Declined call"
            !call.outgoing && tag == "failed" -> "Missed call"
            !call.outgoing -> "Missed call"
            else -> kindLabel
        }
        runCatching {
            chat.recordCall(
                CallHistoryEntity(
                    id = call.callId,
                    peerNexId = call.peerNexId,
                    peerName = call.peerName,
                    outgoing = call.outgoing,
                    startedAt = call.startedAt,
                    endedAt = endedAt,
                    connected = connected,
                    endReason = tag,
                ),
                call.conversationId,
                body,
            )
        }
    }

    private fun durationLabel(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        } else {
            "${seconds}s"
        }
    }

    private fun configureAudio() {
        val call = _state.value
        startCallAudioService(video = call?.video == true && call.cameraOn && hasCameraPermission())
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speakerOn
            if (focusRequest == null) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                val result = audioManager.requestAudioFocus(request)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    log("audio focus not granted result=$result")
                }
                focusRequest = request
            }
        }
    }

    private fun startCallAudioService(video: Boolean) {
        if (audioServiceStarted || !hasMicrophonePermission()) return
        audioServiceStarted = true
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallAudioService::class.java).apply {
                    putExtra(CallAudioService.EXTRA_VIDEO, video)
                },
            )
        }.onFailure {
            audioServiceStarted = false
            log("call audio service start failed ${it.message}")
        }
    }

    private fun stopCallAudioService() {
        if (!audioServiceStarted) return
        audioServiceStarted = false
        runCatching { context.stopService(Intent(context, CallAudioService::class.java)) }
    }

    private fun endAudio() {
        runCatching {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        speakerOn = false
        releaseProximity()
        stopCallAudioService()
    }

    private fun updateProximity() {
        val call = _state.value
        val active = call?.status == CallStatus.CONNECTED && !speakerOn && !call.video
        if (!active) {
            releaseProximity()
            return
        }
        if (proximity?.isHeld == true) return
        runCatching {
            val lock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "NEXCHAT:voice-call",
            )
            lock.setReferenceCounted(false)
            lock.acquire(60L * 60L * 1000L)
            proximity = lock
        }
    }

    private fun releaseProximity() {
        runCatching {
            proximity?.let { if (it.isHeld) it.release() }
        }
        proximity = null
    }

    private fun launchCallActivity(callId: String, autoAccept: Boolean) {
        val intent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_AUTO_ACCEPT, autoAccept)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cachedIce?.let { (cachedAt, servers) ->
                if (now - cachedAt < ICE_CACHE_MILLIS && servers.isNotEmpty()) return servers
            }
        }
        val dtos = runCatching { api.serverConfig().iceServers }
            .onFailure { log("ice config fetch failed: ${it.message}") }
            .getOrNull()
            ?.filter { it.urls.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(IceServerDto(urls = listOf(DEFAULT_STUN)))
        val servers = dtos.mapNotNull { dto ->
            runCatching {
                val builder = PeerConnection.IceServer.builder(dto.urls)
                dto.username?.takeIf { it.isNotBlank() }?.let(builder::setUsername)
                dto.credential?.takeIf { it.isNotBlank() }?.let(builder::setPassword)
                builder.createIceServer()
            }.getOrNull()
        }
        val resolved = servers.ifEmpty {
            listOf(PeerConnection.IceServer(DEFAULT_STUN))
        }
        synchronized(lock) { cachedIce = now to resolved }
        return resolved
    }

    private suspend fun createPeerConnection(callId: String): PeerConnection {
        val videoCall = _state.value?.video == true
        val factory = ensureFactory(videoCall)
        if (videoCall) {
            // The decoder and the call screen must use the same EGL context,
            // even when the local camera is denied and only remote video renders.
            publishVideo { it.copy(eglContext = ensureEgl()) }
        }
        val servers = iceServers()
        val turnCount = servers.count { server ->
            server.urls.any { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
        }
        log(
            "ice servers total=${servers.size} turn=$turnCount " +
                "urls=${servers.flatMap { it.urls }.joinToString(",")}",
        )
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                log("signaling call=${short(callId)} state=$state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (_state.value?.callId != callId) return
                log("ice connection call=${short(callId)} state=$state")
                // WebRTC callbacks run on its own threads. Audio focus, the
                // foreground service and the wake lock must be touched on the
                // main thread or Android can reject them.
                mainHandler.post {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED,
                        -> markConnected(callId)
                        PeerConnection.IceConnectionState.DISCONNECTED -> scheduleDisconnectFailure(callId)
                        PeerConnection.IceConnectionState.FAILED ->
                            failCall(callId, "The call connection could not be established")
                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                log("ice receiving call=${short(callId)} receiving=$receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                log("ice gathering call=${short(callId)} state=$state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                sendIce(callId, candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    log("remote video track call=${short(callId)} id=${track.id()}")
                    publishVideo { it.copy(remoteTrack = track) }
                }
            }

            override fun onRemoveTrack(receiver: RtpReceiver) {
                val removed = receiver.track()
                if (removed is VideoTrack) {
                    synchronized(lock) {
                        if (_videoState.value.remoteTrack?.id() == removed.id()) {
                            _videoState.value = _videoState.value.copy(remoteTrack = null)
                        }
                    }
                }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (_state.value?.callId != callId) return
                log("peer connection call=${short(callId)} state=$state")
                mainHandler.post {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> markConnected(callId)
                        PeerConnection.PeerConnectionState.FAILED ->
                            failCall(callId, "The call could not connect")
                        else -> Unit
                    }
                }
            }
        }
        val peer = factory.createPeerConnection(config, MediaConstraints(), observer)
            ?: throw IllegalStateException("Could not create the call connection")
        synchronized(lock) { connection = peer }
        var source: AudioSource? = null
        var track: AudioTrack? = null
        try {
            source = factory.createAudioSource(MediaConstraints())
            track = factory.createAudioTrack("nexchat_audio", source)
            track.setEnabled(true)
            peer.addTrack(track, listOf("nexchat_stream"))
            synchronized(lock) {
                audioSource = source
                audioTrack = track
            }
            if (videoCall && hasCameraPermission()) {
                attachLocalVideo(factory, peer)
            } else if (videoCall) {
                log("video call without camera permission; receiving only")
            }
        } catch (error: Exception) {
            synchronized(lock) { connection = null }
            runCatching { track?.let { runCatching { it.dispose() } } }
            runCatching { source?.let { runCatching { it.dispose() } } }
            stopLocalCamera()
            runCatching { peer.close() }
            runCatching { peer.dispose() }
            throw error
        }
        return peer
    }

    private fun sendIce(callId: String, candidate: IceCandidate) {
        val call = _state.value ?: return
        if (call.callId != callId || call.status == CallStatus.ENDED) return
        synchronized(lock) {
            if (!localDescriptionSent) {
                if (pendingLocalCandidates.size < MAX_BUFFERED_CANDIDATES) {
                    pendingLocalCandidates += candidate
                }
                return
            }
        }
        dispatchIce(call, candidate)
    }

    private fun flushLocalCandidates(callId: String) {
        val (call, candidates, target) = synchronized(lock) {
            val active = _state.value
            if (active?.callId != callId || active.status == CallStatus.ENDED) {
                pendingLocalCandidates.clear()
                return
            }
            val buffered = pendingLocalCandidates.toList()
            pendingLocalCandidates.clear()
            Triple(active, buffered, remoteDeviceId)
        }
        log("ice flush call=${short(callId)} count=${candidates.size}")
        for (candidate in candidates) dispatchIce(call, candidate, target)
    }

    private fun dispatchIce(
        call: CallUiState,
        candidate: IceCandidate,
        targetSignalDeviceId: Int? = remoteDeviceId,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                chat.sendCallSignalFast(
                    call.conversationId,
                    SecureContent(
                        kind = ContentKind.CALL_ICE,
                        clientId = UUID.randomUUID().toString(),
                        callId = call.callId,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid ?: "0",
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                    targetSignalDeviceId = targetSignalDeviceId,
                )
            }.onFailure { log("ice send failed call=${short(call.callId)} ${it.message}") }
        }
    }

    private fun flushRemoteCandidates(callId: String) {
        val (peer, candidates) = synchronized(lock) {
            val activeDevice = remoteDeviceId
            val buffered = remoteCandidates.remove(callId).orEmpty()
                .filter { activeDevice == null || it.first == activeDevice }
                .map { it.second }
            connection to buffered
        }
        if (peer == null) return
        log("ice remote apply call=${short(callId)} count=${candidates.size}")
        candidates.forEach { candidate ->
            runCatching { peer.addIceCandidate(candidate) }
                .onFailure { log("ice remote add failed call=${short(callId)} ${it.message}") }
        }
    }

    private fun cancelTimers() {
        ringJob?.cancel()
        connectJob?.cancel()
        disconnectJob?.cancel()
        durationJob?.cancel()
        ringJob = null
        connectJob = null
        disconnectJob = null
        durationJob = null
    }

    private fun publishVideo(update: (CallVideoState) -> CallVideoState) {
        synchronized(lock) {
            _videoState.value = update(_videoState.value)
        }
    }

    private fun ensureEgl(): EglBase.Context {
        eglBase?.let { return it.eglBaseContext }
        synchronized(lock) {
            eglBase?.let { return it.eglBaseContext }
            val created = EglBase.create()
            eglBase = created
            return created.eglBaseContext
        }
    }

    /** Attach the front camera to the peer connection before SDP is created. */
    private fun attachLocalVideo(factory: PeerConnectionFactory, peer: PeerConnection) {
        if (!hasCameraPermission()) return
        val egl = ensureEgl()
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        val devices = enumerator.deviceNames
        val device = devices.firstOrNull { enumerator.isFrontFacing(it) }
            ?: devices.firstOrNull()
            ?: throw IllegalStateException("No camera is available on this device")
        var capturer: CameraVideoCapturer? = null
        var textureHelper: SurfaceTextureHelper? = null
        var source: VideoSource? = null
        try {
            val createdCapturer = enumerator.createCapturer(device, null)
                ?: throw IllegalStateException("Could not open the camera")
            capturer = createdCapturer
            val createdTextureHelper = SurfaceTextureHelper.create(VIDEO_CAPTURE_THREAD, egl)
            textureHelper = createdTextureHelper
            val createdSource = factory.createVideoSource(createdCapturer.isScreencast)
            source = createdSource
            createdCapturer.initialize(createdTextureHelper, context, createdSource.capturerObserver)
            createdCapturer.startCapture(
                VIDEO_CAPTURE_WIDTH,
                VIDEO_CAPTURE_HEIGHT,
                VIDEO_CAPTURE_FPS,
            )
            val track = factory.createVideoTrack("nexchat_video", createdSource)
            track.setEnabled(true)
            peer.addTrack(track, listOf("nexchat_stream"))
            synchronized(lock) {
                cameraCapturer = createdCapturer
                cameraTextureHelper = createdTextureHelper
                videoSource = createdSource
                videoTrack = track
            }
            publishVideo { it.copy(eglContext = egl, localTrack = track) }
            log("local video attached call=${short(_state.value?.callId.orEmpty())} device=$device")
        } catch (error: Throwable) {
            runCatching { capturer?.dispose() }
            runCatching { textureHelper?.dispose() }
            runCatching { source?.dispose() }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }

    /** Restart an already-negotiated camera track after the user turns it on. */
    private suspend fun startLocalCamera() = withContext(Dispatchers.Default) {
        val call = _state.value ?: return@withContext
        if (!call.video || !call.cameraOn || call.status == CallStatus.ENDED) {
            return@withContext
        }
        val (capturer, track) = synchronized(lock) { cameraCapturer to videoTrack }
        if (capturer == null || track == null) {
            // Without a negotiated video m-line, adding a track now would not
            // be transmitted; leave the button disabled rather than fake it.
            log("camera start ignored call=${short(call.callId)} track=${track != null}")
            revertCameraState(call.callId)
            return@withContext
        }
        runCatching { track.setEnabled(true) }
            .onFailure { log("camera enable failed call=${short(call.callId)} ${it.message}") }
        try {
            capturer.startCapture(VIDEO_CAPTURE_WIDTH, VIDEO_CAPTURE_HEIGHT, VIDEO_CAPTURE_FPS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            log("camera start interrupted call=${short(call.callId)}")
            revertCameraState(call.callId)
        } catch (error: Exception) {
            log("camera start failed call=${short(call.callId)} ${error.message}")
            revertCameraState(call.callId)
        }
    }

    private fun revertCameraState(callId: String) {
        synchronized(lock) {
            val current = _state.value
            if (current?.callId == callId && current.cameraOn) {
                _state.value = current.copy(cameraOn = false)
            }
        }
    }

    private fun stopLocalCamera() {
        val (capturer, track) = synchronized(lock) { cameraCapturer to videoTrack }
        runCatching { track?.setEnabled(false) }
        try {
            capturer?.stopCapture()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log("camera stop failed ${error.message}")
        }
    }

    private fun cleanupMedia() {
        cancelTimers()
        val peer: PeerConnection?
        val source: AudioSource?
        val track: AudioTrack?
        val factoryRef: PeerConnectionFactory?
        val module: JavaAudioDeviceModule?
        val videoCapturer: CameraVideoCapturer?
        val textureHelper: SurfaceTextureHelper?
        val videoSourceRef: VideoSource?
        val videoTrackRef: VideoTrack?
        synchronized(lock) {
            peer = connection
            connection = null
            source = audioSource
            audioSource = null
            track = audioTrack
            audioTrack = null
            factoryRef = factory
            factory = null
            module = audioModule
            audioModule = null
            videoCapturer = cameraCapturer
            cameraCapturer = null
            textureHelper = cameraTextureHelper
            cameraTextureHelper = null
            videoSourceRef = videoSource
            videoSource = null
            videoTrackRef = videoTrack
            videoTrack = null
            _videoState.value = CallVideoState()
            remoteCandidates.clear()
            pendingLocalCandidates.clear()
            incomingOffer = null
            remoteDescriptionSet = false
            localDescriptionSent = false
            answering = false
            remoteDeviceId = null
        }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { videoTrackRef?.setEnabled(false) }
        try {
            videoCapturer?.stopCapture()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log("camera cleanup failed ${error.message}")
        }
        runCatching { videoTrackRef?.dispose() }
        runCatching { videoSourceRef?.dispose() }
        runCatching { videoCapturer?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { factoryRef?.dispose() }
        runCatching { module?.release() }
        releaseProximity()
    }

    private fun ensureFactory(video: Boolean): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(lock) {
            factory?.let { return it }
            initializeWebRtc(context)
            val module = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(
                    JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
                )
                .setUseHardwareNoiseSuppressor(
                    JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .createAudioDeviceModule()
            val created = try {
                val builder = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(module)
                if (video) {
                    // Encoder and decoder must share the same EGL context as
                    // the SurfaceViewRenderer used by CallActivity.
                    val egl = ensureEgl()
                    builder.setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(egl, true, false),
                    )
                    builder.setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
                }
                builder.createPeerConnectionFactory()
            } catch (error: Exception) {
                runCatching { module.release() }
                throw error
            }
            audioModule = module
            factory = created
            return created
        }
    }

    private suspend fun PeerConnection.createOfferSuspended(
        constraints: MediaConstraints,
    ): SessionDescription = suspendCancellableCoroutine { continuation ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }

                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            },
            constraints,
        )
    }

    private suspend fun PeerConnection.createAnswerSuspended(
        constraints: MediaConstraints,
    ): SessionDescription = suspendCancellableCoroutine { continuation ->
        createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }

                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            },
            constraints,
        )
    }

    private suspend fun PeerConnection.setLocalDescriptionSuspended(
        description: SessionDescription,
    ): Unit = suspendCancellableCoroutine { continuation ->
        setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                }

                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
            description,
        )
    }

    private suspend fun PeerConnection.setRemoteDescriptionSuspended(
        description: SessionDescription,
    ): Unit = suspendCancellableCoroutine { continuation ->
        setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                }

                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
            description,
        )
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, LogRedactor.redact(message))
        } else {
            Log.i(TAG, "call event: <redacted>")
        }
    }

    private fun short(id: String): String = id.take(8)

    companion object {
        private const val TAG = "NEXCHAT_CALL"
        private const val RING_TIMEOUT_MILLIS = 45_000L
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
        private const val DISCONNECT_TIMEOUT_MILLIS = 15_000L
        private const val END_SCREEN_MILLIS = 3_500L
        private const val CALL_SIGNAL_POLL_MILLIS = 1_000L
        // Must stay below the caller's 45 s ring timeout. Otherwise a queued
        // offer can pop the incoming-call screen after the caller has already
        // given up, and Accept has nothing to connect to.
        private const val MAX_OFFER_AGE_MILLIS = 35_000L
        private const val MAX_BUFFERED_CANDIDATES = 80
        private const val MAX_PENDING_CALL_IDS = 4
        private const val MAX_CANDIDATE_CHARS = 2_048
        private const val ICE_CACHE_MILLIS = 30L * 1000L
        private const val DEFAULT_STUN = "stun:stun.l.google.com:19302"
        private const val VIDEO_CAPTURE_THREAD = "NEXCHAT_CAMERA"
        private const val VIDEO_CAPTURE_WIDTH = 1280
        private const val VIDEO_CAPTURE_HEIGHT = 720
        private const val VIDEO_CAPTURE_FPS = 30

        @Volatile private var webRtcInitialized = false

        private fun initializeWebRtc(context: Context) {
            if (webRtcInitialized) return
            synchronized(this) {
                if (webRtcInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                webRtcInitialized = true
            }
        }
    }
}
