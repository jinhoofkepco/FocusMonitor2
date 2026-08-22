package io.remotestudy.telegram

internal object CaptureTimerCaption {
    fun format(states: List<CaptureTimerState?>): String? {
        val lastState = states.lastOrNull() ?: return null
        val phases = states.mapNotNull { it?.phase }
            .fold(mutableListOf<CaptureTimerPhase>()) { result, phase ->
                if (result.lastOrNull() != phase) result += phase
                result
            }
        if (phases.isEmpty()) return null

        val timeline = phases.joinToString("→", transform = ::label)
        val lastPhase = if (phases.size > 1) "${label(lastState.phase)} " else ""
        return "$timeline · 마지막 사진 기준 $lastPhase${remaining(lastState.phaseRemainingMs)} 남음"
    }

    private fun label(phase: CaptureTimerPhase): String = when (phase) {
        CaptureTimerPhase.MEDITATION -> "명상"
        CaptureTimerPhase.STUDY -> "공부"
        CaptureTimerPhase.BREAK -> "휴식"
    }

    private fun remaining(remainingMs: Long): String {
        val totalSeconds = remainingMs / 1_000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}
