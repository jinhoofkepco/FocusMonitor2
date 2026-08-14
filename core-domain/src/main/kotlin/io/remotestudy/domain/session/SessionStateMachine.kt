package io.remotestudy.domain.session

/**
 * Pure, in-memory session state machine driven only by monotonic elapsed time.
 *
 * It has no network, Android, wall-clock, or persistence dependency. Callers may
 * persist [SessionSnapshot] and their command/event IDs outside this module when
 * process-death recovery is needed.
 */
class SessionStateMachine(
    private val schedule: StudySchedule = StudySchedule(),
    private val teacherCountdownDurationMs: Long = DEFAULT_TEACHER_COUNTDOWN_MS,
    private val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
) {
    init {
        require(teacherCountdownDurationMs > 0) {
            "teacherCountdownDurationMs must be positive"
        }
        require(undoWindowMs >= 0) { "undoWindowMs must not be negative" }
    }

    private var status = SessionStatus.READY
    private var phase = SessionPhase.MEDITATION
    private var phaseRemainingMs = schedule.meditationDurationMs
    private var countdownRemainingMs = 0L
    private var startedBy: StartOrigin? = null
    private var latestElapsedMs: Long? = null

    private val processedCommandIds = mutableSetOf<String>()
    private val processedProblemIds = mutableSetOf<String>()
    private val processedUndoIds = mutableSetOf<String>()
    private val problemCompletionTimes = mutableMapOf<String, Long>()
    private var completedProblemCount = 0

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        status = status,
        phase = phase,
        phaseRemainingMs = phaseRemainingMs,
        countdownRemainingMs = countdownRemainingMs,
        completedProblemCount = completedProblemCount,
        startedBy = startedBy,
        observedAtElapsedMs = latestElapsedMs,
    )

    fun dispatch(command: SessionCommand): SessionSnapshot {
        when (command) {
            is StartRequested -> handleStart(command)
            is Tick -> advanceTo(command.atElapsedMs)
            is Pause -> handlePause(command)
            is Resume -> handleResume(command)
            is ProblemCompleted -> handleProblemCompleted(command)
            is UndoProblem -> handleUndo(command)
        }
        return snapshot()
    }

    private fun handleStart(command: StartRequested) {
        if (!processedCommandIds.add(command.commandId)) return
        if (status != SessionStatus.READY || !acceptInitialTimestamp(command.atElapsedMs)) return

        startedBy = command.origin
        when (command.origin) {
            StartOrigin.STUDENT -> {
                status = SessionStatus.RUNNING
                countdownRemainingMs = 0L
            }

            StartOrigin.TEACHER -> {
                status = SessionStatus.START_COUNTDOWN
                countdownRemainingMs = teacherCountdownDurationMs
            }
        }
    }

    private fun handlePause(command: Pause) {
        if (!processedCommandIds.add(command.commandId)) return
        if (!advanceTo(command.atElapsedMs)) return
        if (status == SessionStatus.RUNNING) {
            status = SessionStatus.PAUSED
        }
    }

    private fun handleResume(command: Resume) {
        if (!processedCommandIds.add(command.commandId)) return
        if (!advanceTo(command.atElapsedMs)) return
        if (status == SessionStatus.PAUSED) {
            status = SessionStatus.RUNNING
        }
    }

    private fun handleProblemCompleted(command: ProblemCompleted) {
        if (!processedProblemIds.add(command.eventId)) return
        if (!advanceTo(command.atElapsedMs)) return
        if (status != SessionStatus.RUNNING || phase != SessionPhase.STUDY) return

        problemCompletionTimes[command.eventId] = command.atElapsedMs
        completedProblemCount += 1
    }

    private fun handleUndo(command: UndoProblem) {
        if (!processedUndoIds.add(command.eventId)) return
        if (!advanceTo(command.atElapsedMs)) return

        val completedAt = problemCompletionTimes[command.eventId] ?: return
        val elapsedSinceCompletion = command.atElapsedMs - completedAt
        if (elapsedSinceCompletion in 0..undoWindowMs) {
            problemCompletionTimes.remove(command.eventId)
            completedProblemCount -= 1
        }
    }

    /**
     * Advances time without ever moving it backwards. A stale timestamp is a
     * harmless no-op so a delayed transport event cannot rewind the timer.
     */
    private fun advanceTo(atElapsedMs: Long): Boolean {
        val previousElapsedMs = latestElapsedMs
        if (previousElapsedMs == null) {
            latestElapsedMs = atElapsedMs
            return true
        }
        if (atElapsedMs < previousElapsedMs) return false

        val elapsedDeltaMs = atElapsedMs - previousElapsedMs
        latestElapsedMs = atElapsedMs

        when (status) {
            SessionStatus.START_COUNTDOWN -> advanceCountdown(elapsedDeltaMs)
            SessionStatus.RUNNING -> advanceRunningPhase(elapsedDeltaMs)
            SessionStatus.READY,
            SessionStatus.PAUSED,
            SessionStatus.COMPLETED,
            -> Unit
        }
        return true
    }

    private fun acceptInitialTimestamp(atElapsedMs: Long): Boolean {
        val previousElapsedMs = latestElapsedMs
        if (previousElapsedMs != null && atElapsedMs < previousElapsedMs) return false
        latestElapsedMs = atElapsedMs
        return true
    }

    private fun advanceCountdown(elapsedDeltaMs: Long) {
        if (elapsedDeltaMs < countdownRemainingMs) {
            countdownRemainingMs -= elapsedDeltaMs
            return
        }

        val runningElapsedMs = elapsedDeltaMs - countdownRemainingMs
        countdownRemainingMs = 0L
        status = SessionStatus.RUNNING
        advanceRunningPhase(runningElapsedMs)
    }

    private fun advanceRunningPhase(elapsedDeltaMs: Long) {
        var unconsumedMs = elapsedDeltaMs
        while (status == SessionStatus.RUNNING && unconsumedMs >= phaseRemainingMs) {
            unconsumedMs -= phaseRemainingMs
            moveToNextPhase()
        }

        if (status == SessionStatus.RUNNING) {
            phaseRemainingMs -= unconsumedMs
        }
    }

    private fun moveToNextPhase() {
        when (phase) {
            SessionPhase.MEDITATION -> {
                phase = SessionPhase.STUDY
                phaseRemainingMs = schedule.studyDurationMs
            }

            SessionPhase.STUDY -> {
                phase = SessionPhase.BREAK
                phaseRemainingMs = schedule.breakDurationMs
            }

            SessionPhase.BREAK -> {
                phase = SessionPhase.COMPLETE
                phaseRemainingMs = 0L
                status = SessionStatus.COMPLETED
            }

            SessionPhase.COMPLETE -> {
                phaseRemainingMs = 0L
                status = SessionStatus.COMPLETED
            }
        }
    }

    companion object {
        const val DEFAULT_TEACHER_COUNTDOWN_MS: Long = 5_000L
        const val DEFAULT_UNDO_WINDOW_MS: Long = 5_000L
    }
}
