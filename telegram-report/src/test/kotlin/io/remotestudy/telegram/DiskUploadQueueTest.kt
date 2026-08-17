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
}
