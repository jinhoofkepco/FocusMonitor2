package io.remotestudy.student

import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionSnapshot
import io.remotestudy.domain.session.SessionStatus

internal object BreakResumePolicy {
    fun shouldEnterWaiting(before: SessionSnapshot, after: SessionSnapshot): Boolean =
        before.status == SessionStatus.RUNNING &&
            before.phase != SessionPhase.COMPLETE &&
            after.status == SessionStatus.COMPLETED
}
