package io.remotestudy.domain.session

enum class SessionPhase {
    MEDITATION,
    STUDY,
    BREAK,
    COMPLETE,
}

enum class SessionStatus {
    READY,
    START_COUNTDOWN,
    RUNNING,
    PAUSED,
    COMPLETED,
}

enum class StartOrigin {
    STUDENT,
    TEACHER,
}

data class StudySchedule(
    val meditationDurationMs: Long = DEFAULT_MEDITATION_DURATION_MS,
    val studyDurationMs: Long = DEFAULT_STUDY_DURATION_MS,
    val breakDurationMs: Long = DEFAULT_BREAK_DURATION_MS,
) {
    init {
        require(meditationDurationMs >= 0) { "meditationDurationMs must not be negative" }
        require(studyDurationMs > 0) { "studyDurationMs must be positive" }
        require(breakDurationMs > 0) { "breakDurationMs must be positive" }
    }

    companion object {
        const val DEFAULT_MEDITATION_DURATION_MS: Long = 5L * 60_000L
        const val DEFAULT_STUDY_DURATION_MS: Long = 40L * 60_000L
        const val DEFAULT_BREAK_DURATION_MS: Long = 15L * 60_000L
    }
}

data class SessionSnapshot(
    val status: SessionStatus,
    val phase: SessionPhase,
    val phaseRemainingMs: Long,
    val countdownRemainingMs: Long,
    val completedProblemCount: Int,
    val startedBy: StartOrigin?,
    val observedAtElapsedMs: Long?,
)

sealed interface SessionCommand {
    val atElapsedMs: Long
}

data class StartRequested(
    val commandId: String,
    val origin: StartOrigin,
    override val atElapsedMs: Long,
) : SessionCommand {
    init {
        require(commandId.isNotBlank()) { "commandId must not be blank" }
        require(atElapsedMs >= 0) { "atElapsedMs must not be negative" }
    }
}

data class Tick(
    override val atElapsedMs: Long,
) : SessionCommand {
    init {
        require(atElapsedMs >= 0) { "atElapsedMs must not be negative" }
    }
}

data class Pause(
    val commandId: String,
    override val atElapsedMs: Long,
) : SessionCommand {
    init {
        require(commandId.isNotBlank()) { "commandId must not be blank" }
        require(atElapsedMs >= 0) { "atElapsedMs must not be negative" }
    }
}

data class Resume(
    val commandId: String,
    override val atElapsedMs: Long,
) : SessionCommand {
    init {
        require(commandId.isNotBlank()) { "commandId must not be blank" }
        require(atElapsedMs >= 0) { "atElapsedMs must not be negative" }
    }
}

data class ProblemCompleted(
    val eventId: String,
    override val atElapsedMs: Long,
) : SessionCommand {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(atElapsedMs >= 0) { "atElapsedMs must not be negative" }
    }
}

data class UndoProblem(
    val eventId: String,
    override val atElapsedMs: Long,
) : SessionCommand {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(atElapsedMs >= 0) { "atElapsedMs must not be negative" }
    }
}
