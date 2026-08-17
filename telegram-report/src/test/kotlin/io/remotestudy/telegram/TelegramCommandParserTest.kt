package io.remotestudy.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCommandParserTest {
    private val parser = TelegramCommandParser()

    @Test fun parsesSessionCommands() {
        assertEquals(TelegramCommand.Start, parser.parse("/start"))
        assertEquals(TelegramCommand.Pause, parser.parse("/pause"))
        assertEquals(TelegramCommand.Resume, parser.parse("/resume"))
        assertEquals(TelegramCommand.Stop, parser.parse("/stop"))
        assertEquals(TelegramCommand.Index, parser.parse("/index"))
    }

    @Test fun parsesAllBookSelectors() {
        assertEquals(TelegramCommand.Book(BookSelection.Minute(14, 3)), parser.parse("/b 14:03"))
        assertEquals(TelegramCommand.Book(BookSelection.Exact(14, 3, 20)), parser.parse("/b 14:03:20"))
        assertEquals(TelegramCommand.Book(BookSelection.Range(14, 3, 14, 5)), parser.parse("/b 14:03-14:05"))
        assertEquals(TelegramCommand.Book(BookSelection.RecentMinutes(5)), parser.parse("/b -5"))
    }

    @Test fun rejectsInvalidTimeAndWideRecentValue() {
        assertTrue(parser.parse("/b 25:00") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/b -9999") is TelegramCommand.Unknown)
    }

    @Test fun acceptsBotUsernameWithoutTruncatingOrdinaryMessages() {
        assertEquals(TelegramCommand.Start, parser.parse("/start@focus_monitor_bot"))
        assertEquals(
            TelegramCommand.Book(BookSelection.Minute(14, 3)),
            parser.parse("/b@focus_monitor_bot 14:03"),
        )
        assertEquals(TelegramCommand.Unknown("teacher@example.com"), parser.parse("teacher@example.com"))
    }
}
