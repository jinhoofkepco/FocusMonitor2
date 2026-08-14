package io.remotestudy.transport.nearby

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import io.remotestudy.transport.StudyTransport
import io.remotestudy.transport.TransportEvent
import io.remotestudy.transport.TransportListener
import io.remotestudy.transport.TransportRole
import java.io.File

class NearbyStudyTransport(context: Context) : StudyTransport {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val retryHandler = Handler(Looper.getMainLooper())
    private val endpointNames = mutableMapOf<String, String>()
    private val pendingEndpoints = mutableSetOf<String>()
    private val connectedEndpoints = mutableSetOf<String>()
    private val incomingFilePayloads = mutableMapOf<Long, IncomingFilePayload>()
    private val outgoingFilePayloadIds = mutableSetOf<Long>()

    private var listener: TransportListener? = null
    private var role: TransportRole? = null
    private var localDisplayName: String = ""
    private var generation = 0L
    private var active = false
    private var generationCallbacks: GenerationCallbacks? = null
    private var restartRunnable: Runnable? = null
    private var restartAttempt = 0

    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    override fun start(role: TransportRole, displayName: String) {
        stop()
        val startedGeneration = ++generation
        active = true
        this.role = role
        localDisplayName = displayName.take(32)
        generationCallbacks = GenerationCallbacks(startedGeneration)
        emitIfCurrent(startedGeneration, TransportEvent.Searching)
        when (role) {
            TransportRole.TEACHER -> startAdvertising(startedGeneration)
            TransportRole.STUDENT -> startDiscovery(startedGeneration)
        }
    }

    override fun requestConnection(endpointId: String) {
        val callbacks = currentCallbacks() ?: return
        val callbackGeneration = callbacks.generation
        if (!pendingEndpoints.add(endpointId)) return
        client.requestConnection(localDisplayName, endpointId, callbacks.connectionLifecycleCallback)
            .addOnFailureListener { error ->
                if (!isCurrent(callbackGeneration)) return@addOnFailureListener
                pendingEndpoints.remove(endpointId)
                failure(callbackGeneration, "requestConnection", error)
                scheduleRoleRestart(callbackGeneration, TransportRole.STUDENT)
            }
    }

    override fun approve(endpointId: String) {
        val callbacks = currentCallbacks() ?: return
        val callbackGeneration = callbacks.generation
        client.acceptConnection(endpointId, callbacks.payloadCallback)
            .addOnFailureListener { error ->
                failure(callbackGeneration, "acceptConnection", error)
            }
    }

    override fun reject(endpointId: String) {
        val callbackGeneration = currentCallbacks()?.generation ?: return
        client.rejectConnection(endpointId)
            .addOnFailureListener { error ->
                failure(callbackGeneration, "rejectConnection", error)
            }
    }

    override fun send(bytes: ByteArray): Boolean {
        val callbackGeneration = currentCallbacks()?.generation ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES_PAYLOAD || connectedEndpoints.isEmpty()) return false
        client.sendPayload(connectedEndpoints.toList(), Payload.fromBytes(bytes))
            .addOnFailureListener { error -> failure(callbackGeneration, "sendPayload", error) }
        return true
    }

    override fun sendFile(file: File): Long? {
        val callbackGeneration = currentCallbacks()?.generation ?: return null
        if (!file.isFile || connectedEndpoints.isEmpty()) return null
        val payload = try {
            Payload.fromFile(file)
        } catch (error: Exception) {
            failure(callbackGeneration, "sendFile", error)
            return null
        }
        outgoingFilePayloadIds += payload.id
        try {
            client.sendPayload(connectedEndpoints.toList(), payload)
                .addOnFailureListener { error ->
                    val detail = error.message ?: error.javaClass.simpleName
                    retryHandler.post {
                        completeOutgoingFailure(callbackGeneration, payload.id, detail)
                    }
                }
        } catch (error: Exception) {
            outgoingFilePayloadIds.remove(payload.id)
            failure(callbackGeneration, "sendFile", error)
            return null
        }
        return payload.id
    }

    override fun stop() {
        active = false
        generation++
        cancelRestart()
        generationCallbacks = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpointNames.clear()
        pendingEndpoints.clear()
        connectedEndpoints.clear()
        incomingFilePayloads.clear()
        outgoingFilePayloadIds.clear()
        restartAttempt = 0
        role = null
    }

    private fun startAdvertising(callbackGeneration: Long) {
        val callbacks = currentCallbacks(callbackGeneration) ?: return
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(
            localDisplayName,
            SERVICE_ID,
            callbacks.connectionLifecycleCallback,
            options,
        ).addOnSuccessListener {
            if (isCurrent(callbackGeneration)) restartAttempt = 0
        }.addOnFailureListener { error ->
            if (!isCurrent(callbackGeneration)) return@addOnFailureListener
            failure(callbackGeneration, "startAdvertising", error)
            scheduleRoleRestart(callbackGeneration, TransportRole.TEACHER)
        }
    }

    private fun startDiscovery(callbackGeneration: Long) {
        val callbacks = currentCallbacks(callbackGeneration) ?: return
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, callbacks.endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                if (isCurrent(callbackGeneration)) restartAttempt = 0
            }.addOnFailureListener { error ->
                if (!isCurrent(callbackGeneration)) return@addOnFailureListener
                failure(callbackGeneration, "startDiscovery", error)
                scheduleRoleRestart(callbackGeneration, TransportRole.STUDENT)
            }
    }

    private fun scheduleRoleRestart(callbackGeneration: Long, expectedRole: TransportRole) {
        if (!isCurrent(callbackGeneration) || role != expectedRole || connectedEndpoints.isNotEmpty()) return
        if (restartRunnable != null) return
        when (expectedRole) {
            TransportRole.STUDENT -> client.stopDiscovery()
            TransportRole.TEACHER -> client.stopAdvertising()
        }
        val exponent = restartAttempt.coerceAtMost(MAX_RESTART_EXPONENT)
        val delayMs = RESTART_BASE_DELAY_MS * (1L shl exponent)
        restartAttempt = (restartAttempt + 1).coerceAtMost(MAX_RESTART_EXPONENT)
        val retry = Runnable {
            restartRunnable = null
            if (!isCurrent(callbackGeneration) || role != expectedRole || connectedEndpoints.isNotEmpty()) {
                return@Runnable
            }
            emitIfCurrent(callbackGeneration, TransportEvent.Searching)
            when (expectedRole) {
                TransportRole.STUDENT -> startDiscovery(callbackGeneration)
                TransportRole.TEACHER -> startAdvertising(callbackGeneration)
            }
        }
        restartRunnable = retry
        retryHandler.postDelayed(retry, delayMs)
    }

    private fun cancelRestart() {
        restartRunnable?.let(retryHandler::removeCallbacks)
        restartRunnable = null
    }

    private inner class GenerationCallbacks(val generation: Long) {
        val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (!isCurrent(generation)) return
                endpointNames[endpointId] = info.endpointName
                emitIfCurrent(generation, TransportEvent.EndpointFound(endpointId, info.endpointName))
            }

            override fun onEndpointLost(endpointId: String) {
                if (!isCurrent(generation)) return
                endpointNames.remove(endpointId)
                pendingEndpoints.remove(endpointId)
                emitIfCurrent(generation, TransportEvent.EndpointLost(endpointId))
            }
        }

        val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                if (!isCurrent(generation)) return
                endpointNames[endpointId] = info.endpointName
                emitIfCurrent(
                    generation,
                    TransportEvent.PairingRequested(
                        endpointId = endpointId,
                        displayName = info.endpointName,
                        authenticationDigits = info.authenticationDigits,
                    ),
                )
            }

            override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
                if (!isCurrent(generation)) return
                pendingEndpoints.remove(endpointId)
                when (resolution.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        cancelRestart()
                        restartAttempt = 0
                        connectedEndpoints += endpointId
                        client.stopAdvertising()
                        client.stopDiscovery()
                        emitIfCurrent(
                            generation,
                            TransportEvent.Connected(
                                endpointId,
                                endpointNames[endpointId] ?: "상대 기기",
                            ),
                        )
                    }

                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                        emitIfCurrent(
                            generation,
                            TransportEvent.Failure("connection", "상대 기기에서 연결을 거절했습니다."),
                        )
                        if (role == TransportRole.STUDENT) {
                            scheduleRoleRestart(generation, TransportRole.STUDENT)
                        }
                    }

                    else -> {
                        emitIfCurrent(
                            generation,
                            TransportEvent.Failure(
                                "connection",
                                "연결 실패 코드 ${resolution.status.statusCode}",
                            ),
                        )
                        if (role == TransportRole.STUDENT) {
                            scheduleRoleRestart(generation, TransportRole.STUDENT)
                        }
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                if (!isCurrent(generation)) return
                connectedEndpoints.remove(endpointId)
                incomingFilePayloads.entries.removeAll { it.value.endpointId == endpointId }
                outgoingFilePayloadIds.clear()
                emitIfCurrent(generation, TransportEvent.Disconnected(endpointId))
            }
        }

        val payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                if (!isCurrent(generation)) return
                payload.asBytes()?.let { bytes ->
                    emitIfCurrent(generation, TransportEvent.MessageReceived(endpointId, bytes))
                    return
                }
                if (payload.asFile() != null) {
                    incomingFilePayloads[payload.id] = IncomingFilePayload(endpointId, payload)
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                if (!isCurrent(generation)) return
                if (update.payloadId in outgoingFilePayloadIds) {
                    when (update.status) {
                        PayloadTransferUpdate.Status.SUCCESS -> {
                            if (outgoingFilePayloadIds.remove(update.payloadId)) {
                                emitIfCurrent(generation, TransportEvent.FileSent(update.payloadId))
                            }
                        }

                        PayloadTransferUpdate.Status.FAILURE -> completeOutgoingFailure(
                            generation,
                            update.payloadId,
                            "파일 전송에 실패했습니다.",
                        )

                        PayloadTransferUpdate.Status.CANCELED -> completeOutgoingFailure(
                            generation,
                            update.payloadId,
                            "파일 전송이 취소되었습니다.",
                        )
                    }
                    return
                }
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        val incoming = incomingFilePayloads.remove(update.payloadId) ?: return
                        val uri = checkNotNull(incoming.payload.asFile()).asUri().toString()
                        emitIfCurrent(
                            generation,
                            TransportEvent.FileReceived(
                                endpointId = incoming.endpointId,
                                payloadId = update.payloadId,
                                uri = uri,
                            ),
                        )
                    }

                    PayloadTransferUpdate.Status.FAILURE -> {
                        if (incomingFilePayloads.remove(update.payloadId) == null) return
                        emitIfCurrent(
                            generation,
                            TransportEvent.Failure("payloadTransfer", "파일 수신에 실패했습니다."),
                        )
                    }

                    PayloadTransferUpdate.Status.CANCELED -> {
                        if (incomingFilePayloads.remove(update.payloadId) == null) return
                        emitIfCurrent(
                            generation,
                            TransportEvent.Failure("payloadTransfer", "파일 수신이 취소되었습니다."),
                        )
                    }
                }
            }
        }
    }

    private data class IncomingFilePayload(
        val endpointId: String,
        val payload: Payload,
    )

    private fun currentCallbacks(expectedGeneration: Long = generation): GenerationCallbacks? =
        generationCallbacks?.takeIf { active && it.generation == expectedGeneration && generation == expectedGeneration }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        active && generation == expectedGeneration && generationCallbacks?.generation == expectedGeneration

    private fun failure(expectedGeneration: Long, operation: String, error: Exception) {
        emitIfCurrent(
            expectedGeneration,
            TransportEvent.Failure(operation, error.message ?: error.javaClass.simpleName),
        )
    }

    private fun completeOutgoingFailure(
        expectedGeneration: Long,
        payloadId: Long,
        detail: String,
    ) {
        if (!isCurrent(expectedGeneration) || !outgoingFilePayloadIds.remove(payloadId)) return
        emitIfCurrent(expectedGeneration, TransportEvent.FileSendFailed(payloadId, detail))
    }

    private fun emitIfCurrent(expectedGeneration: Long, event: TransportEvent) {
        if (isCurrent(expectedGeneration)) listener?.onTransportEvent(event)
    }

    companion object {
        private const val SERVICE_ID = "io.remotestudy.connections.v1"
        private const val MAX_BYTES_PAYLOAD = 32 * 1024
        private const val RESTART_BASE_DELAY_MS = 2_000L
        private const val MAX_RESTART_EXPONENT = 3
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    }
}
