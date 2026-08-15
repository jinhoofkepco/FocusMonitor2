package io.remotestudy.transport

import java.io.File

enum class TransportRole { STUDENT, TEACHER }

sealed interface TransportEvent {
    data object Searching : TransportEvent

    data class EndpointFound(
        val endpointId: String,
        val displayName: String,
    ) : TransportEvent

    data class EndpointLost(val endpointId: String) : TransportEvent

    data class PairingRequested(
        val endpointId: String,
        val displayName: String,
        val authenticationDigits: String,
    ) : TransportEvent

    data class Connected(
        val endpointId: String,
        val displayName: String,
    ) : TransportEvent

    data class Disconnected(val endpointId: String) : TransportEvent

    data class MessageReceived(
        val endpointId: String,
        val bytes: ByteArray,
    ) : TransportEvent {
        override fun equals(other: Any?): Boolean =
            other is MessageReceived && endpointId == other.endpointId && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * endpointId.hashCode() + bytes.contentHashCode()
    }

    data class FileReceived(
        val endpointId: String,
        val payloadId: Long,
        val uri: String,
    ) : TransportEvent

    data class FileSent(val payloadId: Long) : TransportEvent

    data class FileProgress(
        val payloadId: Long,
        val bytesTransferred: Long,
        val totalBytes: Long,
    ) : TransportEvent

    data class FileSendFailed(
        val payloadId: Long,
        val detail: String,
    ) : TransportEvent

    data class Failure(
        val operation: String,
        val detail: String,
    ) : TransportEvent
}

fun interface TransportListener {
    fun onTransportEvent(event: TransportEvent)
}

interface StudyTransport {
    fun setListener(listener: TransportListener?)

    fun start(role: TransportRole, displayName: String)

    fun requestConnection(endpointId: String)

    fun approve(endpointId: String)

    fun reject(endpointId: String)

    fun send(bytes: ByteArray): Boolean

    fun sendFile(file: File): Long?

    fun cancelFile(payloadId: Long): Boolean

    fun stop()
}
