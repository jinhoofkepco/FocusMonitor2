package io.remotestudy.voicemessage

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Plays at most one local voice message.
 *
 * Every public call must be made on the Android main thread. After a successful [play], its
 * callback is invoked exactly once on the main thread: success for natural completion, or failure
 * for [stop], replacement by another [play], [close], or MediaPlayer error.
 */
class VoiceMessagePlayer(context: Context) : AutoCloseable {
    @Suppress("unused")
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var completion: ((Result<Unit>) -> Unit)? = null
    private var generation = 0L
    private var closed = false

    fun play(file: File, onComplete: (Result<Unit>) -> Unit): Result<Unit> {
        mainThreadFailure()?.let { return Result.failure(it) }
        if (closed) return Result.failure(IllegalStateException("VoiceMessagePlayer is closed."))
        validatePlayableFile(file).exceptionOrNull()?.let { return Result.failure(it) }

        if (player != null) {
            finishPlayback(Result.failure(VoiceMessagePlaybackReplacedException()))
            if (closed) {
                return Result.failure(IllegalStateException("VoiceMessagePlayer was closed by a callback."))
            }
            if (player != null) {
                return Result.failure(
                    IllegalStateException("A callback started another playback before replacement completed."),
                )
            }
        }

        val nextGeneration = ++generation
        var preparedPlayer: MediaPlayer? = null
        return runCatching {
            val nextPlayer = MediaPlayer()
            preparedPlayer = nextPlayer
            nextPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setOnCompletionListener {
                onMainThread { finishIfCurrent(nextGeneration, Result.success(Unit)) }
            }
            nextPlayer.setOnErrorListener { _, what, extra ->
                onMainThread {
                    finishIfCurrent(
                        nextGeneration,
                        Result.failure(VoiceMessagePlaybackFailedException(what, extra)),
                    )
                }
                true
            }
            nextPlayer.prepare()
            nextPlayer.start()
            player = nextPlayer
            completion = onComplete
            preparedPlayer = null
        }.onFailure {
            runCatching { preparedPlayer?.release() }
        }
    }

    fun stop() {
        requireMainThread()
        if (player == null) return
        finishPlayback(Result.failure(VoiceMessagePlaybackStoppedException()))
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        if (player != null) {
            finishPlayback(Result.failure(VoiceMessagePlaybackStoppedException()))
        }
        closed = true
    }

    private fun finishIfCurrent(expectedGeneration: Long, result: Result<Unit>) {
        if (expectedGeneration != generation || player == null) return
        finishPlayback(result)
    }

    private fun finishPlayback(result: Result<Unit>) {
        val finishedPlayer = player
        val finishedCallback = completion
        player = null
        completion = null
        generation++
        runCatching { finishedPlayer?.stop() }
        runCatching { finishedPlayer?.release() }
        finishedCallback?.invoke(result)
    }

    private fun validatePlayableFile(file: File): Result<Unit> = runCatching {
        require(file.extension.equals("m4a", ignoreCase = true)) {
            "Voice-message file must use the .m4a extension."
        }
        require(file.isFile) { "Voice-message file does not exist: ${file.absolutePath}" }
        require(file.canRead()) { "Voice-message file is not readable: ${file.absolutePath}" }
    }

    private fun mainThreadFailure(): Throwable? =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            null
        } else {
            IllegalStateException("VoiceMessagePlayer must be called on the Android main thread.")
        }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "VoiceMessagePlayer must be called on the Android main thread."
        }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
