package io.remotestudy.student

import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionSnapshot
import io.remotestudy.domain.session.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakResumePolicyTest {
    @Test fun entersWaitingWheneverARunningTickCrossesTheBreakEnd() {
        assertTrue(
            BreakResumePolicy.shouldEnterWaiting(
                snapshot(SessionStatus.RUNNING, SessionPhase.BREAK, 1L),
                snapshot(SessionStatus.COMPLETED, SessionPhase.COMPLETE, 0L),
            ),
        )
        assertTrue(
            BreakResumePolicy.shouldEnterWaiting(
                snapshot(SessionStatus.RUNNING, SessionPhase.STUDY, 1L),
                snapshot(SessionStatus.COMPLETED, SessionPhase.COMPLETE, 0L),
            ),
        )
        assertFalse(
            BreakResumePolicy.shouldEnterWaiting(
                snapshot(SessionStatus.PAUSED, SessionPhase.BREAK, 1L),
                snapshot(SessionStatus.PAUSED, SessionPhase.BREAK, 1L),
            ),
        )
    }

    private fun snapshot(status: SessionStatus, phase: SessionPhase, remainingMs: Long) =
        SessionSnapshot(status, phase, remainingMs, 0L, 0, null, 0L)
}
