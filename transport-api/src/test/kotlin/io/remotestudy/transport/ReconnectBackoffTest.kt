package io.remotestudy.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `backs off to sixteen seconds and resets after connection`() {
        val backoff = ReconnectBackoff()

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 16_000L), List(5) { backoff.nextDelay() })
        backoff.reset()
        assertEquals(2_000L, backoff.nextDelay())
    }
}
