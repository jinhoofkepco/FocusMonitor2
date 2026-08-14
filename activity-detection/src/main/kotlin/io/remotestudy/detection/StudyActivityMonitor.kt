package io.remotestudy.detection

/**
 * Stateful interpretation of normalized frame differences.
 *
 * All time values must come from the same monotonic elapsed-time source. Stale
 * calls are ignored rather than rewinding timers. Unknown (null) evidence emits
 * nothing and leaves the corresponding timer and alert state unchanged.
 */
class StudyActivityMonitor(
    private val config: DetectionConfig = DetectionConfig(),
) {
    private var active = false
    private var latestElapsedMs: Long? = null

    private var absenceStartedAtElapsedMs: Long? = null
    private var awayEmitted = false

    private var lastBookMovementAtElapsedMs: Long? = null
    private var noMovementEmitted = false

    @Synchronized
    fun setActive(active: Boolean, atElapsedMs: Long) {
        require(atElapsedMs >= 0L) { "atElapsedMs must not be negative" }
        if (!acceptTimestamp(atElapsedMs)) return
        if (this.active == active) return

        this.active = active
        resetPresenceState()
        noMovementEmitted = false
        lastBookMovementAtElapsedMs = if (active) atElapsedMs else null
    }

    @Synchronized
    fun observe(evidence: FrameEvidence): List<DetectionEvent> {
        if (!acceptTimestamp(evidence.observedAtElapsedMs)) return emptyList()
        if (!active) return emptyList()

        return buildList(capacity = 2) {
            observePresence(evidence)?.let(::add)
            observeBookMovement(evidence)?.let(::add)
        }
    }

    private fun observePresence(evidence: FrameEvidence): DetectionEvent? {
        val difference = evidence.presenceDifference ?: return null
        val observedAt = evidence.observedAtElapsedMs

        if (difference > config.presenceAbsenceThreshold) {
            val absenceStartedAt = absenceStartedAtElapsedMs
                ?: observedAt.also { absenceStartedAtElapsedMs = it }
            val duration = nonnegativeDuration(absenceStartedAt, observedAt)
            if (!awayEmitted && duration >= config.awayAfterMs) {
                awayEmitted = true
                return DetectionEvent(DetectionEventKind.AWAY, duration)
            }
            return null
        }

        val absenceStartedAt = absenceStartedAtElapsedMs
        val shouldRestore = awayEmitted && absenceStartedAt != null
        val duration = absenceStartedAt?.let { nonnegativeDuration(it, observedAt) } ?: 0L
        resetPresenceState()
        return if (shouldRestore) {
            DetectionEvent(DetectionEventKind.PRESENCE_RESTORED, duration)
        } else {
            null
        }
    }

    private fun observeBookMovement(evidence: FrameEvidence): DetectionEvent? {
        val movement = evidence.bookMovement ?: return null
        val observedAt = evidence.observedAtElapsedMs
        val lastMovementAt = lastBookMovementAtElapsedMs ?: observedAt.also {
            lastBookMovementAtElapsedMs = it
        }

        if (movement >= config.bookMovementThreshold) {
            val duration = nonnegativeDuration(lastMovementAt, observedAt)
            val shouldRestore = noMovementEmitted
            lastBookMovementAtElapsedMs = observedAt
            noMovementEmitted = false
            return if (shouldRestore) {
                DetectionEvent(DetectionEventKind.BOOK_MOVEMENT_RESTORED, duration)
            } else {
                null
            }
        }

        val duration = nonnegativeDuration(lastMovementAt, observedAt)
        if (!noMovementEmitted && duration >= config.noMovementAfterMs) {
            noMovementEmitted = true
            return DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, duration)
        }
        return null
    }

    private fun acceptTimestamp(atElapsedMs: Long): Boolean {
        val latest = latestElapsedMs
        if (latest != null && atElapsedMs < latest) return false
        latestElapsedMs = atElapsedMs
        return true
    }

    private fun resetPresenceState() {
        absenceStartedAtElapsedMs = null
        awayEmitted = false
    }

    private fun nonnegativeDuration(startedAt: Long, observedAt: Long): Long =
        (observedAt - startedAt).coerceAtLeast(0L)
}
