package io.remotestudy.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

object StudyWireCodec {
    private const val MAGIC = 0x52535444 // RSTD
    private const val MAX_PAYLOAD_BYTES = 32 * 1024
    private const val MAX_STRING_BYTES = 4 * 1024

    private const val TYPE_HELLO = 1
    private const val TYPE_START_REQUEST = 2
    private const val TYPE_SESSION_SNAPSHOT = 3
    private const val TYPE_PROBLEM_COMPLETED = 4
    private const val TYPE_ACK = 5
    private const val TYPE_ALERT = 6
    private const val TYPE_ASSET_TRANSFER = 7
    private const val TYPE_ASSET_REQUEST = 8
    private const val TYPE_TEXT_MESSAGE = 9
    private const val TYPE_VOICE_TRANSFER = 10
    private const val TYPE_STUDY_SETTINGS = 11
    private const val TYPE_BOOK_REGION_SETTINGS = 12
    private const val TYPE_CAMERA_PROFILE_STATUS = 13
    private const val TYPE_SESSION_CONTROL = 14

    fun encode(message: StudyMessage): ByteArray {
        require(message.messageId.isNotBlank()) { "messageId must not be blank" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeShort(STUDY_PROTOCOL_VERSION)
            data.writeByte(typeOf(message))
            data.writeSizedString(message.messageId)
            when (message) {
                is StudyMessage.Hello -> {
                    data.writeSizedString(message.deviceName)
                    data.writeByte(message.role.ordinal)
                }

                is StudyMessage.StartRequest -> data.writeByte(message.origin.ordinal)

                is StudyMessage.SessionControl -> data.writeByte(message.action.ordinal)

                is StudyMessage.SessionSnapshot -> {
                    require(message.sessionId.isNotBlank()) { "sessionId must not be blank" }
                    data.writeSizedString(message.sessionId)
                    data.writeByte(message.status.ordinal)
                    data.writeByte(message.phase.ordinal)
                    data.writeLong(message.remainingMs)
                    data.writeInt(message.completedProblems)
                    data.writeLong(message.revision)
                }

                is StudyMessage.ProblemCompleted -> {
                    require(message.eventId.isNotBlank()) { "eventId must not be blank" }
                    data.writeSizedString(message.eventId)
                    data.writeInt(message.totalCount)
                }

                is StudyMessage.Alert -> {
                    require(message.observedDurationMs >= 0) { "observedDurationMs must be non-negative" }
                    data.writeByte(message.kind.ordinal)
                    data.writeLong(message.observedDurationMs)
                }

                is StudyMessage.AssetTransfer -> {
                    require(message.assetId.isNotBlank()) { "assetId must not be blank" }
                    require(message.capturedAtEpochMs >= 0) { "capturedAtEpochMs must be non-negative" }
                    data.writeSizedString(message.assetId)
                    data.writeByte(message.kind.ordinal)
                    data.writeLong(message.payloadId)
                    data.writeLong(message.capturedAtEpochMs)
                }

                is StudyMessage.AssetRequest -> {
                    require(message.assetId.isNotBlank()) { "assetId must not be blank" }
                    data.writeSizedString(message.assetId)
                    data.writeByte(message.kind.ordinal)
                }

                is StudyMessage.TextMessage -> {
                    require(message.sentAtEpochMs >= 0) { "sentAtEpochMs must be non-negative" }
                    data.writeByte(message.sender.ordinal)
                    data.writeSizedString(message.text)
                    data.writeLong(message.sentAtEpochMs)
                }

                is StudyMessage.VoiceTransfer -> {
                    require(message.userMessageId.isNotBlank()) { "userMessageId must not be blank" }
                    require(message.sentAtEpochMs >= 0) { "sentAtEpochMs must be non-negative" }
                    require(message.durationMs in 1..60_000) { "durationMs must be between 1 and 60000" }
                    data.writeSizedString(message.userMessageId)
                    data.writeByte(message.sender.ordinal)
                    data.writeLong(message.payloadId)
                    data.writeLong(message.sentAtEpochMs)
                    data.writeLong(message.durationMs)
                }

                is StudyMessage.StudySettings -> {
                    validateSettings(message)
                    data.writeLong(message.meditationDurationMs)
                    data.writeLong(message.studyDurationMs)
                    data.writeLong(message.breakDurationMs)
                    data.writeLong(message.teacherCountdownMs)
                    data.writeLong(message.captureIntervalMs)
                    data.writeLong(message.awayAfterMs)
                    data.writeLong(message.noMovementAfterMs)
                    data.writeFloat(message.presenceThreshold)
                    data.writeFloat(message.bookMovementThreshold)
                    data.writeFloat(message.presenceRestoreThreshold)
                    data.writeFloat(message.presenceMotionThreshold)
                    data.writeLong(message.alertCooldownMs)
                    data.writeBoolean(message.awayAlertEnabled)
                    data.writeBoolean(message.noMovementAlertEnabled)
                    data.writeBoolean(message.alertSoundEnabled)
                }

                is StudyMessage.BookRegionSettings -> {
                    validateBookRegion(message)
                    data.writeFloat(message.left)
                    data.writeFloat(message.top)
                    data.writeFloat(message.right)
                    data.writeFloat(message.bottom)
                    data.writeByte(message.detailCaptureMode.ordinal)
                    data.writeFloat(message.detailZoomRatio)
                    data.writeLong(message.focusTimeoutMs)
                }

                is StudyMessage.CameraProfileStatus -> {
                    validateCameraProfileStatus(message)
                    data.writeByte(message.requestedMode.ordinal)
                    data.writeByte(message.appliedMode.ordinal)
                    data.writeInt(message.width)
                    data.writeInt(message.height)
                    data.writeBoolean(message.ultra50MpAvailable)
                }

                is StudyMessage.Ack -> {
                    require(message.acknowledgedMessageId.isNotBlank()) {
                        "acknowledgedMessageId must not be blank"
                    }
                    data.writeSizedString(message.acknowledgedMessageId)
                }
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds $MAX_PAYLOAD_BYTES bytes" }
        }
    }

    fun decode(payload: ByteArray): StudyMessage {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds $MAX_PAYLOAD_BYTES bytes" }
        val inputBytes = ByteArrayInputStream(payload)
        val message = DataInputStream(inputBytes).use { data ->
            require(data.readInt() == MAGIC) { "Invalid protocol magic" }
            val version = data.readUnsignedShort()
            require(version == STUDY_PROTOCOL_VERSION) { "Unsupported protocol version: $version" }
            val type = data.readUnsignedByte()
            val messageId = data.readSizedString().also { require(it.isNotBlank()) }
            when (type) {
                TYPE_HELLO -> StudyMessage.Hello(
                    messageId = messageId,
                    deviceName = data.readSizedString(),
                    role = enumValue(data.readUnsignedByte(), PeerRole.entries, "peer role"),
                )

                TYPE_START_REQUEST -> StudyMessage.StartRequest(
                    messageId = messageId,
                    origin = enumValue(data.readUnsignedByte(), WireStartOrigin.entries, "start origin"),
                )

                TYPE_SESSION_SNAPSHOT -> StudyMessage.SessionSnapshot(
                    messageId = messageId,
                    sessionId = data.readSizedString().also { require(it.isNotBlank()) },
                    status = enumValue(data.readUnsignedByte(), WireSessionStatus.entries, "session status"),
                    phase = enumValue(data.readUnsignedByte(), WireSessionPhase.entries, "session phase"),
                    remainingMs = data.readLong().also { require(it >= 0) },
                    completedProblems = data.readInt().also { require(it >= 0) },
                    revision = data.readLong().also { require(it >= 0) },
                )

                TYPE_PROBLEM_COMPLETED -> StudyMessage.ProblemCompleted(
                    messageId = messageId,
                    eventId = data.readSizedString().also { require(it.isNotBlank()) },
                    totalCount = data.readInt().also { require(it >= 0) },
                )

                TYPE_ALERT -> StudyMessage.Alert(
                    messageId = messageId,
                    kind = enumValue(data.readUnsignedByte(), AlertKind.entries, "alert kind"),
                    observedDurationMs = data.readLong().also { require(it >= 0) },
                )

                TYPE_ASSET_TRANSFER -> StudyMessage.AssetTransfer(
                    messageId = messageId,
                    assetId = data.readSizedString().also { require(it.isNotBlank()) },
                    kind = enumValue(data.readUnsignedByte(), AssetKind.entries, "asset kind"),
                    // Nearby payload IDs are opaque signed Long values.
                    payloadId = data.readLong(),
                    capturedAtEpochMs = data.readLong().also { require(it >= 0) },
                )

                TYPE_ASSET_REQUEST -> StudyMessage.AssetRequest(
                    messageId = messageId,
                    assetId = data.readSizedString().also { require(it.isNotBlank()) },
                    kind = enumValue(data.readUnsignedByte(), AssetKind.entries, "asset kind"),
                )

                TYPE_TEXT_MESSAGE -> StudyMessage.TextMessage(
                    messageId = messageId,
                    sender = enumValue(data.readUnsignedByte(), PeerRole.entries, "peer role"),
                    text = data.readSizedString(),
                    sentAtEpochMs = data.readLong().also { require(it >= 0) },
                )

                TYPE_VOICE_TRANSFER -> StudyMessage.VoiceTransfer(
                    messageId = messageId,
                    userMessageId = data.readSizedString().also { require(it.isNotBlank()) },
                    sender = enumValue(data.readUnsignedByte(), PeerRole.entries, "peer role"),
                    // Nearby payload IDs are opaque signed Long values.
                    payloadId = data.readLong(),
                    sentAtEpochMs = data.readLong().also { require(it >= 0) },
                    durationMs = data.readLong().also { require(it in 1..60_000) },
                )

                TYPE_STUDY_SETTINGS -> StudyMessage.StudySettings(
                    messageId = messageId,
                    meditationDurationMs = data.readLong(),
                    studyDurationMs = data.readLong(),
                    breakDurationMs = data.readLong(),
                    teacherCountdownMs = data.readLong(),
                    captureIntervalMs = data.readLong(),
                    awayAfterMs = data.readLong(),
                    noMovementAfterMs = data.readLong(),
                    presenceThreshold = data.readFloat(),
                    bookMovementThreshold = data.readFloat(),
                    presenceRestoreThreshold = data.readFloat(),
                    presenceMotionThreshold = data.readFloat(),
                    alertCooldownMs = data.readLong(),
                    awayAlertEnabled = data.readBoolean(),
                    noMovementAlertEnabled = data.readBoolean(),
                    alertSoundEnabled = data.readBoolean(),
                ).also(::validateSettings)

                TYPE_BOOK_REGION_SETTINGS -> StudyMessage.BookRegionSettings(
                    messageId = messageId,
                    left = data.readFloat(),
                    top = data.readFloat(),
                    right = data.readFloat(),
                    bottom = data.readFloat(),
                    detailCaptureMode = enumValue(
                        data.readUnsignedByte(), DetailCaptureMode.entries, "detail capture mode",
                    ),
                    detailZoomRatio = data.readFloat(),
                    focusTimeoutMs = data.readLong(),
                ).also(::validateBookRegion)

                TYPE_CAMERA_PROFILE_STATUS -> StudyMessage.CameraProfileStatus(
                    messageId = messageId,
                    requestedMode = enumValue(
                        data.readUnsignedByte(), DetailCaptureMode.entries, "requested capture mode",
                    ),
                    appliedMode = enumValue(
                        data.readUnsignedByte(), DetailCaptureMode.entries, "applied capture mode",
                    ),
                    width = data.readInt(),
                    height = data.readInt(),
                    ultra50MpAvailable = data.readBoolean(),
                ).also(::validateCameraProfileStatus)

                TYPE_SESSION_CONTROL -> StudyMessage.SessionControl(
                    messageId = messageId,
                    action = enumValue(
                        data.readUnsignedByte(), SessionControlAction.entries, "session control action",
                    ),
                )

                TYPE_ACK -> StudyMessage.Ack(
                    messageId = messageId,
                    acknowledgedMessageId = data.readSizedString().also { require(it.isNotBlank()) },
                )

                else -> error("Unknown message type: $type")
            }
        }
        require(inputBytes.available() == 0) { "Trailing protocol bytes" }
        return message
    }

    private fun typeOf(message: StudyMessage): Int = when (message) {
        is StudyMessage.Hello -> TYPE_HELLO
        is StudyMessage.StartRequest -> TYPE_START_REQUEST
        is StudyMessage.SessionControl -> TYPE_SESSION_CONTROL
        is StudyMessage.SessionSnapshot -> TYPE_SESSION_SNAPSHOT
        is StudyMessage.ProblemCompleted -> TYPE_PROBLEM_COMPLETED
        is StudyMessage.Alert -> TYPE_ALERT
        is StudyMessage.AssetTransfer -> TYPE_ASSET_TRANSFER
        is StudyMessage.AssetRequest -> TYPE_ASSET_REQUEST
        is StudyMessage.TextMessage -> TYPE_TEXT_MESSAGE
        is StudyMessage.VoiceTransfer -> TYPE_VOICE_TRANSFER
        is StudyMessage.StudySettings -> TYPE_STUDY_SETTINGS
        is StudyMessage.BookRegionSettings -> TYPE_BOOK_REGION_SETTINGS
        is StudyMessage.CameraProfileStatus -> TYPE_CAMERA_PROFILE_STATUS
        is StudyMessage.Ack -> TYPE_ACK
    }

    private fun validateSettings(settings: StudyMessage.StudySettings) {
        require(settings.meditationDurationMs in 0L..86_400_000L)
        require(settings.studyDurationMs in 1_000L..86_400_000L)
        require(settings.breakDurationMs in 1_000L..86_400_000L)
        require(settings.teacherCountdownMs in 1_000L..60_000L)
        require(settings.captureIntervalMs in 1_000L..3_600_000L)
        require(settings.awayAfterMs in 1_000L..3_600_000L)
        require(settings.noMovementAfterMs in 1_000L..3_600_000L)
        require(settings.presenceThreshold.isFinite() && settings.presenceThreshold in 0f..1f)
        require(settings.bookMovementThreshold.isFinite() && settings.bookMovementThreshold in 0f..1f)
        require(settings.presenceRestoreThreshold.isFinite() && settings.presenceRestoreThreshold in 0f..1f)
        require(settings.presenceRestoreThreshold <= settings.presenceThreshold)
        require(settings.presenceMotionThreshold.isFinite() && settings.presenceMotionThreshold in 0f..1f)
        require(settings.alertCooldownMs in 0L..3_600_000L)
    }

    private fun validateCameraProfileStatus(status: StudyMessage.CameraProfileStatus) {
        require(status.width > 0 && status.height > 0)
    }

    private fun validateBookRegion(region: StudyMessage.BookRegionSettings) {
        require(region.left.isFinite() && region.right.isFinite())
        require(region.top.isFinite() && region.bottom.isFinite())
        require(region.left in 0f..1f && region.right in 0f..1f && region.right - region.left >= 0.12f)
        require(region.top in 0f..1f && region.bottom in 0f..1f && region.bottom - region.top >= 0.12f)
        require(region.detailZoomRatio.isFinite() && region.detailZoomRatio in 1f..10f)
        require(region.focusTimeoutMs in 500L..5_000L)
    }

    private fun DataOutputStream.writeSizedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String exceeds $MAX_STRING_BYTES bytes" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSizedString(): String {
        val size = readUnsignedShort()
        require(size <= MAX_STRING_BYTES) { "String exceeds $MAX_STRING_BYTES bytes" }
        val bytes = ByteArray(size)
        try {
            readFully(bytes)
        } catch (error: EOFException) {
            throw IllegalArgumentException("Truncated protocol string", error)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun <T> enumValue(index: Int, values: List<T>, label: String): T =
        values.getOrNull(index) ?: error("Unknown $label: $index")
}
