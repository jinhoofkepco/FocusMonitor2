package io.remotestudy.student

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import java.util.concurrent.atomic.AtomicBoolean

class StudentStudyService : Service(), LifecycleOwner, TelegramCommandHandler {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor { Thread(it, "study-camera") }
    private val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "study-analysis") }
    private val sessionLock = Any()
    private var session = SessionStateMachine()
    private var sessionActive = false
    private var captureInFlight = AtomicBoolean(false)
    private var captureStartedAtElapsedMs = 0L
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
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

        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { mainHandler.post(::restartVoiceAfterSpeech) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { mainHandler.post(::restartVoiceAfterSpeech) }
                })
            }
        }
        reporter = TelegramReporter(
            rootDirectory = File(filesDir, "telegram-report"),
            config = TelegramConfig(BuildConfig.TELEGRAM_BOT_TOKEN, BuildConfig.TELEGRAM_CHAT_ID),
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
        if (!preferences.getBoolean(KEY_ACTIVE, false)) reporter.cleanupPreviousSessionFiles()
        restoreSession()
        if (BuildConfig.TELEGRAM_BOT_TOKEN.isBlank() || BuildConfig.TELEGRAM_CHAT_ID == 0L) {
            broadcastState("텔레그램 설정 필요 · local.properties 확인")
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
            TelegramCommand.Status -> reporter.sendImmediate(statusText(snapshot()))
            is TelegramCommand.Unknown -> {
                if (command.input.trim().startsWith('/')) {
                    reporter.sendImmediate("명령: /start /pause /resume /stop /status /index /b 시간")
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
        session = SessionStateMachine()
        val nowElapsed = SystemClock.elapsedRealtime()
        session.dispatch(StartRequested("tg-${UUID.randomUUID()}", StartOrigin.TEACHER, nowElapsed))
        sessionActive = true
        preferences.edit().putBoolean(KEY_ACTIVE, true).putLong(KEY_STARTED_EPOCH, System.currentTimeMillis()).commit()
        reporter.startFreshSession(System.currentTimeMillis(), nowElapsed)
        motionAnalyzer.resetBaseline()
        persistSnapshot(session.snapshot())
        broadcastState("5초 뒤 공부 시작")
    }

    private fun pauseSession() = synchronized(sessionLock) {
        if (!sessionActive) { reporter.sendImmediate("진행 중인 세션이 없습니다"); return@synchronized }
        val snapshot = session.dispatch(Pause("tg-${UUID.randomUUID()}", SystemClock.elapsedRealtime()))
        persistSnapshot(snapshot)
        reporter.sendImmediate("일시정지 · ${statusText(snapshot)}")
        updateMonitor(snapshot)
    }

    private fun resumeSession() = synchronized(sessionLock) {
        if (!sessionActive) { reporter.sendImmediate("진행 중인 세션이 없습니다"); return@synchronized }
        val snapshot = session.dispatch(Resume("tg-${UUID.randomUUID()}", SystemClock.elapsedRealtime()))
        persistSnapshot(snapshot)
        reporter.sendImmediate("공부 재개 · ${statusText(snapshot)}")
        updateMonitor(snapshot)
    }

    private fun stopSession() = synchronized(sessionLock) {
        if (!sessionActive) { reporter.sendImmediate("진행 중인 세션이 없습니다"); return@synchronized }
        val snapshot = session.snapshot()
        sessionActive = false
        preferences.edit().clear().commit()
        updateMonitor(snapshot.copy(status = SessionStatus.COMPLETED))
        reporter.finishSession("세션 종료 · 완료한 문제 ${snapshot.completedProblemCount}개 · 몽타주 대기 ${reporter.pendingUploadCount()}건")
        broadcastState("세션 종료")
    }

    private fun tickSession() {
        if (!sessionActive) return
        synchronized(sessionLock) {
            val before = session.snapshot()
            val current = session.dispatch(Tick(SystemClock.elapsedRealtime()))
            if (current.status != before.status || current.phase != before.phase || current.phaseRemainingMs / 60_000 != before.phaseRemainingMs / 60_000) {
                persistSnapshot(current)
                broadcastState(statusText(current))
            }
            updateMonitor(current)
            if (current.status == SessionStatus.COMPLETED) {
                sessionActive = false
                preferences.edit().clear().commit()
                reporter.finishSession("세션 완료 · 완료한 문제 ${current.completedProblemCount}개")
            }
        }
    }

    private fun maybeCapture() {
        val current = snapshot()
        if (!sessionActive || current.status != SessionStatus.RUNNING) return
        if (captureInFlight.get() && SystemClock.elapsedRealtime() - captureStartedAtElapsedMs > CAPTURE_STALL_MS) {
            Log.e(TAG, "camera_stall elapsed=${SystemClock.elapsedRealtime() - captureStartedAtElapsedMs}")
            captureInFlight.set(false)
            bindCamera(force = true)
        }
        val capture = imageCapture ?: return
        if (!captureInFlight.compareAndSet(false, true)) return
        captureStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val temp = runCatching { File.createTempFile("one-x-", ".jpg", cacheDir) }.getOrElse {
            captureInFlight.set(false)
            return
        }
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(temp).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val epoch = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    cameraExecutor.execute {
                        runCatching { reporter.recordCapture(temp, epoch, elapsed) }
                            .onFailure { reporter.sendImmediate("촬영 처리 실패: ${it.message.orEmpty()}") }
                        temp.delete()
                        captureInFlight.set(false)
                        broadcastLatestCapture(epoch)
                        logThermal("capture_ok")
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    temp.delete()
                    captureInFlight.set(false)
                    Log.e(TAG, "capture_error code=${exception.imageCaptureError}", exception)
                    reporter.sendImmediate("카메라 촬영 실패 · 자동 재시도")
                }
            },
        )
    }

    private fun bindCamera(force: Boolean = false) {
        if (imageCapture != null && !force) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
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
                analysis.setAnalyzer(analysisExecutor, motionAnalyzer)
                provider.unbindAll()
                provider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, capture, analysis)
                cameraProvider = provider
                imageCapture = capture
                Log.i(TAG, "camera_bound one_x resolution=${capture.resolutionInfo?.resolution}")
            }.onFailure {
                imageCapture = null
                Log.e(TAG, "camera_bind_failed", it)
                broadcastState("카메라 연결 실패: ${it.message.orEmpty()}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.PROBLEM_DONE -> synchronized(sessionLock) {
                val current = session.dispatch(ProblemCompleted("voice-${UUID.randomUUID()}", SystemClock.elapsedRealtime()))
                persistSnapshot(current)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 70)
                reporter.sendImmediate("✅ 풀었어 · 완료 ${current.completedProblemCount}개")
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
                tts.setSpeechRate(0.92f)
                tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "telegram-${System.nanoTime()}")
            }, 180L)
        }
    }

    private fun restartVoiceAfterSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
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
        val phase = when (snapshot.phase) {
            io.remotestudy.domain.session.SessionPhase.MEDITATION -> "명상"
            io.remotestudy.domain.session.SessionPhase.STUDY -> "공부"
            io.remotestudy.domain.session.SessionPhase.BREAK -> "휴식"
            io.remotestudy.domain.session.SessionPhase.COMPLETE -> "완료"
        }
        val seconds = if (snapshot.status == SessionStatus.START_COUNTDOWN) snapshot.countdownRemainingMs / 1_000 else snapshot.phaseRemainingMs / 1_000
        return "$phase · %02d:%02d · 완료 ${snapshot.completedProblemCount}개".format(seconds / 60, seconds % 60)
    }

    private fun persistSnapshot(snapshot: SessionSnapshot) {
        preferences.edit()
            .putBoolean(KEY_ACTIVE, sessionActive)
            .putLong(KEY_SAVED_EPOCH, System.currentTimeMillis())
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_PHASE, snapshot.phase.name)
            .putLong(KEY_PHASE_REMAINING, snapshot.phaseRemainingMs)
            .putLong(KEY_COUNTDOWN_REMAINING, snapshot.countdownRemainingMs)
            .putInt(KEY_PROBLEMS, snapshot.completedProblemCount)
            .commit()
    }

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
        val runningAfterSave = if (savedStatus in setOf(SessionStatus.RUNNING, SessionStatus.START_COUNTDOWN)) {
            (System.currentTimeMillis() - savedEpoch).coerceAtLeast(0L)
        } else 0L
        val totalProgress = (progressAtSave + runningAfterSave).coerceAtMost(TOTAL_SESSION_WITH_COUNTDOWN_MS)
        val nowElapsed = SystemClock.elapsedRealtime()
        val syntheticStart = (nowElapsed - totalProgress).coerceAtLeast(0L)
        session = SessionStateMachine()
        session.dispatch(StartRequested("restore", StartOrigin.TEACHER, syntheticStart))
        val problemCount = preferences.getInt(KEY_PROBLEMS, 0)
        if (problemCount > 0 && totalProgress > STUDY_START_PROGRESS_MS) {
            val problemTime = (syntheticStart + STUDY_START_PROGRESS_MS + 1L).coerceAtMost(nowElapsed)
            session.dispatch(Tick(problemTime))
            repeat(problemCount) {
                session.dispatch(ProblemCompleted("restore-$it", problemTime))
            }
        }
        session.dispatch(Tick(nowElapsed))
        if (savedStatus == SessionStatus.PAUSED && session.snapshot().status == SessionStatus.RUNNING) {
            session.dispatch(Pause("restore-pause", nowElapsed))
        }
        sessionActive = session.snapshot().status != SessionStatus.COMPLETED
        updateMonitor(session.snapshot())
    }

    private fun sessionProgressMs(
        status: SessionStatus,
        phase: SessionPhase,
        phaseRemainingMs: Long,
        countdownRemainingMs: Long,
    ): Long = when (status) {
        SessionStatus.READY -> 0L
        SessionStatus.START_COUNTDOWN -> TEACHER_COUNTDOWN_MS - countdownRemainingMs
        SessionStatus.RUNNING, SessionStatus.PAUSED -> TEACHER_COUNTDOWN_MS + when (phase) {
            SessionPhase.MEDITATION -> StudySchedule.DEFAULT_MEDITATION_DURATION_MS - phaseRemainingMs
            SessionPhase.STUDY -> StudySchedule.DEFAULT_MEDITATION_DURATION_MS +
                StudySchedule.DEFAULT_STUDY_DURATION_MS - phaseRemainingMs
            SessionPhase.BREAK -> StudySchedule.DEFAULT_MEDITATION_DURATION_MS +
                StudySchedule.DEFAULT_STUDY_DURATION_MS +
                StudySchedule.DEFAULT_BREAK_DURATION_MS - phaseRemainingMs
            SessionPhase.COMPLETE -> TOTAL_STUDY_PHASES_MS
        }
        SessionStatus.COMPLETED -> TOTAL_SESSION_WITH_COUNTDOWN_MS
    }.coerceIn(0L, TOTAL_SESSION_WITH_COUNTDOWN_MS)

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
        mainHandler.removeCallbacksAndMessages(null)
        voice.destroy()
        tts.stop(); tts.shutdown()
        tone.release()
        reporter.close()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdownNow(); analysisExecutor.shutdownNow()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

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
        private const val TEACHER_COUNTDOWN_MS = 5_000L
        private const val STUDY_START_PROGRESS_MS = TEACHER_COUNTDOWN_MS + StudySchedule.DEFAULT_MEDITATION_DURATION_MS
        private const val TOTAL_STUDY_PHASES_MS = StudySchedule.DEFAULT_MEDITATION_DURATION_MS +
            StudySchedule.DEFAULT_STUDY_DURATION_MS + StudySchedule.DEFAULT_BREAK_DURATION_MS
        private const val TOTAL_SESSION_WITH_COUNTDOWN_MS = TEACHER_COUNTDOWN_MS + TOTAL_STUDY_PHASES_MS
    }
}
