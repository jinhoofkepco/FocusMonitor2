package io.remotestudy.student

import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionSnapshot
import io.remotestudy.domain.session.SessionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure timer text formatting shared by Telegram status and phase notifications. */
internal object TimerPresentation {
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
        return "%02d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
    }

    fun spokenDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (seconds == 0L) "${minutes}분" else "${minutes}분 ${seconds}초"
    }

    fun statusText(
        snapshot: SessionSnapshot,
        sessionActive: Boolean,
        awaitingBreakResume: Boolean,
        countdownPaused: Boolean,
        completedProblemCount: Int,
    ): String {
        if (awaitingBreakResume) {
            return "복귀 대기 · ‘시작할게’라고 말하세요 · 완료 ${completedProblemCount.coerceAtLeast(0)}개"
        }
        if (!sessionActive) {
            return "세션 대기 · 완료 ${completedProblemCount.coerceAtLeast(0)}개"
        }

        val phase = if (snapshot.status == SessionStatus.START_COUNTDOWN) {
            "시작 대기"
        } else {
            phaseLabel(snapshot.phase)
        }
        val remainingMs = if (snapshot.status == SessionStatus.START_COUNTDOWN) {
            snapshot.countdownRemainingMs
        } else {
            snapshot.phaseRemainingMs
        }
        val paused = if (
            snapshot.status == SessionStatus.PAUSED ||
            (countdownPaused && snapshot.status == SessionStatus.START_COUNTDOWN)
        ) {
            " · 일시정지"
        } else {
            ""
        }
        return "$phase · ${formatDuration(remainingMs)}$paused · 완료 " +
            "${completedProblemCount.coerceAtLeast(0)}개"
    }

    fun nextTransitionDescription(
        snapshot: SessionSnapshot,
        sessionActive: Boolean,
        awaitingBreakResume: Boolean,
        nowEpochMs: Long,
        zoneId: ZoneId,
    ): String? {
        if (!sessionActive || awaitingBreakResume || snapshot.status != SessionStatus.RUNNING) return null
        val next = when (snapshot.phase) {
            SessionPhase.MEDITATION -> "공부 시작"
            SessionPhase.STUDY -> "휴식 시작"
            SessionPhase.BREAK -> "휴식 종료·복귀 대기"
            SessionPhase.COMPLETE -> return null
        }
        val transitionAt = formatClock(nowEpochMs + snapshot.phaseRemainingMs.coerceAtLeast(0L), zoneId)
        return "다음 전환 · $next 예정 $transitionAt"
    }

    fun phaseStartMessage(
        snapshot: SessionSnapshot,
        origin: String,
        sessionActive: Boolean,
        awaitingBreakResume: Boolean,
        nowEpochMs: Long,
        zoneId: ZoneId,
    ): String? {
        if (!sessionActive || awaitingBreakResume || snapshot.status != SessionStatus.RUNNING) return null
        if (snapshot.phase == SessionPhase.COMPLETE) return null

        return buildString {
            append("[전환 알림] ").append(origin).append(" · ")
                .append(phaseLabel(snapshot.phase)).append(" 시작 · ")
                .append(formatDuration(snapshot.phaseRemainingMs)).append(" 남음")
            append("\n발생 시각 · ").append(formatClock(nowEpochMs, zoneId))
            nextTransitionDescription(
                snapshot = snapshot,
                sessionActive = sessionActive,
                awaitingBreakResume = awaitingBreakResume,
                nowEpochMs = nowEpochMs,
                zoneId = zoneId,
            )?.let { append('\n').append(it) }
        }
    }

    private fun phaseLabel(phase: SessionPhase): String = when (phase) {
        SessionPhase.MEDITATION -> "명상"
        SessionPhase.STUDY -> "공부"
        SessionPhase.BREAK -> "휴식"
        SessionPhase.COMPLETE -> "완료"
    }

    private fun formatClock(epochMs: Long, zoneId: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.KOREA)
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(epochMs))
}
