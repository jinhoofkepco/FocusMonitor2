package io.remotestudy.voicemessage

import java.io.File

internal data class VoiceMessagePaths(
    val stagingFile: File,
    val committedFile: File,
)

/** Pure path policy; directory creation and file mutation remain in the recorder. */
internal object VoiceMessageFilePolicy {
    const val MAX_MESSAGE_ID_LENGTH = 64

    private val SAFE_MESSAGE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    fun resolve(outputDir: File, messageId: String): Result<VoiceMessagePaths> = runCatching {
        require(messageId.length <= MAX_MESSAGE_ID_LENGTH) {
            "messageId must be at most $MAX_MESSAGE_ID_LENGTH characters."
        }
        require(SAFE_MESSAGE_ID.matches(messageId)) {
            "messageId must start with an ASCII letter or digit and contain only letters, digits, '_' or '-'."
        }

        val canonicalDirectory = outputDir.canonicalFile
        val staging = File(canonicalDirectory, "$messageId.part").canonicalFile
        val committed = File(canonicalDirectory, "$messageId.m4a").canonicalFile
        require(staging.parentFile == canonicalDirectory && committed.parentFile == canonicalDirectory) {
            "Resolved voice-message files must remain inside outputDir."
        }
        VoiceMessagePaths(stagingFile = staging, committedFile = committed)
    }
}
