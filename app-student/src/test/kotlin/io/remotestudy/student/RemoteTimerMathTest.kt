package io.remotestudy.student

import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionStatus
import io.remotestudy.domain.session.SessionStateMachine
import io.remotestudy.domain.session.StartOrigin
import io.remotestudy.domain.session.StartRequested
import io.remotestudy.domain.session.StudySchedule
import io.remotestudy.domain.session.Tick
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTimerMathTest {
    private val schedule = StudySchedule(
        meditationDurationMs = 0L,
        studyDurationMs = 40L * 60_000L,
        breakDurationMs = 15L * 60_000L,
    )

    @Test fun zeroMeditationStartsStudyAtCountdownBoundary() {
        assertEquals(
            5_000L,
            RemoteTimerMath.progressForPosition(schedule, 5_000L, SessionPhase.STUDY, 40L * 60_000L),
        )
    }

    @Test fun positionsStudyAndBreakByRemainingTime() {
        assertEquals(
            15L * 60_000L,
            RemoteTimerMath.progressForPosition(schedule, 0L, SessionPhase.STUDY, 25L * 60_000L),
        )
        assertEquals(
            45L * 60_000L,
            RemoteTimerMath.progressForPosition(schedule, 0L, SessionPhase.BREAK, 10L * 60_000L),
        )
    }

    @Test fun pausedSnapshotDoesNotLoseItsPosition() {
        assertEquals(
            15L * 60_000L,
            RemoteTimerMath.progressForSnapshot(
                schedule,
                0L,
                SessionStatus.PAUSED,
                SessionPhase.STUDY,
                25L * 60_000L,
                0L,
            ),
        )
    }

    @Test fun countdownAndCompletedPositionsAreBounded() {
        assertEquals(
            2_000L,
            RemoteTimerMath.progressForSnapshot(
                schedule, 5_000L, SessionStatus.START_COUNTDOWN, SessionPhase.MEDITATION, 0L, 3_000L,
            ),
        )
        assertEquals(
            55L * 60_000L + 5_000L,
            RemoteTimerMath.progressForSnapshot(
                schedule, 5_000L, SessionStatus.COMPLETED, SessionPhase.COMPLETE, 0L, 0L,
            ),
        )
    }

    @Test fun zeroMeditationActuallyEntersStudyImmediately() {
        val machine = SessionStateMachine(schedule, teacherCountdownDurationMs = 1L)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))
        val snapshot = machine.dispatch(Tick(0L))
        assertEquals(SessionStatus.RUNNING, snapshot.status)
        assertEquals(SessionPhase.STUDY, snapshot.phase)
        assertEquals(40L * 60_000L, snapshot.phaseRemainingMs)
    }

    @Test fun settingBreakRemainingToZeroCompletesImmediately() {
        val progress = RemoteTimerMath.progressForPosition(schedule, 0L, SessionPhase.BREAK, 0L)
        val machine = SessionStateMachine(schedule, teacherCountdownDurationMs = 1L)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))
        val snapshot = machine.dispatch(Tick(progress))
        assertEquals(SessionStatus.COMPLETED, snapshot.status)
        assertEquals(SessionPhase.COMPLETE, snapshot.phase)
    }
}
