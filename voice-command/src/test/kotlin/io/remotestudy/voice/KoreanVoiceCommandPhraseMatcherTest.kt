package io.remotestudy.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoreanVoiceCommandPhraseMatcherTest {
    private val matcher = KoreanVoiceCommandPhraseMatcher()

    @Test
    fun `matches study start variants`() {
        assertMatches(
            VoiceCommand.STUDY_START,
            "공부 시작",
            "공부를 시작해",
            "공부 시작해 주세요!",
            "공부 시작할게",
        )
    }

    @Test
    fun `matches problem done variants`() {
        assertMatches(
            VoiceCommand.PROBLEM_DONE,
            "풀었어",
            "다 풀었어",
            "문제를 다 풀었어요.",
            "이 문제 풀었습니다",
        )
    }

    @Test
    fun `matches undo variants`() {
        assertMatches(
            VoiceCommand.UNDO,
            "취소",
            "취소해 줘",
            "방금 거 취소해",
            "방금꺼 취소할게",
        )
    }

    @Test
    fun `matches pause variants`() {
        assertMatches(
            VoiceCommand.PAUSE,
            "잠깐 멈춰",
            "잠시 멈춰 주세요",
            "잠깐 멈출게",
            "일시 정지",
        )
    }

    @Test
    fun `matches stop variants`() {
        assertMatches(
            VoiceCommand.STOP,
            "공부 끝",
            "공부를 끝내 주세요",
            "공부 종료",
            "공부 그만할게",
        )
    }

    @Test
    fun `matches the dad message wake phrase`() {
        assertEquals(VoiceCommand.DAD_MESSAGE, matcher.match("아빠"))
        assertNull(matcher.match("우리 아빠"))
    }

    @Test
    fun `normalizes unicode whitespace punctuation and case`() {
        assertEquals(VoiceCommand.STUDY_START, matcher.match("  공부, 시작해!!  "))
        assertEquals(VoiceCommand.UNDO, matcher.match("취소…"))
    }

    @Test
    fun `rejects negation partial keyword and unrelated speech`() {
        listOf(
            "다 못 풀었어",
            "공부 시작 시간이야",
            "공부 끝나면 놀자",
            "멈추지 마",
            "문제 푸는 중",
            "",
            "   ",
        ).forEach { phrase -> assertNull(phrase, matcher.match(phrase)) }
    }

    @Test
    fun `uses first matching recognition hypothesis`() {
        assertEquals(
            VoiceCommand.PROBLEM_DONE,
            matcher.matchFirst(listOf("알 수 없는 말", "다 풀었어", "취소")),
        )
    }

    @Test
    fun `returns null when no hypothesis matches`() {
        assertNull(matcher.matchFirst(listOf("오늘 날씨", "문제 푸는 중")))
    }

    private fun assertMatches(expected: VoiceCommand, vararg phrases: String) {
        phrases.forEach { phrase -> assertEquals(phrase, expected, matcher.match(phrase)) }
    }
}
