package io.remotestudy.telegram

class TelegramCommandParser {
    fun parse(raw: String): TelegramCommand {
        val trimmed = raw.trim()
        val text = if (trimmed.startsWith('/')) {
            val firstSpace = trimmed.indexOf(' ').takeIf { it >= 0 } ?: trimmed.length
            val command = trimmed.substring(0, firstSpace).substringBefore('@')
            command + trimmed.substring(firstSpace)
        } else trimmed
        return when {
            text == "/start" -> TelegramCommand.Start
            text == "/pause" -> TelegramCommand.Pause
            text == "/resume" -> TelegramCommand.Resume
            text == "/stop" -> TelegramCommand.Stop
            text == "/restart" -> TelegramCommand.Restart
            text == "/next" -> TelegramCommand.NextPhase
            text == "/settings" -> TelegramCommand.Settings
            text == "/index" -> TelegramCommand.Index
            text == "/status" -> TelegramCommand.Status
            text.startsWith("/set ") -> parseSetting(text.removePrefix("/set ").trim())
            text.startsWith("/time ") -> parseRemaining(text.removePrefix("/time ").trim())
            text.startsWith("/phase ") -> parsePhase(text.removePrefix("/phase ").trim())
            text.startsWith("/b ") -> parseBook(text.removePrefix("/b ").trim())
            else -> TelegramCommand.Unknown(raw)
        }
    }

    private fun parseSetting(argument: String): TelegramCommand {
        val fields = argument.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (fields.size == 3) {
            val values = fields.map { it.toIntOrNull() ?: return TelegramCommand.Unknown("/set $argument") }
            if (values[0] in 0..120 && values[1] in 1..480 && values[2] in 1..240) {
                return TelegramCommand.SetSchedule(values[0], values[1], values[2])
            }
        }
        if (fields.size == 2 && fields[0] in setOf("countdown", "대기")) {
            val seconds = fields[1].toIntOrNull()
            if (seconds != null && seconds in 0..60) return TelegramCommand.SetCountdown(seconds)
        }
        return TelegramCommand.Unknown("/set $argument")
    }

    private fun parseRemaining(argument: String): TelegramCommand {
        parseDurationSeconds(argument, maxMinutes = 480)?.let { return TelegramCommand.SetRemaining(it) }
        return TelegramCommand.Unknown("/time $argument")
    }

    private fun parsePhase(argument: String): TelegramCommand {
        val fields = argument.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (fields.isEmpty() || fields.size > 2) return TelegramCommand.Unknown("/phase $argument")
        val phase = when (fields[0]) {
            "meditation", "명상" -> RemoteSessionPhase.MEDITATION
            "study", "공부" -> RemoteSessionPhase.STUDY
            "break", "휴식" -> RemoteSessionPhase.BREAK
            else -> return TelegramCommand.Unknown("/phase $argument")
        }
        val remaining = fields.getOrNull(1)?.let {
            parseDurationSeconds(it, maxMinutes = 480) ?: return TelegramCommand.Unknown("/phase $argument")
        }
        return TelegramCommand.GoToPhase(phase, remaining)
    }

    private fun parseDurationSeconds(value: String, maxMinutes: Int): Int? {
        value.toIntOrNull()?.let { minutes ->
            if (minutes in 0..maxMinutes) return minutes * 60
        }
        val match = Regex("^(\\d{1,3}):(\\d{2})$").matchEntire(value) ?: return null
        val minutes = match.groupValues[1].toInt()
        val seconds = match.groupValues[2].toInt()
        return if (minutes in 0..maxMinutes && seconds in 0..59) minutes * 60 + seconds else null
    }

    private fun parseBook(argument: String): TelegramCommand {
        RECENT.matchEntire(argument)?.let {
            val minutes = it.groupValues[1].toIntOrNull() ?: return TelegramCommand.Unknown(argument)
            if (minutes in 1..24 * 60) return TelegramCommand.Book(BookSelection.RecentMinutes(minutes))
        }
        RANGE.matchEntire(argument)?.let {
            val values = it.groupValues.drop(1).map(String::toInt)
            if (valid(values[0], values[1]) && valid(values[2], values[3])) {
                return TelegramCommand.Book(BookSelection.Range(values[0], values[1], values[2], values[3]))
            }
        }
        EXACT.matchEntire(argument)?.let {
            val values = it.groupValues.drop(1).map(String::toInt)
            if (valid(values[0], values[1]) && values[2] in 0..59) {
                return TelegramCommand.Book(BookSelection.Exact(values[0], values[1], values[2]))
            }
        }
        MINUTE.matchEntire(argument)?.let {
            val values = it.groupValues.drop(1).map(String::toInt)
            if (valid(values[0], values[1])) {
                return TelegramCommand.Book(BookSelection.Minute(values[0], values[1]))
            }
        }
        return TelegramCommand.Unknown("/b $argument")
    }

    private fun valid(hour: Int, minute: Int) = hour in 0..23 && minute in 0..59

    private companion object {
        val MINUTE = Regex("^(\\d{1,2}):(\\d{2})$")
        val EXACT = Regex("^(\\d{1,2}):(\\d{2}):(\\d{2})$")
        val RANGE = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$")
        val RECENT = Regex("^-(\\d+)$")
    }
}
