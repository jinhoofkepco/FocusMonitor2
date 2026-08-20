package io.remotestudy.student

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.remotestudy.detection.DetectionEventKind
import io.remotestudy.detection.StudyActivityMonitor
import io.remotestudy.domain.session.Pause
import io.remotestudy.domain.session.ProblemCompleted
import io.remotestudy.domain.session.Resume
import io.remotestudy.domain.session.SessionSnapshot
import io.remotestudy.domain.session.SessionStateMachine
import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionStatus
import io.remotestudy.domain.session.StartOrigin
import io.remotestudy.domain.session.StartRequested
import io.remotestudy.domain.session.Tick
import io.remotestudy.domain.session.StudySchedule
import io.remotestudy.telegram.NormalizedBookRegion
import io.remotestudy.telegram.RemoteSessionPhase
import io.remotestudy.telegram.TelegramCommand
import io.remotestudy.telegram.TelegramCommandHandler
import io.remotestudy.telegram.TelegramConfig
import io.remotestudy.telegram.TelegramReporter
import io.remotestudy.voice.StudentVoiceCommandController
import io.remotestudy.voice.StudentVoiceCommandListener
import io.remotestudy.voice.VoiceCommand
import io.remotestudy.voice.VoiceCommandError
import io.remotestudy.voice.VoiceCommandStatus
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("UnsafeOptInUsageError")
class StudentStudyService : Service(), LifecycleOwner, TelegramCommandHandler {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor { Thread(it, "study-camera") }
    private val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "study-analysis") }
    private val sessionLock = Any()
    private var session = SessionStateMachine()
    private var schedule = StudySchedule()
    private var teacherCountdownMs = DEFAULT_TEACHER_COUNTDOWN_MS
    private var sessionClockOffsetMs = 0L
    private var completedProblemCount = 0
    private var countdownPaused = false
    private var countdownPausedAtSessionMs = 0L
    private var sessionActive = false
    private var captureInFlight = AtomicBoolean(false)
    private val captureGeneration = AtomicLong(0L)
    private var captureStartedAtElapsedMs = 0L
    private val cameraComparisonInFlight = AtomicBoolean(false)
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var focusLocked = false
    private var focusGeneration = 0
    private var cameraBindGeneration = 0
    private var cameraRecoveryNoticePending = false
    private var cameraRecoveryFailureReported = false
    private var nextFocusAttemptElapsedMs = 0L
    @Volatile private var destroyed = false
    private lateinit var reporter: TelegramReporter
    private lateinit var voice: StudentVoiceCommandController
    private lateinit var tts: TextToSpeech
    private lateinit var tone: ToneGenerator
    private lateinit var motionAnalyzer: MotionAnalyzer
    private val activityMonitor = StudyActivityMonitor()
    private val preferences by lazy { getSharedPreferences("student-study", MODE_PRIVATE) }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        Log.i(TAG, "thermal_status=$status")
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickSession()
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    private val captureTicker = object : Runnable {
        override fun run() {
            maybeCapture()
            mainHandler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
        promoteToForeground("텔레그램 연결 준비")
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Student feedback must follow media volume so it remains audible when
        // notification volume is muted but video/music playback is enabled.
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { mainHandler.post(::restartVoiceAfterSpeech) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { mainHandler.post(::restartVoiceAfterSpeech) }
                })
            }
        }
        val storedCredentials = TelegramCredentialStore(this).load()
        val telegramConfig = TelegramConfig(
            botToken = storedCredentials?.botToken ?: BuildConfig.TELEGRAM_BOT_TOKEN,
            allowedChatId = storedCredentials?.chatId ?: BuildConfig.TELEGRAM_CHAT_ID,
        )
        reporter = TelegramReporter(
            rootDirectory = File(filesDir, "telegram-report"),
            config = telegramConfig,
            commandHandler = this,
        )
        motionAnalyzer = MotionAnalyzer(
            elapsedRealtime = SystemClock::elapsedRealtime,
            regionProvider = ::loadBookRegion,
            listener = { evidence ->
                activityMonitor.observe(evidence).forEach { event ->
                    when (event.kind) {
                        DetectionEventKind.AWAY -> reporter.recordAway(System.currentTimeMillis(), event.observedDurationMs)
                        DetectionEventKind.PRESENCE_RESTORED -> reporter.sendImmediate("자리 복귀 · ${event.observedDurationMs / 1_000}초 이탈")
                        DetectionEventKind.NO_BOOK_MOVEMENT -> reporter.sendImmediate("책 움직임 없음 · ${event.observedDurationMs / 1_000}초")
                        DetectionEventKind.BOOK_MOVEMENT_RESTORED -> reporter.sendImmediate("문제 풀이 움직임 재개")
                    }
                }
            },
        )
        voice = StudentVoiceCommandController(
            this,
            object : StudentVoiceCommandListener {
                override fun onCommand(command: VoiceCommand) = handleVoiceCommand(command)
                override fun onMessageRecognized(text: String) {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 80)
                    reporter.sendImmediate("학생 메시지: ${text.trim()}")
                }
                override fun onStatus(status: VoiceCommandStatus) = Unit
                override fun onError(error: VoiceCommandError) {
                    if (!error.recoverable) broadcastState("음성인식 중지: ${error.message.orEmpty()}")
                }
            },
        )
        reporter.updateBookRegion(loadBookRegion())
        loadTimerSettings()
        if (!preferences.getBoolean(KEY_ACTIVE, false)) reporter.cleanupPreviousSessionFiles()
        restoreSession()
        if (!telegramConfig.enabled) {
            broadcastState("텔레그램 설정 필요 · 학생 앱에서 연결하세요")
        } else {
            reporter.start()
            bindCamera()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
            broadcastState("텔레그램 명령 대기")
        }
        registerThermalListener()
        mainHandler.post(ticker)
        mainHandler.post(captureTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_REGION -> {
                val region = regionFromIntent(intent)
                saveBookRegion(region)
                reporter.updateBookRegion(region)
                motionAnalyzer.resetBaseline()
                resetBookFocus()
            }
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun handle(command: TelegramCommand) {
        when (command) {
            TelegramCommand.Start -> startSession()
            TelegramCommand.Pause -> pauseSession()
            TelegramCommand.Resume -> resumeSession()
            TelegramCommand.Stop -> stopSession()
            TelegramCommand.Restart -> restartSession()
            TelegramCommand.NextPhase -> nextPhase()
            TelegramCommand.Settings -> reporter.sendImmediate(settingsText())
            TelegramCommand.Refocus -> requestBookRefocus()
            TelegramCommand.ShowCameraMenu -> reporter.sendCameraMenu()
            TelegramCommand.CameraDiagnostics -> sendCameraDiagnostics()
            TelegramCommand.CameraComparison -> startCameraComparison()
            is TelegramCommand.SetSchedule -> setSchedule(command)
            is TelegramCommand.SetCountdown -> setCountdown(command.seconds)
            is TelegramCommand.SetRemaining -> setRemaining(command.seconds)
            is TelegramCommand.GoToPhase -> goToPhase(command.phase, command.remainingSeconds)
            TelegramCommand.Status -> reporter.sendImmediate(statusText(snapshot()))
            TelegramCommand.Menu -> reporter.sendControlMenu()
            TelegramCommand.ShowAreaGrid -> reporter.sendAreaGrid()
            is TelegramCommand.PreviewBookRegion -> reporter.sendAreaPreview(command)
            is TelegramCommand.SetBookRegion -> {
                saveBookRegion(command.region)
                reporter.updateBookRegion(command.region)
                motionAnalyzer.resetBaseline()
                resetBookFocus()
                reporter.sendImmediate("책 영역 적용 완료 · 다음 촬영부터 새 영역과 초점을 사용합니다")
            }
            TelegramCommand.ShowBookRotation -> reporter.sendRotationMenu()
            is TelegramCommand.SetBookRotation -> {
                reporter.updateBookRotation(command.degrees)
                reporter.sendImmediate("책 상세사진 회전을 ${command.degrees}°로 변경했습니다 · 지금부터 적용됩니다")
            }
            is TelegramCommand.Unknown -> {
                if (command.input.trim().startsWith('/')) {
                    reporter.sendImmediate(commandHelp())
                } else {
                    speakTeacherMessage(command.input)
                }
            }
            TelegramCommand.Index, is TelegramCommand.Book -> Unit // handled inside TelegramReporter
        }
    }

    private fun startSession() = synchronized(sessionLock) {
        if (sessionActive) {
            reporter.sendImmediate("이미 세션이 진행 중입니다 · ${statusText(session.snapshot())}")
            return@synchronized
        }
        completedProblemCount = 0
        countdownPaused = false
        val nowElapsed = SystemClock.elapsedRealtime()
        session = newSessionMachine()
        sessionClockOffsetMs = -nowElapsed
        session.dispatch(
            StartRequested(
                "tg-${UUID.randomUUID()}",
                if (teacherCountdownMs == 0L) StartOrigin.STUDENT else StartOrigin.TEACHER,
                sessionNow(nowElapsed),
            ),
        )
        session.dispatch(Tick(sessionNow(nowElapsed)))
        sessionActive = true
        preferences.edit().putBoolean(KEY_ACTIVE, true).putLong(KEY_STARTED_EPOCH, System.currentTimeMillis()).commit()
        reporter.startFreshSession(System.currentTimeMillis(), nowElapsed)
        motionAnalyzer.resetBaseline()
        persistSnapshot(session.snapshot())
        val started = session.snapshot()
        reporter.sendImmediate("세션 시작 · ${settingsText(oneLine = true)}")
        broadcastState(if (started.status == SessionStatus.START_COUNTDOWN) "${teacherCountdownMs / 1_000}초 뒤 시작" else statusText(started))
    }

    private fun pauseSession() = synchronized(sessionLock) {
        if (!sessionActive) { reporter.sendImmediate("진행 중인 세션이 없습니다"); return@synchronized }
        if (session.snapshot().status == SessionStatus.START_COUNTDOWN) {
            if (!countdownPaused) {
                countdownPaused = true
                countdownPausedAtSessionMs = sessionNow()
                preferences.edit().putBoolean(KEY_COUNTDOWN_PAUSED, true).commit()
                persistSnapshot(session.snapshot())
            }
            reporter.sendImmediate("시작 대기 일시정지 · ${statusText(session.snapshot())}")
            broadcastState("시작 대기 일시정지")
            return@synchronized
        }
        val snapshot = session.dispatch(Pause("tg-${UUID.randomUUID()}", sessionNow()))
        persistSnapshot(snapshot)
        reporter.sendImmediate("일시정지 · ${statusText(snapshot)}")
        updateMonitor(snapshot)
    }

    private fun resumeSession() = synchronized(sessionLock) {
        if (!sessionActive) { reporter.sendImmediate("진행 중인 세션이 없습니다"); return@synchronized }
        if (countdownPaused && session.snapshot().status == SessionStatus.START_COUNTDOWN) {
            sessionClockOffsetMs = countdownPausedAtSessionMs - SystemClock.elapsedRealtime()
            countdownPaused = false
            preferences.edit().putBoolean(KEY_COUNTDOWN_PAUSED, false).commit()
            persistSnapshot(session.snapshot())
            reporter.sendImmediate("시작 대기 재개 · ${statusText(session.snapshot())}")
            broadcastState(statusText(session.snapshot()))
            return@synchronized
        }
        val snapshot = session.dispatch(Resume("tg-${UUID.randomUUID()}", sessionNow()))
        persistSnapshot(snapshot)
        reporter.sendImmediate("공부 재개 · ${statusText(snapshot)}")
        updateMonitor(snapshot)
    }

    private fun stopSession() = synchronized(sessionLock) {
        if (!sessionActive) { reporter.sendImmediate("진행 중인 세션이 없습니다"); return@synchronized }
        val snapshot = session.snapshot()
        sessionActive = false
        clearSessionState()
        updateMonitor(snapshot.copy(status = SessionStatus.COMPLETED))
        reporter.finishSession("세션 종료 · 완료한 문제 ${completedProblemCount}개 · 몽타주 대기 ${reporter.pendingUploadCount()}건")
        broadcastState("세션 종료")
    }

    private fun restartSession() = synchronized(sessionLock) {
        if (sessionActive) {
            reporter.finishSession("선생님 명령으로 현재 회차 종료 · 완료한 문제 ${completedProblemCount}개")
            sessionActive = false
            clearSessionState()
        }
        startSession()
    }

    private fun setSchedule(command: TelegramCommand.SetSchedule) = synchronized(sessionLock) {
        val oldSchedule = schedule
        schedule = StudySchedule(
            command.meditationMinutes * 60_000L,
            command.studyMinutes * 60_000L,
            command.breakMinutes * 60_000L,
        )
        saveTimerSettings()
        val current = session.snapshot()
        if (sessionActive && current.status in setOf(SessionStatus.RUNNING, SessionStatus.PAUSED)) {
            val elapsedInPhase = (phaseDuration(oldSchedule, current.phase) - current.phaseRemainingMs).coerceAtLeast(0L)
            val newRemaining = (phaseDuration(schedule, current.phase) - elapsedInPhase).coerceAtLeast(0L)
            positionAt(current.phase, newRemaining, current.status == SessionStatus.PAUSED)
            val updated = session.snapshot()
            if (!finishIfCompleted(updated, "시간 단축으로 세션 완료")) {
                persistSnapshot(updated)
                updateMonitor(updated)
                broadcastState(statusText(updated))
            }
        } else if (sessionActive && current.status == SessionStatus.START_COUNTDOWN) {
            rebuildAtProgress((teacherCountdownMs - current.countdownRemainingMs).coerceAtLeast(0L), paused = false)
            reconcileCountdownPauseAfterRebuild()
            persistSnapshot(session.snapshot())
        }
        reporter.sendImmediate("시간 설정 즉시 적용 · ${settingsText(oneLine = true)}")
    }

    private fun setCountdown(seconds: Int) = synchronized(sessionLock) {
        val current = session.snapshot()
        val oldCountdown = teacherCountdownMs
        teacherCountdownMs = seconds * 1_000L
        saveTimerSettings()
        if (sessionActive && current.status == SessionStatus.START_COUNTDOWN) {
            val elapsed = (oldCountdown - current.countdownRemainingMs).coerceAtLeast(0L)
            val remaining = (teacherCountdownMs - elapsed).coerceAtLeast(0L)
            rebuildAtProgress((teacherCountdownMs - remaining).coerceAtLeast(0L), paused = false)
            reconcileCountdownPauseAfterRebuild()
            persistSnapshot(session.snapshot())
            broadcastState(statusText(session.snapshot()))
        }
        reporter.sendImmediate("시작 대기 ${seconds}초 적용 · 0초면 /start 즉시 시작")
    }

    private fun setRemaining(seconds: Int) = synchronized(sessionLock) {
        if (!sessionActive) {
            reporter.sendImmediate("진행 중인 세션이 없습니다 · /start 또는 /restart 후 사용하세요")
            return@synchronized
        }
        val current = session.snapshot()
        if (current.status == SessionStatus.START_COUNTDOWN) {
            if (seconds > 60) {
                reporter.sendImmediate("시작 대기시간은 최대 60초입니다")
                return@synchronized
            }
            teacherCountdownMs = maxOf(teacherCountdownMs, seconds * 1_000L)
            saveTimerSettings()
            rebuildAtProgress(teacherCountdownMs - seconds * 1_000L, paused = false)
            reconcileCountdownPauseAfterRebuild()
        } else if (current.status in setOf(SessionStatus.RUNNING, SessionStatus.PAUSED)) {
            ensurePhaseCanHold(current.phase, seconds * 1_000L)
            positionAt(current.phase, seconds * 1_000L, current.status == SessionStatus.PAUSED)
        } else {
            reporter.sendImmediate("현재 상태에서는 남은 시간을 바꿀 수 없습니다")
            return@synchronized
        }
        val updated = session.snapshot()
        if (finishIfCompleted(updated, "남은 시간 변경으로 세션 완료")) return@synchronized
        persistSnapshot(updated)
        updateMonitor(updated)
        broadcastState(statusText(updated))
        reporter.sendImmediate("현재 남은 시간 변경 완료 · ${statusText(updated)}")
    }

    private fun goToPhase(remotePhase: RemoteSessionPhase, remainingSeconds: Int?) = synchronized(sessionLock) {
        if (!sessionActive) initializeRemoteSession()
        countdownPaused = false
        preferences.edit().putBoolean(KEY_COUNTDOWN_PAUSED, false).apply()
        val phase = when (remotePhase) {
            RemoteSessionPhase.MEDITATION -> SessionPhase.MEDITATION
            RemoteSessionPhase.STUDY -> SessionPhase.STUDY
            RemoteSessionPhase.BREAK -> SessionPhase.BREAK
        }
        val requested = remainingSeconds?.times(1_000L) ?: phaseDuration(schedule, phase)
        ensurePhaseCanHold(phase, requested)
        positionAt(phase, requested, paused = false)
        val updated = session.snapshot()
        if (finishIfCompleted(updated, "단계 이동으로 세션 완료")) return@synchronized
        persistSnapshot(updated)
        updateMonitor(updated)
        broadcastState(statusText(updated))
        reporter.sendImmediate("단계 이동 완료 · ${statusText(updated)}")
    }

    private fun nextPhase() = synchronized(sessionLock) {
        if (!sessionActive) {
            reporter.sendImmediate("진행 중인 세션이 없습니다")
            return@synchronized
        }
        val current = session.snapshot()
        if (current.status == SessionStatus.START_COUNTDOWN) {
            countdownPaused = false
            preferences.edit().putBoolean(KEY_COUNTDOWN_PAUSED, false).apply()
            positionAt(SessionPhase.MEDITATION, schedule.meditationDurationMs, paused = false)
        } else when (current.phase) {
            SessionPhase.MEDITATION -> positionAt(SessionPhase.STUDY, schedule.studyDurationMs, paused = false)
            SessionPhase.STUDY -> positionAt(SessionPhase.BREAK, schedule.breakDurationMs, paused = false)
            SessionPhase.BREAK, SessionPhase.COMPLETE -> {
                val snapshot = session.snapshot()
                sessionActive = false
                clearSessionState()
                updateMonitor(snapshot.copy(status = SessionStatus.COMPLETED))
                reporter.finishSession("선생님 명령으로 세션 완료 · 완료한 문제 ${completedProblemCount}개")
                broadcastState("세션 완료")
                return@synchronized
            }
        }
        val updated = session.snapshot()
        persistSnapshot(updated)
        updateMonitor(updated)
        broadcastState(statusText(updated))
        reporter.sendImmediate("다음 단계 · ${statusText(updated)}")
    }

    private fun finishIfCompleted(snapshot: SessionSnapshot, reason: String): Boolean {
        if (snapshot.status != SessionStatus.COMPLETED) return false
        sessionActive = false
        clearSessionState()
        updateMonitor(snapshot)
        reporter.finishSession("$reason · 완료한 문제 ${completedProblemCount}개")
        broadcastState("세션 완료")
        return true
    }

    private fun tickSession() {
        if (!sessionActive) return
        synchronized(sessionLock) {
            if (countdownPaused) return@synchronized
            val before = session.snapshot()
            val current = session.dispatch(Tick(sessionNow()))
            if (current.status != before.status || current.phase != before.phase || current.phaseRemainingMs / 60_000 != before.phaseRemainingMs / 60_000) {
                persistSnapshot(current)
                broadcastState(statusText(current))
            }
            updateMonitor(current)
            if (current.status == SessionStatus.COMPLETED) {
                sessionActive = false
                clearSessionState()
                reporter.finishSession("세션 완료 · 완료한 문제 ${completedProblemCount}개")
            }
        }
    }

    private fun maybeCapture() {
        if (cameraComparisonInFlight.get()) return
        val current = snapshot()
        if (!sessionActive || current.status != SessionStatus.RUNNING) return
        if (captureInFlight.get() && SystemClock.elapsedRealtime() - captureStartedAtElapsedMs > CAPTURE_STALL_MS) {
            Log.e(TAG, "camera_stall elapsed=${SystemClock.elapsedRealtime() - captureStartedAtElapsedMs}")
            captureGeneration.incrementAndGet()
            captureInFlight.set(false)
            bindCamera(force = true)
            return
        }
        val capture = imageCapture ?: return
        if (!captureInFlight.compareAndSet(false, true)) return
        val generation = captureGeneration.incrementAndGet()
        captureStartedAtElapsedMs = SystemClock.elapsedRealtime()
        ensureBookFocus { focusResult ->
            if (destroyed) {
                if (captureGeneration.get() == generation) captureInFlight.set(false)
                return@ensureBookFocus
            }
            focusResult.onFailure {
                if (SystemClock.elapsedRealtime() >= nextFocusAttemptElapsedMs - FOCUS_RETRY_DELAY_MS) {
                    reporter.sendImmediate("책 영역 초점 실패 · 촬영은 계속하고 1분 뒤 재시도합니다")
                }
            }
            captureFocusedFrame(capture, generation)
        }
    }

    private fun captureFocusedFrame(capture: ImageCapture, generation: Long) {
        val temp = runCatching { File.createTempFile("one-x-", ".jpg", cacheDir) }.getOrElse {
            if (captureGeneration.get() == generation) captureInFlight.set(false)
            return
        }
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(temp).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val epoch = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    runCatching {
                        cameraExecutor.execute {
                            if (generation != captureGeneration.get()) {
                                temp.delete()
                                return@execute
                            }
                            runCatching { reporter.recordCapture(temp, epoch, elapsed) }
                                .onFailure { reporter.sendImmediate("촬영 처리 실패: ${it.message.orEmpty()}") }
                            temp.delete()
                            if (captureGeneration.get() == generation) captureInFlight.set(false)
                            broadcastLatestCapture(epoch)
                            logThermal("capture_ok")
                        }
                    }.onFailure {
                        temp.delete()
                        if (captureGeneration.get() == generation) captureInFlight.set(false)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    temp.delete()
                    if (captureGeneration.get() == generation) captureInFlight.set(false)
                    Log.e(TAG, "capture_error code=${exception.imageCaptureError}", exception)
                    reporter.sendImmediate("카메라 촬영 실패 · 자동 재시도")
                }
            },
        )
    }

    private fun ensureBookFocus(callback: (Result<Unit>) -> Unit) {
        if (destroyed) {
            callback(Result.failure(IllegalStateException("서비스가 종료됐습니다")))
            return
        }
        if (focusLocked || SystemClock.elapsedRealtime() < nextFocusAttemptElapsedMs) {
            callback(Result.success(Unit))
            return
        }
        val boundCamera = camera
        if (boundCamera == null) {
            callback(Result.failure(IllegalStateException("카메라가 연결되지 않았습니다")))
            return
        }
        val region = loadBookRegion()
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(
            (region.left + region.right) * 0.5f,
            (region.top + region.bottom) * 0.5f,
        )
        val generation = focusGeneration
        focusAttempt(boundCamera, point, generation, attemptsLeft = 2, callback)
    }

    private fun focusAttempt(
        boundCamera: Camera,
        point: androidx.camera.core.MeteringPoint,
        generation: Int,
        attemptsLeft: Int,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (generation != focusGeneration) {
            callback(Result.failure(IllegalStateException("책 영역이 변경되어 초점을 다시 맞춥니다")))
            return
        }
        val completed = AtomicBoolean(false)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .disableAutoCancel()
            .build()
        val future = runCatching { boundCamera.cameraControl.startFocusAndMetering(action) }.getOrElse {
            handleFocusFailure(boundCamera, point, generation, attemptsLeft, it, callback)
            return
        }
        val timeout = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            handleFocusFailure(
                boundCamera,
                point,
                generation,
                attemptsLeft,
                IllegalStateException("책 영역 초점 시간이 초과됐습니다"),
                callback,
            )
        }
        mainHandler.postDelayed(timeout, FOCUS_TIMEOUT_MS)
        future.addListener({
            if (!completed.compareAndSet(false, true)) return@addListener
            mainHandler.removeCallbacks(timeout)
            val result = runCatching { future.get() }
            if (generation == focusGeneration && result.getOrNull()?.isFocusSuccessful == true) {
                focusLocked = true
                nextFocusAttemptElapsedMs = 0L
                callback(Result.success(Unit))
            } else {
                handleFocusFailure(
                    boundCamera,
                    point,
                    generation,
                    attemptsLeft,
                    result.exceptionOrNull() ?: IllegalStateException("책 영역 초점에 실패했습니다"),
                    callback,
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFocusFailure(
        boundCamera: Camera,
        point: androidx.camera.core.MeteringPoint,
        generation: Int,
        attemptsLeft: Int,
        failure: Throwable,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (generation != focusGeneration) {
            callback(Result.failure(IllegalStateException("책 영역 변경으로 이전 초점 요청을 취소했습니다")))
            return
        }
        if (attemptsLeft > 1 && generation == focusGeneration) {
            focusAttempt(boundCamera, point, generation, attemptsLeft - 1, callback)
        } else {
            focusLocked = false
            nextFocusAttemptElapsedMs = SystemClock.elapsedRealtime() + FOCUS_RETRY_DELAY_MS
            callback(Result.failure(failure))
        }
    }

    private fun resetBookFocus() {
        focusGeneration += 1
        focusLocked = false
        nextFocusAttemptElapsedMs = 0L
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    private fun bindCamera(force: Boolean = false) {
        if (imageCapture != null && !force) return
        val generation = ++cameraBindGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (destroyed || generation != cameraBindGeneration || cameraComparisonInFlight.get()) return@addListener
            var analysisForCleanup: ImageAnalysis? = null
            runCatching {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(selector)
                    .setTargetRotation(currentDisplayRotation())
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                analysisForCleanup = analysis
                analysis.setAnalyzer(analysisExecutor, motionAnalyzer)
                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    this,
                    androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                    analysis,
                )
                cameraProvider = provider
                camera = boundCamera
                imageCapture = capture
                resetBookFocus()
                motionAnalyzer.resetBaseline()
                Log.i(TAG, "camera_bound main resolution=${capture.resolutionInfo?.resolution}")
                if (cameraRecoveryNoticePending) {
                    cameraRecoveryNoticePending = false
                    cameraRecoveryFailureReported = false
                    runCatching {
                        reporter.sendImmediate("기본 카메라 복구 완료 · 10초 촬영과 움직임 판정을 재개합니다")
                    }.onFailure { noticeError ->
                        Log.w(TAG, "camera_recovery_notice_failed", noticeError)
                    }
                }
            }.onFailure {
                analysisForCleanup?.clearAnalyzer()
                if (destroyed || generation != cameraBindGeneration || cameraComparisonInFlight.get()) return@onFailure
                imageCapture = null
                camera = null
                Log.e(TAG, "camera_bind_failed", it)
                broadcastState("카메라 연결 실패: ${it.message.orEmpty()}")
                if (cameraRecoveryNoticePending && !cameraRecoveryFailureReported) {
                    cameraRecoveryFailureReported = true
                    runCatching {
                        reporter.sendImmediate("기본 카메라 복구가 지연되고 있습니다 · 2초 후 자동 재시도합니다")
                    }.onFailure { noticeError ->
                        Log.w(TAG, "camera_recovery_delay_notice_failed", noticeError)
                    }
                }
                mainHandler.postDelayed({
                    if (!destroyed && generation == cameraBindGeneration && imageCapture == null &&
                        !cameraComparisonInFlight.get()
                    ) {
                        bindCamera(force = true)
                    }
                }, CAMERA_BIND_RETRY_MS)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendCameraDiagnostics() {
        mainHandler.post {
            if (destroyed) return@post
            reporter.sendImmediate(cameraDiagnosticsText())
        }
    }

    private fun startCameraComparison() {
        mainHandler.post {
            if (destroyed) return@post
            if (!cameraComparisonInFlight.compareAndSet(false, true)) {
                reporter.sendImmediate("카메라 비교 촬영이 이미 진행 중입니다")
                return@post
            }
            cameraBindGeneration += 1
            reporter.sendImmediate("카메라 비교 촬영 시작 · 약 20초 동안 평소 촬영을 잠시 멈춥니다")
            waitForCameraComparisonSlot(SystemClock.elapsedRealtime() + COMPARISON_SLOT_TIMEOUT_MS)
        }
    }

    private fun waitForCameraComparisonSlot(deadlineElapsedMs: Long) {
        if (destroyed) {
            cameraComparisonInFlight.set(false)
            return
        }
        if (captureInFlight.compareAndSet(false, true)) {
            captureGeneration.incrementAndGet()
            captureStartedAtElapsedMs = SystemClock.elapsedRealtime()
            captureMainComparisonFrame()
            return
        }
        if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
            cameraComparisonInFlight.set(false)
            reporter.sendImmediate("카메라가 다른 사진을 처리 중이라 비교 촬영을 시작하지 못했습니다 · 잠시 뒤 다시 눌러주세요")
            return
        }
        mainHandler.postDelayed({ waitForCameraComparisonSlot(deadlineElapsedMs) }, COMPARISON_SLOT_RETRY_MS)
    }

    private fun captureMainComparisonFrame() {
        val capture = imageCapture
        val boundCamera = camera
        if (capture == null || boundCamera == null) {
            finishCameraComparison("기본 카메라가 준비되지 않아 비교 촬영을 중단했습니다")
            return
        }
        ensureBookFocus {
            captureComparisonFile(capture, "camera-main-") { result ->
                result.fold(
                    onSuccess = { file ->
                        val cameraId = runCatching { Camera2CameraInfo.from(boundCamera.cameraInfo).cameraId }
                            .getOrDefault("unknown")
                        val mainZoomRatio = runCatching { boundCamera.cameraInfo.intrinsicZoomRatio }
                            .getOrDefault(1f)
                        executeComparisonWork(file, "A 사진") {
                            val queued = runCatching {
                                reporter.sendDiagnosticDocument(
                                    file,
                                    comparisonCaption(
                                        label = "A · 기본 후면 카메라",
                                        file = file,
                                        cameraId = cameraId,
                                        zoomRatio = mainZoomRatio,
                                    ),
                                )
                            }
                            file.delete()
                            mainHandler.post {
                                queued.fold(
                                    onSuccess = { capturePhysicalThreeXFrame() },
                                    onFailure = { finishCameraComparison("A 사진을 전송 큐에 저장하지 못했습니다: ${it.message.orEmpty()}") },
                                )
                            }
                        }
                    },
                    onFailure = { failure ->
                        finishCameraComparison("A 기본 카메라 촬영 실패: ${failure.message.orEmpty()}")
                    },
                )
            }
        }
    }

    private fun capturePhysicalThreeXFrame() {
        if (destroyed) {
            cameraComparisonInFlight.set(false)
            captureInFlight.set(false)
            return
        }
        if (Build.VERSION.SDK_INT < 28) {
            finishCameraComparison("Android 9 미만에서는 물리 3× 출력 지정이 지원되지 않습니다")
            return
        }
        val provider = cameraProvider
        if (provider == null) {
            finishCameraComparison("카메라 제공자를 찾지 못해 3× 촬영을 건너뛰었습니다")
            return
        }
        val inventory = readCameraInventory(provider)
        val candidate = inventory.threeXCandidate
        if (candidate == null) {
            finishCameraComparison("이 기기가 앱에 공개한 약 3× 물리 렌즈가 없습니다 · A 사진만 보냈습니다")
            return
        }
        val yuvSize = candidate.maxYuvSize
        if (yuvSize == null) {
            finishCameraComparison("물리 3× 렌즈가 YUV 촬영 크기를 공개하지 않았습니다 · 기본 카메라로 복구합니다")
            return
        }
        val target = runCatching { File.createTempFile("camera-physical-yuv-3x-", ".jpg", cacheDir) }
            .getOrElse {
                finishCameraComparison("B 사진 임시파일 생성 실패: ${it.message.orEmpty()}")
                return
            }
        runCatching { provider.unbindAll() }
        imageCapture = null
        camera = null
        resetBookFocus()
        val orientation = physicalJpegOrientation(candidate.sensorOrientationDegrees)
        Log.i(
            TAG,
            "camera2_physical_yuv_start logical=${inventory.logicalCameraId} " +
                "physical=${candidate.cameraId} yuv=${yuvSize.width}x${yuvSize.height} orientation=$orientation",
        )
        mainHandler.postDelayed({
            if (destroyed || !cameraComparisonInFlight.get()) {
                target.delete()
                return@postDelayed
            }
            PhysicalYuvCameraCapture(this, ContextCompat.getMainExecutor(this)).capture(
                PhysicalYuvCameraCapture.Request(
                    logicalCameraId = inventory.logicalCameraId,
                    physicalCameraId = candidate.cameraId,
                    outputSize = yuvSize,
                    outputFile = target,
                    jpegOrientationDegrees = orientation,
                ),
            ) { result ->
                result.fold(
                    onSuccess = { frame -> handlePhysicalYuvFrame(candidate, inventory.logicalCameraId, frame) },
                    onFailure = { failure ->
                        target.delete()
                        finishCameraComparison(
                            "B Camera2 물리 YUV 촬영 실패: ${failure.message.orEmpty()} · 기본 카메라로 복구합니다",
                        )
                    },
                )
            }
        }, CAMERA2_RELEASE_SETTLE_MS)
    }

    private fun handlePhysicalYuvFrame(
        candidate: PhysicalCameraDescriptor,
        logicalCameraId: String,
        frame: PhysicalYuvCameraCapture.Frame,
    ) {
        val observation = PhysicalCaptureObservation(
            activePhysicalId = frame.activePhysicalId,
            physicalResultIds = frame.physicalResultIds,
            captureResultFocalLengthMm = frame.captureResultFocalLengthMm,
            exifFocalLengthMm = null,
        )
        val verified = CameraTargetPolicy.verifiesPhysicalCapture(
            expectedCameraId = candidate.cameraId,
            expectedFocalLengthMm = candidate.focalLengthMm,
            observation = observation,
        )
        Log.i(
            TAG,
            "camera2_physical_yuv_result requested=${candidate.cameraId} active=${frame.activePhysicalId} " +
                "physicalResults=${frame.physicalResultIds} focal=${frame.captureResultFocalLengthMm} verified=$verified",
        )
        executeComparisonWork(frame.file, "B 사진") {
            val queued = runCatching {
                reporter.sendDiagnosticDocument(
                    frame.file,
                    comparisonCaption(
                        label = if (verified) "B · Camera2 물리 약 3× 확인됨" else "B · Camera2 물리 약 3× 확인 실패",
                        file = frame.file,
                        cameraId = candidate.cameraId,
                        zoomRatio = candidate.estimatedZoomRatio,
                        extra = physicalEvidenceCaption(
                            candidate = candidate,
                            boundLogicalId = logicalCameraId,
                            boundResolution = frame.size,
                            observation = observation,
                        ) + "\nroute=Camera2 logical+physical YUV · requestedPhysicalFocal=" +
                            formatFocal(frame.requestedPhysicalFocalLengthMm),
                    ),
                )
            }
            frame.file.delete()
            mainHandler.post {
                queued.fold(
                    onSuccess = {
                        finishCameraComparison(
                            if (verified) {
                                "Camera2 물리 3× 확인 성공 · A/B 원본을 전송 대기열에 저장했습니다"
                            } else {
                                "Camera2 물리 3× 확인 실패 · B 사진은 참고용으로 보냈으며 기본 카메라로 복구합니다"
                            },
                        )
                    },
                    onFailure = {
                        finishCameraComparison("B 사진을 전송 큐에 저장하지 못했습니다: ${it.message.orEmpty()}")
                    },
                )
            }
        }
    }

    private fun executeComparisonWork(file: File, stage: String, work: () -> Unit) {
        if (destroyed) {
            file.delete()
            return
        }
        runCatching {
            cameraExecutor.execute {
                if (destroyed) file.delete() else work()
            }
        }.onFailure { failure ->
            file.delete()
            if (!destroyed) mainHandler.post {
                finishCameraComparison("$stage 처리 시작 실패: ${failure.message.orEmpty()}")
            }
        }
    }

    private fun focusComparisonCamera(boundCamera: Camera, afterFocus: () -> Unit) {
        val completed = AtomicBoolean(false)
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        val future = runCatching { boundCamera.cameraControl.startFocusAndMetering(action) }.getOrElse {
            afterFocus()
            return
        }
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) afterFocus()
        }
        mainHandler.postDelayed(timeout, COMPARISON_FOCUS_TIMEOUT_MS)
        future.addListener({
            if (!completed.compareAndSet(false, true)) return@addListener
            mainHandler.removeCallbacks(timeout)
            afterFocus()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureComparisonFile(
        capture: ImageCapture,
        prefix: String,
        callback: (Result<File>) -> Unit,
    ) {
        val target = runCatching { File.createTempFile(prefix, ".jpg", cacheDir) }
            .getOrElse { callback(Result.failure(it)); return }
        val completed = AtomicBoolean(false)
        val timeout = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            target.delete()
            callback(Result.failure(IllegalStateException("촬영 시간이 초과됐습니다")))
        }
        mainHandler.postDelayed(timeout, COMPARISON_CAPTURE_TIMEOUT_MS)
        runCatching {
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (!completed.compareAndSet(false, true)) {
                            target.delete()
                            return
                        }
                        mainHandler.removeCallbacks(timeout)
                        callback(Result.success(target))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (!completed.compareAndSet(false, true)) return
                        mainHandler.removeCallbacks(timeout)
                        target.delete()
                        callback(Result.failure(exception))
                    }
                },
            )
        }.onFailure { failure ->
            if (!completed.compareAndSet(false, true)) return@onFailure
            mainHandler.removeCallbacks(timeout)
            target.delete()
            callback(Result.failure(failure))
        }
    }

    private fun finishCameraComparison(message: String) {
        if (!cameraComparisonInFlight.compareAndSet(true, false)) return
        val provider = cameraProvider
        runCatching { provider?.unbindAll() }
        imageCapture = null
        camera = null
        resetBookFocus()
        motionAnalyzer.resetBaseline()
        captureInFlight.set(false)
        cameraRecoveryNoticePending = true
        cameraRecoveryFailureReported = false
        if (!destroyed) {
            bindCamera(force = true)
            reporter.sendImmediate(message)
        }
    }

    private fun cameraDiagnosticsText(): String {
        val provider = cameraProvider ?: return "카메라 진단 · 아직 카메라가 준비되지 않았습니다"
        val inventory = readCameraInventory(provider)
        val currentResolution = imageCapture?.resolutionInfo?.resolution?.let { "${it.width}×${it.height}" } ?: "준비 중"
        return buildString {
            append("카메라 진단 · 앱 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("기기 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
            append("현재 기본 촬영 ").append(currentResolution).append(" · logical ").append(inventory.logicalCameraId).append('\n')
            append("logical multi-camera: ").append(if (inventory.logicalMultiCamera) "지원" else "미지원").append('\n')
            if (inventory.physicalCameras.isEmpty()) {
                append("앱에 공개된 물리 렌즈 없음")
            } else {
                inventory.physicalCameras.forEach { lens ->
                    append("physical ").append(lens.cameraId)
                        .append(" · 추정 화각 ").append("%.2f×".format(Locale.US, lens.estimatedZoomRatio))
                        .append(" · focal ").append(lens.focalLengthsText)
                        .append(" · sensor ").append(lens.sensorSizeText)
                        .append(" · JPEG ").append(lens.maxJpegText)
                        .append(" · YUV ").append(lens.maxYuvText)
                        .append('\n')
                }
                append("약 3× 후보: ").append(inventory.threeXCandidate?.cameraId ?: "없음")
            }
            append("\n진단은 렌즈를 바꾸지 않습니다. 실제 비교는 /camera test")
        }
    }

    private fun readCameraInventory(provider: ProcessCameraProvider): CameraInventory {
        val logical = runCatching {
            CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
        }.getOrNull()
        if (logical == null) return CameraInventory("unknown", false, emptyList(), null)
        val rawPhysical = logical.physicalCameraInfos.mapNotNull { info -> describePhysicalCamera(info) }
        val estimatedRatios = CameraTargetPolicy.estimateZoomRatios(
            rawPhysical.map {
                PhysicalLensOptics(it.cameraId, it.focalLengthMm, it.sensorWidthMm, it.sensorAreaMm2)
            },
        ).associateBy(PhysicalLensTarget::cameraId)
        val physical = rawPhysical.map { raw ->
            PhysicalCameraDescriptor(
                cameraId = raw.cameraId,
                estimatedZoomRatio = estimatedRatios[raw.cameraId]?.zoomRatio ?: Float.NaN,
                focalLengthMm = raw.focalLengthMm,
                focalLengthsText = raw.focalLengthsText,
                sensorSizeText = raw.sensorSizeText,
                maxJpegSize = raw.maxJpegSize,
                maxJpegText = raw.maxJpegText,
                maxYuvSize = raw.maxYuvSize,
                maxYuvText = raw.maxYuvText,
                sensorOrientationDegrees = raw.sensorOrientationDegrees,
            )
        }.sortedBy(PhysicalCameraDescriptor::cameraId)
        val selected = CameraTargetPolicy.chooseThreeX(
            physical.map { PhysicalLensTarget(it.cameraId, it.estimatedZoomRatio) },
        )
        return CameraInventory(
            logicalCameraId = runCatching { Camera2CameraInfo.from(logical).cameraId }.getOrDefault("unknown"),
            logicalMultiCamera = logical.isLogicalMultiCameraSupported,
            physicalCameras = physical,
            threeXCandidate = selected?.let { choice -> physical.firstOrNull { it.cameraId == choice.cameraId } },
        )
    }

    private fun describePhysicalCamera(info: CameraInfo): RawPhysicalCamera? = runCatching {
        val camera2 = Camera2CameraInfo.from(info)
        val focalLengths = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf()
        val sensorSize = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val maxJpeg = camera2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        val maxYuv = camera2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        val focalLength = focalLengths.firstOrNull()?.takeIf { it.isFinite() && it > 0f }
        val sensorWidth = sensorSize?.width?.takeIf { it.isFinite() && it > 0f }
        RawPhysicalCamera(
            cameraId = camera2.cameraId,
            focalLengthMm = focalLength ?: Float.NaN,
            sensorWidthMm = sensorWidth ?: Float.NaN,
            sensorAreaMm2 = sensorSize?.let { it.width * it.height } ?: Float.NaN,
            focalLengthsText = focalLengths.takeIf { it.isNotEmpty() }
                ?.joinToString("/") { "%.1fmm".format(Locale.US, it) } ?: "unknown",
            sensorSizeText = sensorSize?.let { "%.1f×%.1fmm".format(Locale.US, it.width, it.height) } ?: "unknown",
            maxJpegSize = maxJpeg,
            maxJpegText = maxJpeg?.let { "${it.width}×${it.height}" } ?: "unknown",
            maxYuvSize = maxYuv,
            maxYuvText = maxYuv?.let { "${it.width}×${it.height}" } ?: "unknown",
            sensorOrientationDegrees = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
        )
    }.getOrNull()

    private fun physicalEvidenceCaption(
        candidate: PhysicalCameraDescriptor,
        boundLogicalId: String,
        boundResolution: Size?,
        observation: PhysicalCaptureObservation,
    ): String = buildString {
        append("requestedPhysical=").append(candidate.cameraId)
            .append(" · boundLogical=").append(boundLogicalId)
            .append(" · requestedYUV=").append(candidate.maxYuvText)
            .append(" · boundResolution=").append(boundResolution?.let { "${it.width}×${it.height}" } ?: "unknown")
        append("\nactivePhysical=").append(observation.activePhysicalId ?: "unknown")
            .append(" · physicalResults=")
            .append(observation.physicalResultIds.sorted().joinToString(",").ifBlank { "none" })
            .append(" · resultFocal=").append(formatFocal(observation.captureResultFocalLengthMm))
            .append(" · exifFocal=").append(formatFocal(observation.exifFocalLengthMm))
            .append(" · expectedFocal=").append(formatFocal(candidate.focalLengthMm))
    }

    private fun formatFocal(value: Float?): String = value
        ?.takeIf { it.isFinite() && it > 0f }
        ?.let { "%.2fmm".format(Locale.US, it) }
        ?: "unknown"

    private fun comparisonCaption(
        label: String,
        file: File,
        cameraId: String,
        zoomRatio: Float,
        extra: String? = null,
    ): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return buildString {
            append(label).append(" · 원본 문서\n")
            append(options.outWidth).append('×').append(options.outHeight)
                .append(" · cameraId=").append(cameraId)
                .append(" · zoom=").append("%.2f×".format(Locale.US, zoomRatio))
            extra?.let { append("\n").append(it) }
            append("\n회전·잘라내기·확대 보정 미적용")
        }
    }


    private fun physicalJpegOrientation(sensorOrientationDegrees: Int): Int {
        val displayDegrees = when (currentDisplayRotation()) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientationDegrees - displayDegrees + 360) % 360
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.PROBLEM_DONE -> synchronized(sessionLock) {
                val before = session.snapshot().completedProblemCount
                val current = session.dispatch(ProblemCompleted("voice-${UUID.randomUUID()}", sessionNow()))
                if (current.completedProblemCount > before) completedProblemCount += 1
                persistSnapshot(current)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 70)
                reporter.sendImmediate("✅ 풀었어 · 완료 ${completedProblemCount}개")
                broadcastState(statusText(current))
            }
            VoiceCommand.DAD_MESSAGE -> tone.startTone(ToneGenerator.TONE_PROP_ACK, 60)
            VoiceCommand.STUDY_START -> broadcastState("공부 시작은 텔레그램에서 합니다")
            VoiceCommand.UNDO, VoiceCommand.PAUSE, VoiceCommand.STOP -> broadcastState("세션 제어는 텔레그램에서 합니다")
        }
    }

    private fun speakTeacherMessage(text: String) {
        val clean = text.trim().take(1_000)
        if (clean.isEmpty()) return
        reporter.sendImmediate("선생님 메시지 전달 완료")
        mainHandler.post {
            voice.stop()
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 70)
            mainHandler.postDelayed({
                tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "telegram-${System.nanoTime()}")
            }, 180L)
        }
    }

    private fun restartVoiceAfterSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
        }
    }

    private fun configureTts() {
        tts.language = Locale.KOREA
        tts.setSpeechRate(0.88f)
        tts.setPitch(1.0f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val bestKoreanLocal = runCatching {
            tts.voices.orEmpty()
                .filter { voice ->
                    voice.locale.language == Locale.KOREAN.language &&
                        !voice.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
                }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency })
                .firstOrNull()
        }.getOrNull()
        bestKoreanLocal?.let { selected ->
            runCatching { tts.voice = selected }
            Log.i(TAG, "tts_voice=${selected.name} quality=${selected.quality} latency=${selected.latency}")
        }
    }

    private fun requestBookRefocus() {
        mainHandler.post {
            resetBookFocus()
            reporter.sendImmediate("책 영역 초점 초기화 완료 · 다음 촬영 전에 다시 맞춥니다")
        }
    }

    private fun currentDisplayRotation(): Int =
        getSystemService(DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_90

    private fun updateMonitor(snapshot: SessionSnapshot) {
        activityMonitor.setActive(sessionActive && snapshot.status == SessionStatus.RUNNING, SystemClock.elapsedRealtime())
    }

    private fun snapshot(): SessionSnapshot = synchronized(sessionLock) { session.snapshot() }

    private fun statusText(snapshot: SessionSnapshot): String {
        val phase = if (snapshot.status == SessionStatus.START_COUNTDOWN) "시작 대기" else when (snapshot.phase) {
            io.remotestudy.domain.session.SessionPhase.MEDITATION -> "명상"
            io.remotestudy.domain.session.SessionPhase.STUDY -> "공부"
            io.remotestudy.domain.session.SessionPhase.BREAK -> "휴식"
            io.remotestudy.domain.session.SessionPhase.COMPLETE -> "완료"
        }
        val seconds = if (snapshot.status == SessionStatus.START_COUNTDOWN) snapshot.countdownRemainingMs / 1_000 else snapshot.phaseRemainingMs / 1_000
        val paused = if (countdownPaused && snapshot.status == SessionStatus.START_COUNTDOWN) " · 일시정지" else ""
        return "$phase · %02d:%02d$paused · 완료 ${completedProblemCount}개".format(seconds / 60, seconds % 60)
    }

    private fun persistSnapshot(snapshot: SessionSnapshot) {
        preferences.edit()
            .putBoolean(KEY_ACTIVE, sessionActive)
            .putLong(KEY_SAVED_EPOCH, System.currentTimeMillis())
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_PHASE, snapshot.phase.name)
            .putLong(KEY_PHASE_REMAINING, snapshot.phaseRemainingMs)
            .putLong(KEY_COUNTDOWN_REMAINING, snapshot.countdownRemainingMs)
            .putInt(KEY_PROBLEMS, completedProblemCount)
            .commit()
    }

    private fun initializeRemoteSession() {
        val nowElapsed = SystemClock.elapsedRealtime()
        completedProblemCount = 0
        countdownPaused = false
        sessionActive = true
        preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_EPOCH, System.currentTimeMillis())
            .commit()
        reporter.startFreshSession(System.currentTimeMillis(), nowElapsed)
        motionAnalyzer.resetBaseline()
    }

    private fun positionAt(phase: SessionPhase, remainingMs: Long, paused: Boolean) {
        rebuildAtProgress(
            RemoteTimerMath.progressForPosition(schedule, teacherCountdownMs, phase, remainingMs),
            paused,
        )
    }

    private fun rebuildAtProgress(progressMs: Long, paused: Boolean) {
        val actualNow = SystemClock.elapsedRealtime()
        val maximum = teacherCountdownMs + totalPhaseDurationMs()
        val target = progressMs.coerceIn(0L, maximum)
        session = newSessionMachine()
        sessionClockOffsetMs = -actualNow
        session.dispatch(
            StartRequested(
                "position-${UUID.randomUUID()}",
                if (teacherCountdownMs == 0L) StartOrigin.STUDENT else StartOrigin.TEACHER,
                0L,
            ),
        )
        session.dispatch(Tick(target))
        sessionClockOffsetMs = target - actualNow
        if (paused && session.snapshot().status == SessionStatus.RUNNING) {
            session.dispatch(Pause("position-pause-${UUID.randomUUID()}", target))
        }
    }

    private fun reconcileCountdownPauseAfterRebuild() {
        if (!countdownPaused) return
        if (session.snapshot().status == SessionStatus.START_COUNTDOWN) {
            countdownPausedAtSessionMs = sessionNow()
        } else {
            countdownPaused = false
            preferences.edit().putBoolean(KEY_COUNTDOWN_PAUSED, false).commit()
        }
    }

    private fun newSessionMachine() = SessionStateMachine(
        schedule = schedule,
        teacherCountdownDurationMs = teacherCountdownMs.coerceAtLeast(1L),
    )

    private fun sessionNow(actualElapsedMs: Long = SystemClock.elapsedRealtime()): Long =
        (actualElapsedMs + sessionClockOffsetMs).coerceAtLeast(0L)

    private fun phaseDuration(value: StudySchedule, phase: SessionPhase): Long =
        RemoteTimerMath.phaseDuration(value, phase)

    private fun totalPhaseDurationMs(): Long =
        RemoteTimerMath.totalPhaseDuration(schedule)

    private fun ensurePhaseCanHold(phase: SessionPhase, remainingMs: Long) {
        schedule = when (phase) {
            SessionPhase.MEDITATION -> if (remainingMs > schedule.meditationDurationMs) {
                schedule.copy(meditationDurationMs = remainingMs)
            } else schedule
            SessionPhase.STUDY -> if (remainingMs > schedule.studyDurationMs) {
                schedule.copy(studyDurationMs = remainingMs)
            } else schedule
            SessionPhase.BREAK -> if (remainingMs > schedule.breakDurationMs) {
                schedule.copy(breakDurationMs = remainingMs.coerceAtLeast(1L))
            } else schedule
            SessionPhase.COMPLETE -> schedule
        }
        saveTimerSettings()
    }

    private fun loadTimerSettings() {
        val meditation = preferences.getLong(KEY_MEDITATION_DURATION, StudySchedule.DEFAULT_MEDITATION_DURATION_MS)
        val study = preferences.getLong(KEY_STUDY_DURATION, StudySchedule.DEFAULT_STUDY_DURATION_MS)
        val rest = preferences.getLong(KEY_BREAK_DURATION, StudySchedule.DEFAULT_BREAK_DURATION_MS)
        schedule = runCatching { StudySchedule(meditation, study, rest) }.getOrDefault(StudySchedule())
        teacherCountdownMs = preferences.getLong(KEY_TEACHER_COUNTDOWN, DEFAULT_TEACHER_COUNTDOWN_MS)
            .coerceIn(0L, 60_000L)
    }

    private fun saveTimerSettings() {
        preferences.edit()
            .putLong(KEY_MEDITATION_DURATION, schedule.meditationDurationMs)
            .putLong(KEY_STUDY_DURATION, schedule.studyDurationMs)
            .putLong(KEY_BREAK_DURATION, schedule.breakDurationMs)
            .putLong(KEY_TEACHER_COUNTDOWN, teacherCountdownMs)
            .commit()
    }

    private fun clearSessionState() {
        preferences.edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_STARTED_EPOCH)
            .remove(KEY_SAVED_EPOCH)
            .remove(KEY_STATUS)
            .remove(KEY_PHASE)
            .remove(KEY_PHASE_REMAINING)
            .remove(KEY_COUNTDOWN_REMAINING)
            .remove(KEY_PROBLEMS)
            .remove(KEY_COUNTDOWN_PAUSED)
            .commit()
    }

    private fun settingsText(oneLine: Boolean = false): String {
        val separator = if (oneLine) " · " else "\n"
        return listOf(
            "명상 ${schedule.meditationDurationMs / 60_000}분",
            "공부 ${schedule.studyDurationMs / 60_000}분",
            "휴식 ${schedule.breakDurationMs / 60_000}분",
            "시작 대기 ${teacherCountdownMs / 1_000}초",
            "책 상세 회전 ${reporter.currentBookRotation()}°",
        ).joinToString(separator)
    }

    private fun commandHelp(): String =
        "명령\n" +
            "/menu 버튼 메뉴 열기\n" +
            "/area 10×10 책 영역 격자\n" +
            "/rotate 0|90|180|270 책 상세사진 회전\n" +
            "/camera 카메라 진단 메뉴\n" +
            "/camera info 기기 렌즈 정보\n" +
            "/camera test 기본 1×·물리 약 3× 원본 비교\n" +
            "/settings 현재 설정\n" +
            "/set 0 40 15 명상·공부·휴식(분)\n" +
            "/set countdown 5 시작 대기(초)\n" +
            "/start /restart /pause /resume /stop\n" +
            "/time 25:30 현재 남은 시간\n" +
            "/phase meditation|study|break [분 또는 분:초]\n" +
            "/next 다음 단계\n" +
            "/focus 책 영역 초점 다시 맞추기\n" +
            "/status /index /b 시간"

    private fun restoreSession() {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return
        val startedEpoch = preferences.getLong(KEY_STARTED_EPOCH, 0L)
        if (startedEpoch <= 0L) return
        val savedEpoch = preferences.getLong(KEY_SAVED_EPOCH, startedEpoch)
        val savedStatus = runCatching {
            SessionStatus.valueOf(preferences.getString(KEY_STATUS, SessionStatus.RUNNING.name)!!)
        }.getOrDefault(SessionStatus.RUNNING)
        val savedPhase = runCatching {
            SessionPhase.valueOf(preferences.getString(KEY_PHASE, SessionPhase.MEDITATION.name)!!)
        }.getOrDefault(SessionPhase.MEDITATION)
        val phaseRemaining = preferences.getLong(
            KEY_PHASE_REMAINING,
            StudySchedule.DEFAULT_MEDITATION_DURATION_MS,
        )
        val countdownRemaining = preferences.getLong(KEY_COUNTDOWN_REMAINING, 0L)
        val progressAtSave = sessionProgressMs(savedStatus, savedPhase, phaseRemaining, countdownRemaining)
        countdownPaused = preferences.getBoolean(KEY_COUNTDOWN_PAUSED, false) &&
            savedStatus == SessionStatus.START_COUNTDOWN
        val runningAfterSave = if (
            savedStatus in setOf(SessionStatus.RUNNING, SessionStatus.START_COUNTDOWN) && !countdownPaused
        ) {
            (System.currentTimeMillis() - savedEpoch).coerceAtLeast(0L)
        } else 0L
        val totalProgress = (progressAtSave + runningAfterSave)
            .coerceAtMost(teacherCountdownMs + totalPhaseDurationMs())
        completedProblemCount = preferences.getInt(KEY_PROBLEMS, 0).coerceAtLeast(0)
        rebuildAtProgress(totalProgress, savedStatus == SessionStatus.PAUSED)
        reconcileCountdownPauseAfterRebuild()
        sessionActive = session.snapshot().status != SessionStatus.COMPLETED
        if (!sessionActive) clearSessionState()
        updateMonitor(session.snapshot())
    }

    private fun sessionProgressMs(
        status: SessionStatus,
        phase: SessionPhase,
        phaseRemainingMs: Long,
        countdownRemainingMs: Long,
    ): Long = RemoteTimerMath.progressForSnapshot(
        schedule,
        teacherCountdownMs,
        status,
        phase,
        phaseRemainingMs,
        countdownRemainingMs,
    )

    private fun loadBookRegion(): NormalizedBookRegion = NormalizedBookRegion(
        preferences.getFloat(KEY_REGION_LEFT, NormalizedBookRegion.DEFAULT.left),
        preferences.getFloat(KEY_REGION_TOP, NormalizedBookRegion.DEFAULT.top),
        preferences.getFloat(KEY_REGION_RIGHT, NormalizedBookRegion.DEFAULT.right),
        preferences.getFloat(KEY_REGION_BOTTOM, NormalizedBookRegion.DEFAULT.bottom),
    )

    private fun saveBookRegion(region: NormalizedBookRegion) {
        preferences.edit()
            .putFloat(KEY_REGION_LEFT, region.left).putFloat(KEY_REGION_TOP, region.top)
            .putFloat(KEY_REGION_RIGHT, region.right).putFloat(KEY_REGION_BOTTOM, region.bottom)
            .apply()
    }

    private fun regionFromIntent(intent: Intent) = NormalizedBookRegion(
        intent.getFloatExtra(EXTRA_LEFT, NormalizedBookRegion.DEFAULT.left),
        intent.getFloatExtra(EXTRA_TOP, NormalizedBookRegion.DEFAULT.top),
        intent.getFloatExtra(EXTRA_RIGHT, NormalizedBookRegion.DEFAULT.right),
        intent.getFloatExtra(EXTRA_BOTTOM, NormalizedBookRegion.DEFAULT.bottom),
    )

    private fun promoteToForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 30) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else if (Build.VERSION.SDK_INT == 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, StudentActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("원격공부 촬영 중")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "공부 촬영", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun broadcastState(text: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_TEXT, text))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun broadcastLatestCapture(epochMs: Long) {
        val file = File(filesDir, "telegram-report/originals/$epochMs.jpg")
        sendBroadcast(
            Intent(ACTION_CAPTURE).setPackage(packageName)
                .putExtra(EXTRA_FILE, file.absolutePath)
                .putExtra(EXTRA_TEXT, statusText(snapshot())),
        )
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
        }
    }

    private fun logThermal(event: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            Log.i(TAG, "$event thermal_status=${getSystemService(PowerManager::class.java).currentThermalStatus}")
        }
    }

    override fun onDestroy() {
        destroyed = true
        captureGeneration.incrementAndGet()
        focusGeneration += 1
        cameraBindGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        voice.destroy()
        tts.stop(); tts.shutdown()
        tone.release()
        reporter.close()
        cameraProvider?.unbindAll()
        camera = null
        cameraExecutor.shutdownNow(); analysisExecutor.shutdownNow()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private data class CameraInventory(
        val logicalCameraId: String,
        val logicalMultiCamera: Boolean,
        val physicalCameras: List<PhysicalCameraDescriptor>,
        val threeXCandidate: PhysicalCameraDescriptor?,
    )

    private data class PhysicalCameraDescriptor(
        val cameraId: String,
        val estimatedZoomRatio: Float,
        val focalLengthMm: Float,
        val focalLengthsText: String,
        val sensorSizeText: String,
        val maxJpegSize: Size?,
        val maxJpegText: String,
        val maxYuvSize: Size?,
        val maxYuvText: String,
        val sensorOrientationDegrees: Int,
    )

    private data class RawPhysicalCamera(
        val cameraId: String,
        val focalLengthMm: Float,
        val sensorWidthMm: Float,
        val sensorAreaMm2: Float,
        val focalLengthsText: String,
        val sensorSizeText: String,
        val maxJpegSize: Size?,
        val maxJpegText: String,
        val maxYuvSize: Size?,
        val maxYuvText: String,
        val sensorOrientationDegrees: Int,
    )

    companion object {
        const val ACTION_STATE = "io.remotestudy.student.STATE"
        const val ACTION_CAPTURE = "io.remotestudy.student.CAPTURE"
        const val ACTION_UPDATE_REGION = "io.remotestudy.student.UPDATE_REGION"
        const val ACTION_STOP_SERVICE = "io.remotestudy.student.STOP_SERVICE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FILE = "file"
        const val EXTRA_LEFT = "left"
        const val EXTRA_TOP = "top"
        const val EXTRA_RIGHT = "right"
        const val EXTRA_BOTTOM = "bottom"
        private const val TAG = "RemoteStudyService"
        private const val CHANNEL_ID = "study-session"
        private const val NOTIFICATION_ID = 41
        private const val CAPTURE_INTERVAL_MS = 10_000L
        private const val CAPTURE_STALL_MS = 30_000L
        private const val FOCUS_TIMEOUT_MS = 2_500L
        private const val FOCUS_RETRY_DELAY_MS = 60_000L
        private const val COMPARISON_SLOT_TIMEOUT_MS = 10_000L
        private const val COMPARISON_SLOT_RETRY_MS = 250L
        private const val COMPARISON_FOCUS_TIMEOUT_MS = 4_000L
        private const val COMPARISON_CAPTURE_TIMEOUT_MS = 15_000L
        private const val CAMERA2_RELEASE_SETTLE_MS = 500L
        private const val CAMERA_BIND_RETRY_MS = 2_000L
        private const val KEY_ACTIVE = "active"
        private const val KEY_STARTED_EPOCH = "started_epoch"
        private const val KEY_SAVED_EPOCH = "saved_epoch"
        private const val KEY_STATUS = "status"
        private const val KEY_PHASE = "phase"
        private const val KEY_PHASE_REMAINING = "phase_remaining"
        private const val KEY_COUNTDOWN_REMAINING = "countdown_remaining"
        private const val KEY_PROBLEMS = "problems"
        private const val KEY_REGION_LEFT = "region_left"
        private const val KEY_REGION_TOP = "region_top"
        private const val KEY_REGION_RIGHT = "region_right"
        private const val KEY_REGION_BOTTOM = "region_bottom"
        private const val KEY_MEDITATION_DURATION = "meditation_duration"
        private const val KEY_STUDY_DURATION = "study_duration"
        private const val KEY_BREAK_DURATION = "break_duration"
        private const val KEY_TEACHER_COUNTDOWN = "teacher_countdown"
        private const val KEY_COUNTDOWN_PAUSED = "countdown_paused"
        private const val DEFAULT_TEACHER_COUNTDOWN_MS = 5_000L
    }
}
