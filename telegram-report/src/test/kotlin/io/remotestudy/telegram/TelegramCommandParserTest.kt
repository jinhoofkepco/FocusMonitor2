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
        assertEquals(TelegramCommand.Restart, parser.parse("/restart"))
        assertEquals(TelegramCommand.NextPhase, parser.parse("/next"))
        assertEquals(TelegramCommand.Settings, parser.parse("/settings"))
        assertEquals(TelegramCommand.Refocus, parser.parse("/focus"))
        assertEquals(TelegramCommand.ShowCameraMenu, parser.parse("/camera"))
        assertEquals(TelegramCommand.Index, parser.parse("/index"))
        assertEquals(TelegramCommand.Menu, parser.parse("/menu"))
        assertEquals(TelegramCommand.Menu, parser.parse("/help"))
    }

    @Test fun parsesRemoteTimerControls() {
        assertEquals(TelegramCommand.SetSchedule(0, 40, 15), parser.parse("/set 0 40 15"))
        assertEquals(TelegramCommand.BeginSchedule(0, 40, 15), parser.parse("/begin 0 40 15"))
        assertEquals(
            TelegramCommand.BeginSchedule(5, 40, 15),
            parser.parse("/begin@focus_monitor_bot 5 40 15"),
        )
        assertEquals(TelegramCommand.SetCountdown(0), parser.parse("/set countdown 0"))
        assertEquals(TelegramCommand.SetCountdown(5), parser.parse("/set 대기 5"))
        assertEquals(TelegramCommand.SetRemaining(25 * 60), parser.parse("/time 25"))
        assertEquals(TelegramCommand.SetRemaining(25 * 60 + 30), parser.parse("/time 25:30"))
        assertEquals(
            TelegramCommand.GoToPhase(RemoteSessionPhase.STUDY, null),
            parser.parse("/phase 공부"),
        )
        assertEquals(
            TelegramCommand.GoToPhase(RemoteSessionPhase.BREAK, 10 * 60),
            parser.parse("/phase break 10"),
        )
    }

    @Test fun parsesTenByTenAreaSelection() {
        assertEquals(TelegramCommand.ShowAreaGrid, parser.parse("/area"))
        assertEquals(
            TelegramCommand.PreviewBookRegion(
                NormalizedBookRegion(0.1f, 0.1f, 0.8f, 0.8f),
                "B2–H8",
            ),
            parser.parse("/area b2 h8"),
        )
        assertEquals(
            TelegramCommand.PreviewBookRegion(
                NormalizedBookRegion(0.8f, 0.4f, 1.0f, 0.6f),
                "I5–J6",
            ),
            parser.parse("/area ij 56"),
        )
        assertEquals(
            TelegramCommand.PreviewBookRegion(
                NormalizedBookRegion(0.8f, 0.4f, 1.0f, 1.0f),
                "I5–J10",
            ),
            parser.parse("/area IJ 5-10"),
        )
        assertTrue(parser.parse("/area H8 B2") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/area JI 65") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/area A0 J10") is TelegramCommand.Unknown)
    }

    @Test fun parsesBookRotation() {
        assertEquals(TelegramCommand.ShowBookRotation, parser.parse("/rotate"))
        assertEquals(TelegramCommand.SetBookRotation(180), parser.parse("/rotate 180"))
        assertTrue(parser.parse("/rotate 45") is TelegramCommand.Unknown)
    }

    @Test fun parsesCameraDiagnosticsAndComparison() {
        assertEquals(TelegramCommand.CameraDiagnostics, parser.parse("/camera info"))
        assertEquals(TelegramCommand.CameraDiagnostics, parser.parse("/camera 진단"))
        assertEquals(TelegramCommand.CameraComparison, parser.parse("/camera test"))
        assertEquals(TelegramCommand.CameraComparison, parser.parse("/camera 비교"))
        assertEquals(TelegramCommand.CameraComparison, parser.parse("/camera@focus_monitor_bot test"))
        assertTrue(parser.parse("/camera tele") is TelegramCommand.Unknown)
    }

    @Test fun rejectsUnsafeRemoteTimerValues() {
        assertTrue(parser.parse("/set -1 40 15") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/set 0 0 15") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin -1 40 15") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin 0 0 15") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin 0 40 0") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin 0 40") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin 121 40 15") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin 0 481 15") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/begin 0 40 241") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/set countdown 61") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/time 481") is TelegramCommand.Unknown)
        assertTrue(parser.parse("/phase study 10:99") is TelegramCommand.Unknown)
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
