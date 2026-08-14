package io.remotestudy.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Pure Korean phrase matcher with deliberately bounded variants.
 *
 * It avoids substring and edit-distance matching because false activation is more harmful than
 * asking the student to repeat a command. Android recognition hypotheses should be passed in
 * confidence order through [matchFirst].
 */
class KoreanVoiceCommandPhraseMatcher {
    fun match(phrase: String): VoiceCommand? {
        val normalized = normalize(phrase)
        if (normalized.isEmpty()) return null

        return COMMAND_PATTERNS.firstOrNull { (_, pattern) -> pattern.matches(normalized) }?.first
    }

    fun matchFirst(hypotheses: List<String>): VoiceCommand? =
        hypotheses.firstNotNullOfOrNull(::match)

    internal fun normalize(phrase: String): String =
        Normalizer.normalize(phrase, Normalizer.Form.NFKC)
            .lowercase(Locale.KOREAN)
            .replace(NON_COMMAND_CHARACTERS, "")

    private companion object {
        val NON_COMMAND_CHARACTERS = Regex("[\\s\\p{P}\\p{S}]+")

        val COMMAND_PATTERNS = listOf(
            VoiceCommand.STUDY_START to Regex(
                "^공부(를)?시작(해|해줘|해주세요|하자|할게|합니다|할까요)?$",
            ),
            VoiceCommand.PROBLEM_DONE to Regex(
                "^(문제(를)?|이문제(를)?)?(다)?풀었(어|어요|습니다)?$",
            ),
            VoiceCommand.UNDO to Regex(
                "^(방금(거|꺼))?취소(해|해줘|해주세요|할게)?$",
            ),
            VoiceCommand.PAUSE to Regex(
                "^((잠깐|잠시)(멈춰|멈춰줘|멈춰주세요|멈출게)|일시정지)$",
            ),
            VoiceCommand.STOP to Regex(
                "^공부(를)?(끝|끝내|끝내줘|끝내주세요|끝낼게|종료|그만|그만해|그만할게)$",
            ),
        )
    }
}
