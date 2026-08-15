package io.remotestudy.protocol

const val STUDY_PROTOCOL_VERSION: Int = 3

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
    /** Fresh, unpixelated image captured at the selected zoom used by BOOK_ROI. */
    BOOK_CALIBRATION,
    CAMERA_COMPARE_1X,
    CAMERA_COMPARE_2X,
    CAMERA_COMPARE_3X,
}

enum class DetailCaptureMode {
    STANDARD_12_MP,
    ULTRA_50_MP,
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

    /** Numeric settings selected on the teacher phone and applied before a session starts. */
    data class StudySettings(
        override val messageId: String,
        val meditationDurationMs: Long,
        val studyDurationMs: Long,
        val breakDurationMs: Long,
        val teacherCountdownMs: Long,
        val captureIntervalMs: Long,
        val awayAfterMs: Long,
        val noMovementAfterMs: Long,
        val presenceThreshold: Float,
        val bookMovementThreshold: Float,
        val presenceRestoreThreshold: Float = 0.12f,
        val presenceMotionThreshold: Float = 0.006f,
        val alertCooldownMs: Long = 300_000L,
        val awayAlertEnabled: Boolean = true,
        val noMovementAlertEnabled: Boolean = true,
        val alertSoundEnabled: Boolean = false,
    ) : StudyMessage

    /** Upright camera coordinates derived from the teacher's book selection. */
    data class BookRegionSettings(
        override val messageId: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val detailCaptureMode: DetailCaptureMode = DetailCaptureMode.STANDARD_12_MP,
        val detailZoomRatio: Float = 2f,
        val focusTimeoutMs: Long = 2_000L,
    ) : StudyMessage

    data class CameraProfileStatus(
        override val messageId: String,
        val requestedMode: DetailCaptureMode,
        val appliedMode: DetailCaptureMode,
        val width: Int,
        val height: Int,
        val ultra50MpAvailable: Boolean,
    ) : StudyMessage

    data class Ack(
        override val messageId: String,
        val acknowledgedMessageId: String,
    ) : StudyMessage
}
