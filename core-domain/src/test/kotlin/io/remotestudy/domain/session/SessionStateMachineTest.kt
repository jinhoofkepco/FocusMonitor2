package io.remotestudy.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateMachineTest {
    private val shortSchedule = StudySchedule(
        meditationDurationMs = 1_000L,
        studyDurationMs = 2_000L,
        breakDurationMs = 1_500L,
    )

    @Test
    fun `default schedule is five forty fifteen minutes`() {
        val schedule = StudySchedule()

        assertEquals(5L * 60_000L, schedule.meditationDurationMs)
        assertEquals(40L * 60_000L, schedule.studyDurationMs)
        assertEquals(15L * 60_000L, schedule.breakDurationMs)
    }

    @Test
    fun `initial snapshot is ready for meditation`() {
        val snapshot = SessionStateMachine(shortSchedule).snapshot()

        assertEquals(SessionStatus.READY, snapshot.status)
        assertEquals(SessionPhase.MEDITATION, snapshot.phase)
        assertEquals(1_000L, snapshot.phaseRemainingMs)
        assertEquals(0L, snapshot.countdownRemainingMs)
        assertEquals(0, snapshot.completedProblemCount)
        assertNull(snapshot.startedBy)
        assertNull(snapshot.observedAtElapsedMs)
    }

    @Test
    fun `student start immediately begins meditation`() {
        val machine = SessionStateMachine(shortSchedule)

        val snapshot = machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 100L))

        assertEquals(SessionStatus.RUNNING, snapshot.status)
        assertEquals(SessionPhase.MEDITATION, snapshot.phase)
        assertEquals(1_000L, snapshot.phaseRemainingMs)
        assertEquals(StartOrigin.STUDENT, snapshot.startedBy)
    }

    @Test
    fun `teacher start waits exactly five seconds before meditation`() {
        val machine = SessionStateMachine(shortSchedule)

        machine.dispatch(StartRequested("start", StartOrigin.TEACHER, 10_000L))
        val beforeBoundary = machine.dispatch(Tick(14_999L))
        val atBoundary = machine.dispatch(Tick(15_000L))

        assertEquals(SessionStatus.START_COUNTDOWN, beforeBoundary.status)
        assertEquals(1L, beforeBoundary.countdownRemainingMs)
        assertEquals(SessionStatus.RUNNING, atBoundary.status)
        assertEquals(SessionPhase.MEDITATION, atBoundary.phase)
        assertEquals(1_000L, atBoundary.phaseRemainingMs)
        assertEquals(0L, atBoundary.countdownRemainingMs)
    }

    @Test
    fun `large tick crosses all phase boundaries and completes`() {
        val machine = SessionStateMachine(shortSchedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))

        val studyBoundary = machine.dispatch(Tick(1_000L))
        assertEquals(SessionPhase.STUDY, studyBoundary.phase)
        assertEquals(2_000L, studyBoundary.phaseRemainingMs)

        val breakBoundary = machine.dispatch(Tick(3_000L))
        assertEquals(SessionPhase.BREAK, breakBoundary.phase)
        assertEquals(1_500L, breakBoundary.phaseRemainingMs)

        val completed = machine.dispatch(Tick(10_000L))
        assertEquals(SessionStatus.COMPLETED, completed.status)
        assertEquals(SessionPhase.COMPLETE, completed.phase)
        assertEquals(0L, completed.phaseRemainingMs)
    }

    @Test
    fun `pause consumes time up to pause and resume excludes paused duration`() {
        val machine = SessionStateMachine(shortSchedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))

        val paused = machine.dispatch(Pause("pause", 400L))
        val stillPaused = machine.dispatch(Tick(10_000L))
        val resumed = machine.dispatch(Resume("resume", 12_000L))
        val afterResume = machine.dispatch(Tick(12_599L))
        val atBoundary = machine.dispatch(Tick(12_600L))

        assertEquals(SessionStatus.PAUSED, paused.status)
        assertEquals(600L, paused.phaseRemainingMs)
        assertEquals(600L, stillPaused.phaseRemainingMs)
        assertEquals(SessionStatus.RUNNING, resumed.status)
        assertEquals(600L, resumed.phaseRemainingMs)
        assertEquals(SessionPhase.MEDITATION, afterResume.phase)
        assertEquals(1L, afterResume.phaseRemainingMs)
        assertEquals(SessionPhase.STUDY, atBoundary.phase)
        assertEquals(2_000L, atBoundary.phaseRemainingMs)
    }

    @Test
    fun `duplicate command IDs cannot start pause or resume twice`() {
        val machine = SessionStateMachine(shortSchedule)

        machine.dispatch(StartRequested("same", StartOrigin.STUDENT, 0L))
        machine.dispatch(StartRequested("same", StartOrigin.TEACHER, 100L))
        machine.dispatch(Pause("pause", 200L))
        val duplicatePause = machine.dispatch(Pause("pause", 900L))
        machine.dispatch(Resume("resume", 1_000L))
        val duplicateResume = machine.dispatch(Resume("resume", 1_400L))

        assertEquals(SessionStatus.PAUSED, duplicatePause.status)
        assertEquals(800L, duplicatePause.phaseRemainingMs)
        assertEquals(SessionStatus.RUNNING, duplicateResume.status)
        assertEquals(800L, duplicateResume.phaseRemainingMs)
        assertEquals(StartOrigin.STUDENT, duplicateResume.startedBy)
    }

    @Test
    fun `problem completion is counted only once during study`() {
        val machine = SessionStateMachine(shortSchedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))
        machine.dispatch(Tick(1_000L))

        machine.dispatch(ProblemCompleted("problem-1", 1_100L))
        val duplicate = machine.dispatch(ProblemCompleted("problem-1", 1_200L))

        assertEquals(1, duplicate.completedProblemCount)
        assertEquals(1_900L, duplicate.phaseRemainingMs)
    }

    @Test
    fun `problem completion outside running study is ignored permanently`() {
        val machine = SessionStateMachine(shortSchedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))

        machine.dispatch(ProblemCompleted("too-early", 500L))
        machine.dispatch(Tick(1_000L))
        val retried = machine.dispatch(ProblemCompleted("too-early", 1_100L))

        assertEquals(0, retried.completedProblemCount)
    }

    @Test
    fun `undo is accepted at five second boundary and is idempotent`() {
        val schedule = StudySchedule(1_000L, 20_000L, 1_000L)
        val machine = SessionStateMachine(schedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))
        machine.dispatch(Tick(1_000L))
        machine.dispatch(ProblemCompleted("problem-1", 2_000L))

        val undone = machine.dispatch(UndoProblem("problem-1", 7_000L))
        val duplicateUndo = machine.dispatch(UndoProblem("problem-1", 7_100L))

        assertEquals(0, undone.completedProblemCount)
        assertEquals(0, duplicateUndo.completedProblemCount)
    }

    @Test
    fun `undo after five seconds is ignored`() {
        val schedule = StudySchedule(1_000L, 20_000L, 1_000L)
        val machine = SessionStateMachine(schedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))
        machine.dispatch(Tick(1_000L))
        machine.dispatch(ProblemCompleted("problem-1", 2_000L))

        val snapshot = machine.dispatch(UndoProblem("problem-1", 7_001L))

        assertEquals(1, snapshot.completedProblemCount)
    }

    @Test
    fun `stale elapsed time never rewinds the timer`() {
        val machine = SessionStateMachine(shortSchedule)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 1_000L))
        val advanced = machine.dispatch(Tick(1_500L))
        val stale = machine.dispatch(Tick(1_200L))

        assertEquals(500L, advanced.phaseRemainingMs)
        assertEquals(advanced, stale)
    }
}
