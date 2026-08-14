package io.remotestudy.sync

import io.remotestudy.protocol.StudyMessage
import io.remotestudy.protocol.StudyWireCodec
import java.util.UUID

/**
 * UI-thread-confined at-least-once delivery helper for small control messages.
 *
 * Non-ACK messages remain pending until the peer acknowledges their message ID.
 * Receivers acknowledge repeats but expose each message ID to the app once.
 * Persistence belongs to a storage adapter and is deliberately not hidden here.
 */
class ReliableMessageChannel(
    private val transmitter: (ByteArray) -> Boolean,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
    private val maxPendingMessages: Int = DEFAULT_MAX_PENDING,
    private val maxRememberedInboundIds: Int = DEFAULT_MAX_INBOUND_IDS,
) {
    init {
        require(retryIntervalMs > 0)
        require(maxPendingMessages > 0)
        require(maxRememberedInboundIds > 0)
    }

    private data class Pending(
        val message: StudyMessage,
        val bytes: ByteArray,
        val coalesceKey: String?,
        var lastAttemptAtElapsedMs: Long?,
    )

    private val pendingById = linkedMapOf<String, Pending>()
    private val inboundIds = linkedSetOf<String>()
    private var connected = false

    val pendingCount: Int get() = pendingById.size

    fun setConnected(value: Boolean, atElapsedMs: Long) {
        require(atElapsedMs >= 0)
        connected = value
        if (value) retryAll(atElapsedMs)
    }

    /**
     * Queues and opportunistically sends a message. A coalesce key replaces an
     * older pending item of the same kind, which is useful for session snapshots.
     */
    fun send(
        message: StudyMessage,
        atElapsedMs: Long,
        coalesceKey: String? = null,
    ): Boolean {
        require(atElapsedMs >= 0)
        if (message is StudyMessage.Ack) return connected && transmitter(StudyWireCodec.encode(message))
        if (pendingById.containsKey(message.messageId)) return true

        if (coalesceKey != null) {
            val supersededId = pendingById.values
                .firstOrNull { it.coalesceKey == coalesceKey }
                ?.message
                ?.messageId
            if (supersededId != null) pendingById.remove(supersededId)
        }
        if (pendingById.size >= maxPendingMessages) return false

        val pending = Pending(
            message = message,
            bytes = StudyWireCodec.encode(message),
            coalesceKey = coalesceKey,
            lastAttemptAtElapsedMs = null,
        )
        pendingById[message.messageId] = pending
        if (connected) transmit(pending, atElapsedMs)
        return true
    }

    /** Returns a decoded message once, or null for ACK/duplicate input. */
    fun receive(bytes: ByteArray, atElapsedMs: Long): StudyMessage? {
        require(atElapsedMs >= 0)
        val message = StudyWireCodec.decode(bytes)
        if (message is StudyMessage.Ack) {
            pendingById.remove(message.acknowledgedMessageId)
            return null
        }

        if (connected) {
            transmitter(
                StudyWireCodec.encode(
                    StudyMessage.Ack(
                        messageId = idFactory(),
                        acknowledgedMessageId = message.messageId,
                    ),
                ),
            )
        }

        if (!rememberInbound(message.messageId)) return null
        return message
    }

    fun retryDue(atElapsedMs: Long) {
        require(atElapsedMs >= 0)
        if (!connected) return
        pendingById.values.forEach { pending ->
            val lastAttempt = pending.lastAttemptAtElapsedMs
            if (lastAttempt == null || atElapsedMs - lastAttempt >= retryIntervalMs) {
                transmit(pending, atElapsedMs)
            }
        }
    }

    fun pendingMessages(): List<StudyMessage> = pendingById.values.map { it.message }

    private fun retryAll(atElapsedMs: Long) {
        pendingById.values.forEach { transmit(it, atElapsedMs) }
    }

    private fun transmit(pending: Pending, atElapsedMs: Long) {
        pending.lastAttemptAtElapsedMs = atElapsedMs
        transmitter(pending.bytes)
    }

    private fun rememberInbound(messageId: String): Boolean {
        if (!inboundIds.add(messageId)) return false
        while (inboundIds.size > maxRememberedInboundIds) {
            inboundIds.remove(inboundIds.first())
        }
        return true
    }

    companion object {
        const val DEFAULT_RETRY_INTERVAL_MS = 2_000L
        const val DEFAULT_MAX_PENDING = 512
        const val DEFAULT_MAX_INBOUND_IDS = 2_048
    }
}
