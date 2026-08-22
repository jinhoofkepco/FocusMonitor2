package io.remotestudy.student

import io.remotestudy.domain.session.Pause
import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionSnapshot
import io.remotestudy.domain.session.SessionStateMachine
import io.remotestudy.domain.session.SessionStatus
import io.remotestudy.domain.session.StartOrigin
import io.remotestudy.domain.session.StartRequested
import io.remotestudy.domain.session.StudySchedule
import io.remotestudy.domain.session.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimerPresentationTest {
    private val zoneId = ZoneId.of("Asia/Seoul")

    @Test fun exactlyFortyMinuteBoundaryStartsBreakAndFormatsTransitionMessage() {
        val schedule = StudySchedule(
            meditationDurationMs = 0L,
            studyDurationMs = 40L * 60_000L,
            breakDurationMs = 15L * 60_000L,
        )
        val machine = SessionStateMachine(schedule, teacherCountdownDurationMs = 1L)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))

        val justBefore = machine.dispatch(Tick(40L * 60_000L - 1L))
        assertEquals(SessionPhase.STUDY, justBefore.phase)
        assertEquals(1L, justBefore.phaseRemainingMs)

        val atBoundary = machine.dispatch(Tick(40L * 60_000L))
        assertEquals(SessionPhase.BREAK, atBoundary.phase)
        assertEquals(15L * 60_000L, atBoundary.phaseRemainingMs)

        val occurredAt = Instant.parse("2026-08-22T01:40:00Z").toEpochMilli()
        assertEquals(
            "[전환 알림] 자동 전환 · 휴식 시작 · 15:00 남음\n" +
                "발생 시각 · 10:40:00\n" +
                "다음 전환 · 휴식 종료·복귀 대기 예정 10:55:00",
            TimerPresentation.phaseStartMessage(
                snapshot = atBoundary,
                origin = "자동 전환",
                sessionActive = true,
                awaitingBreakResume = false,
                nowEpochMs = occurredAt,
                zoneId = zoneId,
            ),
        )
    }

    @Test fun pausedStudyHasPausedStatusAndNoTransitionEstimate() {
        val schedule = StudySchedule(0L, 40L * 60_000L, 15L * 60_000L)
        val machine = SessionStateMachine(schedule, teacherCountdownDurationMs = 1L)
        machine.dispatch(StartRequested("start", StartOrigin.STUDENT, 0L))
        val paused = machine.dispatch(Pause("pause", 5L * 60_000L))

        assertEquals(
            "공부 · 35:00 · 일시정지 · 완료 2개",
            TimerPresentation.statusText(
                snapshot = paused,
                sessionActive = true,
                awaitingBreakResume = false,
                countdownPaused = false,
                completedProblemCount = 2,
            ),
        )
        assertNull(
            TimerPresentation.nextTransitionDescription(
                snapshot = paused,
                sessionActive = true,
                awaitingBreakResume = false,
                nowEpochMs = 0L,
                zoneId = zoneId,
            ),
        )
    }

    @Test fun breakResumeWaitUsesExplicitWaitingStatusAndSuppressesPhaseMessage() {
        val waiting = SessionSnapshot(
            status = SessionStatus.PAUSED,
            phase = SessionPhase.STUDY,
            phaseRemainingMs = 40L * 60_000L,
            countdownRemainingMs = 0L,
            completedProblemCount = 0,
            startedBy = StartOrigin.TEACHER,
            observedAtElapsedMs = 0L,
        )

        assertEquals(
            "복귀 대기 · ‘시작할게’라고 말하세요 · 완료 3개",
            TimerPresentation.statusText(
                snapshot = waiting,
                sessionActive = true,
                awaitingBreakResume = true,
                countdownPaused = false,
                completedProblemCount = 3,
            ),
        )
        assertNull(
            TimerPresentation.phaseStartMessage(
                snapshot = waiting,
                origin = "자동 전환",
                sessionActive = true,
                awaitingBreakResume = true,
                nowEpochMs = 0L,
                zoneId = zoneId,
            ),
        )
    }

    @Test fun durationFormattingClampsNegativeAndKeepsLongMinutes() {
        assertEquals("00:00", TimerPresentation.formatDuration(-1L))
        assertEquals("125:07", TimerPresentation.formatDuration(125L * 60_000L + 7_999L))
        assertEquals("25분", TimerPresentation.spokenDuration(25L * 60_000L))
        assertEquals("25분 30초", TimerPresentation.spokenDuration(25L * 60_000L + 30_000L))
    }
}
