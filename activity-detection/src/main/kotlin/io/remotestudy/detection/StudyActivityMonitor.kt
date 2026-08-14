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
    private var awayNotificationEmitted = false
    private var lastAwayNotificationAtElapsedMs: Long? = null
    private var presenceConfirmationFrames = 0

    private var lastBookMovementAtElapsedMs: Long? = null
    private var noMovementEmitted = false
    private var lastNoMovementNotificationAtElapsedMs: Long? = null

    @Synchronized
    fun setActive(active: Boolean, atElapsedMs: Long) {
        require(atElapsedMs >= 0L) { "atElapsedMs must not be negative" }
        if (!acceptTimestamp(atElapsedMs)) return
        if (this.active == active) return

        this.active = active
        resetPresenceState()
        noMovementEmitted = false
        if (!active) {
            lastAwayNotificationAtElapsedMs = null
            lastNoMovementNotificationAtElapsedMs = null
        }
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
        val motion = evidence.presenceMotion ?: 0f
        val bookMotion = evidence.bookMovement ?: 0f
        val presenceEvidence = difference <= config.presenceRestoreThreshold ||
            motion >= config.presenceMotionThreshold ||
            bookMotion >= config.bookMovementThreshold

        if (awayEmitted) {
            presenceConfirmationFrames = if (presenceEvidence) presenceConfirmationFrames + 1 else 0
            if (presenceConfirmationFrames < PRESENCE_CONFIRMATION_FRAMES) return null
            val absenceStartedAt = absenceStartedAtElapsedMs
            val duration = absenceStartedAt?.let { nonnegativeDuration(it, observedAt) } ?: 0L
            val shouldRestore = awayNotificationEmitted
            resetPresenceState()
            return if (shouldRestore) {
                DetectionEvent(DetectionEventKind.PRESENCE_RESTORED, duration)
            } else {
                null
            }
        }

        if (difference > config.presenceAbsenceThreshold && !presenceEvidence) {
            val absenceStartedAt = absenceStartedAtElapsedMs
                ?: observedAt.also { absenceStartedAtElapsedMs = it }
            val duration = nonnegativeDuration(absenceStartedAt, observedAt)
            if (!awayEmitted && duration >= config.awayAfterMs) {
                awayEmitted = true
                val lastAlertAt = lastAwayNotificationAtElapsedMs
                val cooldownPassed = lastAlertAt == null ||
                    observedAt - lastAlertAt >= config.alertCooldownMs
                if (cooldownPassed) {
                    lastAwayNotificationAtElapsedMs = observedAt
                    awayNotificationEmitted = true
                    return DetectionEvent(DetectionEventKind.AWAY, duration)
                }
            }
            return null
        }

        absenceStartedAtElapsedMs = null
        presenceConfirmationFrames = 0
        return null
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
            val lastAlertAt = lastNoMovementNotificationAtElapsedMs
            val cooldownPassed = lastAlertAt == null || observedAt - lastAlertAt >= config.alertCooldownMs
            if (cooldownPassed) {
                lastNoMovementNotificationAtElapsedMs = observedAt
                return DetectionEvent(DetectionEventKind.NO_BOOK_MOVEMENT, duration)
            }
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
        awayNotificationEmitted = false
        presenceConfirmationFrames = 0
    }

    private fun nonnegativeDuration(startedAt: Long, observedAt: Long): Long =
        (observedAt - startedAt).coerceAtLeast(0L)

    companion object {
        private const val PRESENCE_CONFIRMATION_FRAMES = 3
    }
}
