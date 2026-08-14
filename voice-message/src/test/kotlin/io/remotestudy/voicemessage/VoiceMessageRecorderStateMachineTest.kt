package io.remotestudy.voicemessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageRecorderStateMachineTest {
    @Test
    fun `moves between idle and recording`() {
        val machine = VoiceMessageRecorderStateMachine()

        assertEquals(VoiceMessageRecorderState.IDLE, machine.state)
        assertTrue(machine.beginRecording().isSuccess)
        assertEquals(VoiceMessageRecorderState.RECORDING, machine.state)
        assertTrue(machine.requireRecording().isSuccess)

        machine.returnToIdle()
        assertEquals(VoiceMessageRecorderState.IDLE, machine.state)
        assertTrue(machine.beginRecording().isSuccess)
    }

    @Test
    fun `rejects a second simultaneous recording`() {
        val machine = VoiceMessageRecorderStateMachine()

        assertTrue(machine.beginRecording().isSuccess)
        assertTrue(machine.beginRecording().isFailure)
        assertEquals(VoiceMessageRecorderState.RECORDING, machine.state)
    }

    @Test
    fun `requires an active recording before stop`() {
        val machine = VoiceMessageRecorderStateMachine()

        assertTrue(machine.requireRecording().isFailure)
        assertEquals(VoiceMessageRecorderState.IDLE, machine.state)
    }

    @Test
    fun `close is terminal and returns to idle`() {
        val machine = VoiceMessageRecorderStateMachine()
        machine.beginRecording().getOrThrow()

        machine.close()

        assertEquals(VoiceMessageRecorderState.IDLE, machine.state)
        assertTrue(machine.isClosed)
        assertTrue(machine.beginRecording().isFailure)
        assertTrue(machine.requireRecording().isFailure)
    }
}
