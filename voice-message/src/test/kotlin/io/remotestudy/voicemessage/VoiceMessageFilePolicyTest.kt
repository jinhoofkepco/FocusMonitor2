package io.remotestudy.voicemessage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageFilePolicyTest {
    private val outputDir = File("build/test-output/voice")

    @Test
    fun `resolves safe staging and committed filenames`() {
        val paths = VoiceMessageFilePolicy
            .resolve(outputDir, "msg_01-ABC")
            .getOrThrow()

        assertEquals("msg_01-ABC.part", paths.stagingFile.name)
        assertEquals("msg_01-ABC.m4a", paths.committedFile.name)
        assertEquals(outputDir.canonicalFile, paths.stagingFile.parentFile)
        assertEquals(outputDir.canonicalFile, paths.committedFile.parentFile)
    }

    @Test
    fun `accepts a 64 character id`() {
        assertTrue(VoiceMessageFilePolicy.resolve(outputDir, "a".repeat(64)).isSuccess)
    }

    @Test
    fun `rejects unsafe or ambiguous ids`() {
        listOf(
            "",
            "../message",
            "folder/message",
            "message.m4a",
            " message",
            "메시지",
            "a".repeat(65),
        ).forEach { messageId ->
            assertTrue(messageId, VoiceMessageFilePolicy.resolve(outputDir, messageId).isFailure)
        }
    }
}
