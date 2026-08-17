package io.remotestudy.telegram

import java.io.File

data class TelegramConfig(
    val botToken: String,
    val allowedChatId: Long,
    val captureIntervalMs: Long = 10_000L,
    val montagePeriodMs: Long = 60_000L,
    val originalBudgetBytes: Long = 300L * 1024L * 1024L,
) {
    val enabled: Boolean get() = botToken.isNotBlank() && allowedChatId != 0L
    val cellsPerMontage: Int = (montagePeriodMs / captureIntervalMs).toInt().coerceAtLeast(1)

    init {
        require(captureIntervalMs > 0L)
        require(montagePeriodMs >= captureIntervalMs)
        require(originalBudgetBytes > 0L)
    }
}

sealed interface TelegramCommand {
    data object Start : TelegramCommand
    data object Pause : TelegramCommand
    data object Resume : TelegramCommand
    data object Stop : TelegramCommand
    data object Restart : TelegramCommand
    data object NextPhase : TelegramCommand
    data object Settings : TelegramCommand
    data object Index : TelegramCommand
    data object Status : TelegramCommand
    data class SetSchedule(
        val meditationMinutes: Int,
        val studyMinutes: Int,
        val breakMinutes: Int,
    ) : TelegramCommand
    data class SetCountdown(val seconds: Int) : TelegramCommand
    data class SetRemaining(val seconds: Int) : TelegramCommand
    data class GoToPhase(val phase: RemoteSessionPhase, val remainingSeconds: Int?) : TelegramCommand
    data class Book(val selection: BookSelection) : TelegramCommand
    data class Unknown(val input: String) : TelegramCommand
}

enum class RemoteSessionPhase { MEDITATION, STUDY, BREAK }

sealed interface BookSelection {
    data class Minute(val hour: Int, val minute: Int) : BookSelection
    data class Exact(val hour: Int, val minute: Int, val second: Int) : BookSelection
    data class Range(val startHour: Int, val startMinute: Int, val endHour: Int, val endMinute: Int) : BookSelection
    data class RecentMinutes(val minutes: Int) : BookSelection
}

data class TelegramUpdate(val updateId: Long, val chatId: Long?, val text: String?)
data class TelegramApiResult(val messageId: Long? = null)

data class TelegramSetupChat(
    val updateId: Long,
    val chatId: Long,
    val displayName: String,
    val username: String?,
)

data class ArchivedOriginal(val capturedAtEpochMs: Long, val file: File)

data class NormalizedBookRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    companion object {
        val DEFAULT = NormalizedBookRegion(0.07f, 0.30f, 0.93f, 0.70f)
    }
}

enum class UploadKind { PHOTO, DOCUMENT, MESSAGE, MESSAGE_AND_PIN }

data class UploadEntry(
    val id: String,
    val kind: UploadKind,
    val filePath: String?,
    val text: String,
    val attempts: Int,
    val nextAttemptEpochMs: Long,
)

interface TelegramCommandHandler {
    /** Returning normally means all resulting work is durably queued. */
    fun handle(command: TelegramCommand)
}
