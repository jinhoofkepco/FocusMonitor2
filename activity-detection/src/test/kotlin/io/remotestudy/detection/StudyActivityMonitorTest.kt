package io.remotestudy.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyActivityMonitorTest {
    private val config = DetectionConfig(
        presenceAbsenceThreshold = 0.18f,
        bookMovementThreshold = 0.012f,
        awayAfterMs = 10_000L,
        noMovementAfterMs = 30_000L,
    )

    @Test
    fun `config defaults match product thresholds`() {
        assertEquals(config, DetectionConfig())
    }

    @Test
    fun `config evidence and public time inputs are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionConfig(presenceAbsenceThreshold = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionConfig(bookMovementThreshold = 1.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionConfig(awayAfterMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionConfig(noMovementAfterMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameEvidence(0L, presenceDifference = -0.01f, bookMovement = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyActivityMonitor().setActive(true, -1L)
        }
    }

    @Test
    fun `inactive observations emit nothing and do not accumulate`() {
        val monitor = StudyActivityMonitor(config)

        assertTrue(monitor.observe(frame(0L, presence = 1f, movement = 0f)).isEmpty())
        assertTrue(monitor.observe(frame(60_000L, presence = 1f, movement = 0f)).isEmpty())

        monitor.setActive(true, 70_000L)
        assertTrue(monitor.observe(frame(99_999L, presence = 0f, movement = 0f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, 30_000L)),
            monitor.observe(frame(100_000L, presence = 0f, movement = 0f)),
        )
    }

    @Test
    fun `away fires once at boundary and restores once at inclusive presence threshold`() {
        val monitor = activeMonitor()

        assertTrue(monitor.observe(frame(100L, presence = 0.19f)).isEmpty())
        assertTrue(monitor.observe(frame(10_099L, presence = 0.19f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.AWAY, 10_000L)),
            monitor.observe(frame(10_100L, presence = 0.19f)),
        )
        assertTrue(monitor.observe(frame(11_000L, presence = 1f)).isEmpty())
        assertTrue(monitor.observe(frame(12_000L, presence = 0.12f)).isEmpty())
        assertTrue(monitor.observe(frame(13_000L, presence = 0.12f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.PRESENCE_RESTORED, 13_900L)),
            monitor.observe(frame(14_000L, presence = 0.12f)),
        )
    }

    @Test
    fun `short absence resets without a restored event`() {
        val monitor = activeMonitor()

        assertTrue(monitor.observe(frame(1_000L, presence = 1f)).isEmpty())
        assertTrue(monitor.observe(frame(10_999L, presence = 0f)).isEmpty())
        assertTrue(monitor.observe(frame(20_000L, presence = 0f)).isEmpty())
    }

    @Test
    fun `small frame motion keeps a still student present even when posture differs from baseline`() {
        val monitor = activeMonitor()

        assertTrue(monitor.observe(frame(0L, presence = 0.4f, presenceMotion = 0.01f)).isEmpty())
        assertTrue(monitor.observe(frame(10_000L, presence = 0.4f, presenceMotion = 0.01f)).isEmpty())
        assertTrue(monitor.observe(frame(60_000L, presence = 0.4f, presenceMotion = 0.01f)).isEmpty())
    }

    @Test
    fun `restore requires three frames and cooldown suppresses rapid repeated away alert`() {
        val monitor = activeMonitor()
        monitor.observe(frame(0L, presence = 0.4f, presenceMotion = 0f))
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.AWAY, 10_000L)),
            monitor.observe(frame(10_000L, presence = 0.4f, presenceMotion = 0f)),
        )
        assertTrue(monitor.observe(frame(11_000L, presence = 0.1f)).isEmpty())
        assertTrue(monitor.observe(frame(12_000L, presence = 0.1f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.PRESENCE_RESTORED, 13_000L)),
            monitor.observe(frame(13_000L, presence = 0.1f)),
        )
        assertTrue(monitor.observe(frame(14_000L, presence = 0.4f, presenceMotion = 0f)).isEmpty())
        assertTrue(monitor.observe(frame(24_000L, presence = 0.4f, presenceMotion = 0f)).isEmpty())
    }

    @Test
    fun `no movement fires once at boundary and movement restores once`() {
        val monitor = activeMonitor()

        assertTrue(monitor.observe(frame(29_999L, movement = 0.011f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, 30_000L)),
            monitor.observe(frame(30_000L, movement = 0.011f)),
        )
        assertTrue(monitor.observe(frame(31_000L, movement = 0f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.BOOK_MOVEMENT_RESTORED, 32_000L)),
            monitor.observe(frame(32_000L, movement = 0.012f)),
        )
        assertTrue(monitor.observe(frame(33_000L, movement = 1f)).isEmpty())
    }

    @Test
    fun `movement before timeout moves the no movement reference`() {
        val monitor = activeMonitor()

        assertTrue(monitor.observe(frame(20_000L, movement = 0.5f)).isEmpty())
        assertTrue(monitor.observe(frame(49_999L, movement = 0f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, 30_000L)),
            monitor.observe(frame(50_000L, movement = 0f)),
        )
    }

    @Test
    fun `unknown evidence neither emits nor changes timer state`() {
        val monitor = activeMonitor()

        monitor.observe(frame(0L, presence = 1f, movement = 0f))
        assertTrue(monitor.observe(frame(10_000L)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.AWAY, 10_000L)),
            monitor.observe(frame(10_000L, presence = 1f)),
        )

        assertTrue(monitor.observe(frame(30_000L)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, 30_000L)),
            monitor.observe(frame(30_000L, movement = 0f)),
        )
    }

    @Test
    fun `stale evidence and stale activation changes are ignored`() {
        val monitor = activeMonitor(startedAt = 1_000L)
        monitor.observe(frame(1_000L, presence = 1f, movement = 0f))
        monitor.observe(frame(11_000L, presence = 1f, movement = 0f))

        assertTrue(monitor.observe(frame(5_000L, presence = 0f, movement = 1f)).isEmpty())
        monitor.setActive(false, 5_000L)

        assertTrue(monitor.observe(frame(12_000L, presence = 0f)).isEmpty())
        assertTrue(monitor.observe(frame(13_000L, presence = 0f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.PRESENCE_RESTORED, 13_000L)),
            monitor.observe(frame(14_000L, presence = 0f)),
        )
    }

    @Test
    fun `deactivation clears alert state and reactivation starts a fresh book timer`() {
        val monitor = activeMonitor()
        monitor.observe(frame(0L, presence = 1f, movement = 0f))
        monitor.observe(frame(10_000L, presence = 1f, movement = 0f))
        monitor.observe(frame(30_000L, presence = 1f, movement = 0f))

        monitor.setActive(false, 31_000L)
        assertTrue(monitor.observe(frame(90_000L, presence = 0f, movement = 1f)).isEmpty())
        monitor.setActive(true, 100_000L)

        assertTrue(monitor.observe(frame(129_999L, presence = 0f, movement = 0f)).isEmpty())
        assertEquals(
            listOf(DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, 30_000L)),
            monitor.observe(frame(130_000L, presence = 0f, movement = 0f)),
        )
    }

    @Test
    fun `duplicate boundary observations cannot repeat events and durations stay nonnegative`() {
        val monitor = activeMonitor()
        monitor.observe(frame(0L, presence = 1f, movement = 0f))

        val first = monitor.observe(frame(30_000L, presence = 1f, movement = 0f))
        val duplicate = monitor.observe(frame(30_000L, presence = 1f, movement = 0f))

        assertEquals(
            listOf(
                DetectionEvent(DetectionEventKind.AWAY, 30_000L),
                DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, 30_000L),
            ),
            first,
        )
        assertTrue(duplicate.isEmpty())
        assertTrue(first.all { it.observedDurationMs >= 0L })
    }

    private fun activeMonitor(startedAt: Long = 0L): StudyActivityMonitor =
        StudyActivityMonitor(config).also { it.setActive(true, startedAt) }

    private fun frame(
        at: Long,
        presence: Float? = null,
        presenceMotion: Float? = null,
        movement: Float? = null,
    ) = FrameEvidence(
        observedAtElapsedMs = at,
        presenceDifference = presence,
        presenceMotion = presenceMotion,
        bookMovement = movement,
    )
}
