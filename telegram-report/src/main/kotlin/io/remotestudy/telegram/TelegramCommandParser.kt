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
            text == "/index" -> TelegramCommand.Index
            text == "/status" -> TelegramCommand.Status
            text.startsWith("/b ") -> parseBook(text.removePrefix("/b ").trim())
            else -> TelegramCommand.Unknown(raw)
        }
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
