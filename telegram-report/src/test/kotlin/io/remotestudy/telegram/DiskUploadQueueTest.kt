package io.remotestudy.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiskUploadQueueTest {
    @Test fun survivesRestartAndAcknowledgement() {
        val dir = Files.createTempDirectory("telegram-queue").toFile()
        val journal = dir.resolve("queue.jsonl")
        val first = DiskUploadQueue(journal)
        val entry = first.enqueue(UploadKind.MESSAGE, null, "안녕\n학생", 100L)

        val restored = DiskUploadQueue(journal)
        assertEquals("안녕\n학생", restored.due(100L)?.text)
        restored.acknowledge(entry.id)
        assertEquals(0, DiskUploadQueue(journal).size())
    }

    @Test fun retryUsesFutureBackoff() {
        val journal = Files.createTempDirectory("telegram-queue").resolve("q.jsonl").toFile()
        val queue = DiskUploadQueue(journal)
        val entry = queue.enqueue(UploadKind.MESSAGE, null, "x", 1_000L)
        val retried = queue.retry(entry.id, 1_000L)!!
        assertTrue(retried.nextAttemptEpochMs > 1_000L)
        assertNull(queue.due(1_001L))
    }

    @Test fun montageButtonsSurviveProcessRestart() {
        val journal = Files.createTempDirectory("telegram-buttons").resolve("q.jsonl").toFile()
        val markup = "{\"inline_keyboard\":[[{\"text\":\"1\",\"callback_data\":\"book:123\"}]]}"
        DiskUploadQueue(journal).enqueue(UploadKind.PHOTO, null, "#1", 10L, markup)
        assertEquals(markup, DiskUploadQueue(journal).due(10L)?.replyMarkup)
    }

    @Test fun immediateMessageIsChosenAheadOfReadyPhotoBacklog() {
        val journal = Files.createTempDirectory("telegram-priority").resolve("q.jsonl").toFile()
        val queue = DiskUploadQueue(journal)
        queue.enqueue(UploadKind.PHOTO, null, "montage", 10L)
        queue.enqueue(UploadKind.DOCUMENT, null, "detail", 10L)
        val transition = queue.enqueue(UploadKind.MESSAGE, null, "[전환 알림] 휴식 시작", 10L)

        assertEquals(transition.id, queue.due(10L)?.id)
        assertEquals(transition.id, queue.dueMessage(10L)?.id)
        assertEquals("montage", queue.dueMedia(10L)?.text)
    }

    @Test fun sessionSummaryWaitsForItsPhotoWithoutBlockingNewSessionMessages() {
        val journal = Files.createTempDirectory("telegram-session-order").resolve("q.jsonl").toFile()
        val queue = DiskUploadQueue(journal)
        val partial = queue.enqueue(UploadKind.PHOTO, null, "partial montage", 10L)
        val oldSummary = queue.enqueue(
            UploadKind.MESSAGE_AND_PIN,
            null,
            "이전 회차 종료",
            10L,
            dependsOnId = partial.id,
        )
        val newSession = queue.enqueue(UploadKind.MESSAGE, null, "새 회차 시작", 10L)

        assertEquals(newSession.id, queue.dueMessage(10L)?.id)
        assertEquals(partial.id, queue.dueMedia(10L)?.id)
        queue.acknowledge(partial.id)
        assertEquals(oldSummary.id, queue.dueMedia(10L)?.id)
    }

    @Test fun completedMontageCanBeUsedAsSessionSummaryBarrier() {
        val journal = Files.createTempDirectory("telegram-full-montage").resolve("q.jsonl").toFile()
        val queue = DiskUploadQueue(journal)
        val completedMontage = queue.enqueue(UploadKind.PHOTO, null, "six cells", 10L)
        val summary = queue.enqueue(
            UploadKind.MESSAGE_AND_PIN,
            null,
            "회차 종료",
            10L,
            dependsOnId = queue.latestPhotoId(),
        )

        assertEquals(completedMontage.id, queue.dueMedia(10L)?.id)
        queue.acknowledge(completedMontage.id)
        assertEquals(summary.id, queue.dueMedia(10L)?.id)
    }

    @Test fun failedEarlierPhotoBlocksLaterPhotoAndItsSessionSummary() {
        val journal = Files.createTempDirectory("telegram-photo-order").resolve("q.jsonl").toFile()
        val queue = DiskUploadQueue(journal)
        val first = queue.enqueue(UploadKind.PHOTO, null, "#1", 10L)
        val last = queue.enqueue(UploadKind.PHOTO, null, "#2", 10L)
        queue.enqueue(
            UploadKind.MESSAGE_AND_PIN,
            null,
            "회차 종료",
            10L,
            dependsOnId = last.id,
        )

        queue.retry(first.id, 10L)
        assertNull(queue.dueMedia(11L))
        assertNull(queue.dueMessage(11L))
        assertEquals(first.id, queue.dueMedia(4_010L)?.id)
        queue.acknowledge(first.id)
        assertEquals(last.id, queue.dueMedia(4_010L)?.id)
        queue.acknowledge(last.id)
        assertEquals(UploadKind.MESSAGE_AND_PIN, queue.dueMedia(4_010L)?.kind)
    }

    @Test fun pinFailureCannotResendAnAlreadyDeliveredSummary() {
        val journal = Files.createTempDirectory("telegram-pin").resolve("q.jsonl").toFile()
        val queue = DiskUploadQueue(journal)
        val summary = queue.enqueue(UploadKind.MESSAGE_AND_PIN, null, "이전 회차 종료", 10L)
        val nextSession = queue.enqueue(UploadKind.MESSAGE, null, "새 회차 시작", 10L)

        val pin = queue.convertMessageToPin(summary.id, 1234L, 11L)!!
        assertEquals(UploadKind.PIN, pin.kind)
        assertEquals("1234", pin.text)
        queue.retry(pin.id, 11L)

        // The old summary is now represented only by a pin request. While its
        // pin retry waits, later chat messages may proceed without duplicating
        // the summary text.
        assertEquals(nextSession.id, queue.dueMessage(12L)?.id)
        val restored = DiskUploadQueue(journal)
        assertEquals(1, restored.snapshot().count { it.kind == UploadKind.PIN })
        assertEquals(0, restored.snapshot().count { it.kind == UploadKind.MESSAGE_AND_PIN })
    }
}
