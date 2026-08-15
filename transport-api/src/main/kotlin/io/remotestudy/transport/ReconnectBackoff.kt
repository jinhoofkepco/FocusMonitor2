package io.remotestudy.transport

/** Bounded reconnect spacing so the old Bluetooth/Wi-Fi Direct generation can close first. */
class ReconnectBackoff(
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 16_000L,
) {
    private var nextDelayMs = baseDelayMs

    init {
        require(baseDelayMs > 0)
        require(maxDelayMs >= baseDelayMs)
    }

    fun nextDelay(): Long {
        val result = nextDelayMs
        nextDelayMs = (nextDelayMs * 2).coerceAtMost(maxDelayMs)
        return result
    }

    fun reset() {
        nextDelayMs = baseDelayMs
    }
}
