package io.remotestudy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StudyWireCodecTest {
    @Test
    fun `all message variants round trip`() {
        val messages = listOf(
            StudyMessage.Hello("m1", "학생폰", PeerRole.STUDENT),
            StudyMessage.StartRequest("m2", WireStartOrigin.TEACHER),
            StudyMessage.SessionSnapshot(
                "m3",
                "session-1",
                WireSessionStatus.RUNNING,
                WireSessionPhase.STUDY,
                2_399_000,
                7,
                19,
            ),
            StudyMessage.ProblemCompleted("m4", "event-1", 8),
            StudyMessage.Alert("m5", AlertKind.AWAY, 10_000),
            StudyMessage.Alert("m6", AlertKind.NO_BOOK_MOVEMENT, 30_000),
            StudyMessage.Alert("m7", AlertKind.PRESENCE_RESTORED, 12_000),
            StudyMessage.Alert("m8", AlertKind.BOOK_MOVEMENT_RESTORED, 33_000),
            StudyMessage.AssetTransfer(
                messageId = "m9",
                assetId = "snapshot-1-thumbnail",
                kind = AssetKind.THUMBNAIL,
                payloadId = 41,
                capturedAtEpochMs = 1_723_624_500_000,
            ),
            StudyMessage.AssetTransfer(
                messageId = "m10",
                assetId = "snapshot-1-book",
                kind = AssetKind.BOOK_ROI,
                payloadId = 42,
                capturedAtEpochMs = 1_723_624_500_000,
            ),
            StudyMessage.AssetRequest("m11", "snapshot-1-thumbnail", AssetKind.THUMBNAIL),
            StudyMessage.AssetRequest("m12", "snapshot-1-book", AssetKind.BOOK_ROI),
            StudyMessage.AssetTransfer(
                "m12a", "fresh-2x-calibration", AssetKind.BOOK_CALIBRATION, 44,
                1_723_624_500_000,
            ),
            StudyMessage.AssetRequest("m12b", "fresh-2x-calibration", AssetKind.BOOK_CALIBRATION),
            StudyMessage.AssetRequest("m12c", "compare-1x", AssetKind.CAMERA_COMPARE_1X),
            StudyMessage.AssetRequest("m12d", "compare-2x", AssetKind.CAMERA_COMPARE_2X),
            StudyMessage.AssetRequest("m12e", "compare-3x", AssetKind.CAMERA_COMPARE_3X),
            StudyMessage.TextMessage("m13", PeerRole.STUDENT, "이 문제를 모르겠어요.", 1_723_624_510_000),
            StudyMessage.VoiceTransfer(
                messageId = "m14",
                userMessageId = "user-message-1",
                sender = PeerRole.TEACHER,
                payloadId = 43,
                sentAtEpochMs = 1_723_624_520_000,
                durationMs = 12_500,
            ),
            StudyMessage.StudySettings(
                "m15", 300_000, 2_400_000, 900_000, 5_000, 10_000,
                10_000, 30_000, 0.18f, 0.012f,
            ),
            StudyMessage.BookRegionSettings(
                "m16", 0.1f, 0.2f, 0.9f, 0.8f,
                DetailCaptureMode.STANDARD_12_MP, 3f, 3_000L,
            ),
            StudyMessage.CameraProfileStatus(
                "m17", DetailCaptureMode.ULTRA_50_MP, DetailCaptureMode.STANDARD_12_MP,
                4_000, 3_000, false,
            ),
            StudyMessage.Ack("m18", "m14"),
        )

        messages.forEach { message ->
            assertEquals(message, StudyWireCodec.decode(StudyWireCodec.encode(message)))
        }
    }

    @Test
    fun `invalid magic is rejected`() {
        val encoded = StudyWireCodec.encode(StudyMessage.Ack("m1", "m0"))
        encoded[0] = 0

        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.decode(encoded)
        }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val encoded = StudyWireCodec.encode(StudyMessage.Ack("m1", "m0")) + byteArrayOf(1)

        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.decode(encoded)
        }
    }

    @Test
    fun `payloads larger than nearby bytes limit are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.decode(ByteArray(32 * 1024 + 1))
        }
    }

    @Test
    fun `negative alert duration is rejected before encoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.encode(StudyMessage.Alert("m1", AlertKind.AWAY, -1))
        }
    }

    @Test
    fun `negative asset timestamp is rejected before encoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.encode(
                StudyMessage.AssetTransfer("m1", "asset-1", AssetKind.BOOK_ROI, -1, -1),
            )
        }
    }

    @Test
    fun `nearby signed payload identifiers round trip`() {
        listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE).forEachIndexed { index, payloadId ->
            val asset = StudyMessage.AssetTransfer(
                messageId = "asset-$index",
                assetId = "asset-id-$index",
                kind = AssetKind.BOOK_ROI,
                payloadId = payloadId,
                capturedAtEpochMs = 0,
            )
            val voice = StudyMessage.VoiceTransfer(
                messageId = "voice-$index",
                userMessageId = "user-$index",
                sender = PeerRole.STUDENT,
                payloadId = payloadId,
                sentAtEpochMs = 0,
                durationMs = 1,
            )

            assertEquals(asset, StudyWireCodec.decode(StudyWireCodec.encode(asset)))
            assertEquals(voice, StudyWireCodec.decode(StudyWireCodec.encode(voice)))
        }
    }

    @Test
    fun `text message enforces four kibibyte utf8 boundary`() {
        val atLimit = "가".repeat(1_365) + "a"
        val message = StudyMessage.TextMessage("m1", PeerRole.STUDENT, atLimit, 0)

        assertEquals(message, StudyWireCodec.decode(StudyWireCodec.encode(message)))
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.encode(message.copy(text = atLimit + "a"))
        }
    }

    @Test
    fun `voice duration accepts inclusive boundaries`() {
        listOf(1L, 60_000L).forEach { durationMs ->
            val message = StudyMessage.VoiceTransfer(
                messageId = "m-$durationMs",
                userMessageId = "user-$durationMs",
                sender = PeerRole.TEACHER,
                payloadId = 10,
                sentAtEpochMs = 0,
                durationMs = durationMs,
            )

            assertEquals(message, StudyWireCodec.decode(StudyWireCodec.encode(message)))
        }
    }

    @Test
    fun `voice duration rejects values outside inclusive boundaries`() {
        listOf(-1L, 0L, 60_001L).forEach { durationMs ->
            assertThrows(IllegalArgumentException::class.java) {
                StudyWireCodec.encode(
                    StudyMessage.VoiceTransfer(
                        messageId = "m1",
                        userMessageId = "user-1",
                        sender = PeerRole.STUDENT,
                        payloadId = 10,
                        sentAtEpochMs = 0,
                        durationMs = durationMs,
                    ),
                )
            }
        }
    }

    @Test
    fun `study settings allow zero meditation`() {
        val settings = StudyMessage.StudySettings(
            "settings", 0, 60_000, 60_000, 5_000, 10_000,
            10_000, 30_000, 0.18f, 0.012f,
        )

        assertEquals(settings, StudyWireCodec.decode(StudyWireCodec.encode(settings)))
    }

    @Test
    fun `message timestamps and voice identifiers are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.encode(StudyMessage.TextMessage("m1", PeerRole.STUDENT, "질문", -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.encode(
                StudyMessage.VoiceTransfer("m2", "user-1", PeerRole.TEACHER, 1, -1, 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyWireCodec.encode(
                StudyMessage.VoiceTransfer("m3", " ", PeerRole.TEACHER, 1, 0, 1),
            )
        }
    }

    @Test
    fun `routing identifiers must not be blank`() {
        listOf(
            StudyMessage.StartRequest(" ", WireStartOrigin.STUDENT),
            StudyMessage.SessionSnapshot("m1", " ", WireSessionStatus.READY, WireSessionPhase.MEDITATION, 1, 0, 0),
            StudyMessage.ProblemCompleted("m2", " ", 1),
            StudyMessage.AssetRequest("m3", " ", AssetKind.BOOK_ROI),
            StudyMessage.Ack("m4", " "),
        ).forEach { message ->
            assertThrows(IllegalArgumentException::class.java) {
                StudyWireCodec.encode(message)
            }
        }
    }
}
