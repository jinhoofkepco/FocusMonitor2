package io.remotestudy.detection

data class DetectionConfig(
    val presenceAbsenceThreshold: Float = 0.18f,
    val bookMovementThreshold: Float = 0.012f,
    val awayAfterMs: Long = 10_000L,
    val noMovementAfterMs: Long = 30_000L,
) {
    init {
        require(presenceAbsenceThreshold.isFinite()) {
            "presenceAbsenceThreshold must be finite"
        }
        require(presenceAbsenceThreshold in 0f..1f) {
            "presenceAbsenceThreshold must be between 0 and 1"
        }
        require(bookMovementThreshold.isFinite()) {
            "bookMovementThreshold must be finite"
        }
        require(bookMovementThreshold in 0f..1f) {
            "bookMovementThreshold must be between 0 and 1"
        }
        require(awayAfterMs > 0L) { "awayAfterMs must be positive" }
        require(noMovementAfterMs > 0L) { "noMovementAfterMs must be positive" }
    }
}

data class FrameEvidence(
    val observedAtElapsedMs: Long,
    val presenceDifference: Float?,
    val bookMovement: Float?,
) {
    init {
        require(observedAtElapsedMs >= 0L) { "observedAtElapsedMs must not be negative" }
        requireNormalizedOrUnknown("presenceDifference", presenceDifference)
        requireNormalizedOrUnknown("bookMovement", bookMovement)
    }

    private fun requireNormalizedOrUnknown(name: String, value: Float?) {
        if (value == null) return
        require(value.isFinite()) { "$name must be finite when known" }
        require(value in 0f..1f) { "$name must be between 0 and 1 when known" }
    }
}

enum class DetectionEventKind {
    AWAY,
    NO_BOOK_MOVEMENT,
    PRESENCE_RESTORED,
    BOOK_MOVEMENT_RESTORED,
}

data class DetectionEvent(
    val kind: DetectionEventKind,
    val observedDurationMs: Long,
) {
    init {
        require(observedDurationMs >= 0L) { "observedDurationMs must not be negative" }
    }
}
