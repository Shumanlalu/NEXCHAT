package com.nexchat.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.nexchat.app.core.crypto.ContentKind
import com.nexchat.app.core.crypto.SecureContent
import com.nexchat.app.core.db.CallHistoryEntity
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.IceServerDto
import com.nexchat.app.data.CallSignalEvent
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.service.NotificationHelper
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
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
    val endReason: String? = null,
)

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
) {
    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    private val lock = Any()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var audioModule: JavaAudioDeviceModule? = null
    @Volatile private var connection: PeerConnection? = null
    @Volatile private var audioSource: AudioSource? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var incomingOffer: String? = null
    // Once a particular peer device answers, signals from the user's other
    // devices (for example a missed-call timeout on a tablet that kept
    // ringing) must not be allowed to tear down the connected call.
    @Volatile private var remoteDeviceId: Int? = null
    @Volatile private var speakerOn = false
    @Volatile private var historyRecorded = false

    private var ringJob: Job? = null
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private val remoteCandidates =
        mutableMapOf<String, MutableList<Pair<Int, IceCandidate>>>()
    private val recentCalls = LinkedHashSet<String>()
    private var cachedIce: Pair<Long, List<PeerConnection.IceServer>>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null

    fun startOutgoing(
        conversationId: String,
        peerNexId: String,
        peerName: String,
        peerAvatar: String?,
    ) {
        if (!hasMicrophonePermission()) {
            throw IllegalStateException("Microphone permission is required for calls")
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
            _state.value = call
        }
        notifications.cancelIncomingCall()
        configureAudio()
        launchCallActivity(call.callId, autoAccept = false)
        scope.launch { sendOffer(call) }
    }

    fun accept(callId: String) {
        val call = synchronized(lock) {
            val current = _state.value
            if (current == null || current.callId != callId || current.outgoing ||
                current.status != CallStatus.RINGING
            ) {
                return
            }
            val updated = current.copy(status = CallStatus.CONNECTING)
            _state.value = updated
            updated
        }
        notifications.cancelIncomingCall()
        if (!hasMicrophonePermission()) {
            declineForPermission(callId)
            return
        }
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
        notifications.cancelIncomingCall()
        scope.launch(Dispatchers.IO) {
            runCatching {
                chat.sendCallSignal(
                    call.conversationId,
                    callContent(ContentKind.CALL_REJECT, call.callId, reason = tag),
                    durable = true,
                )
            }
        }
        finishCall(call.callId, label, tag)
    }

    fun declineForPermission(callId: String) {
        val call = _state.value ?: return
        if (call.callId != callId) return
        notifications.cancelIncomingCall()
        scope.launch(Dispatchers.IO) {
            runCatching {
                chat.sendCallSignal(
                    call.conversationId,
                    callContent(ContentKind.CALL_REJECT, call.callId, reason = "declined"),
                    durable = true,
                )
            }
        }
        finishCall(callId, "Microphone permission is required for calls", "declined")
    }

    fun hangup() {
        val call = _state.value ?: return
        if (call.status == CallStatus.ENDED) return
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
        scope.launch(Dispatchers.IO) {
            runCatching {
                chat.sendCallSignal(
                    call.conversationId,
                    callContent(ContentKind.CALL_HANGUP, call.callId, reason = tag),
                    durable = true,
                )
            }
        }
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

    fun clearEnded(callId: String) {
        synchronized(lock) {
            if (_state.value?.callId == callId && _state.value?.status == CallStatus.ENDED) {
                _state.value = null
            }
        }
    }

    fun onSignal(event: CallSignalEvent) {
        when (event) {
            is CallSignalEvent.Offer -> scope.launch { handleOffer(event) }
            is CallSignalEvent.Answer -> handleAnswer(event)
            is CallSignalEvent.Ice -> handleIce(event)
            is CallSignalEvent.Ended -> handleRemoteEnded(event)
            is CallSignalEvent.Rejected -> handleRejected(event)
        }
    }

    private suspend fun sendOffer(call: CallUiState) = withContext(Dispatchers.IO) {
        try {
            val peer = createPeerConnection(call.callId)
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            val offer = peer.createOfferSuspended(constraints)
            peer.setLocalDescriptionSuspended(offer)
            if (_state.value?.callId != call.callId) return@withContext
            chat.sendCallSignal(
                call.conversationId,
                SecureContent(
                    kind = ContentKind.CALL_OFFER,
                    clientId = UUID.randomUUID().toString(),
                    callId = call.callId,
                    sdp = offer.description,
                    sdpType = "offer",
                ),
                durable = true,
            )
            startRingTimeout(call.callId)
        } catch (error: Exception) {
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
            remoteDescriptionSet = true
            flushRemoteCandidates(call.callId)
            val answer = peer.createAnswerSuspended(MediaConstraints())
            peer.setLocalDescriptionSuspended(answer)
            chat.sendCallSignal(
                call.conversationId,
                SecureContent(
                    kind = ContentKind.CALL_ANSWER,
                    clientId = UUID.randomUUID().toString(),
                    callId = call.callId,
                    sdp = answer.description,
                    sdpType = "answer",
                ),
                durable = true,
            )
            startConnectTimeout(call.callId)
        } catch (error: Exception) {
            failCall(call.callId, error.message ?: "Could not answer the call")
        }
    }

    private suspend fun handleOffer(event: CallSignalEvent.Offer) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (event.sentAt > 0 && now - event.sentAt > MAX_OFFER_AGE_MILLIS) return@withContext
        synchronized(lock) {
            if (recentCalls.contains(event.callId)) return@withContext
        }
        val current = _state.value
        if (current != null) {
            if (current.callId != event.callId) sendBusyReject(event)
            return@withContext
        }
        if (chat.isBlocked(event.fromNexId)) return@withContext

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
        )
        synchronized(lock) {
            if (_state.value != null || recentCalls.contains(event.callId)) return@withContext
            historyRecorded = false
            incomingOffer = event.sdp
            remoteDescriptionSet = false
            remoteDeviceId = event.fromSignalDeviceId
            // Drop candidates buffered for call IDs that never produced an
            // offer we accepted, so they cannot occupy the pending-call cap.
            remoteCandidates.keys.retainAll(setOf(event.callId))
            remoteCandidates[event.callId] = remoteCandidates[event.callId] ?: mutableListOf()
            _state.value = call
        }
        notifications.showIncomingCall(call.callId, call.peerName)
        launchCallActivity(call.callId, autoAccept = false)
        startIncomingRingTimeout(call.callId)
    }

    private fun handleAnswer(event: CallSignalEvent.Answer) {
        synchronized(lock) {
            val call = _state.value ?: return
            if (!call.outgoing || call.callId != event.callId || call.peerNexId != event.fromNexId) return
            if (call.status != CallStatus.RINGING && call.status != CallStatus.CONNECTING) return
            if (remoteDescriptionSet) return
            val answeredDevice = remoteDeviceId
            if (answeredDevice != null && answeredDevice != event.fromSignalDeviceId) return
            remoteDeviceId = event.fromSignalDeviceId
        }
        scope.launch(Dispatchers.IO) {
            try {
                val peer = connection ?: return@launch
                peer.setRemoteDescriptionSuspended(
                    SessionDescription(SessionDescription.Type.ANSWER, event.sdp),
                )
                remoteDescriptionSet = true
                synchronized(lock) {
                    val current = _state.value
                    if (current?.callId == event.callId) {
                        _state.value = current.copy(status = CallStatus.CONNECTING)
                    }
                }
                flushRemoteCandidates(event.callId)
                startConnectTimeout(event.callId)
            } catch (error: Exception) {
                failCall(event.callId, error.message ?: "Could not connect the call")
            }
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
        if (activeDevice != null && activeDevice != event.fromSignalDeviceId) return
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
        if (peer != null) runCatching { peer.addIceCandidate(candidate) }
    }

    private fun handleRemoteEnded(event: CallSignalEvent.Ended) {
        val call = _state.value ?: return
        if (call.callId != event.callId || call.peerNexId != event.fromNexId) return
        val activeDevice = remoteDeviceId
        if (activeDevice != null && activeDevice != event.fromSignalDeviceId) return
        val connected = call.connectedAt != null
        val label: String
        val tag: String
        when {
            connected -> {
                label = "Call ended"
                tag = "completed"
            }
            call.outgoing && (event.reason == "missed" || event.reason == "no_answer") -> {
                label = "No answer"
                tag = "no_answer"
            }
            call.outgoing -> {
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
        val call = _state.value ?: return
        if (call.callId != event.callId || call.peerNexId != event.fromNexId) return
        val activeDevice = remoteDeviceId
        if (activeDevice != null && activeDevice != event.fromSignalDeviceId) return
        val (label, tag) = when (event.reason) {
            "busy" -> "Contact is on another call" to "busy"
            "declined" -> "Call declined" to "declined"
            else -> "Call declined" to "declined"
        }
        finishCall(call.callId, label, tag)
    }

    private fun sendBusyReject(event: CallSignalEvent.Offer) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val conversation = chat.openDirectConversation(event.fromNexId, event.displayName)
                chat.sendCallSignal(
                    conversation.id,
                    callContent(ContentKind.CALL_REJECT, event.callId, reason = "busy"),
                    durable = true,
                )
            }
        }
    }

    private fun callContent(kind: String, callId: String, reason: String? = null): SecureContent =
        SecureContent(
            kind = kind,
            clientId = UUID.randomUUID().toString(),
            callId = callId,
            callEndReason = reason,
        )

    private fun startRingTimeout(callId: String) {
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            val call = _state.value
            if (call?.callId == callId && call.outgoing &&
                call.status != CallStatus.CONNECTED && call.status != CallStatus.ENDED
            ) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        chat.sendCallSignal(
                            call.conversationId,
                            callContent(ContentKind.CALL_HANGUP, call.callId, reason = "no_answer"),
                            durable = true,
                        )
                    }
                }
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
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        chat.sendCallSignal(
                            call.conversationId,
                            callContent(ContentKind.CALL_HANGUP, call.callId, reason = "missed"),
                            durable = true,
                        )
                    }
                }
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
    }

    private fun failCall(callId: String, message: String) {
        val call = _state.value
        if (call?.callId == callId && call.status != CallStatus.ENDED) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    chat.sendCallSignal(
                        call.conversationId,
                        callContent(ContentKind.CALL_HANGUP, call.callId, reason = "failed"),
                        durable = true,
                    )
                }
            }
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
            current.copy(status = CallStatus.ENDED, endReason = label, muted = false)
                .also { _state.value = it }
        }
        cancelTimers()
        notifications.cancelIncomingCall()
        scope.launch(Dispatchers.Default) {
            cleanupMedia()
            endAudio()
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
        val body = when {
            connected -> "Voice call · ${durationLabel(seconds)}"
            call.outgoing && tag == "declined" -> "Call declined"
            call.outgoing && tag == "busy" -> "Contact was on another call"
            call.outgoing && tag == "no_answer" -> "No answer"
            call.outgoing && tag == "failed" -> "Call failed"
            call.outgoing && tag == "canceled" -> "Outgoing call"
            !call.outgoing && tag == "declined" -> "Declined call"
            !call.outgoing && tag == "failed" -> "Missed call"
            !call.outgoing -> "Missed call"
            else -> "Voice call"
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
                audioManager.requestAudioFocus(request)
                focusRequest = request
            }
        }
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
    }

    private fun updateProximity() {
        val active = _state.value?.status == CallStatus.CONNECTED && !speakerOn
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

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cachedIce?.let { (cachedAt, servers) ->
                if (now - cachedAt < ICE_CACHE_MILLIS && servers.isNotEmpty()) return servers
            }
        }
        val dtos = runCatching { api.serverConfig().iceServers }
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
        val factory = ensureFactory()
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (_state.value?.callId != callId) return
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED,
                    -> markConnected(callId)
                    PeerConnection.IceConnectionState.DISCONNECTED -> scheduleDisconnectFailure(callId)
                    PeerConnection.IceConnectionState.FAILED ->
                        failCall(callId, "The call connection was lost")
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIce(callId, candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (_state.value?.callId != callId) return
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> markConnected(callId)
                    PeerConnection.PeerConnectionState.FAILED ->
                        failCall(callId, "The call could not connect")
                    else -> Unit
                }
            }
        }
        val peer = factory.createPeerConnection(config, MediaConstraints(), observer)
            ?: throw IllegalStateException("Could not create the call connection")
        synchronized(lock) { connection = peer }
        try {
            val source = factory.createAudioSource(MediaConstraints())
            val track = factory.createAudioTrack("nexchat_audio", source)
            track.setEnabled(true)
            peer.addTrack(track, listOf("nexchat_stream"))
            synchronized(lock) {
                audioSource = source
                audioTrack = track
            }
        } catch (error: Exception) {
            synchronized(lock) { connection = null }
            runCatching { peer.close() }
            runCatching { peer.dispose() }
            throw error
        }
        return peer
    }

    private fun sendIce(callId: String, candidate: IceCandidate) {
        val call = _state.value ?: return
        if (call.callId != callId || call.status == CallStatus.ENDED) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                chat.sendCallSignal(
                    call.conversationId,
                    SecureContent(
                        kind = ContentKind.CALL_ICE,
                        clientId = UUID.randomUUID().toString(),
                        callId = call.callId,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                    durable = true,
                )
            }
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
        candidates.forEach { candidate ->
            runCatching { peer.addIceCandidate(candidate) }
        }
    }

    private fun cancelTimers() {
        ringJob?.cancel()
        connectJob?.cancel()
        disconnectJob?.cancel()
        ringJob = null
        connectJob = null
        disconnectJob = null
    }

    private fun cleanupMedia() {
        cancelTimers()
        val peer: PeerConnection?
        val source: AudioSource?
        val track: AudioTrack?
        val factoryRef: PeerConnectionFactory?
        val module: JavaAudioDeviceModule?
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
            remoteCandidates.clear()
            incomingOffer = null
            remoteDescriptionSet = false
            remoteDeviceId = null
        }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { factoryRef?.dispose() }
        runCatching { module?.release() }
        releaseProximity()
    }

    private fun ensureFactory(): PeerConnectionFactory {
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
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(module)
                    .createPeerConnectionFactory()
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

    companion object {
        private const val RING_TIMEOUT_MILLIS = 45_000L
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
        private const val DISCONNECT_TIMEOUT_MILLIS = 15_000L
        private const val END_SCREEN_MILLIS = 1_900L
        private const val MAX_OFFER_AGE_MILLIS = 90_000L
        private const val MAX_BUFFERED_CANDIDATES = 80
        private const val MAX_PENDING_CALL_IDS = 4
        private const val MAX_CANDIDATE_CHARS = 2_048
        private const val ICE_CACHE_MILLIS = 10L * 60L * 1000L
        private const val DEFAULT_STUN = "stun:stun.l.google.com:19302"

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
