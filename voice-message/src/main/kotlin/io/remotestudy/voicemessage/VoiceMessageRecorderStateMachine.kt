package io.remotestudy.voicemessage

/** Pure lifecycle guard used by [VoiceMessageRecorder]. */
internal class VoiceMessageRecorderStateMachine {
    var state: VoiceMessageRecorderState = VoiceMessageRecorderState.IDLE
        private set

    var isClosed: Boolean = false
        private set

    fun beginRecording(): Result<Unit> = runCatching {
        check(!isClosed) { "VoiceMessageRecorder is closed." }
        check(state == VoiceMessageRecorderState.IDLE) { "A recording is already active." }
        state = VoiceMessageRecorderState.RECORDING
    }

    fun requireRecording(): Result<Unit> = runCatching {
        check(!isClosed) { "VoiceMessageRecorder is closed." }
        check(state == VoiceMessageRecorderState.RECORDING) { "No recording is active." }
    }

    fun returnToIdle() {
        state = VoiceMessageRecorderState.IDLE
    }

    fun close() {
        state = VoiceMessageRecorderState.IDLE
        isClosed = true
    }
}
