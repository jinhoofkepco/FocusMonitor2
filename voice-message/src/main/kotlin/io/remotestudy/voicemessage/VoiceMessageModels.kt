package io.remotestudy.voicemessage

import java.io.File

enum class VoiceMessageRecorderState {
    IDLE,
    RECORDING,
}

data class RecordedVoiceMessage(
    val file: File,
    /** Monotonic elapsed recording duration. Successful recordings are always at least 1 ms. */
    val durationMs: Long,
)

open class VoiceMessagePlaybackException(message: String) : IllegalStateException(message)

class VoiceMessagePlaybackStoppedException :
    VoiceMessagePlaybackException("Voice-message playback was stopped.")

class VoiceMessagePlaybackReplacedException :
    VoiceMessagePlaybackException("Voice-message playback was replaced by another file.")

class VoiceMessagePlaybackFailedException(
    val what: Int,
    val extra: Int,
) : VoiceMessagePlaybackException("MediaPlayer failed (what=$what, extra=$extra).")
