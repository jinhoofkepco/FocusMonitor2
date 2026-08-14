package io.remotestudy.voicemessage

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import kotlin.math.max

/**
 * Records one MPEG-4/AAC voice message at a time.
 *
 * Every public call must be made on the Android main thread. `start` and `stop` return a failed
 * [Result] for a thread violation; `state`, `cancel`, and `close` throw. Listener callbacks are
 * always dispatched on the main thread.
 *
 * Audio is first written to `<messageId>.part`. Only a successful [stop] commits it to `.m4a`.
 */
class VoiceMessageRecorder @JvmOverloads constructor(
    context: Context,
    private val listener: Listener? = null,
) : AutoCloseable {
    interface Listener {
        /** Called exactly once when the 60-second cap automatically produces a valid message. */
        fun onAutoStopped(message: RecordedVoiceMessage)

        /** Called for an asynchronous MediaRecorder failure or failed automatic stop. */
        fun onRecordingError(error: Throwable)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachine = VoiceMessageRecorderStateMachine()

    private var activeRecorder: MediaRecorder? = null
    private var activePaths: VoiceMessagePaths? = null
    private var recordingStartedAtMillis = 0L
    private var autoStopRunnable: Runnable? = null

    val state: VoiceMessageRecorderState
        get() {
            requireMainThread()
            return stateMachine.state
        }

    fun start(outputDir: File, messageId: String): Result<Unit> {
        mainThreadFailure()?.let { return Result.failure(it) }
        stateMachine.beginRecording().exceptionOrNull()?.let { return Result.failure(it) }

        var recorderBeingPrepared: MediaRecorder? = null
        var pathsBeingPrepared: VoiceMessagePaths? = null
        return runCatching {
            ensureOutputDirectory(outputDir)
            val paths = VoiceMessageFilePolicy.resolve(outputDir, messageId).getOrThrow()
            pathsBeingPrepared = paths
            prepareTargetFiles(paths)

            val recorder = createMediaRecorder()
            recorderBeingPrepared = recorder
            configureRecorder(recorder, paths.stagingFile)
            recorder.prepare()
            recorder.start()

            activeRecorder = recorder
            activePaths = paths
            recordingStartedAtMillis = SystemClock.elapsedRealtime()
            recorderBeingPrepared = null
            pathsBeingPrepared = null
            scheduleAutoStop()
        }.onFailure {
            runCatching { recorderBeingPrepared?.reset() }
            runCatching { recorderBeingPrepared?.release() }
            pathsBeingPrepared?.let(::deleteStagingFile)
            stateMachine.returnToIdle()
        }
    }

    fun stop(): Result<RecordedVoiceMessage> {
        mainThreadFailure()?.let { return Result.failure(it) }
        return stopInternal()
    }

    fun cancel() {
        requireMainThread()
        if (stateMachine.state != VoiceMessageRecorderState.RECORDING) return
        cancelInternal()
    }

    override fun close() {
        requireMainThread()
        if (stateMachine.isClosed) return
        if (stateMachine.state == VoiceMessageRecorderState.RECORDING) cancelInternal()
        cancelAutoStop()
        stateMachine.close()
    }

    private fun configureRecorder(recorder: MediaRecorder, stagingFile: File) {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(AUDIO_CHANNELS)
        recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE_HZ)
        recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE_BPS)
        recorder.setMaxDuration(MAX_DURATION_MILLIS.toInt())
        recorder.setOutputFile(stagingFile.absolutePath)
        recorder.setOnInfoListener { source, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                onMainThread { autoStopIfCurrent(source) }
            }
        }
        recorder.setOnErrorListener { source, what, extra ->
            onMainThread { recordingErrorIfCurrent(source, what, extra) }
        }
    }

    private fun stopInternal(): Result<RecordedVoiceMessage> {
        stateMachine.requireRecording().exceptionOrNull()?.let { return Result.failure(it) }
        cancelAutoStop()

        val recorder = activeRecorder
            ?: return failMissingActiveRecording("MediaRecorder is missing while RECORDING.")
        val paths = activePaths
            ?: return failMissingActiveRecording("Output paths are missing while RECORDING.")
        // Main-thread scheduling and device callbacks may arrive just after the
        // MediaRecorder cap. Keep the public contract within the wire limit.
        val durationMs = max(1L, SystemClock.elapsedRealtime() - recordingStartedAtMillis)
            .coerceAtMost(MAX_DURATION_MILLIS)

        activeRecorder = null
        activePaths = null
        recordingStartedAtMillis = 0L

        return runCatching {
            recorder.stop()
            recorder.release()
            commitStagingFile(paths)
            RecordedVoiceMessage(file = paths.committedFile, durationMs = durationMs)
        }.onFailure {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            deleteStagingFile(paths)
        }.also {
            stateMachine.returnToIdle()
        }
    }

    private fun failMissingActiveRecording(message: String): Result<RecordedVoiceMessage> {
        val paths = activePaths
        activeRecorder?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        activeRecorder = null
        activePaths = null
        recordingStartedAtMillis = 0L
        cancelAutoStop()
        paths?.let(::deleteStagingFile)
        stateMachine.returnToIdle()
        return Result.failure(IllegalStateException(message))
    }

    private fun cancelInternal() {
        cancelAutoStop()
        val recorder = activeRecorder
        val paths = activePaths
        activeRecorder = null
        activePaths = null
        recordingStartedAtMillis = 0L
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        paths?.let(::deleteStagingFile)
        stateMachine.returnToIdle()
    }

    private fun scheduleAutoStop() {
        cancelAutoStop()
        val scheduledRecorder = activeRecorder
        val runnable = Runnable { autoStopIfCurrent(scheduledRecorder) }
        autoStopRunnable = runnable
        mainHandler.postDelayed(runnable, MAX_DURATION_MILLIS)
    }

    private fun cancelAutoStop() {
        autoStopRunnable?.let(mainHandler::removeCallbacks)
        autoStopRunnable = null
    }

    private fun autoStopIfCurrent(source: MediaRecorder?) {
        if (source == null || source !== activeRecorder) return
        if (stateMachine.state != VoiceMessageRecorderState.RECORDING) return
        stopInternal().fold(
            onSuccess = { listener?.onAutoStopped(it) },
            onFailure = { listener?.onRecordingError(it) },
        )
    }

    private fun recordingErrorIfCurrent(source: MediaRecorder, what: Int, extra: Int) {
        if (source !== activeRecorder) return
        if (stateMachine.state != VoiceMessageRecorderState.RECORDING) return
        cancelInternal()
        listener?.onRecordingError(
            IllegalStateException("MediaRecorder failed (what=$what, extra=$extra)."),
        )
    }

    private fun ensureOutputDirectory(outputDir: File) {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("Unable to create outputDir: ${outputDir.absolutePath}")
        }
        require(outputDir.isDirectory) { "outputDir is not a directory: ${outputDir.absolutePath}" }
        require(outputDir.canWrite()) { "outputDir is not writable: ${outputDir.absolutePath}" }
    }

    private fun prepareTargetFiles(paths: VoiceMessagePaths) {
        check(!paths.committedFile.exists()) {
            "A committed voice message already exists: ${paths.committedFile.name}"
        }
        if (paths.stagingFile.exists() && !paths.stagingFile.delete()) {
            throw IllegalStateException("Unable to remove stale staging file: ${paths.stagingFile.name}")
        }
    }

    private fun commitStagingFile(paths: VoiceMessagePaths) {
        check(paths.stagingFile.isFile) { "MediaRecorder did not create the staging file." }
        check(!paths.committedFile.exists()) {
            "A committed voice message already exists: ${paths.committedFile.name}"
        }
        Files.move(paths.stagingFile.toPath(), paths.committedFile.toPath())
    }

    private fun deleteStagingFile(paths: VoiceMessagePaths) {
        runCatching { if (paths.stagingFile.exists()) paths.stagingFile.delete() }
    }

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun mainThreadFailure(): Throwable? =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            null
        } else {
            IllegalStateException("VoiceMessageRecorder must be called on the Android main thread.")
        }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "VoiceMessageRecorder must be called on the Android main thread."
        }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val MAX_DURATION_MILLIS = 60_000L
        const val AUDIO_CHANNELS = 1
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_BIT_RATE_BPS = 64_000
    }
}
