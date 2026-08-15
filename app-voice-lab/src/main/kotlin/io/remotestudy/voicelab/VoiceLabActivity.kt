package io.remotestudy.voicelab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.remotestudy.voice.StudentVoiceCommandController
import io.remotestudy.voice.StudentVoiceCommandListener
import io.remotestudy.voice.VoiceCommand
import io.remotestudy.voice.VoiceCommandError
import io.remotestudy.voice.VoiceCommandStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceLabActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var modeGroup: RadioGroup
    private lateinit var startButton: Button
    private lateinit var stateText: TextView
    private lateinit var resultText: TextView
    private lateinit var logText: TextView
    private lateinit var currentController: StudentVoiceCommandController
    private var directRecognizer: SpeechRecognizer? = null
    private var running = false
    private var directGeneration = 0L
    private var sessionCount = 0
    private var errorCount = 0
    private var commandCount = 0
    private val logs = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentController = StudentVoiceCommandController(
            this,
            object : StudentVoiceCommandListener {
                override fun onCommand(command: VoiceCommand) {
                    commandCount++
                    resultText.text = "명령 감지: ${commandLabel(command)}"
                    appendLog("현재 방식 명령: ${commandLabel(command)}")
                    refreshState("인식 중")
                }

                override fun onRecognitionText(text: String, isFinal: Boolean) {
                    resultText.text = if (isFinal) "최종: $text" else "중간: $text"
                    if (isFinal) appendLog("현재 방식 결과: $text")
                }

                override fun onStatus(status: VoiceCommandStatus) {
                    refreshState("현재 방식 ${status.state}")
                }

                override fun onError(error: VoiceCommandError) {
                    errorCount++
                    running = false
                    startButton.text = "시험 시작"
                    appendLog("현재 방식 중지: ${error.kind} code=${error.platformCode} ${error.message.orEmpty()}")
                    refreshState("중지됨")
                }
            },
        )
        setContentView(buildView())
        appendLog("기기: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        ensurePermission()
    }

    override fun onStop() {
        stopTest("화면 이탈")
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        currentController.destroy()
        destroyDirectRecognizer()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(0xFFF5F7FC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "음성인식 비교 시험"
            textSize = 24f
            setTextColor(0xFF172033.toInt())
        })
        root.addView(TextView(this).apply {
            text = "각 방식에서 ‘풀었어’를 20번, ‘아빠’를 10번 말하세요. 인식 수·오류 수와 시스템 소리 발생 여부를 비교합니다."
            textSize = 14f
            setTextColor(0xFF65708A.toInt())
            setPadding(0, dp(8), 0, dp(10))
        })
        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            LabMode.DIRECT_RESTART to "A · 처음 방식 — 시스템 마이크, 종료 시 재시작",
            LabMode.DIRECT_SEGMENTED to "B · 장시간 직접 인식 — 시스템 마이크, segmented 실험",
            LabMode.CURRENT_EXTERNAL to "C · 현재 학생앱 — 외부 PCM 연속 입력",
        ).forEachIndexed { index, (mode, label) ->
            modeGroup.addView(RadioButton(this).apply {
                id = mode.viewId
                text = label
                textSize = 14f
                isChecked = index == 0
            })
        }
        root.addView(modeGroup)
        stateText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF3457D5.toInt())
            setPadding(0, dp(10), 0, dp(6))
        }
        resultText = TextView(this).apply {
            text = "인식 결과가 여기에 표시됩니다"
            textSize = 20f
            setTextColor(0xFF172033.toInt())
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(stateText)
        root.addView(resultText, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startButton = Button(this).apply {
            text = "시험 시작"
            setOnClickListener { if (running) stopTest("사용자 중지") else startTest() }
        }
        controls.addView(startButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(Button(this).apply {
            text = "로그 복사"
            setOnClickListener { copyLog() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF374151.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(logText)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        refreshState("대기")
        return root
    }

    private fun startTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensurePermission()
            return
        }
        stopEngines()
        running = true
        sessionCount = 0
        errorCount = 0
        commandCount = 0
        modeGroup.isEnabled = false
        for (index in 0 until modeGroup.childCount) modeGroup.getChildAt(index).isEnabled = false
        startButton.text = "시험 중지"
        val mode = selectedMode()
        appendLog("시험 시작: ${mode.label}")
        if (mode == LabMode.CURRENT_EXTERNAL) {
            sessionCount = 1
            currentController.start()
        } else {
            startDirectSession(mode)
        }
        refreshState("시작 중")
    }

    private fun startDirectSession(mode: LabMode) {
        if (!running) return
        destroyDirectRecognizer()
        val generation = ++directGeneration
        sessionCount++
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        directRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = active(generation) { refreshState("듣는 중") }
            override fun onBeginningOfSpeech() = active(generation) { refreshState("말 감지") }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = active(generation) { refreshState("처리 중") }
            override fun onError(error: Int) = active(generation) {
                errorCount++
                appendLog("오류 code=$error (${errorLabel(error)})")
                scheduleDirectRestart(mode, generation)
            }
            override fun onResults(results: Bundle?) = active(generation) {
                showDirectResults(results, true)
                scheduleDirectRestart(mode, generation)
            }
            override fun onPartialResults(partialResults: Bundle?) = active(generation) {
                showDirectResults(partialResults, false)
            }
            override fun onSegmentResults(segmentResults: Bundle) = active(generation) {
                showDirectResults(segmentResults, true)
            }
            override fun onEndOfSegmentedSession() = active(generation) {
                appendLog("segmented 세션 종료")
                scheduleDirectRestart(mode, generation)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(directIntent(mode))
        appendLog("세션 #$sessionCount 시작")
        refreshState("시작 중")
    }

    private fun directIntent(mode: LabMode) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
        putStringArrayListExtra(
            RecognizerIntent.EXTRA_BIASING_STRINGS,
            arrayListOf("풀었어", "풀었어요", "문제 풀었어", "다 풀었어", "아빠"),
        )
        if (mode == LabMode.DIRECT_SEGMENTED && Build.VERSION.SDK_INT >= 33) {
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
        }
    }

    private fun showDirectResults(bundle: Bundle?, final: Boolean) {
        val candidates = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val primary = candidates.firstOrNull().orEmpty()
        resultText.text = if (final) "최종: $primary" else "중간: $primary"
        if (!final) return
        val normalized = primary.replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        val detected = when {
            "풀었" in normalized || normalized == "벌써" || normalized == "벌써요" -> "풀었어"
            normalized.startsWith("아빠") -> "아빠"
            else -> null
        }
        if (detected != null) {
            commandCount++
            appendLog("명령 감지: $detected · 원문=$primary")
        } else {
            appendLog("결과: $primary")
        }
        refreshState("인식 중")
    }

    private fun scheduleDirectRestart(mode: LabMode, generation: Long) {
        if (!running || generation != directGeneration) return
        directRecognizer?.destroy()
        directRecognizer = null
        handler.postDelayed({
            if (running && generation == directGeneration) startDirectSession(mode)
        }, 700L)
    }

    private fun stopTest(reason: String) {
        if (!running) return
        running = false
        appendLog("시험 종료: $reason")
        stopEngines()
        startButton.text = "시험 시작"
        for (index in 0 until modeGroup.childCount) modeGroup.getChildAt(index).isEnabled = true
        refreshState("중지")
    }

    private fun stopEngines() {
        handler.removeCallbacksAndMessages(null)
        currentController.stop()
        destroyDirectRecognizer()
    }

    private fun destroyDirectRecognizer() {
        directGeneration++
        runCatching { directRecognizer?.cancel() }
        runCatching { directRecognizer?.destroy() }
        directRecognizer = null
    }

    private fun active(generation: Long, action: () -> Unit) {
        if (running && generation == directGeneration) action()
    }

    private fun selectedMode(): LabMode = LabMode.entries.firstOrNull {
        it.viewId == modeGroup.checkedRadioButtonId
    } ?: LabMode.DIRECT_RESTART

    private fun refreshState(label: String) {
        stateText.text = "$label · 세션 $sessionCount · 명령 $commandCount · 오류 $errorCount"
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.addLast("$timestamp  $message")
        while (logs.size > 150) logs.removeFirst()
        if (::logText.isInitialized) logText.text = logs.joinToString("\n")
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("음성인식 시험 로그", logs.joinToString("\n")))
        appendLog("로그를 클립보드에 복사했습니다")
    }

    private fun ensurePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    @Deprecated("Framework permission callback retained for minSdk 26")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            appendLog("마이크 권한이 거부되었습니다")
        }
    }

    private fun commandLabel(command: VoiceCommand) = when (command) {
        VoiceCommand.PROBLEM_DONE -> "풀었어"
        VoiceCommand.DAD_MESSAGE -> "아빠"
        VoiceCommand.STUDY_START -> "공부 시작"
        VoiceCommand.UNDO -> "취소"
        VoiceCommand.PAUSE -> "일시 정지"
        VoiceCommand.STOP -> "공부 종료"
    }

    private fun errorLabel(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크"
        SpeechRecognizer.ERROR_NO_MATCH -> "일치 없음"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "음성서비스"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말 없음"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "요청 과다"
        else -> "기타"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class LabMode(val viewId: Int, val label: String) {
        DIRECT_RESTART(1001, "A 처음 방식"),
        DIRECT_SEGMENTED(1002, "B 장시간 직접 인식"),
        CURRENT_EXTERNAL(1003, "C 현재 학생앱 방식"),
    }

    private companion object { const val REQUEST_AUDIO = 700 }
}
