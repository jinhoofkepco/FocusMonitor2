package io.remotestudy.student

import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionStatus
import io.remotestudy.domain.session.StudySchedule

internal object RemoteTimerMath {
    fun phaseDuration(schedule: StudySchedule, phase: SessionPhase): Long = when (phase) {
        SessionPhase.MEDITATION -> schedule.meditationDurationMs
        SessionPhase.STUDY -> schedule.studyDurationMs
        SessionPhase.BREAK -> schedule.breakDurationMs
        SessionPhase.COMPLETE -> 0L
    }

    fun totalPhaseDuration(schedule: StudySchedule): Long =
        schedule.meditationDurationMs + schedule.studyDurationMs + schedule.breakDurationMs

    fun progressForPosition(
        schedule: StudySchedule,
        countdownMs: Long,
        phase: SessionPhase,
        remainingMs: Long,
    ): Long {
        val safeRemaining = remainingMs.coerceIn(0L, phaseDuration(schedule, phase))
        val phaseProgress = when (phase) {
            SessionPhase.MEDITATION -> schedule.meditationDurationMs - safeRemaining
            SessionPhase.STUDY -> schedule.meditationDurationMs + schedule.studyDurationMs - safeRemaining
            SessionPhase.BREAK -> schedule.meditationDurationMs + schedule.studyDurationMs +
                schedule.breakDurationMs - safeRemaining
            SessionPhase.COMPLETE -> totalPhaseDuration(schedule)
        }
        return countdownMs + phaseProgress
    }

    fun progressForSnapshot(
        schedule: StudySchedule,
        countdownMs: Long,
        status: SessionStatus,
        phase: SessionPhase,
        phaseRemainingMs: Long,
        countdownRemainingMs: Long,
    ): Long {
        val maximum = countdownMs + totalPhaseDuration(schedule)
        val progress = when (status) {
            SessionStatus.READY -> 0L
            SessionStatus.START_COUNTDOWN -> countdownMs - countdownRemainingMs
            SessionStatus.RUNNING, SessionStatus.PAUSED -> progressForPosition(
                schedule, countdownMs, phase, phaseRemainingMs,
            )
            SessionStatus.COMPLETED -> maximum
        }
        return progress.coerceIn(0L, maximum)
    }
}
