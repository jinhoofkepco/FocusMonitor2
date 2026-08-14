package io.remotestudy.protocol

const val STUDY_PROTOCOL_VERSION: Int = 1

enum class PeerRole { STUDENT, TEACHER }

enum class WireStartOrigin { STUDENT, TEACHER }

enum class WireSessionStatus {
    READY,
    START_COUNTDOWN,
    RUNNING,
    PAUSED,
    COMPLETED,
}

enum class WireSessionPhase {
    MEDITATION,
    STUDY,
    BREAK,
    COMPLETE,
}

enum class AlertKind {
    AWAY,
    NO_BOOK_MOVEMENT,
    PRESENCE_RESTORED,
    BOOK_MOVEMENT_RESTORED,
}

enum class AssetKind {
    THUMBNAIL,
    BOOK_ROI,
}

sealed interface StudyMessage {
    val messageId: String

    data class Hello(
        override val messageId: String,
        val deviceName: String,
        val role: PeerRole,
    ) : StudyMessage

    data class StartRequest(
        override val messageId: String,
        val origin: WireStartOrigin,
    ) : StudyMessage

    data class SessionSnapshot(
        override val messageId: String,
        val sessionId: String,
        val status: WireSessionStatus,
        val phase: WireSessionPhase,
        val remainingMs: Long,
        val completedProblems: Int,
        val revision: Long,
    ) : StudyMessage

    data class ProblemCompleted(
        override val messageId: String,
        val eventId: String,
        val totalCount: Int,
    ) : StudyMessage

    data class Alert(
        override val messageId: String,
        val kind: AlertKind,
        val observedDurationMs: Long,
    ) : StudyMessage

    data class AssetTransfer(
        override val messageId: String,
        val assetId: String,
        val kind: AssetKind,
        val payloadId: Long,
        val capturedAtEpochMs: Long,
    ) : StudyMessage

    data class AssetRequest(
        override val messageId: String,
        val assetId: String,
        val kind: AssetKind,
    ) : StudyMessage

    data class TextMessage(
        override val messageId: String,
        val sender: PeerRole,
        val text: String,
        val sentAtEpochMs: Long,
    ) : StudyMessage

    data class VoiceTransfer(
        override val messageId: String,
        val userMessageId: String,
        val sender: PeerRole,
        val payloadId: Long,
        val sentAtEpochMs: Long,
        val durationMs: Long,
    ) : StudyMessage

    data class Ack(
        override val messageId: String,
        val acknowledgedMessageId: String,
    ) : StudyMessage
}
