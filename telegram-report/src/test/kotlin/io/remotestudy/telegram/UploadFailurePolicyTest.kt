package io.remotestudy.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class UploadFailurePolicyTest {
    @Test fun ordinaryTelegram4xxIsPermanent() {
        assertTrue(UploadFailurePolicy.isPermanent(TelegramApiException(400, "bad photo")))
        assertTrue(UploadFailurePolicy.isPermanent(TelegramApiException(413, "too large")))
    }

    @Test fun rateLimitTimeoutServerAndNetworkFailuresRemainRetryable() {
        assertFalse(UploadFailurePolicy.isPermanent(TelegramApiException(408, "timeout")))
        assertFalse(UploadFailurePolicy.isPermanent(TelegramApiException(429, "retry later")))
        assertFalse(UploadFailurePolicy.isPermanent(TelegramApiException(500, "server")))
        assertFalse(UploadFailurePolicy.isPermanent(IOException("offline")))
    }

    @Test fun missingOrInvalidLocalPayloadIsPermanent() {
        assertTrue(UploadFailurePolicy.isPermanent(FileNotFoundException("gone")))
        assertTrue(UploadFailurePolicy.isPermanent(IllegalArgumentException("missing file")))
    }
}
