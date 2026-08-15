package io.remotestudy.student

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.remotestudy.camera.BookCameraView
import io.remotestudy.camera.BookRegion
import io.remotestudy.camera.CaptureAssets
import io.remotestudy.camera.FrameObservation
import io.remotestudy.camera.DetailCaptureMode as CameraDetailCaptureMode
import io.remotestudy.detection.DetectionEventKind
import io.remotestudy.detection.DetectionConfig
import io.remotestudy.detection.FrameEvidence
import io.remotestudy.detection.StudyActivityMonitor
import io.remotestudy.domain.session.ProblemCompleted
import io.remotestudy.domain.session.Pause
import io.remotestudy.domain.session.Resume
import io.remotestudy.domain.session.SessionPhase
import io.remotestudy.domain.session.SessionSnapshot
import io.remotestudy.domain.session.SessionStateMachine
import io.remotestudy.domain.session.StudySchedule
import io.remotestudy.domain.session.SessionStatus
import io.remotestudy.domain.session.StartOrigin
import io.remotestudy.domain.session.StartRequested
import io.remotestudy.domain.session.Tick
import io.remotestudy.domain.session.UndoProblem
import io.remotestudy.protocol.PeerRole
import io.remotestudy.protocol.AlertKind
import io.remotestudy.protocol.AssetKind
import io.remotestudy.protocol.StudyMessage
import io.remotestudy.protocol.DetailCaptureMode as WireDetailCaptureMode
import io.remotestudy.protocol.WireSessionPhase
import io.remotestudy.protocol.WireSessionStatus
import io.remotestudy.transport.TransportEvent
import io.remotestudy.transport.TransportRole
import io.remotestudy.transport.nearby.NearbyPermissionSet
import io.remotestudy.transport.nearby.NearbyStudyTransport
import io.remotestudy.sync.ReliableMessageChannel
import io.remotestudy.voice.StudentVoiceCommandController
import io.remotestudy.voice.StudentVoiceCommandListener
import io.remotestudy.voice.VoiceCommand
import io.remotestudy.voice.VoiceCommandError
import io.remotestudy.voice.VoiceCommandStatus
import io.remotestudy.voicemessage.RecordedVoiceMessage
import io.remotestudy.voicemessage.VoiceMessagePlayer
import io.remotestudy.voicemessage.VoiceMessageRecorder
import io.remotestudy.voicemessage.VoiceMessageRecorderState
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class StudentActivity : ComponentActivity() {
    private sealed interface TeacherReply {
        data class Text(val value: String) : TeacherReply

        data class Voice(val file: File) : TeacherReply
    }

    private var session = SessionStateMachine()
    private val sessionId = UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper())
    // Keep acknowledgements very short, but make them unmistakable on a mounted phone.
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private var activityMonitor = StudyActivityMonitor()
    private var captureIntervalMs = DEFAULT_CAPTURE_INTERVAL_MS

    private lateinit var transport: NearbyStudyTransport
    private lateinit var reliableChannel: ReliableMessageChannel
    private lateinit var voiceController: StudentVoiceCommandController
    private lateinit var voiceMessageRecorder: VoiceMessageRecorder
    private lateinit var voiceMessagePlayer: VoiceMessagePlayer
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var cameraView: BookCameraView
    private lateinit var connectionPill: TextView
    private lateinit var phaseLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var eventLabel: TextView
    private lateinit var countLabel: TextView
    private lateinit var pairingPanel: LinearLayout
    private lateinit var pairingDigits: TextView
    private lateinit var calibrateButton: Button
    private lateinit var startButton: Button
    private lateinit var problemButton: Button
    private lateinit var undoButton: Button
    private lateinit var voiceMessageButton: Button
    private lateinit var listenReplyButton: Button

    private var pendingEndpointId: String? = null
    private var requestedEndpointId: String? = null
    private var connected = false
    private var transportStarted = false
    private var cameraBound = false
    private var cameraReady = false
    private var cameraBindRetryCount = 0
    private var voiceStarted = false
    private var appInForeground = false
    private var activityDestroyed = false
    private var calibrated = true
    private var revision = 0L
    private var lastPublishedKey: String? = null
    private var lastRenderedPhase: SessionPhase? = null
    private var lastRenderedStatus: SessionStatus? = null
    private var latestProblemEventId: String? = null
    private var captureInFlight = false
    private var pendingCalibrationAssetId: String? = null
    private val pendingComparisonRequests = ArrayDeque<StudyMessage.AssetRequest>()
    private var lastCaptureAtElapsedMs: Long? = null
    private val captureById = linkedMapOf<String, CaptureAssets>()
    private val thumbnailOfferedIds = mutableSetOf<String>()
    private val pendingAssetTransfers = linkedSetOf<AssetTransferKey>()
    private var monitoringActive = false
    private var activeOutgoingVoiceMessageId: String? = null
    private val pendingOutgoingVoiceMessages = linkedMapOf<String, RecordedVoiceMessage>()
    private val outgoingFileAttemptByPayloadId = mutableMapOf<Long, OutgoingFileAttempt>()
    private val outgoingPayloadIdByKey = mutableMapOf<OutgoingFileKey, Long>()
    private val outgoingRetryCountByKey = mutableMapOf<OutgoingFileKey, Int>()
    private val outgoingRetryRunnableByKey = mutableMapOf<OutgoingFileKey, Runnable>()
    private val voiceTransferByPayloadId = linkedMapOf<Long, StudyMessage.VoiceTransfer>()
    private val earlyVoiceFileUriByPayloadId = linkedMapOf<Long, String>()
    private val ignoredAssetPayloadIds = linkedSetOf<Long>()
    private var latestTeacherReply: TeacherReply? = null
    private var latestTeacherReplyAtEpochMs = Long.MIN_VALUE
    private var pendingInitialTeacherText: String? = null
    private var pendingInitialTeacherTextAtEpochMs = Long.MIN_VALUE
    private var textToSpeechReady = false
    private var activeReplyPlaybackId: String? = null
    private var awayAlertEnabled = true
    private var noMovementAlertEnabled = true
    private var requestedDetailMode = CameraDetailCaptureMode.STANDARD_12_MP
    private var requestedDetailZoomRatio = DEFAULT_DETAIL_ZOOM_RATIO
    private var requestedFocusTimeoutMs = DEFAULT_FOCUS_TIMEOUT_MS
    private var pendingCameraProfileApply = false
    private var cameraProfileApplying = false

    private val ticker = object : Runnable {
        override fun run() {
            val snapshot = session.dispatch(Tick(SystemClock.elapsedRealtime()))
            renderSession(snapshot)
            publishSnapshotIfChanged(snapshot)
            updateMonitoring(snapshot)
            maybeCapture(snapshot)
            reliableChannel.retryDue(SystemClock.elapsedRealtime())
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContentView())
        savedInstanceState?.let { state ->
            runCatching {
                BookRegion(
                    state.getFloat(STATE_BOOK_LEFT),
                    state.getFloat(STATE_BOOK_TOP),
                    state.getFloat(STATE_BOOK_RIGHT),
                    state.getFloat(STATE_BOOK_BOTTOM),
                )
            }.onSuccess(cameraView::setBookRegion)
        }
        getSharedPreferences("camera-profile", MODE_PRIVATE).let { preferences ->
            if (preferences.contains("left")) {
                runCatching {
                    BookRegion(
                        preferences.getFloat("left", BookRegion.DEFAULT.left),
                        preferences.getFloat("top", BookRegion.DEFAULT.top),
                        preferences.getFloat("right", BookRegion.DEFAULT.right),
                        preferences.getFloat("bottom", BookRegion.DEFAULT.bottom),
                    )
                }.onSuccess(cameraView::setBookRegion)
            }
            requestedDetailMode = runCatching {
                CameraDetailCaptureMode.valueOf(
                    preferences.getString("detailMode", CameraDetailCaptureMode.STANDARD_12_MP.name)
                        ?: CameraDetailCaptureMode.STANDARD_12_MP.name,
                )
            }.getOrDefault(CameraDetailCaptureMode.STANDARD_12_MP)
            requestedDetailZoomRatio = preferences.getFloat("detailZoomRatio", DEFAULT_DETAIL_ZOOM_RATIO)
            requestedFocusTimeoutMs = preferences.getLong("focusTimeoutMs", DEFAULT_FOCUS_TIMEOUT_MS)
            cameraView.setDetailCaptureSettings(requestedDetailZoomRatio, requestedFocusTimeoutMs)
        }

        transport = NearbyStudyTransport(this).also { nearby ->
            nearby.setListener { event -> runOnUiThread { handleTransportEvent(event) } }
        }
        reliableChannel = ReliableMessageChannel(transmitter = transport::send)
        voiceController = StudentVoiceCommandController(
            context = this,
            listener = object : StudentVoiceCommandListener {
                override fun onCommand(command: VoiceCommand) = handleVoiceCommand(command)

                override fun onMessageRecognized(text: String) {
                    sendRecognizedMessage(text)
                }

                override fun onStatus(status: VoiceCommandStatus) = Unit

                override fun onError(error: VoiceCommandError) {
                    if (!error.recoverable) {
                        voiceStarted = false
                        eventLabel.text = error.message
                            ?: "음성 명령을 사용할 수 없습니다 · 버튼은 계속 사용할 수 있어요"
                    }
                }
            },
        )
        voiceMessageRecorder = VoiceMessageRecorder(
            context = this,
            listener = object : VoiceMessageRecorder.Listener {
                override fun onAutoStopped(message: RecordedVoiceMessage) {
                    completeRecordedVoiceMessage(message, automaticallyStopped = true)
                }

                override fun onRecordingError(error: Throwable) {
                    activeOutgoingVoiceMessageId = null
                    resetVoiceMessageButton()
                    resumeVoiceCommandsAfterMessage()
                    eventLabel.text = "음성 메시지 녹음 실패: ${error.message.orEmpty()}"
                }
            },
        )
        voiceMessagePlayer = VoiceMessagePlayer(this)
        textToSpeech = TextToSpeech(this) { status ->
            handler.post { configureTextToSpeech(status) }
        }
        prunePersistedStudentMedia()
        requestPermissionsAndStart()
        renderSession(session.snapshot())
        handler.post(ticker)
    }

    override fun onStart() {
        super.onStart()
        appInForeground = true
        updateMonitoring(session.snapshot())
        if (::voiceController.isInitialized &&
            (!::voiceMessageRecorder.isInitialized ||
                voiceMessageRecorder.state == VoiceMessageRecorderState.IDLE)
        ) {
            startVoiceIfAllowed()
        }
    }

    override fun onStop() {
        appInForeground = false
        stopReplyPlayback()
        if (::voiceController.isInitialized && voiceStarted) {
            voiceController.stop()
            voiceStarted = false
        }
        if (::voiceMessageRecorder.isInitialized &&
            voiceMessageRecorder.state == VoiceMessageRecorderState.RECORDING
        ) {
            voiceMessageRecorder.cancel()
            activeOutgoingVoiceMessageId = null
            if (::voiceMessageButton.isInitialized) resetVoiceMessageButton()
        }
        setMonitoringActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        activityDestroyed = true
        handler.removeCallbacksAndMessages(null)
        voiceMessageRecorder.close()
        voiceMessagePlayer.close()
        textToSpeech.stop()
        textToSpeech.shutdown()
        voiceController.destroy()
        transport.setListener(null)
        transport.stop()
        cameraView.close()
        fileExecutor.shutdownNow()
        tone.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val region = cameraView.currentBookRegion()
        outState.putFloat(STATE_BOOK_LEFT, region.left)
        outState.putFloat(STATE_BOOK_TOP, region.top)
        outState.putFloat(STATE_BOOK_RIGHT, region.right)
        outState.putFloat(STATE_BOOK_BOTTOM, region.bottom)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cameraView.updateTargetRotation(cameraView.display?.rotation ?: android.view.Surface.ROTATION_0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            bindCameraIfAllowed()
            startVoiceIfAllowed()
            if (nearbyPermissionsGranted()) startTransport()
            else setConnectionState("연결 권한 필요", COLOR_WARNING)
        }
    }

    private fun requestPermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            addAll(NearbyPermissionSet.requiredForCurrentDevice())
        }
        val missing = required.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            bindCameraIfAllowed()
            startVoiceIfAllowed()
            startTransport()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun bindCameraIfAllowed() {
        if (cameraBound || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraBound = true
        cameraView.setFrameObservationListener(::handleFrameObservation)
        cameraView.bind(this) { result ->
            result.onSuccess {
                cameraReady = true
                calibrated = true
                cameraView.armPresenceBaseline()
                cameraBindRetryCount = 0
                calibrateButton.isEnabled = true
                if (pendingCameraProfileApply) {
                    applyRequestedCameraProfile(reportToTeacher = true)
                } else if (requestedDetailMode != CameraDetailCaptureMode.STANDARD_12_MP) {
                    applyRequestedCameraProfile(reportToTeacher = false)
                }
            }.onFailure {
                cameraBound = false
                cameraReady = false
                calibrated = true
                calibrateButton.isEnabled = false
                eventLabel.text = "카메라를 열 수 없습니다: ${it.message.orEmpty()}"
                if (cameraBindRetryCount < MAX_CAMERA_BIND_RETRIES) {
                    cameraBindRetryCount += 1
                    handler.postDelayed(::bindCameraIfAllowed, CAMERA_BIND_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun startVoiceIfAllowed() {
        if (!appInForeground || voiceStarted || activeReplyPlaybackId != null) return
        if (::voiceMessageRecorder.isInitialized &&
            voiceMessageRecorder.state == VoiceMessageRecorderState.RECORDING
        ) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        voiceStarted = true
        voiceController.start()
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.STUDY_START -> studentStart()
            VoiceCommand.PROBLEM_DONE -> completeProblem()
            VoiceCommand.UNDO -> undoProblem()
            VoiceCommand.PAUSE -> togglePause()
            VoiceCommand.STOP -> {
                if (session.snapshot().status == SessionStatus.RUNNING) togglePause()
                eventLabel.text = "공부를 멈췄습니다 · 종료는 접힌 메뉴에서 선택하세요"
            }
            VoiceCommand.DAD_MESSAGE -> {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 55)
                eventLabel.text = "메시지를 말씀해 주세요"
            }
        }
    }

    private fun sendRecognizedMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (clean.replace(" ", "") in setOf("녹음", "음성", "음성메시지")) {
            startVoiceMessageRecording()
            val recordingId = activeOutgoingVoiceMessageId
            if (recordingId != null) {
                eventLabel.text = "음성 메시지를 말씀해 주세요 · 12초 뒤 자동 전송"
                handler.postDelayed({
                    if (activeOutgoingVoiceMessageId == recordingId &&
                        voiceMessageRecorder.state == VoiceMessageRecorderState.RECORDING
                    ) {
                        stopVoiceMessageRecording()
                    }
                }, VOICE_DICTATION_DURATION_MS)
            }
            return
        }
        val queued = send(
            StudyMessage.TextMessage(
                messageId = UUID.randomUUID().toString(),
                sender = PeerRole.STUDENT,
                text = clean,
                sentAtEpochMs = System.currentTimeMillis(),
            ),
        )
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 85)
        eventLabel.text = if (queued) {
            "선생님께 메시지를 보냈습니다: ${clean.take(40)}"
        } else {
            "메시지를 보관했습니다 · 연결되면 다시 전송합니다"
        }
    }

    private fun togglePause() {
        val now = SystemClock.elapsedRealtime()
        val snapshot = when (session.snapshot().status) {
            SessionStatus.RUNNING -> session.dispatch(Pause(UUID.randomUUID().toString(), now))
            SessionStatus.PAUSED -> session.dispatch(Resume(UUID.randomUUID().toString(), now))
            else -> return
        }
        eventLabel.text = if (snapshot.status == SessionStatus.PAUSED) "잠시 멈췄습니다" else "공부를 다시 시작합니다"
        renderSession(snapshot)
        publishSnapshotIfChanged(snapshot, force = true)
    }

    private fun nearbyPermissionsGranted(): Boolean =
        NearbyPermissionSet.requiredForCurrentDevice().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startTransport() {
        if (transportStarted) return
        transportStarted = true
        requestedEndpointId = null
        transport.start(TransportRole.STUDENT, "학생폰")
    }

    private fun handleTransportEvent(event: TransportEvent) {
        if (activityDestroyed) return
        when (event) {
            TransportEvent.Searching -> {
                requestedEndpointId = null
                setConnectionState("선생님폰 찾는 중", COLOR_WARNING)
            }
            is TransportEvent.EndpointFound -> {
                if (requestedEndpointId == null) {
                    requestedEndpointId = event.endpointId
                    transport.requestConnection(event.endpointId)
                    setConnectionState("${event.displayName} 연결 중", COLOR_WARNING)
                }
            }

            is TransportEvent.EndpointLost -> {
                if (requestedEndpointId == event.endpointId) {
                    requestedEndpointId = null
                    setConnectionState("선생님폰 다시 찾는 중", COLOR_WARNING)
                }
            }

            is TransportEvent.PairingRequested -> {
                pendingEndpointId = event.endpointId
                pairingPanel.visibility = View.GONE
                setConnectionState("자동 연결 중", COLOR_WARNING)
                transport.approve(event.endpointId)
            }

            is TransportEvent.Connected -> {
                connected = true
                reliableChannel.setConnected(true, SystemClock.elapsedRealtime())
                resetOutgoingRetriesForReconnect()
                pairingPanel.visibility = View.GONE
                setConnectionState("${event.displayName} 연결됨", COLOR_SUCCESS)
                send(
                    StudyMessage.Hello(
                        messageId = UUID.randomUUID().toString(),
                        deviceName = "학생폰",
                        role = PeerRole.STUDENT,
                    ),
                )
                publishSnapshotIfChanged(session.snapshot(), force = true)
                retryPendingAssetTransfers()
                offerLatestUnsentThumbnail()
                retryPendingVoiceMessages()
            }

            is TransportEvent.Disconnected -> {
                connected = false
                reliableChannel.setConnected(false, SystemClock.elapsedRealtime())
                clearOutgoingInflightForDisconnect()
                requestedEndpointId = null
                setConnectionState("연결 끊김", COLOR_DANGER)
                eventLabel.text = "선생님폰 연결이 끊겼지만 타이머는 계속됩니다"
                transportStarted = false
                startTransport()
            }

            is TransportEvent.MessageReceived -> receiveMessage(event.bytes)
            is TransportEvent.FileReceived -> receiveVoiceFile(event)
            is TransportEvent.FileSent -> handleOutgoingFileSent(event.payloadId)
            is TransportEvent.FileSendFailed -> handleOutgoingFileFailure(event.payloadId, event.detail)
            is TransportEvent.Failure -> {
                if (event.operation in setOf("requestConnection", "connection", "startDiscovery")) {
                    requestedEndpointId = null
                }
                setConnectionState("연결 오류", COLOR_DANGER)
                eventLabel.text = "${event.operation}: ${event.detail}"
            }
        }
    }

    private fun receiveMessage(bytes: ByteArray) {
        val message = runCatching {
            reliableChannel.receive(bytes, SystemClock.elapsedRealtime())
        }.getOrElse {
            eventLabel.text = "지원하지 않는 메시지를 받았습니다"
            return
        } ?: return
        when (message) {
            is StudyMessage.StartRequest -> {
                if (!cameraReady) {
                    eventLabel.text = "카메라 준비 뒤 시작할 수 있습니다"
                    return
                }
                val snapshot = session.dispatch(
                    StartRequested(
                        commandId = message.messageId,
                        origin = StartOrigin.TEACHER,
                        atElapsedMs = SystemClock.elapsedRealtime(),
                    ),
                )
                eventLabel.text = "선생님 시작 요청 · 첫 명상 사이클부터 시작합니다"
                renderSession(snapshot)
                publishSnapshotIfChanged(snapshot, force = true)
            }

            is StudyMessage.AssetRequest -> handleAssetRequest(message)
            is StudyMessage.TextMessage -> receiveTeacherText(message)
            is StudyMessage.VoiceTransfer -> registerVoiceTransfer(message)
            is StudyMessage.StudySettings -> applyStudySettings(message)
            is StudyMessage.BookRegionSettings -> applyBookRegionSettings(message)
            is StudyMessage.AssetTransfer -> {
                voiceTransferByPayloadId.remove(message.payloadId)
                earlyVoiceFileUriByPayloadId.remove(message.payloadId)
                ignoredAssetPayloadIds += message.payloadId
                while (ignoredAssetPayloadIds.size > MAX_PENDING_INCOMING_FILES) {
                    ignoredAssetPayloadIds.remove(ignoredAssetPayloadIds.first())
                }
            }
            is StudyMessage.Ack,
            is StudyMessage.Alert,
            is StudyMessage.Hello,
            is StudyMessage.ProblemCompleted,
            is StudyMessage.SessionSnapshot,
            is StudyMessage.CameraProfileStatus,
            -> Unit
        }
    }

    private fun applyBookRegionSettings(settings: StudyMessage.BookRegionSettings) {
        runCatching {
            BookRegion(settings.left, settings.top, settings.right, settings.bottom)
        }.onSuccess { region ->
            cameraView.setBookRegion(region)
            cameraView.armPresenceBaseline()
            requestedDetailMode = settings.detailCaptureMode.toCameraMode()
            requestedDetailZoomRatio = settings.detailZoomRatio
            requestedFocusTimeoutMs = settings.focusTimeoutMs
            cameraView.setDetailCaptureSettings(requestedDetailZoomRatio, requestedFocusTimeoutMs)
            getSharedPreferences("camera-profile", MODE_PRIVATE).edit()
                .putFloat("left", region.left)
                .putFloat("top", region.top)
                .putFloat("right", region.right)
                .putFloat("bottom", region.bottom)
                .putString("detailMode", requestedDetailMode.name)
                .putFloat("detailZoomRatio", requestedDetailZoomRatio)
                .putLong("focusTimeoutMs", requestedFocusTimeoutMs)
                .apply()
            eventLabel.text = "책 영역 · ${requestedDetailZoomRatio}배 · 초점 설정 적용 중"
            pendingCameraProfileApply = true
            if (cameraReady) applyRequestedCameraProfile(reportToTeacher = true)
        }.onFailure {
            eventLabel.text = "책 영역 설정을 적용할 수 없습니다"
        }
    }

    private fun applyRequestedCameraProfile(reportToTeacher: Boolean) {
        if (!cameraReady || captureInFlight) {
            pendingCameraProfileApply = pendingCameraProfileApply || reportToTeacher
            eventLabel.text = if (captureInFlight) {
                "현재 촬영 완료 뒤 책 상세 화질을 적용합니다"
            } else {
                "카메라 준비 뒤 책 상세 화질을 적용합니다"
            }
            return
        }
        val shouldReport = reportToTeacher || pendingCameraProfileApply
        pendingCameraProfileApply = false
        cameraProfileApplying = true
        cameraView.applyDetailCaptureMode(requestedDetailMode) { result ->
            cameraProfileApplying = false
            result.onSuccess { profile ->
                cameraView.armPresenceBaseline()
                if (shouldReport) {
                    send(
                        StudyMessage.CameraProfileStatus(
                            messageId = UUID.randomUUID().toString(),
                            requestedMode = profile.requestedMode.toWireMode(),
                            appliedMode = profile.appliedMode.toWireMode(),
                            width = profile.width,
                            height = profile.height,
                            ultra50MpAvailable = profile.ultra50MpAvailable,
                        ),
                        coalesceKey = "camera-profile-status",
                    )
                }
                eventLabel.text = if (profile.requestedMode == profile.appliedMode) {
                    "책 상세 ${profile.width}×${profile.height} 적용 완료"
                } else {
                    "50MP 미지원 · ${profile.width}×${profile.height}로 적용"
                }
            }.onFailure {
                pendingCameraProfileApply = shouldReport
                eventLabel.text = "책 상세 화질 적용 실패: ${it.message.orEmpty()}"
            }
            if (result.isSuccess && pendingCameraProfileApply) {
                applyRequestedCameraProfile(reportToTeacher = true)
            } else if (result.isSuccess) {
                startNextSpecialCaptureIfNeeded()
            }
        }
    }

    private fun applyStudySettings(settings: StudyMessage.StudySettings) {
        val scheduleApplied = session.snapshot().status == SessionStatus.READY
        if (scheduleApplied) {
            session = SessionStateMachine(
                schedule = StudySchedule(
                    meditationDurationMs = settings.meditationDurationMs,
                    studyDurationMs = settings.studyDurationMs,
                    breakDurationMs = settings.breakDurationMs,
                ),
                teacherCountdownDurationMs = settings.teacherCountdownMs,
            )
        }
        activityMonitor = StudyActivityMonitor(
            DetectionConfig(
                presenceAbsenceThreshold = settings.presenceThreshold,
                presenceRestoreThreshold = settings.presenceRestoreThreshold,
                presenceMotionThreshold = settings.presenceMotionThreshold,
                bookMovementThreshold = settings.bookMovementThreshold,
                awayAfterMs = settings.awayAfterMs,
                noMovementAfterMs = settings.noMovementAfterMs,
                alertCooldownMs = settings.alertCooldownMs,
            ),
        )
        awayAlertEnabled = settings.awayAlertEnabled
        noMovementAlertEnabled = settings.noMovementAlertEnabled
        captureIntervalMs = settings.captureIntervalMs
        if (monitoringActive) activityMonitor.setActive(true, SystemClock.elapsedRealtime())
        lastPublishedKey = null
        renderSession(session.snapshot())
        publishSnapshotIfChanged(session.snapshot(), force = true)
        eventLabel.text = if (scheduleApplied) {
            "시간·알림 설정 적용 완료"
        } else {
            "알림·촬영 설정 즉시 적용 완료 · 시간은 다음 시작부터 적용"
        }
    }

    private fun receiveTeacherText(message: StudyMessage.TextMessage) {
        if (message.sender != PeerRole.TEACHER) return
        if (message.sentAtEpochMs >= latestTeacherReplyAtEpochMs) {
            latestTeacherReply = TeacherReply.Text(message.text)
            latestTeacherReplyAtEpochMs = message.sentAtEpochMs
            listenReplyButton.visibility = View.VISIBLE
        }
        if (textToSpeechReady) {
            speakTeacherText(message.text)
        } else {
            if (message.sentAtEpochMs >= pendingInitialTeacherTextAtEpochMs) {
                pendingInitialTeacherText = message.text
                pendingInitialTeacherTextAtEpochMs = message.sentAtEpochMs
            }
            eventLabel.text = "선생님 답변이 도착했습니다 · 음성 읽기를 준비 중입니다"
        }
    }

    private fun registerVoiceTransfer(message: StudyMessage.VoiceTransfer) {
        if (message.sender != PeerRole.TEACHER) {
            earlyVoiceFileUriByPayloadId.remove(message.payloadId)
            return
        }
        voiceTransferByPayloadId[message.payloadId] = message
        trimIncomingVoiceMaps()
        earlyVoiceFileUriByPayloadId.remove(message.payloadId)?.let { sourceUri ->
            materializeVoiceReply(message, sourceUri)
        }
    }

    private fun receiveVoiceFile(event: TransportEvent.FileReceived) {
        if (ignoredAssetPayloadIds.remove(event.payloadId)) return
        val metadata = voiceTransferByPayloadId[event.payloadId]
        if (metadata == null) {
            earlyVoiceFileUriByPayloadId[event.payloadId] = event.uri
            trimIncomingVoiceMaps()
        } else {
            materializeVoiceReply(metadata, event.uri)
        }
    }

    private fun materializeVoiceReply(
        metadata: StudyMessage.VoiceTransfer,
        sourceUri: String,
    ) {
        voiceTransferByPayloadId.remove(metadata.payloadId)
        earlyVoiceFileUriByPayloadId.remove(metadata.payloadId)
        fileExecutor.execute {
            val result = runCatching {
                val directory = File(cacheDir, "received-voice-messages").apply {
                    check(exists() || mkdirs()) { "음성 답변 캐시를 만들 수 없습니다" }
                }
                val staging = File(directory, "${metadata.payloadId}.part")
                val target = File(directory, "${metadata.payloadId}.m4a")
                if (staging.exists()) check(staging.delete()) { "이전 임시 음성 파일을 지울 수 없습니다" }
                try {
                    val input = checkNotNull(contentResolver.openInputStream(Uri.parse(sourceUri))) {
                        "수신 음성 파일을 열 수 없습니다"
                    }
                    input.use { source ->
                        staging.outputStream().use { destination -> source.copyTo(destination) }
                    }
                    check(staging.length() > 0L) { "수신 음성 파일이 비어 있습니다" }
                    Files.move(
                        staging.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    pruneReceivedVoiceMessages(directory, keep = target)
                    target
                } catch (error: Throwable) {
                    staging.delete()
                    throw error
                }
            }
            runOnUiThread {
                result.onSuccess { file ->
                    if (isDestroyed) {
                        file.delete()
                        return@onSuccess
                    }
                    if (metadata.sentAtEpochMs >= latestTeacherReplyAtEpochMs) {
                        latestTeacherReply = TeacherReply.Voice(file)
                        latestTeacherReplyAtEpochMs = metadata.sentAtEpochMs
                        listenReplyButton.visibility = View.VISIBLE
                    }
                    eventLabel.text = "선생님 음성 답변이 도착했습니다"
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 55)
                    handler.postDelayed({ playLatestTeacherReply() }, 220L)
                }.onFailure {
                    if (!isDestroyed) {
                        eventLabel.text = "음성 답변 저장 실패: ${it.message.orEmpty()}"
                    }
                }
            }
        }
    }

    private fun trimIncomingVoiceMaps() {
        while (voiceTransferByPayloadId.size > MAX_PENDING_INCOMING_FILES) {
            voiceTransferByPayloadId.remove(voiceTransferByPayloadId.keys.first())
        }
        while (earlyVoiceFileUriByPayloadId.size > MAX_PENDING_INCOMING_FILES) {
            earlyVoiceFileUriByPayloadId.remove(earlyVoiceFileUriByPayloadId.keys.first())
        }
    }

    private fun pruneReceivedVoiceMessages(directory: File, keep: File) {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) && it != keep }
            .sortedByDescending(File::lastModified)
        files.drop(MAX_RECEIVED_VOICE_MESSAGES - 1).forEach(File::delete)
    }

    private fun prunePersistedStudentMedia() {
        fileExecutor.execute {
            pruneDirectory(File(filesDir, "study-assets"), MAX_LOCAL_ASSETS * 2)
            pruneDirectory(File(filesDir, "voice-messages/outgoing"), MAX_STORED_OUTGOING_VOICE_MESSAGES)
        }
    }

    private fun pruneDirectory(directory: File, keepFileCount: Int) {
        directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .drop(keepFileCount)
            .forEach(File::delete)
    }

    private fun configureTextToSpeech(status: Int) {
        if (activityDestroyed || isDestroyed) return
        if (status != TextToSpeech.SUCCESS) {
            textToSpeechReady = false
            eventLabel.text = "텍스트 답변 읽기를 사용할 수 없습니다"
            return
        }
        val languageResult = textToSpeech.setLanguage(Locale.KOREA)
        textToSpeechReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!textToSpeechReady) {
            eventLabel.text = "한국어 음성 읽기를 사용할 수 없습니다"
            return
        }
        textToSpeech.setSpeechRate(0.82f)
        textToSpeech.setPitch(1.0f)
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let(::finishReplyPlaybackOnMain)
                }

                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let(::finishReplyPlaybackOnMain)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let(::finishReplyPlaybackOnMain)
                }
            },
        )
        pendingInitialTeacherText?.let { text ->
            pendingInitialTeacherText = null
            pendingInitialTeacherTextAtEpochMs = Long.MIN_VALUE
            speakTeacherText(text)
        }
    }

    private fun speakTeacherText(text: String) {
        if (!textToSpeechReady) {
            pendingInitialTeacherText = text
            eventLabel.text = "텍스트 답변 읽기를 준비 중입니다"
            return
        }
        if (voiceMessageRecorder.state == VoiceMessageRecorderState.RECORDING) {
            pendingInitialTeacherText = text
            eventLabel.text = "선생님 답변 도착 · 녹음이 끝나면 읽어 드립니다"
            return
        }
        val playbackId = beginReplyPlayback()
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 55)
        eventLabel.text = "선생님 답변을 읽습니다"
        handler.postDelayed({
            if (activityDestroyed || activeReplyPlaybackId != playbackId) return@postDelayed
            val result = textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                playbackId,
            )
            if (result != TextToSpeech.SUCCESS) {
                finishReplyPlayback(playbackId)
                eventLabel.text = "선생님 답변을 읽지 못했습니다"
            }
        }, 190L)
    }

    private fun playLatestTeacherReply() {
        when (val reply = latestTeacherReply) {
            null -> listenReplyButton.visibility = View.GONE
            is TeacherReply.Text -> speakTeacherText(reply.value)
            is TeacherReply.Voice -> {
                val playbackId = beginReplyPlayback()
                voiceMessagePlayer.play(reply.file) { result ->
                    finishReplyPlayback(playbackId)
                    if (!isDestroyed) {
                        eventLabel.text = if (result.isSuccess) {
                            "선생님 음성 답변 재생이 끝났습니다"
                        } else {
                            "선생님 음성 답변 재생이 중단됐습니다"
                        }
                    }
                }.onSuccess {
                    eventLabel.text = "선생님 음성 답변을 재생합니다"
                }.onFailure {
                    finishReplyPlayback(playbackId)
                    eventLabel.text = "음성 답변을 재생할 수 없습니다: ${it.message.orEmpty()}"
                }
            }
        }
    }

    private fun toggleVoiceMessageRecording() {
        when (voiceMessageRecorder.state) {
            VoiceMessageRecorderState.IDLE -> startVoiceMessageRecording()
            VoiceMessageRecorderState.RECORDING -> stopVoiceMessageRecording()
        }
    }

    private fun startVoiceMessageRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            eventLabel.text = "음성 메시지를 보내려면 마이크 권한이 필요합니다"
            return
        }
        stopReplyPlayback()
        pauseVoiceCommandsForMessage()
        val messageId = UUID.randomUUID().toString()
        voiceMessageRecorder.start(
            outputDir = File(filesDir, "voice-messages/outgoing"),
            messageId = messageId,
        ).onSuccess {
            activeOutgoingVoiceMessageId = messageId
            voiceMessageButton.text = "녹음 중 · 눌러서 보내기"
            voiceMessageButton.background = rounded(COLOR_DANGER, 13f)
            eventLabel.text = "음성 메시지 녹음 중 · 최대 60초"
        }.onFailure {
            activeOutgoingVoiceMessageId = null
            resetVoiceMessageButton()
            resumeVoiceCommandsAfterMessage()
            eventLabel.text = "음성 메시지를 시작할 수 없습니다: ${it.message.orEmpty()}"
        }
    }

    private fun stopVoiceMessageRecording() {
        voiceMessageRecorder.stop().fold(
            onSuccess = { completeRecordedVoiceMessage(it, automaticallyStopped = false) },
            onFailure = {
                activeOutgoingVoiceMessageId = null
                resetVoiceMessageButton()
                resumeVoiceCommandsAfterMessage()
                eventLabel.text = "음성 메시지 저장 실패: ${it.message.orEmpty()}"
            },
        )
    }

    private fun completeRecordedVoiceMessage(
        recorded: RecordedVoiceMessage,
        automaticallyStopped: Boolean,
    ) {
        val messageId = activeOutgoingVoiceMessageId ?: recorded.file.nameWithoutExtension
        activeOutgoingVoiceMessageId = null
        resetVoiceMessageButton()
        resumeVoiceCommandsAfterMessage()
        pendingOutgoingVoiceMessages[messageId] = recorded
        val sent = trySendPendingVoiceMessage(messageId, recorded)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 85)
        eventLabel.text = when {
            sent && automaticallyStopped -> "60초 음성 메시지 전송 중입니다"
            sent -> "음성 메시지 전송 중입니다"
            connected -> "음성 메시지 전송을 재시도합니다"
            else -> "연결되면 음성 메시지를 자동으로 보냅니다"
        }
        pendingInitialTeacherText?.let { text ->
            pendingInitialTeacherText = null
            pendingInitialTeacherTextAtEpochMs = Long.MIN_VALUE
            handler.post { speakTeacherText(text) }
        }
    }

    private fun trySendPendingVoiceMessage(
        messageId: String,
        recorded: RecordedVoiceMessage,
    ): Boolean {
        val key = OutgoingFileKey.Voice(messageId)
        if (key in outgoingPayloadIdByKey) return true
        if (!connected || !recorded.file.isFile) return false
        val payloadId = transport.sendFile(recorded.file)
        if (payloadId == null) {
            scheduleOutgoingRetry(key)
            return false
        }
        registerOutgoingAttempt(
            payloadId,
            key,
            StudyMessage.VoiceTransfer(
                messageId = UUID.randomUUID().toString(),
                userMessageId = messageId,
                sender = PeerRole.STUDENT,
                payloadId = payloadId,
                sentAtEpochMs = System.currentTimeMillis(),
                durationMs = recorded.durationMs.coerceIn(1L, MAX_VOICE_DURATION_MS),
            ),
        )
        return true
    }

    private fun retryPendingVoiceMessages() {
        pendingOutgoingVoiceMessages.toList().forEach { (messageId, recorded) ->
            trySendPendingVoiceMessage(messageId, recorded)
        }
    }

    private fun pauseVoiceCommandsForMessage() {
        if (!voiceStarted) return
        voiceController.stop()
        voiceStarted = false
    }

    private fun resumeVoiceCommandsAfterMessage() {
        startVoiceIfAllowed()
    }

    private fun beginReplyPlayback(): String {
        activeReplyPlaybackId = null
        textToSpeech.stop()
        voiceMessagePlayer.stop()
        val playbackId = "teacher-reply-${UUID.randomUUID()}"
        activeReplyPlaybackId = playbackId
        pauseVoiceCommandsForMessage()
        return playbackId
    }

    private fun finishReplyPlaybackOnMain(playbackId: String) {
        handler.post { finishReplyPlayback(playbackId) }
    }

    private fun finishReplyPlayback(playbackId: String) {
        if (activeReplyPlaybackId != playbackId) return
        activeReplyPlaybackId = null
        resumeVoiceCommandsAfterMessage()
    }

    private fun stopReplyPlayback() {
        if (!::textToSpeech.isInitialized || !::voiceMessagePlayer.isInitialized) return
        activeReplyPlaybackId = null
        textToSpeech.stop()
        voiceMessagePlayer.stop()
    }

    private fun resetVoiceMessageButton() {
        voiceMessageButton.text = "선생님께 음성 메시지"
        voiceMessageButton.background = rounded(COLOR_PRIMARY, 13f)
    }

    private fun updateMonitoring(snapshot: SessionSnapshot) {
        val shouldMonitor = appInForeground &&
            snapshot.status == SessionStatus.RUNNING &&
            snapshot.phase == SessionPhase.STUDY
        setMonitoringActive(shouldMonitor)
    }

    private fun setMonitoringActive(active: Boolean) {
        if (active == monitoringActive) return
        monitoringActive = active
        if (active) cameraView.armPresenceBaseline()
        activityMonitor.setActive(active, SystemClock.elapsedRealtime())
        if (!active) lastCaptureAtElapsedMs = null
    }

    private fun handleFrameObservation(observation: FrameObservation) {
        if (!monitoringActive) return
        activityMonitor.observe(
            FrameEvidence(
                observedAtElapsedMs = observation.observedAtElapsedMs,
                presenceDifference = observation.presenceDifference,
                presenceMotion = observation.presenceMotion,
                bookMovement = observation.bookMovement,
            ),
        ).forEach { event ->
            val alertKind = when (event.kind) {
                DetectionEventKind.AWAY -> AlertKind.AWAY
                DetectionEventKind.NO_BOOK_MOVEMENT -> AlertKind.NO_BOOK_MOVEMENT
                DetectionEventKind.PRESENCE_RESTORED -> AlertKind.PRESENCE_RESTORED
                DetectionEventKind.BOOK_MOVEMENT_RESTORED -> AlertKind.BOOK_MOVEMENT_RESTORED
            }
            val enabled = when (event.kind) {
                DetectionEventKind.AWAY, DetectionEventKind.PRESENCE_RESTORED -> awayAlertEnabled
                DetectionEventKind.NO_BOOK_MOVEMENT, DetectionEventKind.BOOK_MOVEMENT_RESTORED ->
                    noMovementAlertEnabled
            }
            if (!enabled) return@forEach
            sendAlert(alertKind, event.observedDurationMs)
            eventLabel.text = when (event.kind) {
                DetectionEventKind.AWAY -> "자리 판정 구역 이탈 알림을 전송했습니다"
                DetectionEventKind.NO_BOOK_MOVEMENT -> "책 영역 움직임 없음 알림을 전송했습니다"
                DetectionEventKind.PRESENCE_RESTORED -> "자리 판정 구역에 다시 보입니다"
                DetectionEventKind.BOOK_MOVEMENT_RESTORED -> "책 영역 움직임이 다시 감지됐습니다"
            }
        }
    }

    private fun sendAlert(kind: AlertKind, durationMs: Long) {
        send(
            StudyMessage.Alert(
                messageId = UUID.randomUUID().toString(),
                kind = kind,
                observedDurationMs = durationMs.coerceAtLeast(0),
            ),
        )
    }

    private fun maybeCapture(snapshot: SessionSnapshot) {
        if (!appInForeground) return
        val shouldCapture = snapshot.status == SessionStatus.RUNNING &&
            snapshot.phase in setOf(SessionPhase.MEDITATION, SessionPhase.STUDY)
        if (!shouldCapture || captureInFlight) return
        val now = SystemClock.elapsedRealtime()
        val previousCapture = lastCaptureAtElapsedMs
        if (previousCapture != null && now - previousCapture < captureIntervalMs) return

        lastCaptureAtElapsedMs = now
        captureInFlight = true
        val assetId = UUID.randomUUID().toString()
        cameraView.captureAssets(
            outputDir = File(filesDir, "study-assets"),
            assetId = assetId,
            capturedAtEpochMs = System.currentTimeMillis(),
        ) { result ->
            captureInFlight = false
            result.onSuccess { assets ->
                captureById[assets.assetId] = assets
                pruneLocalAssets()
                if (connected) offerAsset(assets, AssetKind.THUMBNAIL)
            }.onFailure {
                eventLabel.text = "학습 사진 생성 실패: ${it.message.orEmpty()}"
            }
            if (pendingCameraProfileApply) {
                applyRequestedCameraProfile(reportToTeacher = true)
            } else {
                startNextSpecialCaptureIfNeeded()
            }
        }
    }

    private fun handleAssetRequest(request: StudyMessage.AssetRequest) {
        when (request.kind) {
            AssetKind.THUMBNAIL -> Unit
            AssetKind.BOOK_ROI -> {
                val assets = captureById[request.assetId]
                if (assets == null) {
                    eventLabel.text = "요청한 고화질 책 사진이 이 세션에 없습니다"
                    return
                }
                if (offerAsset(assets, AssetKind.BOOK_ROI)) {
                    eventLabel.text = "${requestedDetailZoomRatio}배 고화질 책 영역을 선생님께 전송 중입니다"
                }
            }
            AssetKind.BOOK_CALIBRATION -> {
                pendingCalibrationAssetId = request.assetId
                startNextSpecialCaptureIfNeeded()
            }
            AssetKind.CAMERA_COMPARE_1X,
            AssetKind.CAMERA_COMPARE_2X,
            AssetKind.CAMERA_COMPARE_3X,
            -> {
                if (pendingComparisonRequests.none { it.assetId == request.assetId }) {
                    pendingComparisonRequests.addLast(request)
                }
                startNextSpecialCaptureIfNeeded()
            }
        }
    }

    private fun startNextSpecialCaptureIfNeeded() {
        if (pendingCalibrationAssetId != null) startPendingCalibrationCaptureIfNeeded()
        else startPendingComparisonCaptureIfNeeded()
    }

    private fun startPendingCalibrationCaptureIfNeeded() {
        val assetId = pendingCalibrationAssetId ?: return
        if (captureInFlight || cameraProfileApplying) return
        if (!cameraReady || !appInForeground) {
            eventLabel.text = "책 영역 설정 사진을 촬영할 수 없습니다 · 카메라 화면을 확인해 주세요"
            return
        }
        pendingCalibrationAssetId = null
        captureInFlight = true
        eventLabel.text = "선생님 설정용 실제 ${requestedDetailZoomRatio}배 사진을 촬영 중입니다"
        cameraView.captureAssets(
            outputDir = File(filesDir, "study-assets"),
            assetId = assetId,
            capturedAtEpochMs = System.currentTimeMillis(),
            includeCalibration = true,
            focusAtFrameCenter = true,
        ) { result ->
            captureInFlight = false
            result.onSuccess { assets ->
                captureById[assets.assetId] = assets
                pruneLocalAssets()
                if (offerAsset(assets, AssetKind.BOOK_CALIBRATION)) {
                    eventLabel.text = "실제 ${requestedDetailZoomRatio}배 책 영역 설정 사진을 선생님께 전송 중입니다"
                }
            }.onFailure {
                eventLabel.text = "책 영역 설정 사진 실패: ${it.message.orEmpty()}"
            }
            if (pendingCameraProfileApply) {
                applyRequestedCameraProfile(reportToTeacher = true)
            } else {
                startNextSpecialCaptureIfNeeded()
            }
        }
    }

    private fun startPendingComparisonCaptureIfNeeded() {
        val request = pendingComparisonRequests.firstOrNull() ?: return
        if (captureInFlight || cameraProfileApplying) return
        if (!cameraReady || !appInForeground) {
            eventLabel.text = "카메라 비교 촬영 대기 · 학생 화면을 켜 주세요"
            return
        }
        pendingComparisonRequests.removeFirst()
        val zoomRatio = when (request.kind) {
            AssetKind.CAMERA_COMPARE_1X -> 1f
            AssetKind.CAMERA_COMPARE_2X -> 2f
            AssetKind.CAMERA_COMPARE_3X -> 3f
            else -> return
        }
        captureInFlight = true
        eventLabel.text = "카메라 비교 ${zoomRatio.toInt()}배 · 초점 맞추는 중"
        cameraView.captureAssets(
            outputDir = File(filesDir, "study-assets"),
            assetId = request.assetId,
            capturedAtEpochMs = System.currentTimeMillis(),
            includeCalibration = true,
            detailZoomRatioOverride = zoomRatio,
            focusTimeoutOverrideMs = requestedFocusTimeoutMs,
            focusAtFrameCenter = true,
        ) { result ->
            captureInFlight = false
            result.onSuccess { assets ->
                captureById[assets.assetId] = assets
                pruneLocalAssets()
                if (offerAsset(assets, request.kind)) {
                    eventLabel.text = "비교 ${zoomRatio.toInt()}배 사진 전송 중"
                }
            }.onFailure {
                eventLabel.text = "비교 ${zoomRatio.toInt()}배 촬영 실패: ${it.message.orEmpty()}"
            }
            if (pendingCameraProfileApply) applyRequestedCameraProfile(reportToTeacher = true)
            else startNextSpecialCaptureIfNeeded()
        }
    }

    private fun offerLatestUnsentThumbnail() {
        val latest = captureById.values.lastOrNull { it.assetId !in thumbnailOfferedIds } ?: return
        offerAsset(latest, AssetKind.THUMBNAIL)
    }

    private fun offerAsset(assets: CaptureAssets, kind: AssetKind): Boolean {
        val transferKey = AssetTransferKey(assets.assetId, kind)
        if (kind == AssetKind.THUMBNAIL) {
            discardSupersededPendingThumbnails(keep = transferKey)
        }
        pendingAssetTransfers += transferKey
        return trySendPendingAsset(transferKey)
    }

    private fun trySendPendingAsset(transferKey: AssetTransferKey): Boolean {
        val key = OutgoingFileKey.Asset(transferKey)
        if (key in outgoingPayloadIdByKey) return true
        if (!connected) return false
        val assets = captureById[transferKey.assetId]
        if (assets == null) {
            pendingAssetTransfers.remove(transferKey)
            return false
        }
        val file = when (transferKey.kind) {
            AssetKind.THUMBNAIL -> assets.thumbnailFile
            AssetKind.BOOK_ROI -> assets.bookRoiFile
            AssetKind.BOOK_CALIBRATION -> assets.bookCalibrationFile ?: run {
                pendingAssetTransfers.remove(transferKey)
                return false
            }
            AssetKind.CAMERA_COMPARE_1X,
            AssetKind.CAMERA_COMPARE_2X,
            AssetKind.CAMERA_COMPARE_3X,
            -> assets.bookCalibrationFile ?: run {
                pendingAssetTransfers.remove(transferKey)
                return false
            }
        }
        val payloadId = transport.sendFile(file)
        if (payloadId == null) {
            scheduleOutgoingRetry(key)
            return false
        }
        registerOutgoingAttempt(
            payloadId,
            key,
            StudyMessage.AssetTransfer(
                messageId = UUID.randomUUID().toString(),
                assetId = assets.assetId,
                kind = transferKey.kind,
                payloadId = payloadId,
                capturedAtEpochMs = assets.capturedAtEpochMs,
            ),
        )
        return true
    }

    private fun retryPendingAssetTransfers() {
        pendingAssetTransfers
            .filter { it.kind != AssetKind.THUMBNAIL }
            .forEach(::trySendPendingAsset)
        val latestThumbnail = pendingAssetTransfers
            .filter { it.kind == AssetKind.THUMBNAIL }
            .maxByOrNull { captureById[it.assetId]?.capturedAtEpochMs ?: Long.MIN_VALUE }
        if (latestThumbnail != null) {
            discardSupersededPendingThumbnails(keep = latestThumbnail)
            trySendPendingAsset(latestThumbnail)
        }
    }

    private fun discardSupersededPendingThumbnails(keep: AssetTransferKey) {
        pendingAssetTransfers
            .filter { transfer ->
                transfer.kind == AssetKind.THUMBNAIL &&
                    transfer != keep &&
                    OutgoingFileKey.Asset(transfer) !in outgoingPayloadIdByKey
            }
            .toList()
            .forEach { transfer ->
                val key = OutgoingFileKey.Asset(transfer)
                pendingAssetTransfers.remove(transfer)
                cancelOutgoingRetry(key)
                outgoingRetryCountByKey.remove(key)
                thumbnailOfferedIds += transfer.assetId
                captureById[transfer.assetId]?.thumbnailFile?.let { staleFile ->
                    fileExecutor.execute { staleFile.delete() }
                }
            }
    }

    private fun registerOutgoingAttempt(
        payloadId: Long,
        key: OutgoingFileKey,
        metadata: StudyMessage,
    ) {
        outgoingPayloadIdByKey[key] = payloadId
        outgoingFileAttemptByPayloadId[payloadId] = OutgoingFileAttempt(
            key = key,
            metadata = metadata,
            fileSent = false,
        )
    }

    private fun handleOutgoingFileSent(payloadId: Long) {
        val attempt = outgoingFileAttemptByPayloadId[payloadId] ?: return
        outgoingFileAttemptByPayloadId[payloadId] = attempt.copy(fileSent = true)
        tryQueueOutgoingMetadata(payloadId)
    }

    private fun tryQueueOutgoingMetadata(payloadId: Long) {
        val attempt = outgoingFileAttemptByPayloadId[payloadId]?.takeIf { it.fileSent } ?: return
        if (!send(attempt.metadata)) {
            scheduleOutgoingRetry(attempt.key)
            return
        }
        completeOutgoingAttempt(payloadId)
    }

    private fun completeOutgoingAttempt(payloadId: Long) {
        val attempt = removeOutgoingAttempt(payloadId) ?: return
        cancelOutgoingRetry(attempt.key)
        outgoingRetryCountByKey.remove(attempt.key)
        when (val key = attempt.key) {
            is OutgoingFileKey.Asset -> {
                pendingAssetTransfers.remove(key.transfer)
                if (key.transfer.kind == AssetKind.THUMBNAIL) {
                    thumbnailOfferedIds += key.transfer.assetId
                    captureById[key.transfer.assetId]?.thumbnailFile?.let { sentFile ->
                        fileExecutor.execute { sentFile.delete() }
                    }
                }
                if (key.transfer.kind != AssetKind.THUMBNAIL) {
                    eventLabel.text = when (key.transfer.kind) {
                        AssetKind.BOOK_CALIBRATION -> "책 영역 설정 사진을 선생님께 전송했습니다"
                        AssetKind.BOOK_ROI -> "${requestedDetailZoomRatio}배 고화질 책 영역을 선생님께 전송했습니다"
                        else -> "카메라 비교 사진을 선생님께 전송했습니다"
                    }
                }
            }

            is OutgoingFileKey.Voice -> {
                val recorded = pendingOutgoingVoiceMessages.remove(key.messageId) ?: return
                fileExecutor.execute { recorded.file.delete() }
                eventLabel.text = "음성 메시지를 선생님께 전송했습니다"
            }
        }
    }

    private fun handleOutgoingFileFailure(payloadId: Long, detail: String) {
        val attempt = removeOutgoingAttempt(payloadId) ?: return
        eventLabel.text = "파일 전송 실패 · $detail"
        scheduleOutgoingRetry(attempt.key)
    }

    private fun removeOutgoingAttempt(payloadId: Long): OutgoingFileAttempt? {
        val attempt = outgoingFileAttemptByPayloadId.remove(payloadId) ?: return null
        if (outgoingPayloadIdByKey[attempt.key] == payloadId) {
            outgoingPayloadIdByKey.remove(attempt.key)
        }
        return attempt
    }

    private fun scheduleOutgoingRetry(key: OutgoingFileKey) {
        if (!connected || activityDestroyed || key in outgoingRetryRunnableByKey) return
        val activePayloadId = outgoingPayloadIdByKey[key]
        if (activePayloadId != null && outgoingFileAttemptByPayloadId[activePayloadId]?.fileSent != true) return
        val retryCount = outgoingRetryCountByKey[key] ?: 0
        if (retryCount >= MAX_OUTGOING_FILE_RETRIES) {
            eventLabel.text = "파일 전송 재시도를 중단했습니다 · 재연결 시 다시 전송합니다"
            return
        }
        outgoingRetryCountByKey[key] = retryCount + 1
        val retry = Runnable {
            outgoingRetryRunnableByKey.remove(key)
            if (!connected || activityDestroyed) return@Runnable
            val payloadId = outgoingPayloadIdByKey[key]
            if (payloadId != null) {
                if (outgoingFileAttemptByPayloadId[payloadId]?.fileSent == true) {
                    tryQueueOutgoingMetadata(payloadId)
                }
                return@Runnable
            }
            when (key) {
                is OutgoingFileKey.Asset -> {
                    if (key.transfer in pendingAssetTransfers) trySendPendingAsset(key.transfer)
                }

                is OutgoingFileKey.Voice -> {
                    pendingOutgoingVoiceMessages[key.messageId]?.let { recorded ->
                        trySendPendingVoiceMessage(key.messageId, recorded)
                    }
                }
            }
        }
        outgoingRetryRunnableByKey[key] = retry
        handler.postDelayed(retry, OUTGOING_FILE_RETRY_DELAY_MS)
    }

    private fun cancelOutgoingRetry(key: OutgoingFileKey) {
        outgoingRetryRunnableByKey.remove(key)?.let(handler::removeCallbacks)
    }

    private fun clearOutgoingInflightForDisconnect() {
        outgoingFileAttemptByPayloadId
            .filterValues { !it.fileSent }
            .keys
            .toList()
            .forEach(::removeOutgoingAttempt)
        outgoingRetryRunnableByKey.values.forEach(handler::removeCallbacks)
        outgoingRetryRunnableByKey.clear()
    }

    private fun resetOutgoingRetriesForReconnect() {
        outgoingRetryCountByKey.clear()
        outgoingRetryRunnableByKey.values.forEach(handler::removeCallbacks)
        outgoingRetryRunnableByKey.clear()
        outgoingFileAttemptByPayloadId
            .filterValues { it.fileSent }
            .keys
            .toList()
            .forEach(::tryQueueOutgoingMetadata)
    }

    private fun pruneLocalAssets() {
        while (captureById.size > MAX_LOCAL_ASSETS) {
            val protectedAssetIds = buildSet {
                pendingAssetTransfers.forEach { add(it.assetId) }
                outgoingFileAttemptByPayloadId.values.forEach { attempt ->
                    val key = attempt.key
                    if (key is OutgoingFileKey.Asset) add(key.transfer.assetId)
                }
            }
            val oldestId = captureById.keys.firstOrNull { it !in protectedAssetIds } ?: break
            val oldest = captureById.remove(oldestId) ?: continue
            thumbnailOfferedIds.remove(oldestId)
            oldest.thumbnailFile.delete()
            oldest.bookCalibrationFile?.delete()
            oldest.bookRoiFile.delete()
        }
    }

    private fun studentStart() {
        if (!cameraReady) {
            eventLabel.text = "카메라를 준비하는 중입니다"
            return
        }
        if (session.snapshot().status != SessionStatus.READY) {
            eventLabel.text = "이미 학습 세션이 시작됐습니다"
            return
        }
        val snapshot = session.dispatch(
            StartRequested(
                commandId = UUID.randomUUID().toString(),
                origin = StartOrigin.STUDENT,
                atElapsedMs = SystemClock.elapsedRealtime(),
            ),
        )
        val advanced = session.dispatch(Tick(SystemClock.elapsedRealtime()))
        eventLabel.text = if (advanced.phase == SessionPhase.STUDY) "공부를 시작합니다" else "명상을 시작합니다"
        renderSession(advanced)
        publishSnapshotIfChanged(advanced, force = true)
    }

    private fun completeProblem() {
        val eventId = UUID.randomUUID().toString()
        val before = session.snapshot().completedProblemCount
        val snapshot = session.dispatch(ProblemCompleted(eventId, SystemClock.elapsedRealtime()))
        if (snapshot.completedProblemCount == before) {
            eventLabel.text = "문제 완료는 공부 시간에 기록할 수 있어요"
            return
        }
        latestProblemEventId = eventId
        countLabel.text = "푼 문제 ${snapshot.completedProblemCount}개"
        eventLabel.text = "문제 완료를 선생님께 알렸습니다"
        undoButton.visibility = View.VISIBLE
        handler.postDelayed({
            if (latestProblemEventId == eventId) {
                undoButton.visibility = View.GONE
                latestProblemEventId = null
            }
        }, UNDO_WINDOW_MS)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 55)
        send(
            StudyMessage.ProblemCompleted(
                messageId = UUID.randomUUID().toString(),
                eventId = eventId,
                totalCount = snapshot.completedProblemCount,
            ),
        )
        publishSnapshotIfChanged(snapshot, force = true)
        vibrate(70)
    }

    private fun undoProblem() {
        val eventId = latestProblemEventId ?: return
        val snapshot = session.dispatch(UndoProblem(eventId, SystemClock.elapsedRealtime()))
        latestProblemEventId = null
        undoButton.visibility = View.GONE
        eventLabel.text = "방금 문제 완료 기록을 취소했습니다"
        renderSession(snapshot)
        publishSnapshotIfChanged(snapshot, force = true)
    }

    private fun renderSession(snapshot: SessionSnapshot) {
        phaseLabel.text = when (snapshot.status) {
            SessionStatus.READY -> "바로 시작 가능"
            SessionStatus.START_COUNTDOWN -> "곧 시작합니다"
            SessionStatus.PAUSED -> "잠시 멈춤"
            SessionStatus.COMPLETED -> "오늘 공부 완료"
            SessionStatus.RUNNING -> when (snapshot.phase) {
                SessionPhase.MEDITATION -> "명상 시간"
                SessionPhase.STUDY -> "공부 시간"
                SessionPhase.BREAK -> "쉬는 시간"
                SessionPhase.COMPLETE -> "오늘 공부 완료"
            }
        }
        val remaining = if (snapshot.status == SessionStatus.START_COUNTDOWN) {
            snapshot.countdownRemainingMs
        } else {
            snapshot.phaseRemainingMs
        }
        timerLabel.text = formatDuration(remaining)
        countLabel.text = "푼 문제 ${snapshot.completedProblemCount}개"
        problemButton.isEnabled = snapshot.status == SessionStatus.RUNNING && snapshot.phase == SessionPhase.STUDY
        startButton.isEnabled = cameraReady && snapshot.status == SessionStatus.READY

        val phaseChanged = lastRenderedPhase != null && lastRenderedPhase != snapshot.phase
        val meditationStarted = snapshot.status == SessionStatus.RUNNING &&
            snapshot.phase == SessionPhase.MEDITATION &&
            (lastRenderedStatus == SessionStatus.READY ||
                lastRenderedStatus == SessionStatus.START_COUNTDOWN)
        if (phaseChanged || meditationStarted) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
            vibrate(250)
        }
        lastRenderedPhase = snapshot.phase
        lastRenderedStatus = snapshot.status
    }

    private fun publishSnapshotIfChanged(snapshot: SessionSnapshot, force: Boolean = false) {
        val remaining = if (snapshot.status == SessionStatus.START_COUNTDOWN) {
            snapshot.countdownRemainingMs
        } else {
            snapshot.phaseRemainingMs
        }
        val key = "${snapshot.status}:${snapshot.phase}:${remaining / 1_000}:${snapshot.completedProblemCount}"
        if (!force && key == lastPublishedKey) return
        lastPublishedKey = key
        revision += 1
        send(
            StudyMessage.SessionSnapshot(
                messageId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                status = snapshot.status.toWire(),
                phase = snapshot.phase.toWire(),
                remainingMs = remaining,
                completedProblems = snapshot.completedProblemCount,
                revision = revision,
            ),
            coalesceKey = SESSION_SNAPSHOT_KEY,
        )
    }

    private fun send(message: StudyMessage, coalesceKey: String? = null): Boolean =
        reliableChannel.send(message, SystemClock.elapsedRealtime(), coalesceKey)

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraView = BookCameraView(this).apply { setGuideVisible(false) }
        root.addView(cameraView, FrameLayout.LayoutParams(-1, -1))

        connectionPill = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(13), dp(8))
        }
        setConnectionState("연결 준비 중", COLOR_MUTED)
        root.addView(
            connectionPill,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                marginEnd = dp(16)
            },
        )

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xDFFFFFFF.toInt(), 15f)
        }
        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        phaseLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(COLOR_MUTED)
            setTypeface(typeface, Typeface.BOLD)
        }
        countLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_MUTED)
        }
        labels.addView(phaseLabel)
        labels.addView(countLabel)
        timerLabel = TextView(this).apply {
            textSize = 38f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.END
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        timerRow.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        timerRow.addView(timerLabel)
        statusPanel.addView(timerRow)

        eventLabel = TextView(this).apply {
            text = "카메라 준비 후 음성으로 시작할 수 있습니다"
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }
        statusPanel.addView(eventLabel, matchWrap(top = 4))
        root.addView(
            statusPanel,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                marginStart = dp(12); marginEnd = dp(12); topMargin = dp(68)
            },
        )

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        pairingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFFFFF6D8.toInt(), 12f)
            visibility = View.GONE
        }
        pairingDigits = TextView(this).apply {
            textSize = 23f
            setTextColor(COLOR_TEXT)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        pairingPanel.addView(pairingDigits, LinearLayout.LayoutParams(0, -2, 1f))
        pairingPanel.addView(actionButton("승인", COLOR_PRIMARY).apply {
            setOnClickListener { pendingEndpointId?.let(transport::approve) }
        }, LinearLayout.LayoutParams(dp(88), dp(48)))
        bottomPanel.addView(pairingPanel, matchWrap(bottom = 6))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        calibrateButton = actionButton("책 영역은 선생님폰에서 설정", COLOR_SECONDARY).apply {
            isEnabled = false
        }
        startButton = actionButton("공부 시작", COLOR_PRIMARY).apply {
            isEnabled = false
            setOnClickListener { studentStart() }
        }
        problemButton = actionButton("문제 풀었어", COLOR_SUCCESS).apply {
            isEnabled = false
            setOnClickListener { completeProblem() }
        }
        undoButton = actionButton("취소", COLOR_MUTED).apply {
            visibility = View.GONE
            setOnClickListener { undoProblem() }
        }
        voiceMessageButton = actionButton("선생님께 음성 메시지", COLOR_PRIMARY).apply {
            setOnClickListener { toggleVoiceMessageRecording() }
        }
        listenReplyButton = actionButton("선생님 답변 듣기", COLOR_SECONDARY).apply {
            visibility = View.GONE
            setOnClickListener { playLatestTeacherReply() }
        }
        listOf(startButton, problemButton, undoButton, voiceMessageButton, listenReplyButton)
            .forEach { button ->
                actionRow.addView(button, LinearLayout.LayoutParams(dp(126), dp(48)).apply {
                    marginEnd = dp(6)
                })
            }
        val actionScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionRow, FrameLayout.LayoutParams(-2, dp(48)))
        }
        val menuLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        menuLine.addView(actionScroller, LinearLayout.LayoutParams(0, dp(48), 0f))
        val menuToggle = actionButton("⋮", COLOR_MUTED).apply {
            textSize = 22f
            contentDescription = "메뉴 열기"
            setOnClickListener {
                val opening = actionRow.visibility != View.VISIBLE
                actionRow.visibility = if (opening) View.VISIBLE else View.GONE
                actionScroller.layoutParams = LinearLayout.LayoutParams(0, dp(48), if (opening) 1f else 0f)
                text = if (opening) "×" else "⋮"
                contentDescription = if (opening) "메뉴 닫기" else "메뉴 열기"
            }
        }
        menuLine.addView(menuToggle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(5) })
        bottomPanel.addView(menuLine, LinearLayout.LayoutParams(-1, dp(48)))

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(12)
            },
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (connectionPill.layoutParams as FrameLayout.LayoutParams).let { params ->
                params.topMargin = systemBars.top + dp(16)
                connectionPill.layoutParams = params
            }
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).let { params ->
                params.bottomMargin = systemBars.bottom + dp(12)
                bottomPanel.layoutParams = params
            }
            (statusPanel.layoutParams as FrameLayout.LayoutParams).let { params ->
                params.topMargin = systemBars.top + dp(62)
                statusPanel.layoutParams = params
            }
            insets
        }
        return root
    }

    private fun setConnectionState(text: String, color: Int) {
        connectionPill.text = text
        connectionPill.setTextColor(Color.WHITE)
        connectionPill.background = rounded(withAlpha(color, 220), 24f)
    }

    private fun SessionStatus.toWire(): WireSessionStatus = when (this) {
        SessionStatus.READY -> WireSessionStatus.READY
        SessionStatus.START_COUNTDOWN -> WireSessionStatus.START_COUNTDOWN
        SessionStatus.RUNNING -> WireSessionStatus.RUNNING
        SessionStatus.PAUSED -> WireSessionStatus.PAUSED
        SessionStatus.COMPLETED -> WireSessionStatus.COMPLETED
    }

    private fun SessionPhase.toWire(): WireSessionPhase = when (this) {
        SessionPhase.MEDITATION -> WireSessionPhase.MEDITATION
        SessionPhase.STUDY -> WireSessionPhase.STUDY
        SessionPhase.BREAK -> WireSessionPhase.BREAK
        SessionPhase.COMPLETE -> WireSessionPhase.COMPLETE
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) + 999) / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun vibrate(durationMs: Long) {
        getSystemService(Vibrator::class.java).vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    }

    private fun actionButton(label: String, color: Int) = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(0x88FFFFFF.toInt(), Color.WHITE),
            ),
        )
        isAllCaps = false
        background = statefulRounded(color, 13f)
        minHeight = dp(48)
    }

    private fun statefulRounded(color: Int, radiusDp: Float) = StateListDrawable().apply {
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            rounded(withAlpha(color, 90), radiusDp),
        )
        addState(intArrayOf(), rounded(color, radiusDp))
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(color)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun matchWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun weightWrap(weight: Float, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, dp(52), weight).apply {
            marginStart = dp(start)
            marginEnd = dp(end)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class AssetTransferKey(
        val assetId: String,
        val kind: AssetKind,
    )

    private sealed interface OutgoingFileKey {
        data class Asset(val transfer: AssetTransferKey) : OutgoingFileKey

        data class Voice(val messageId: String) : OutgoingFileKey
    }

    private data class OutgoingFileAttempt(
        val key: OutgoingFileKey,
        val metadata: StudyMessage,
        val fileSent: Boolean,
    )

    private fun WireDetailCaptureMode.toCameraMode(): CameraDetailCaptureMode = when (this) {
        WireDetailCaptureMode.STANDARD_12_MP -> CameraDetailCaptureMode.STANDARD_12_MP
        WireDetailCaptureMode.ULTRA_50_MP -> CameraDetailCaptureMode.ULTRA_50_MP
    }

    private fun CameraDetailCaptureMode.toWireMode(): WireDetailCaptureMode = when (this) {
        CameraDetailCaptureMode.STANDARD_12_MP -> WireDetailCaptureMode.STANDARD_12_MP
        CameraDetailCaptureMode.ULTRA_50_MP -> WireDetailCaptureMode.ULTRA_50_MP
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 401
        private const val STATE_BOOK_LEFT = "book-left"
        private const val STATE_BOOK_TOP = "book-top"
        private const val STATE_BOOK_RIGHT = "book-right"
        private const val STATE_BOOK_BOTTOM = "book-bottom"
        private const val DEFAULT_DETAIL_ZOOM_RATIO = 2f
        private const val DEFAULT_FOCUS_TIMEOUT_MS = 2_000L
        private const val TICK_INTERVAL_MS = 250L
        private const val UNDO_WINDOW_MS = 5_000L
        private const val DEFAULT_CAPTURE_INTERVAL_MS = 10_000L
        private const val CAMERA_BIND_RETRY_DELAY_MS = 2_000L
        private const val MAX_CAMERA_BIND_RETRIES = 3
        private const val MAX_LOCAL_ASSETS = 300
        private const val MAX_PENDING_INCOMING_FILES = 64
        private const val MAX_RECEIVED_VOICE_MESSAGES = 20
        private const val MAX_STORED_OUTGOING_VOICE_MESSAGES = 20
        private const val MAX_VOICE_DURATION_MS = 60_000L
        private const val VOICE_DICTATION_DURATION_MS = 12_000L
        private const val MAX_OUTGOING_FILE_RETRIES = 3
        private const val OUTGOING_FILE_RETRY_DELAY_MS = 2_000L
        private const val SESSION_SNAPSHOT_KEY = "session-snapshot"
        private const val COLOR_TEXT = 0xFF172033.toInt()
        private const val COLOR_MUTED = 0xFF65708A.toInt()
        private const val COLOR_PRIMARY = 0xFF3457D5.toInt()
        private const val COLOR_SECONDARY = 0xFF6F7A91.toInt()
        private const val COLOR_SUCCESS = 0xFF16885B.toInt()
        private const val COLOR_WARNING = 0xFF9A6A00.toInt()
        private const val COLOR_DANGER = 0xFFB33A3A.toInt()
    }
}
