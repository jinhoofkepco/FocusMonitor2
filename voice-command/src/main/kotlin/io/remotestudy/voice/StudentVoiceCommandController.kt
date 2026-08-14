package io.remotestudy.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min

/**
 * Continuously recognizes a small, Korean command vocabulary while explicitly started.
 *
 * This class never writes audio to storage or forwards audio itself. When [allowSystemFallback]
 * is true (the default), Android's selected system recognizer may process audio remotely even
 * though [RecognizerIntent.EXTRA_PREFER_OFFLINE] is requested. Set it to false when on-device-only
 * processing is a hard privacy requirement.
 *
 * All SpeechRecognizer operations and every [StudentVoiceCommandListener] callback run on the
 * Android main thread. [stop] cancels pending automatic restarts but a later explicit [start] is
 * allowed. [destroy] is terminal.
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
    private var restartRunnable: Runnable? = null
    private var requestedActive = false
    private var destroyed = false
    private var listening = false
    private var recognitionCycle = 0L
    private var consecutiveErrors = 0
    private var awaitingMessage = false

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
        consecutiveErrors = 0
        lastCommandAtMillis.clear()
        awaitingMessage = false
        cancelScheduledRestart()

        if (!hasRecordAudioPermission()) {
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.PERMISSION_DENIED,
                message = "RECORD_AUDIO permission has not been granted.",
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
        consecutiveErrors = 0
        cancelScheduledRestart()
        invalidateCurrentCycle()
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
        cancelScheduledRestart()
        invalidateCurrentCycle()
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

        if (!allowSystemFallback) {
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.RECOGNIZER_UNAVAILABLE,
                message = "On-device speech recognition is unavailable and system fallback is disabled.",
            )
            return false
        }

        return runCatching {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognitionMode = RecognitionMode.SYSTEM
        }.fold(
            onSuccess = { true },
            onFailure = { throwable ->
                requestedActive = false
                dispatchTerminalError(
                    kind = VoiceCommandErrorKind.RECOGNIZER_UNAVAILABLE,
                    message = throwable.message ?: "Failed to create Android SpeechRecognizer.",
                )
                false
            },
        )
    }

    private fun beginListening() {
        if (!requestedActive || destroyed || listening) return
        val activeRecognizer = recognizer ?: if (ensureRecognizer()) recognizer else null
        if (activeRecognizer == null) return

        cancelScheduledRestart()
        val cycle = ++recognitionCycle
        activeRecognizer.setRecognitionListener(createRecognitionListener(cycle))

        try {
            activeRecognizer.startListening(recognitionIntent())
            listening = true
            listener.onStatus(
                VoiceCommandStatus(
                    state = VoiceCommandState.LISTENING,
                    recognitionMode = recognitionMode,
                ),
            )
        } catch (securityException: SecurityException) {
            listening = false
            requestedActive = false
            dispatchTerminalError(
                kind = VoiceCommandErrorKind.PERMISSION_DENIED,
                message = securityException.message,
            )
        } catch (runtimeException: RuntimeException) {
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
            handlePlatformError(error)
        }

        override fun onResults(results: Bundle?) = handleCycle(cycle) {
            listening = false
            consecutiveErrors = 0
            val hypotheses = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            val primary = hypotheses.firstOrNull { it.isNotBlank() }
            if (awaitingMessage) {
                if (primary != null) {
                    awaitingMessage = false
                    listener.onMessageRecognized(primary.trim())
                }
            } else {
                val dadPhrase = primary?.trim()?.takeIf { it.startsWith("아빠") }?.indexOf("아빠") ?: -1
                if (dadPhrase == 0) {
                    emitIfNotDebounced(VoiceCommand.DAD_MESSAGE)
                    val remainder = primary.orEmpty().substring(dadPhrase + 2).trim()
                    if (remainder.isNotEmpty()) {
                        awaitingMessage = false
                        listener.onMessageRecognized(remainder)
                    }
                } else {
                    phraseMatcher.matchFirst(hypotheses)?.let(::emitIfNotDebounced)
                }
            }
            scheduleRestart(BASE_RESTART_DELAY_MILLIS)
        }

        override fun onPartialResults(partialResults: Bundle?) = handleCycle(cycle) {
            val hypotheses = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            phraseMatcher.matchFirst(hypotheses)
                ?.takeUnless { it == VoiceCommand.DAD_MESSAGE }
                ?.let(::emitIfNotDebounced)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handlePlatformError(platformCode: Int) {
        val kind = platformErrorKind(platformCode)
        val terminal = kind == VoiceCommandErrorKind.PERMISSION_DENIED ||
            kind == VoiceCommandErrorKind.LANGUAGE_NOT_SUPPORTED ||
            kind == VoiceCommandErrorKind.LANGUAGE_UNAVAILABLE

        if (terminal) {
            requestedActive = false
            dispatchTerminalError(kind, platformCode = platformCode)
            return
        }

        val retryDelay = when (kind) {
            VoiceCommandErrorKind.NO_MATCH,
            VoiceCommandErrorKind.SPEECH_TIMEOUT,
            -> {
                consecutiveErrors = 0
                BASE_RESTART_DELAY_MILLIS
            }

            VoiceCommandErrorKind.RECOGNIZER_BUSY -> {
                consecutiveErrors++
                max(BUSY_MINIMUM_DELAY_MILLIS, exponentialErrorDelay())
            }

            else -> {
                consecutiveErrors++
                exponentialErrorDelay()
            }
        }

        listener.onError(
            VoiceCommandError(
                kind = kind,
                platformCode = platformCode,
                recoverable = true,
                retryInMillis = retryDelay,
            ),
        )
        scheduleRestart(retryDelay)
    }

    private fun handleControllerError(kind: VoiceCommandErrorKind, message: String?) {
        consecutiveErrors++
        val retryDelay = exponentialErrorDelay()
        listener.onError(
            VoiceCommandError(
                kind = kind,
                recoverable = true,
                retryInMillis = retryDelay,
                message = message,
            ),
        )
        scheduleRestart(retryDelay)
    }

    private fun dispatchTerminalError(
        kind: VoiceCommandErrorKind,
        platformCode: Int? = null,
        message: String? = null,
    ) {
        cancelScheduledRestart()
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

    private fun scheduleRestart(delayMillis: Long) {
        if (!requestedActive || destroyed) return

        cancelScheduledRestart()
        val scheduledCycle = recognitionCycle
        val runnable = Runnable {
            restartRunnable = null
            if (requestedActive && !destroyed && scheduledCycle == recognitionCycle) {
                beginListening()
            }
        }
        restartRunnable = runnable
        listener.onStatus(
            VoiceCommandStatus(
                state = VoiceCommandState.RETRY_WAIT,
                recognitionMode = recognitionMode,
                retryInMillis = delayMillis,
            ),
        )
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
    }

    private fun invalidateCurrentCycle() {
        recognitionCycle++
        listening = false
    }

    private fun exponentialErrorDelay(): Long {
        val exponent = min(consecutiveErrors, MAX_BACKOFF_EXPONENT)
        return min(
            MAX_ERROR_BACKOFF_MILLIS,
            BASE_RESTART_DELAY_MILLIS * (1L shl exponent),
        )
    }

    private fun hasRecordAudioPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, KOREAN_LOCALE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, KOREAN_LOCALE)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RECOGNITION_RESULTS)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        const val MAX_RECOGNITION_RESULTS = 5
        const val BASE_RESTART_DELAY_MILLIS = 600L
        const val BUSY_MINIMUM_DELAY_MILLIS = 2_400L
        const val MAX_ERROR_BACKOFF_MILLIS = 9_600L
        const val MAX_BACKOFF_EXPONENT = 4
        const val COMMAND_DEBOUNCE_MILLIS = 2_000L
    }
}
