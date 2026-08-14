package io.remotestudy.sync

import io.remotestudy.protocol.StudyMessage
import io.remotestudy.protocol.StudyWireCodec
import io.remotestudy.protocol.WireStartOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliableMessageChannelTest {
    private val sent = mutableListOf<StudyMessage>()
    private var nextId = 0
    private val channel = ReliableMessageChannel(
        transmitter = { bytes ->
            sent += StudyWireCodec.decode(bytes)
            true
        },
        idFactory = { "ack-${nextId++}" },
    )

    @Test
    fun `offline message is retried after reconnect and removed by ack`() {
        val message = StudyMessage.StartRequest("start-1", WireStartOrigin.TEACHER)

        assertTrue(channel.send(message, 100))
        assertEquals(0, sent.size)
        assertEquals(1, channel.pendingCount)

        channel.setConnected(true, 200)
        assertEquals(listOf(message), sent)

        channel.receive(
            StudyWireCodec.encode(StudyMessage.Ack("peer-ack", "start-1")),
            250,
        )
        assertEquals(0, channel.pendingCount)
    }

    @Test
    fun `duplicate inbound message is delivered once but acknowledged each time`() {
        channel.setConnected(true, 0)
        val message = StudyMessage.StartRequest("same", WireStartOrigin.TEACHER)
        val bytes = StudyWireCodec.encode(message)

        assertEquals(message, channel.receive(bytes, 10))
        assertNull(channel.receive(bytes, 20))

        val acks = sent.filterIsInstance<StudyMessage.Ack>()
        assertEquals(2, acks.size)
        assertTrue(acks.all { it.acknowledgedMessageId == "same" })
    }

    @Test
    fun `coalescing keeps only latest pending snapshot`() {
        val first = StudyMessage.ProblemCompleted("m1", "event-1", 1)
        val latest = StudyMessage.ProblemCompleted("m2", "event-2", 2)

        channel.send(first, 0, coalesceKey = "snapshot")
        channel.send(latest, 1, coalesceKey = "snapshot")

        assertEquals(listOf(latest), channel.pendingMessages())
    }

    @Test
    fun `connected pending message retries only when interval is due`() {
        channel.setConnected(true, 0)
        val message = StudyMessage.StartRequest("m1", WireStartOrigin.STUDENT)
        channel.send(message, 10)

        channel.retryDue(2_009)
        assertEquals(1, sent.filter { it == message }.size)

        channel.retryDue(2_010)
        assertEquals(2, sent.filter { it == message }.size)
    }
}
