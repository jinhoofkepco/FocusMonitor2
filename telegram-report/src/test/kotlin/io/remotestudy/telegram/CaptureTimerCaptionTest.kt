package io.remotestudy.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CaptureTimerCaptionTest {
    @Test fun singlePhaseUsesLastPhotoRemainingTime() {
        val states = listOf(
            CaptureTimerState(CaptureTimerPhase.STUDY, 39L * 60_000L),
            CaptureTimerState(CaptureTimerPhase.STUDY, 38L * 60_000L + 50_000L),
        )

        assertEquals(
            "공부 · 마지막 사진 기준 38:50 남음",
            CaptureTimerCaption.format(states),
        )
    }

    @Test fun phaseBoundaryShowsTransitionAndLastPhase() {
        val states = listOf(
            CaptureTimerState(CaptureTimerPhase.STUDY, 10_000L),
            CaptureTimerState(CaptureTimerPhase.BREAK, 15L * 60_000L),
            CaptureTimerState(CaptureTimerPhase.BREAK, 14L * 60_000L + 50_000L),
        )

        assertEquals(
            "공부→휴식 · 마지막 사진 기준 휴식 14:50 남음",
            CaptureTimerCaption.format(states),
        )
    }

    @Test fun missingLastCellStateDoesNotClaimItDescribesLastPhoto() {
        assertNull(
            CaptureTimerCaption.format(
                listOf(CaptureTimerState(CaptureTimerPhase.MEDITATION, 1_000L), null),
            ),
        )
    }

    @Test fun montageResultPreservesTimerStateForEveryCell() {
        val states = listOf(
            CaptureTimerState(CaptureTimerPhase.STUDY, 40L * 60_000L),
            CaptureTimerState(CaptureTimerPhase.STUDY, 39L * 60_000L + 50_000L),
        )
        val result = MontageComposer.MontageResult(
            sequence = 1,
            firstCapturedAtEpochMs = 1_000L,
            lastCapturedAtEpochMs = 11_000L,
            file = File("unused.jpg"),
            cells = 2,
            capturedAtEpochMs = listOf(1_000L, 11_000L),
            timerStates = states,
        )

        assertEquals(states, result.timerStates)
        assertEquals(result.cells, result.timerStates.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun remainingTimeCannotBeNegative() {
        CaptureTimerState(CaptureTimerPhase.BREAK, -1L)
    }
}
