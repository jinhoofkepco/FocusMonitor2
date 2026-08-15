package io.remotestudy.voice

/** Commands intentionally kept small to reduce false activations. */
enum class VoiceCommand {
    STUDY_START,
    PROBLEM_DONE,
    UNDO,
    PAUSE,
    STOP,
    DAD_MESSAGE,
}

enum class RecognitionMode {
    /** Android's on-device SpeechRecognizer implementation. */
    ON_DEVICE,

    /** The default system recognition service. It may use a network service. */
    SYSTEM,
}

enum class VoiceCommandState {
    STARTING,
    LISTENING,
    PROCESSING,
    RETRY_WAIT,
    STOPPED,
    DESTROYED,
    UNAVAILABLE,
}

data class VoiceCommandStatus(
    val state: VoiceCommandState,
    val recognitionMode: RecognitionMode? = null,
    val retryInMillis: Long? = null,
)

enum class VoiceCommandErrorKind {
    RECOGNIZER_UNAVAILABLE,
    PERMISSION_DENIED,
    NETWORK,
    AUDIO,
    SERVICE,
    CLIENT,
    NO_MATCH,
    SPEECH_TIMEOUT,
    RECOGNIZER_BUSY,
    TOO_MANY_REQUESTS,
    LANGUAGE_NOT_SUPPORTED,
    LANGUAGE_UNAVAILABLE,
    CONTROLLER_DESTROYED,
    UNKNOWN,
}

data class VoiceCommandError(
    val kind: VoiceCommandErrorKind,
    val platformCode: Int? = null,
    val recoverable: Boolean,
    val retryInMillis: Long? = null,
    val message: String? = null,
)

interface StudentVoiceCommandListener {
    /** Always called on the Android main thread. */
    fun onCommand(command: VoiceCommand)

    /** Final Korean dictation captured after the "아빠" wake phrase. */
    fun onMessageRecognized(text: String) = Unit

    /** Diagnostic hook used by the standalone voice lab; production callers may ignore it. */
    fun onRecognitionText(text: String, isFinal: Boolean) = Unit

    /** Always called on the Android main thread. */
    fun onStatus(status: VoiceCommandStatus)

    /** Always called on the Android main thread. */
    fun onError(error: VoiceCommandError)
}
