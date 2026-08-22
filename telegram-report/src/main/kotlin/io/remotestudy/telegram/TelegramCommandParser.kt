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
            text == "/focus" -> TelegramCommand.Refocus
            text == "/camera" -> TelegramCommand.ShowCameraMenu
            text.startsWith("/camera ") -> parseCamera(text.removePrefix("/camera ").trim())
            text == "/index" -> TelegramCommand.Index
            text == "/status" -> TelegramCommand.Status
            text == "/menu" || text == "/help" -> TelegramCommand.Menu
            text == "/area" -> TelegramCommand.ShowAreaGrid
            text.startsWith("/area ") -> parseArea(text.removePrefix("/area ").trim())
            text == "/rotate" -> TelegramCommand.ShowBookRotation
            text.startsWith("/rotate ") -> parseRotation(text.removePrefix("/rotate ").trim())
            text.startsWith("/begin ") -> parseBegin(text.removePrefix("/begin ").trim())
            text.startsWith("/set ") -> parseSetting(text.removePrefix("/set ").trim())
            text.startsWith("/time ") -> parseRemaining(text.removePrefix("/time ").trim())
            text.startsWith("/phase ") -> parsePhase(text.removePrefix("/phase ").trim())
            text.startsWith("/b ") -> parseBook(text.removePrefix("/b ").trim())
            else -> TelegramCommand.Unknown(raw)
        }
    }

    private fun parseSetting(argument: String): TelegramCommand {
        val fields = argument.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        parseScheduleValues(fields)?.let { values ->
            return TelegramCommand.SetSchedule(values[0], values[1], values[2])
        }
        if (fields.size == 2 && fields[0] in setOf("countdown", "대기")) {
            val seconds = fields[1].toIntOrNull()
            if (seconds != null && seconds in 0..60) return TelegramCommand.SetCountdown(seconds)
        }
        return TelegramCommand.Unknown("/set $argument")
    }

    private fun parseBegin(argument: String): TelegramCommand {
        val fields = argument.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val values = parseScheduleValues(fields) ?: return TelegramCommand.Unknown("/begin $argument")
        return TelegramCommand.BeginSchedule(values[0], values[1], values[2])
    }

    private fun parseScheduleValues(fields: List<String>): List<Int>? {
        if (fields.size != 3) return null
        val values = fields.map { it.toIntOrNull() ?: return null }
        return values.takeIf {
            values[0] in 0..120 && values[1] in 1..480 && values[2] in 1..240
        }
    }

    private fun parseArea(argument: String): TelegramCommand {
        val normalized = argument.uppercase().replace(Regex("\\s+"), " ").trim()
        val compact = AREA_COMPACT.matchEntire(normalized)
        val legacy = AREA_LEGACY.matchEntire(normalized)
        val left = compact?.groupValues?.get(1)?.get(0) ?: legacy?.groupValues?.get(1)?.get(0)
            ?: return TelegramCommand.Unknown("/area $argument")
        val right = compact?.groupValues?.get(1)?.get(1) ?: legacy?.groupValues?.get(3)?.get(0)
            ?: return TelegramCommand.Unknown("/area $argument")
        val packedRows = compact?.groupValues?.get(2)?.takeIf(String::isNotBlank)
        val topText = packedRows?.substring(0, 1)
            ?: compact?.groupValues?.get(3)?.takeIf(String::isNotBlank)
            ?: legacy?.groupValues?.get(2)
            ?: return TelegramCommand.Unknown("/area $argument")
        val bottomText = packedRows?.substring(1, 2)
            ?: compact?.groupValues?.get(4)?.takeIf(String::isNotBlank)
            ?: legacy?.groupValues?.get(4)
            ?: return TelegramCommand.Unknown("/area $argument")
        val leftCell = left - 'A'
        val topCell = topText.toInt() - 1
        val rightCell = right - 'A'
        val bottomCell = bottomText.toInt() - 1
        if (leftCell !in 0..9 || rightCell !in 0..9 || topCell !in 0..9 || bottomCell !in 0..9) {
            return TelegramCommand.Unknown("/area $argument")
        }
        if (rightCell < leftCell || bottomCell < topCell) return TelegramCommand.Unknown("/area $argument")
        return TelegramCommand.PreviewBookRegion(
            NormalizedBookRegion(
                left = leftCell / 10f,
                top = topCell / 10f,
                right = (rightCell + 1) / 10f,
                bottom = (bottomCell + 1) / 10f,
            ),
            "$left${topCell + 1}–$right${bottomCell + 1}",
        )
    }

    private fun parseRotation(argument: String): TelegramCommand =
        argument.toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) }
            ?.let { TelegramCommand.SetBookRotation(it) }
            ?: TelegramCommand.Unknown("/rotate $argument")

    private fun parseCamera(argument: String): TelegramCommand = when (argument.lowercase()) {
        "info", "status", "진단", "정보" -> TelegramCommand.CameraDiagnostics
        "test", "compare", "비교", "촬영" -> TelegramCommand.CameraComparison
        else -> TelegramCommand.Unknown("/camera $argument")
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
        // Preferred: /area IJ 56. Rows involving 10 use a separator: /area IJ 5-10.
        val AREA_COMPACT = Regex("^([A-J]{2})\\s+(?:([1-9]{2})|(10|[1-9])-(10|[1-9]))$")
        val AREA_LEGACY = Regex("^([A-J])(10|[1-9])\\s+([A-J])(10|[1-9])$")
    }
}
