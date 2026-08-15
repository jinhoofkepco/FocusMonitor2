package io.remotestudy.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.EnumMap

/**
 * Continuously recognizes a small Korean command vocabulary while explicitly started.
 *
 * This class never writes audio to storage or forwards audio itself. When [allowSystemFallback]
 * is true (the default), Android's selected system recognizer may process audio remotely even
 * though [RecognizerIntent.EXTRA_PREFER_OFFLINE] is requested. Set it to false when on-device-only
 * processing is a hard privacy requirement.
 *
 * Android 13+ supplies one app-owned AudioRecord stream to a segmented SpeechRecognizer session.
 * This avoids the platform start/stop chime caused by repeatedly reopening the system microphone.
 * When system recognition is allowed it is preferred for accuracy; unsupported devices fail
 * visibly instead of falling back to a noisy restart loop.
 */
class StudentVoiceCommandController @JvmOverloads constructor(
    context: Context,
    private val listener: StudentVoiceCommandListener,
    private val allowSystemFallback: Boolean = true,
    private val phraseMatcher: KoreanVoiceCommandPhraseMatcher =
        KoreanVoiceCommandPhraseMatcher(),
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommandAtMillis = EnumMap<VoiceCommand, Long>(VoiceCommand::class.java)

    private var recognizer: SpeechRecognizer? = null
    private var recognitionMode: RecognitionMode? = null
    private var requestedActive = false
    private var destroyed = false
    private var listening = false
    private var recognitionCycle = 0L
    private var awaitingMessage = false
    private var audioRecord: AudioRecord? = null
    private var audioReadDescriptor: ParcelFileDescriptor? = null
    private var audioWriteDescriptor: ParcelFileDescriptor? = null
    private var audioThread: Thread? = null
    private var audioGeneration = 0L

    fun start() = onMainThread {
        if (destroyed) {
            listener.onError(
                VoiceCommandError(
                    kind = VoiceCommandErrorKind.CONTROLLER_DESTROYED,
                    recoverable = false,
                    message = "The voice command controller has already been destroyed.",
                ),
            )
            return@onMainThread
        }
        if (requestedActive) return@onMainThread

        requestedActive = true
        lastCommandAtMillis.clear()
        awaitingMessage = false

        if (!hasRecordAudioPermission()) {
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.PERMISSION_DENIED,
                message = "RECORD_AUDIO permission has not been granted.",
            )
            return@onMainThread
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.RECOGNIZER_UNAVAILABLE,
                message = "연속 음성인식은 Android 13 이상에서만 사용할 수 있습니다.",
            )
            return@onMainThread
        }

        if (!ensureRecognizer()) return@onMainThread
        beginListening()
    }

    fun stop() = onMainThread {
        if (destroyed) return@onMainThread

        requestedActive = false
        awaitingMessage = false
        invalidateCurrentCycle()
        closeContinuousAudio()
        runCatching { recognizer?.cancel() }
        listener.onStatus(
            VoiceCommandStatus(
                state = VoiceCommandState.STOPPED,
                recognitionMode = recognitionMode,
            ),
        )
    }

    fun destroy() = onMainThread {
        if (destroyed) return@onMainThread

        requestedActive = false
        awaitingMessage = false
        destroyed = true
        invalidateCurrentCycle()
        closeContinuousAudio()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listener.onStatus(
            VoiceCommandStatus(
                state = VoiceCommandState.DESTROYED,
                recognitionMode = recognitionMode,
            ),
        )
        recognitionMode = null
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true

        listener.onStatus(VoiceCommandStatus(state = VoiceCommandState.STARTING))
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.RECOGNIZER_UNAVAILABLE,
                message = "No Android speech recognition service is available.",
            )
            return false
        }

        if (allowSystemFallback) {
            val systemRecognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }.getOrNull()
            if (systemRecognizer != null) {
                recognizer = systemRecognizer
                recognitionMode = RecognitionMode.SYSTEM
                return true
            }
        }

        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        if (onDeviceAvailable) {
            val onDeviceRecognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }.getOrNull()
            if (onDeviceRecognizer != null) {
                recognizer = onDeviceRecognizer
                recognitionMode = RecognitionMode.ON_DEVICE
                return true
            }
        }

        requestedActive = false
        dispatchTerminalError(
            kind = VoiceCommandErrorKind.RECOGNIZER_UNAVAILABLE,
            message = "연속 음성인식을 지원하는 음성서비스를 사용할 수 없습니다.",
        )
        return false
    }

    private fun beginListening() {
        if (!requestedActive || destroyed || listening) return
        val activeRecognizer = recognizer ?: if (ensureRecognizer()) recognizer else null
        if (activeRecognizer == null) return

        val cycle = ++recognitionCycle
        activeRecognizer.setRecognitionListener(createRecognitionListener(cycle))

        try {
            val audioSource = openContinuousAudio(cycle)
            activeRecognizer.startListening(recognitionIntent(audioSource))
            listening = true
            listener.onStatus(
                VoiceCommandStatus(
                    state = VoiceCommandState.LISTENING,
                    recognitionMode = recognitionMode,
                ),
            )
        } catch (securityException: SecurityException) {
            closeContinuousAudio()
            listening = false
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.PERMISSION_DENIED,
                message = securityException.message,
            )
        } catch (runtimeException: RuntimeException) {
            closeContinuousAudio()
            listening = false
            handleControllerError(
                kind = VoiceCommandErrorKind.CLIENT,
                message = runtimeException.message,
            )
        }
    }

    private fun createRecognitionListener(cycle: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = handleCycle(cycle) {
            listener.onStatus(
                VoiceCommandStatus(
                    state = VoiceCommandState.LISTENING,
                    recognitionMode = recognitionMode,
                ),
            )
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        /** The supplied bytes are deliberately ignored and never retained. */
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = handleCycle(cycle) {
            listener.onStatus(
                VoiceCommandStatus(
                    state = VoiceCommandState.PROCESSING,
                    recognitionMode = recognitionMode,
                ),
            )
        }

        override fun onError(error: Int) = handleCycle(cycle) {
            listening = false
            requestedActive = false
            closeContinuousAudio()
            dispatchTerminalError(
                kind = platformErrorKind(error),
                platformCode = error,
                message = "연속 음성인식이 중단되었습니다. 앱을 다시 열어 주세요.",
            )
        }

        override fun onResults(results: Bundle?) = handleCycle(cycle) {
            listening = false
            processFinalResults(results)
            requestedActive = false
            closeContinuousAudio()
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.SERVICE,
                message = "기기 음성서비스가 연속 인식을 지원하지 않습니다.",
            )
        }

        override fun onSegmentResults(segmentResults: Bundle) = handleCycle(cycle) {
            processFinalResults(segmentResults)
        }

        override fun onEndOfSegmentedSession() = handleCycle(cycle) {
            listening = false
            requestedActive = false
            closeContinuousAudio()
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.SERVICE,
                message = "연속 음성인식 세션이 종료되었습니다. 앱을 다시 열어 주세요.",
            )
        }

        override fun onPartialResults(partialResults: Bundle?) = handleCycle(cycle) {
            val hypotheses = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            hypotheses.firstOrNull { it.isNotBlank() }
                ?.let { listener.onRecognitionText(it.trim(), false) }
            phraseMatcher.matchFirst(hypotheses)
                ?.takeUnless { it == VoiceCommand.DAD_MESSAGE }
                ?.let(::emitIfNotDebounced)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleControllerError(kind: VoiceCommandErrorKind, message: String?) {
        requestedActive = false
        closeContinuousAudio()
        dispatchTerminalError(kind = kind, message = message)
    }

    private fun dispatchTerminalError(
        kind: VoiceCommandErrorKind,
        platformCode: Int? = null,
        message: String? = null,
    ) {
        listening = false
        listener.onError(
            VoiceCommandError(
                kind = kind,
                platformCode = platformCode,
                recoverable = false,
                message = message,
            ),
        )
        listener.onStatus(
            VoiceCommandStatus(
                state = VoiceCommandState.UNAVAILABLE,
                recognitionMode = recognitionMode,
            ),
        )
    }

    private fun emitIfNotDebounced(command: VoiceCommand) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastCommandAtMillis[command]
        if (previous == null || now - previous >= COMMAND_DEBOUNCE_MILLIS) {
            lastCommandAtMillis[command] = now
            if (command == VoiceCommand.DAD_MESSAGE) awaitingMessage = true
            listener.onCommand(command)
        }
    }

    private fun processFinalResults(results: Bundle?) {
        val hypotheses = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        val primary = hypotheses.firstOrNull { it.isNotBlank() }
        primary?.let { listener.onRecognitionText(it.trim(), true) }
        if (awaitingMessage) {
            if (primary != null) {
                awaitingMessage = false
                listener.onMessageRecognized(primary.trim())
            }
            return
        }

        val normalized = primary?.trim().orEmpty()
        if (normalized.startsWith("아빠")) {
            emitIfNotDebounced(VoiceCommand.DAD_MESSAGE)
            val remainder = normalized.removePrefix("아빠").trim()
            if (remainder.isNotEmpty()) {
                awaitingMessage = false
                listener.onMessageRecognized(remainder)
            }
        } else {
            phraseMatcher.matchFirst(hypotheses)?.let(::emitIfNotDebounced)
        }
    }

    @SuppressLint("MissingPermission") // start() checks RECORD_AUDIO immediately before this path.
    private fun openContinuousAudio(cycle: Long): ParcelFileDescriptor {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        closeContinuousAudio()
        val minimumBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "마이크 버퍼를 만들 수 없습니다." }
        val bufferSize = maxOf(minimumBuffer, AUDIO_BUFFER_BYTES)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "마이크를 초기화할 수 없습니다."
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readDescriptor = pipe[0]
        val writeDescriptor = pipe[1]
        val generation = ++audioGeneration
        audioRecord = record
        audioReadDescriptor = readDescriptor
        audioWriteDescriptor = writeDescriptor

        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "마이크 녹음을 시작할 수 없습니다."
            }
            audioThread = Thread(
                {
                    val buffer = ByteArray(bufferSize)
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeDescriptor).use { output ->
                            while (
                                requestedActive && !destroyed &&
                                cycle == recognitionCycle && generation == audioGeneration
                            ) {
                                val count = record.read(
                                    buffer,
                                    0,
                                    buffer.size,
                                    AudioRecord.READ_BLOCKING,
                                )
                                if (count > 0) {
                                    output.write(buffer, 0, count)
                                } else if (count != AudioRecord.ERROR_INVALID_OPERATION) {
                                    throw IllegalStateException("마이크 읽기 오류: $count")
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        mainHandler.post {
                            if (
                                requestedActive && !destroyed &&
                                cycle == recognitionCycle && generation == audioGeneration
                            ) {
                                requestedActive = false
                                listening = false
                                closeContinuousAudio()
                                runCatching { recognizer?.cancel() }
                                dispatchTerminalError(
                                    kind = VoiceCommandErrorKind.AUDIO,
                                    message = error.message ?: "연속 마이크 입력이 중단되었습니다.",
                                )
                            }
                        }
                    }
                },
                "continuous-speech-audio",
            ).apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            return readDescriptor
        } catch (error: Throwable) {
            closeContinuousAudio()
            throw error
        }
    }

    private fun closeContinuousAudio() {
        audioGeneration++
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { audioWriteDescriptor?.close() }
        runCatching { audioReadDescriptor?.close() }
        audioWriteDescriptor = null
        audioReadDescriptor = null
        audioThread?.interrupt()
        audioThread = null
    }

    private fun invalidateCurrentCycle() {
        recognitionCycle++
        listening = false
    }

    private fun hasRecordAudioPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun recognitionIntent(audioSource: ParcelFileDescriptor) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, KOREAN_LOCALE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, KOREAN_LOCALE)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RECOGNITION_RESULTS)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // For the system recognizer, forcing offline recognition noticeably reduces
        // Korean accuracy on some Samsung/Google service combinations.
        putExtra(
            RecognizerIntent.EXTRA_PREFER_OFFLINE,
            recognitionMode == RecognitionMode.ON_DEVICE,
        )
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_SAMPLE_RATE_HZ)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        putStringArrayListExtra(
            RecognizerIntent.EXTRA_BIASING_STRINGS,
            arrayListOf(
                "풀었어",
                "풀었어요",
                "문제 풀었어",
                "다 풀었어",
                "아빠",
                "아빠 녹음",
                "공부 시작",
                "일시 정지",
                "그만",
            ),
        )
    }

    private fun handleCycle(cycle: Long, action: () -> Unit) = onMainThread {
        if (!requestedActive || destroyed || cycle != recognitionCycle) return@onMainThread
        action()
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun platformErrorKind(code: Int): VoiceCommandErrorKind = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK,
        -> VoiceCommandErrorKind.NETWORK

        SpeechRecognizer.ERROR_AUDIO -> VoiceCommandErrorKind.AUDIO
        SpeechRecognizer.ERROR_SERVER -> VoiceCommandErrorKind.SERVICE
        SpeechRecognizer.ERROR_CLIENT -> VoiceCommandErrorKind.CLIENT
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceCommandErrorKind.SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceCommandErrorKind.NO_MATCH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceCommandErrorKind.RECOGNIZER_BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceCommandErrorKind.PERMISSION_DENIED
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceCommandErrorKind.TOO_MANY_REQUESTS
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceCommandErrorKind.SERVICE
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            VoiceCommandErrorKind.LANGUAGE_NOT_SUPPORTED

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceCommandErrorKind.LANGUAGE_UNAVAILABLE
        else -> VoiceCommandErrorKind.UNKNOWN
    }

    private companion object {
        const val KOREAN_LOCALE = "ko-KR"
        const val MAX_RECOGNITION_RESULTS = 8
        const val COMMAND_DEBOUNCE_MILLIS = 2_000L
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_BUFFER_BYTES = 16_384
    }
}
