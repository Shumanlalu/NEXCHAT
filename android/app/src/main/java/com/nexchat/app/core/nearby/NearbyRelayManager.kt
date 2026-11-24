package com.nexchat.app.core.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.nexchat.app.core.crypto.SignalCryptoManager
import com.nexchat.app.core.crypto.canonicalSignalDeviceId
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.db.ProcessedEnvelopeEntity
import com.nexchat.app.core.db.RelayPacketEntity
import com.nexchat.app.core.network.EnvelopeDto
import com.nexchat.app.core.network.KeyBundle
import com.nexchat.app.core.network.OutgoingEnvelope
import com.nexchat.app.core.security.LogRedactor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class LocalIdentity(
    val nexId: String,
    val deviceId: String,
    val signalDeviceId: Int,
    val deviceName: String?,
)

private class NearbyConnection(
    val address: String,
    val socket: BluetoothSocket,
) {
    private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
    private val writeMutex = Mutex()

    @Volatile var nexId: String? = null

    @Volatile var signalDeviceId: Int? = null

    @Volatile var deviceName: String? = null

    @Volatile var keyBundle: KeyBundle? = null

    suspend fun write(frame: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            writer.write(frame)
            writer.flush()
        }
    }
}

/**
 * Bluetooth Classic (RFCOMM) mesh transport. Frames are newline-delimited JSON.
 * The routing header is Signal-encrypted for each immediate neighbour, while
 * the inner envelope ciphertext stays end-to-end encrypted for the destination.
 */
class NearbyRelayManager(
    private val context: Context,
    private val crypto: SignalCryptoManager,
    private val db: NexDatabase,
    private val onIncoming: suspend (EnvelopeDto) -> Boolean,
    private val onDelivered: suspend (String, Long) -> Unit,
    private val onFailed: suspend (String, String) -> Unit,
) {
    private val _events = MutableSharedFlow<NearbyDeliveryEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NearbyDeliveryEvent> = _events.asSharedFlow()

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile private var runtime: CoroutineScope? = null

    @Volatile private var adapter: BluetoothAdapter? = null

    @Volatile private var serverSocket: BluetoothServerSocket? = null

    @Volatile private var discoveryJob: Job? = null

    @Volatile private var sweepJob: Job? = null

    @Volatile private var discoveryReceiver: BroadcastReceiver? = null

    @Volatile private var cachedLocalNexId: String? = null

    @Volatile private var lastDiscoveryAt = 0L

    private val connectionLock = Any()
    private val connections = LinkedHashMap<String, NearbyConnection>()
    private val dialing = HashSet<String>()
    private val discovered = HashSet<String>()
    private val clientIds = ConcurrentHashMap<String, String>()
    private val rateLimiter = NearbyPolicy.RateLimiter()

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    @Synchronized
    fun start() {
        if (runtime != null) return
        _enabled.value = false
        if (!hasPermissions()) {
            log("Nearby disabled: required permissions are missing")
            return
        }
        try {
            val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (bluetooth == null || !bluetooth.isEnabled) {
                log("Nearby disabled: Bluetooth adapter unavailable")
                return
            }
            val handler = CoroutineExceptionHandler { _, error ->
                log("Nearby transport error: ${error.javaClass.simpleName}")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
            rateLimiter.reset()
            adapter = bluetooth
            runtime = scope
            _enabled.value = true
            registerDiscoveryReceiver()
            scope.launch { acceptConnections() }
            sweepJob = scope.launch { sweepLoop() }
            scheduleDiscovery(force = true)
        } catch (error: SecurityException) {
            _enabled.value = false
            log("Nearby disabled: Bluetooth permission error")
        } catch (error: Exception) {
            _enabled.value = false
            log("Nearby disabled: ${error.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop() {
        val scope = runtime ?: return
        runtime = null
        _enabled.value = false
        discoveryJob?.cancel()
        discoveryJob = null
        sweepJob?.cancel()
        sweepJob = null
        scope.cancel()
        unregisterDiscoveryReceiver()
        cancelDiscoveryQuietly()
        val open = synchronized(connectionLock) {
            val values = connections.values.toList()
            connections.clear()
            dialing.clear()
            discovered.clear()
            values
        }
        open.forEach(::closeConnection)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Already closed.
        } catch (_: Exception) {
            // Best effort shutdown.
        }
        serverSocket = null
        updatePeerSnapshot()
    }

    fun syncNow() {
        val scope = runtime ?: return
        if (!_enabled.value || !hasPermissions()) return
        scope.launch {
            try {
                sweepExpiredPackets()
                retryStoredPackets()
            } catch (error: Exception) {
                log("Nearby sync error: ${error.javaClass.simpleName}")
            }
        }
        scheduleDiscovery(force = false)
    }

    suspend fun enqueue(envelopes: List<OutgoingEnvelope>, localNexId: String): List<NearbyAccepted> =
        withContext(Dispatchers.IO) {
            if (!_enabled.value || !hasPermissions()) return@withContext emptyList()
            if (localNexId.isNotBlank()) cachedLocalNexId = localNexId
            val identity = localIdentity()
            if (identity.nexId.isBlank()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val accepted = ArrayList<NearbyAccepted>(envelopes.size)
            for (envelope in envelopes) {
                if (envelope.recipientNexId.isBlank() || envelope.ciphertext.isBlank()) continue
                val packetId = "nb-" + UUID.randomUUID().toString().replace("-", "")
                val ttlSeconds = envelope.expiresInSeconds
                    .coerceIn(1, NearbyPolicy.MAX_PACKET_TTL_SECONDS)
                    .toLong()
                val payload = NearbyRoutedPayload(
                    packetId = packetId,
                    type = NearbyProtocol.TYPE_MESSAGE,
                    targetNexId = envelope.recipientNexId,
                    targetSignalDeviceId = canonicalSignalDeviceId(envelope.recipientSignalDeviceId),
                    originNexId = identity.nexId,
                    originSignalDeviceId = identity.signalDeviceId,
                    clientId = envelope.clientId,
                    groupId = envelope.groupId,
                    innerCipherType = envelope.cipherType,
                    innerCiphertext = envelope.ciphertext,
                    createdAt = now,
                    expiresAt = now + ttlSeconds * 1000L,
                    hopCount = 0,
                    maxHops = NearbyPolicy.DEFAULT_MAX_HOPS,
                )
                if (!NearbyPolicy.shouldProcessPacket(payload, alreadyProcessed = false, now = now)) continue
                if (NearbyFrameCodec.encodeRouting(payload) == null) continue
                val stored = storeAndForward(
                    payload = payload,
                    senderAddress = null,
                    fromLocal = true,
                    increment = false,
                )
                if (stored) {
                    accepted += NearbyAccepted(
                        clientId = envelope.clientId,
                        recipientNexId = envelope.recipientNexId,
                        recipientSignalDeviceId = envelope.recipientSignalDeviceId,
                    )
                }
            }
            accepted
        }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun scheduleDiscovery(force: Boolean) {
        val scope = runtime ?: return
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (!force && now - lastDiscoveryAt < NearbyPolicy.DISCOVERY_MIN_INTERVAL_MS) return
            if (discoveryJob?.isActive == true) return
            lastDiscoveryAt = now
            discoveryJob = scope.launch { performDiscovery() }
        }
    }

    /** One short discovery window; never a continuous scan. */
    private suspend fun performDiscovery() {
        if (!hasPermissions()) return
        val bluetooth = adapter ?: return
        synchronized(connectionLock) { discovered.clear() }
        try {
            cancelDiscoveryQuietly()
            if (bluetooth.startDiscovery()) {
                delay(NearbyPolicy.DISCOVERY_WINDOW_MS)
            }
        } catch (error: SecurityException) {
            log("Nearby discovery skipped: permission error")
        } catch (error: Exception) {
            log("Nearby discovery error: ${error.javaClass.simpleName}")
        } finally {
            cancelDiscoveryQuietly()
        }
        val found = synchronized(connectionLock) { discovered.toList().distinct() }
        val discoveredDevices = found.mapNotNull { address ->
            try {
                bluetooth.getRemoteDevice(address)
            } catch (_: Exception) {
                null
            }
        }
        val bondedDevices = try {
            bluetooth.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (device in (discoveredDevices + bondedDevices).distinctBy(::safeAddress)) {
            if (runtime?.isActive != true) break
            dialDevice(device)
        }
    }

    private fun registerDiscoveryReceiver() {
        if (discoveryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @SuppressLint("MissingPermission")
                    @Suppress("DEPRECATION")
                    val legacy: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    legacy
                }
                val address = device?.let(::safeAddress) ?: return
                synchronized(connectionLock) { discovered += address }
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            discoveryReceiver = receiver
        } catch (error: Exception) {
            log("Nearby discovery receiver unavailable")
        }
    }

    private fun unregisterDiscoveryReceiver() {
        val receiver = discoveryReceiver ?: return
        discoveryReceiver = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // Already unregistered.
        }
    }

    private fun cancelDiscoveryQuietly() {
        if (!hasPermissions()) return
        try {
            val bluetooth = adapter
            if (bluetooth?.isDiscovering == true) bluetooth.cancelDiscovery()
        } catch (_: SecurityException) {
            log("Nearby discovery cancel skipped: permission error")
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private suspend fun acceptConnections() {
        val bluetooth = adapter ?: return
        val server = try {
            bluetooth.listenUsingRfcommWithServiceRecord(
                NearbyProtocol.SERVICE_NAME,
                NearbyProtocol.SERVICE_UUID,
            )
        } catch (error: SecurityException) {
            log("Nearby server unavailable: permission error")
            _enabled.value = false
            return
        } catch (error: Exception) {
            log("Nearby server unavailable: ${error.javaClass.simpleName}")
            _enabled.value = false
            return
        }
        serverSocket = server
        try {
            while (runtime?.isActive == true) {
                val socket = withContext(Dispatchers.IO) { server.accept() } ?: break
                val address = safeAddress(socket.remoteDevice)
                if (address == null) {
                    closeSocket(socket)
                    continue
                }
                val connection = NearbyConnection(address, socket)
                var register = false
                synchronized(connectionLock) {
                    if (!connections.containsKey(address) && !dialing.contains(address)) {
                        connections[address] = connection
                        register = true
                    }
                }
                if (register) registerConnection(connection) else closeSocket(socket)
            }
        } catch (_: IOException) {
            // Server socket closed during stop().
        } catch (error: SecurityException) {
            log("Nearby accept failed: permission error")
        } catch (error: Exception) {
            log("Nearby accept error: ${error.javaClass.simpleName}")
        } finally {
            try {
                server.close()
            } catch (_: Exception) {
                // Already closed.
            }
            if (serverSocket === server) serverSocket = null
        }
    }

    private suspend fun dialDevice(device: BluetoothDevice) {
        val address = safeAddress(device) ?: return
        val shouldDial = synchronized(connectionLock) {
            if (!connections.containsKey(address) && dialing.add(address)) true else false
        }
        if (!shouldDial) return
        try {
            cancelDiscoveryQuietly()
            val socket = withContext(Dispatchers.IO) {
                device.createRfcommSocketToServiceRecord(NearbyProtocol.SERVICE_UUID)
            }
            try {
                withContext(Dispatchers.IO) { socket.connect() }
            } catch (_: IOException) {
                closeSocket(socket)
                return
            }
            val connection = NearbyConnection(address, socket)
            var register = false
            synchronized(connectionLock) {
                if (!connections.containsKey(address)) {
                    connections[address] = connection
                    register = true
                }
            }
            if (register) registerConnection(connection) else closeSocket(socket)
        } catch (_: SecurityException) {
            log("Nearby dial skipped: permission error")
        } catch (_: Exception) {
            log("Nearby dial failed")
        } finally {
            synchronized(connectionLock) { dialing.remove(address) }
        }
    }

    private fun registerConnection(connection: NearbyConnection) {
        val scope = runtime ?: run {
            closeConnection(connection)
            return
        }
        scope.launch {
            try {
                sendHello(connection)
                readFrames(connection)
            } catch (_: CancellationException) {
                // Shutdown.
            } catch (_: IOException) {
                // Peer disconnected.
            } catch (_: SecurityException) {
                log("Nearby link skipped: permission error")
            } catch (error: Exception) {
                log("Nearby link error: ${error.javaClass.simpleName}")
            } finally {
                closeConnection(connection)
                synchronized(connectionLock) {
                    if (connections[connection.address] === connection) {
                        connections.remove(connection.address)
                    }
                    discovered -= connection.address
                }
                updatePeerSnapshot()
            }
        }
    }

    private suspend fun sendHello(connection: NearbyConnection) {
        val identity = localIdentity()
        if (identity.nexId.isBlank()) throw IOException("Local nearby identity unavailable")
        val bundle = crypto.nearbyKeyBundle()
        val frame = NearbyWireFrame.hello(
            NearbyHelloFrame(
                nexId = identity.nexId,
                signalDeviceId = identity.signalDeviceId,
                deviceName = identity.deviceName,
                keyBundle = bundle,
            ),
        )
        val encoded = NearbyFrameCodec.encode(frame) ?: throw IOException("Nearby HELLO frame too large")
        connection.write(encoded)
    }

    private suspend fun readFrames(connection: NearbyConnection) {
        val reader = BufferedReader(InputStreamReader(connection.socket.inputStream, Charsets.UTF_8))
        while (runtime?.isActive == true) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            val bytes = line.toByteArray(Charsets.UTF_8).size
            if (!NearbyPolicy.frameSizeAllowed(bytes)) {
                log("Nearby frame rejected: too large")
                continue
            }
            val key = connection.address.hashCode().toString()
            if (!rateLimiter.allow(key, System.currentTimeMillis())) {
                log("Nearby frame rejected: peer rate limit")
                continue
            }
            val frame = NearbyFrameCodec.decode(line) ?: continue
            when (frame.type) {
                NearbyProtocol.TYPE_HELLO -> {
                    val hello = frame.hello ?: continue
                    handleHello(connection, hello)
                }
                NearbyProtocol.TYPE_MESSAGE, NearbyProtocol.TYPE_ACK -> handleRouted(connection, frame)
                else -> Unit
            }
        }
    }

    private suspend fun handleHello(connection: NearbyConnection, hello: NearbyHelloFrame) {
        val bundle = hello.keyBundle ?: return
        if (hello.nexId.isBlank() || hello.signalDeviceId <= 0) return
        if (canonicalSignalDeviceId(bundle.signalDeviceId) != canonicalSignalDeviceId(hello.signalDeviceId)) return
        connection.nexId = hello.nexId
        connection.signalDeviceId = canonicalSignalDeviceId(hello.signalDeviceId)
        connection.deviceName = hello.deviceName
        connection.keyBundle = bundle
        updatePeerSnapshot()
        // A new HELLO may be the first opportunity to deliver stored packets.
        retryStoredPackets()
    }

    private suspend fun handleRouted(connection: NearbyConnection, frame: NearbyWireFrame) {
        val ciphertext = frame.routingCiphertext ?: return
        val cipherType = frame.routingCipherType ?: return
        val senderNexId = connection.nexId ?: return
        val senderSignalDeviceId = connection.signalDeviceId ?: return
        val identity = localIdentity()
        val now = System.currentTimeMillis()
        val wrapper = EnvelopeDto(
            id = "nearby-routing",
            senderNexId = senderNexId,
            senderDeviceId = "nearby",
            senderSignalDeviceId = senderSignalDeviceId,
            recipientDeviceId = identity.deviceId,
            recipientSignalDeviceId = identity.signalDeviceId,
            clientId = "nearby-routing",
            cipherType = cipherType,
            ciphertext = ciphertext,
            createdAt = iso(now),
            expiresAt = iso(now + 60_000L),
        )
        val header = try {
            crypto.decryptText(wrapper)
        } catch (error: Exception) {
            log("Nearby routing header rejected")
            return
        }
        val payload = NearbyFrameCodec.decodeRouting(header) ?: return
        val dedupeId = NearbyPolicy.dedupeKey(payload)
        val processed = db.processedEnvelopeDao().get(dedupeId) != null
        if (!NearbyPolicy.shouldProcessPacket(payload, processed, now)) return
        if (payload.targetNexId == identity.nexId) {
            if (payload.type == NearbyProtocol.TYPE_ACK) {
                handleAck(payload)
            } else {
                deliverLocally(payload, identity)
            }
        } else {
            db.processedEnvelopeDao().insert(ProcessedEnvelopeEntity(dedupeId, now))
            storeAndForward(
                payload = payload,
                senderAddress = connection.address,
                fromLocal = false,
                increment = true,
            )
        }
    }

    private suspend fun deliverLocally(payload: NearbyRoutedPayload, identity: LocalIdentity) {
        val envelope = EnvelopeDto(
            id = payload.packetId,
            senderNexId = payload.originNexId,
            senderDeviceId = "nearby",
            senderSignalDeviceId = payload.originSignalDeviceId,
            recipientDeviceId = identity.deviceId,
            recipientSignalDeviceId = identity.signalDeviceId,
            groupId = payload.groupId,
            clientId = payload.clientId,
            cipherType = payload.innerCipherType,
            // The inner ciphertext is passed through byte-for-byte; only the
            // final recipient's Signal session decrypts it.
            ciphertext = payload.innerCiphertext.orEmpty(),
            createdAt = iso(payload.createdAt),
            expiresAt = iso(payload.expiresAt),
            ephemeral = false,
        )
        val handled = try {
            onIncoming(envelope)
        } catch (_: Exception) {
            false
        }
        val alreadyProcessed = db.processedEnvelopeDao().get(payload.packetId) != null
        db.relayDao().deleteByPacketId(payload.packetId)
        clientIds.remove(payload.packetId)
        if (handled || alreadyProcessed) {
            db.processedEnvelopeDao().insert(
                ProcessedEnvelopeEntity(payload.packetId, System.currentTimeMillis()),
            )
            sendAck(payload, identity)
        } else {
            reportFailure(payload.clientId, "Nearby envelope was rejected by the recipient")
        }
    }

    private suspend fun sendAck(payload: NearbyRoutedPayload, identity: LocalIdentity) {
        val at = System.currentTimeMillis()
        val ack = NearbyPolicy.ackCopy(payload, identity.nexId, identity.signalDeviceId, at)
        if (NearbyPolicy.isExpired(ack.expiresAt, at)) return
        db.processedEnvelopeDao().insert(ProcessedEnvelopeEntity(NearbyPolicy.dedupeKey(ack), at))
        for (peer in NearbyPolicy.ackTargets(ack, currentPeers(), excludedAddress = null)) {
            sendRouted(ack, peer)
        }
    }

    private suspend fun handleAck(payload: NearbyRoutedPayload) {
        val at = payload.ackAt ?: System.currentTimeMillis()
        db.processedEnvelopeDao().insert(
            ProcessedEnvelopeEntity(NearbyPolicy.dedupeKey(payload), System.currentTimeMillis()),
        )
        db.relayDao().deleteByPacketId(payload.packetId)
        clientIds.remove(payload.packetId)
        try {
            onDelivered(payload.clientId, at)
        } catch (_: Exception) {
            log("Nearby delivery callback failed")
        }
        _events.tryEmit(NearbyDeliveryEvent.Delivered(payload.clientId, at))
    }

    /** Stores a packet, then re-wraps the unchanged inner ciphertext for neighbours. */
    private suspend fun storeAndForward(
        payload: NearbyRoutedPayload,
        senderAddress: String?,
        fromLocal: Boolean,
        increment: Boolean,
    ): Boolean {
        val now = System.currentTimeMillis()
        val outbound = if (increment) NearbyPolicy.relayCopy(payload) else payload
        if (!NearbyPolicy.canRelay(outbound, now)) {
            reportFailure(payload.clientId, "Nearby packet exceeded its hop or time limit")
            return false
        }
        if (!boundStorage(now)) return false
        clientIds[payload.packetId] = payload.clientId
        db.processedEnvelopeDao().insert(ProcessedEnvelopeEntity(NearbyPolicy.dedupeKey(payload), now))
        db.relayDao().upsert(
            RelayPacketEntity(
                id = NearbyPolicy.storageId(payload.packetId, payload.clientId, fromLocal),
                packetId = payload.packetId,
                targetNexId = outbound.targetNexId,
                targetSignalDeviceId = outbound.targetSignalDeviceId,
                originNexId = outbound.originNexId,
                originSignalDeviceId = outbound.originSignalDeviceId,
                groupId = outbound.groupId,
                cipherType = outbound.innerCipherType,
                ciphertext = outbound.innerCiphertext.orEmpty(),
                createdAt = outbound.createdAt,
                expiresAt = outbound.expiresAt,
                hopCount = outbound.hopCount,
                maxHops = outbound.maxHops,
                receivedAt = now,
            ),
        )
        var sent = 0
        for (peer in NearbyPolicy.relayTargets(outbound, currentPeers(), senderAddress)) {
            if (sendRouted(outbound, peer)) sent++
        }
        if (sent > 0) _events.tryEmit(NearbyDeliveryEvent.Relayed(payload.packetId, outbound.hopCount))
        return true
    }

    private suspend fun boundStorage(now: Long): Boolean {
        expireStoredPackets(now)
        while (db.relayDao().activeCount(now) >= NearbyPolicy.MAX_STORED_PACKETS) {
            val oldest = db.relayDao().active(now, NearbyPolicy.MAX_STORED_PACKETS).firstOrNull()
                ?: return true
            val clientId = clientIds[oldest.packetId]
                ?: NearbyPolicy.clientIdFromStorageId(oldest.id, oldest.packetId)
            db.relayDao().deleteByPacketId(oldest.packetId)
            clientIds.remove(oldest.packetId)
            reportFailure(clientId, "Nearby storage limit reached; packet was dropped")
        }
        return true
    }

    private suspend fun sendRouted(payload: NearbyRoutedPayload, peer: NearbyPeer): Boolean {
        val bundle = peer.keyBundle ?: return false
        val header = NearbyFrameCodec.encodeRouting(payload) ?: return false
        val wrapped = try {
            crypto.encryptText(header, peer.nexId, peer.signalDeviceId, bundle)
        } catch (_: Exception) {
            log("Nearby routing header could not be encrypted")
            return false
        }
        val wire = if (payload.type == NearbyProtocol.TYPE_ACK) {
            NearbyWireFrame.ack(wrapped.ciphertext, wrapped.cipherType)
        } else {
            NearbyWireFrame.routed(wrapped.ciphertext, wrapped.cipherType)
        }
        val frame = NearbyFrameCodec.encode(wire) ?: return false
        val connection = synchronized(connectionLock) { connections[peer.address] } ?: return false
        return try {
            connection.write(frame)
            true
        } catch (_: Exception) {
            log("Nearby frame could not be written")
            false
        }
    }

    private suspend fun retryStoredPackets() {
        val peers = currentPeers()
        if (peers.isEmpty()) return
        for (row in db.relayDao().active(System.currentTimeMillis(), NearbyPolicy.MAX_STORED_PACKETS)) {
            val payload = NearbyRoutedPayload(
                packetId = row.packetId,
                type = NearbyProtocol.TYPE_MESSAGE,
                targetNexId = row.targetNexId,
                targetSignalDeviceId = row.targetSignalDeviceId,
                originNexId = row.originNexId,
                originSignalDeviceId = row.originSignalDeviceId,
                clientId = clientIds[row.packetId]
                    ?: NearbyPolicy.clientIdFromStorageId(row.id, row.packetId),
                groupId = row.groupId,
                innerCipherType = row.cipherType,
                innerCiphertext = row.ciphertext,
                createdAt = row.createdAt,
                expiresAt = row.expiresAt,
                hopCount = row.hopCount,
                maxHops = row.maxHops,
            )
            for (peer in NearbyPolicy.relayTargets(payload, peers, excludedAddress = null)) {
                sendRouted(payload, peer)
            }
        }
    }

    private suspend fun sweepLoop() {
        while (runtime?.isActive == true) {
            delay(30_000L)
            if (runtime?.isActive != true) break
            try {
                sweepExpiredPackets()
            } catch (error: Exception) {
                log("Nearby sweep error: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun sweepExpiredPackets() {
        expireStoredPackets(System.currentTimeMillis())
    }

    private suspend fun expireStoredPackets(now: Long) {
        for (row in db.relayDao().active(0L, NearbyPolicy.MAX_STORED_PACKETS * 2)) {
            if (!NearbyPolicy.isExpired(row.expiresAt, now) &&
                NearbyPolicy.withinHopLimit(row.hopCount, row.maxHops)
            ) {
                continue
            }
            val clientId = clientIds[row.packetId]
                ?: NearbyPolicy.clientIdFromStorageId(row.id, row.packetId)
            db.relayDao().deleteByPacketId(row.packetId)
            clientIds.remove(row.packetId)
            reportFailure(clientId, "Nearby packet expired before delivery")
        }
        db.relayDao().purgeExpired(now)
    }

    private suspend fun reportFailure(clientId: String, message: String) {
        try {
            onFailed(clientId, message)
        } catch (_: Exception) {
            log("Nearby failure callback failed")
        }
        _events.tryEmit(NearbyDeliveryEvent.Failed(clientId, message))
    }

    private fun currentPeers(): List<NearbyPeer> = synchronized(connectionLock) {
        connections.values.mapNotNull { connection ->
            val nexId = connection.nexId ?: return@mapNotNull null
            val signalDeviceId = connection.signalDeviceId ?: return@mapNotNull null
            NearbyPeer(
                nexId = nexId,
                signalDeviceId = signalDeviceId,
                deviceName = connection.deviceName,
                address = connection.address,
                connected = true,
                keyBundle = connection.keyBundle,
            )
        }
    }

    private fun updatePeerSnapshot() {
        _peers.value = currentPeers()
    }

    private suspend fun localIdentity(): LocalIdentity = withContext(Dispatchers.IO) {
        val profile = runCatching { db.profileDao().get() }.getOrNull()
        val device = crypto.deviceIdentity()
        LocalIdentity(
            nexId = cachedLocalNexId?.takeIf { it.isNotBlank() } ?: profile?.nexId.orEmpty(),
            deviceId = device.deviceId,
            signalDeviceId = canonicalSignalDeviceId(device.signalDeviceId),
            deviceName = profile?.displayName?.takeIf { it.isNotBlank() },
        )
    }

    private fun safeAddress(device: BluetoothDevice): String? = try {
        device.address
    } catch (_: SecurityException) {
        null
    }

    private fun closeConnection(connection: NearbyConnection) {
        try {
            connection.socket.close()
        } catch (_: IOException) {
            // Already closed.
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private fun closeSocket(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private fun iso(timestamp: Long): String = Instant.ofEpochMilli(timestamp).toString()

    private fun log(message: String) {
        Log.d(TAG, LogRedactor.redact(message))
    }

    private companion object {
        const val TAG = "NearbyRelay"
    }
}
