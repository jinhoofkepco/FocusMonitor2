package io.remotestudy.telegram

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/** Separates retryable transport outages from requests that can never succeed unchanged. */
internal object UploadFailurePolicy {
    fun isPermanent(error: Throwable): Boolean = when (error) {
        is TelegramApiException -> error.statusCode in 400..499 &&
            error.statusCode !in setOf(408, 409, 425, 429)
        is FileNotFoundException, is NoSuchFileException, is SecurityException,
        is IllegalArgumentException -> true
        else -> false
    }

    fun shortReason(error: Throwable): String = when (error) {
        is TelegramApiException -> "Telegram ${error.statusCode} · ${error.message.orEmpty().take(120)}"
        is FileNotFoundException, is NoSuchFileException -> "로컬 파일을 찾을 수 없음"
        is SecurityException -> "로컬 파일 접근 거부"
        is IllegalArgumentException -> "파일 또는 요청 형식 오류"
        else -> error.javaClass.simpleName
    }
}
