package io.remotestudy.teacher

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.remotestudy.protocol.AlertKind
import io.remotestudy.protocol.AssetKind
import io.remotestudy.protocol.DetailCaptureMode
import io.remotestudy.protocol.PeerRole
import io.remotestudy.protocol.StudyMessage
import io.remotestudy.protocol.WireSessionPhase
import io.remotestudy.protocol.WireSessionStatus
import io.remotestudy.protocol.WireStartOrigin
import io.remotestudy.transport.TransportEvent
import io.remotestudy.transport.TransportRole
import io.remotestudy.transport.nearby.NearbyPermissionSet
import io.remotestudy.transport.nearby.NearbyStudyTransport
import io.remotestudy.sync.ReliableMessageChannel
import io.remotestudy.voicemessage.RecordedVoiceMessage
import io.remotestudy.voicemessage.VoiceMessagePlayer
import io.remotestudy.voicemessage.VoiceMessageRecorder
import io.remotestudy.voicemessage.VoiceMessageRecorderState
import java.io.File
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class TeacherActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val retryTicker = object : Runnable {
        override fun run() {
            reliableChannel.retryDue(SystemClock.elapsedRealtime())
            handler.postDelayed(this, 1_000L)
        }
    }
    private lateinit var transport: NearbyStudyTransport
    private lateinit var reliableChannel: ReliableMessageChannel
    private lateinit var voiceRecorder: VoiceMessageRecorder
    private lateinit var voicePlayer: VoiceMessagePlayer
    private lateinit var connectionPill: TextView
    private lateinit var phaseLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var problemLabel: TextView
    private lateinit var eventLabel: TextView
    private lateinit var conversationButton: Button
    private lateinit var pairingPanel: LinearLayout
    private lateinit var pairingDigits: TextView
    private lateinit var approveButton: Button
    private lateinit var rejectButton: Button
    private lateinit var startButton: Button
    private lateinit var latestThumbnail: ImageView
    private lateinit var thumbnailLabel: TextView
    private lateinit var thumbnailScroller: HorizontalScrollView
    private lateinit var thumbnailStrip: GridLayout
    private lateinit var bookRegionOverlay: TeacherBookRegionOverlay
    private lateinit var bookRegionButton: Button
    private lateinit var cameraCompareButton: Button
    private lateinit var studentVoiceButton: Button
    private lateinit var textReplyButton: Button
    private lateinit var voiceReplyButton: Button

    private var pendingEndpointId: String? = null
    private var transportStarted = false
    private var transportConnected = false
    private var activityDestroyed = false
    private var latestSessionId: String? = null
    private var latestSessionRevision = -1L
    private var latestStudentVoiceFile: File? = null
    private val transferByPayloadId = linkedMapOf<Long, StudyMessage.AssetTransfer>()
    private val voiceTransferByPayloadId = linkedMapOf<Long, StudyMessage.VoiceTransfer>()
    private val earlyFileUriByPayloadId = linkedMapOf<Long, String>()
    private val pendingOutgoingVoiceByUserMessageId = linkedMapOf<String, PendingOutgoingVoice>()
    private val outgoingVoiceAttemptByPayloadId = mutableMapOf<Long, OutgoingVoiceAttempt>()
    private val outgoingVoicePayloadIdByUserMessageId = mutableMapOf<String, Long>()
    private val outgoingVoiceRetryCountByUserMessageId = mutableMapOf<String, Int>()
    private val outgoingVoiceRetryRunnableByUserMessageId = mutableMapOf<String, Runnable>()
    private val recentThumbnails = ArrayDeque<RecentThumbnail>()
    private var latestThumbnailBitmap: Bitmap? = null
    private val conversationHistory = ArrayDeque<ConversationEntry>()
    private var conversationDialog: AlertDialog? = null
    private var conversationList: LinearLayout? = null
    private var conversationScroll: ScrollView? = null
    private val pendingRoiAssetIds = mutableSetOf<String>()
    private var roiDialog: AlertDialog? = null
    private var fullScreenPhotoDialog: Dialog? = null
    private var bookCalibrationDialog: Dialog? = null
    private var pendingCalibrationAssetId: String? = null
    private var cameraComparisonDialog: Dialog? = null
    private val pendingComparisonAssetIds = mutableMapOf<AssetKind, String>()
    private val cameraComparisonBitmaps = linkedMapOf<AssetKind, Bitmap>()
    private var studySettings = TeacherStudySettings()
    private var detailCaptureMode = DetailCaptureMode.STANDARD_12_MP
    private var detailZoomRatio = DEFAULT_DETAIL_ZOOM_RATIO
    private var focusTimeoutMs = DEFAULT_FOCUS_TIMEOUT_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        studySettings = loadStudySettings()
        detailCaptureMode = loadDetailCaptureMode()
        detailZoomRatio = loadDetailZoomRatio()
        focusTimeoutMs = loadFocusTimeoutMs()
        setContentView(buildContentView())

        voicePlayer = VoiceMessagePlayer(this)
        voiceRecorder = VoiceMessageRecorder(
            this,
            object : VoiceMessageRecorder.Listener {
                override fun onAutoStopped(message: RecordedVoiceMessage) {
                    voiceReplyButton.text = "음성 답장"
                    eventLabel.text = "60초 녹음 완료 · 음성 답장 전송 중"
                    queueRecordedVoice(message)
                }

                override fun onRecordingError(error: Throwable) {
                    voiceReplyButton.text = "음성 답장"
                    eventLabel.text = "음성 녹음 실패: ${error.message.orEmpty()}"
                }
            },
        )

        transport = NearbyStudyTransport(this).also { nearby ->
            nearby.setListener { event -> runOnUiThread { handleTransportEvent(event) } }
        }
        reliableChannel = ReliableMessageChannel(transmitter = transport::send)
        pruneOutgoingVoiceCache()
        requestPermissionsAndStart()
        handler.post(retryTicker)
    }

    override fun onStop() {
        if (::voiceRecorder.isInitialized && voiceRecorder.state == VoiceMessageRecorderState.RECORDING) {
            voiceRecorder.cancel()
            voiceReplyButton.text = "음성 답장"
            eventLabel.text = "화면을 벗어나 음성 답장 녹음을 취소했습니다"
        }
        if (::voicePlayer.isInitialized) voicePlayer.stop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        activityDestroyed = true
        handler.removeCallbacksAndMessages(null)
        roiDialog?.dismiss()
        roiDialog = null
        bookCalibrationDialog?.dismiss()
        bookCalibrationDialog = null
        cameraComparisonDialog?.dismiss()
        cameraComparisonDialog = null
        clearCameraComparisonBitmaps()
        fullScreenPhotoDialog?.dismiss()
        fullScreenPhotoDialog = null
        latestThumbnail.setImageDrawable(null)
        latestThumbnailBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        latestThumbnailBitmap = null
        recentThumbnails.forEach { entry ->
            entry.imageView.setImageDrawable(null)
            if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
        }
        recentThumbnails.clear()
        voiceRecorder.close()
        voicePlayer.close()
        transport.setListener(null)
        transport.stop()
        fileExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                if (nearbyPermissionsGranted()) startTransport()
                else setConnectionState("근거리 권한 필요", COLOR_WARNING)
                if (!recordAudioGranted()) {
                    eventLabel.text = "마이크 권한 없이도 텍스트 답장과 근거리 연결은 사용할 수 있습니다"
                }
            }

            REQUEST_RECORD_AUDIO -> {
                if (recordAudioGranted()) startVoiceRecording()
                else eventLabel.text = "마이크 권한이 없어 음성 답장만 사용할 수 없습니다"
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val required = NearbyPermissionSet.requiredForCurrentDevice().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        required += Manifest.permission.RECORD_AUDIO
        val missing = required.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startTransport()
        else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun nearbyPermissionsGranted(): Boolean =
        NearbyPermissionSet.requiredForCurrentDevice().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun recordAudioGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startTransport() {
        if (transportStarted) return
        transportStarted = true
        transport.start(TransportRole.TEACHER, "선생님폰")
    }

    private fun handleTransportEvent(event: TransportEvent) {
        if (activityDestroyed) return
        when (event) {
            TransportEvent.Searching -> setConnectionState("학생 검색 대기", COLOR_WARNING)
            is TransportEvent.EndpointFound -> Unit
            is TransportEvent.EndpointLost -> Unit
            is TransportEvent.PairingRequested -> {
                pendingEndpointId = event.endpointId
                pairingPanel.visibility = View.GONE
                setConnectionState("자동 연결 중", COLOR_WARNING)
                transport.approve(event.endpointId)
            }

            is TransportEvent.Connected -> {
                transportConnected = true
                reliableChannel.setConnected(true, SystemClock.elapsedRealtime())
                resetOutgoingVoiceRetriesForReconnect()
                flushPendingVoiceMessages()
                pairingPanel.visibility = View.GONE
                setConnectionState("${event.displayName} 연결됨", COLOR_SUCCESS)
                startButton.isEnabled = true
                eventLabel.text = "연결 완료 · 학생의 배치 완료를 기다리는 중"
                reliableChannel.send(
                    studySettings.toMessage(UUID.randomUUID().toString()),
                    SystemClock.elapsedRealtime(),
                    "study-settings",
                )
                sendCurrentBookProfile()
            }

            is TransportEvent.Disconnected -> {
                transportConnected = false
                reliableChannel.setConnected(false, SystemClock.elapsedRealtime())
                clearOutgoingVoiceInflightForDisconnect()
                startButton.isEnabled = false
                setConnectionState("연결 끊김", COLOR_DANGER)
                eventLabel.text = "상태를 확인할 수 없음 · 자리 비움으로 판정하지 않음"
                transportStarted = false
                startTransport()
            }

            is TransportEvent.MessageReceived -> receiveMessage(event.bytes)
            is TransportEvent.FileReceived -> receiveFile(event)
            is TransportEvent.FileSent -> handleOutgoingVoiceFileSent(event.payloadId)
            is TransportEvent.FileSendFailed -> handleOutgoingVoiceFileFailure(event.payloadId, event.detail)
            is TransportEvent.Failure -> {
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
            is StudyMessage.SessionSnapshot -> {
                val currentSessionId = latestSessionId
                if (currentSessionId == message.sessionId && message.revision <= latestSessionRevision) return
                if (currentSessionId != message.sessionId) latestSessionId = message.sessionId
                latestSessionRevision = message.revision
                phaseLabel.text = phaseText(message.phase, message.status)
                timerLabel.text = formatDuration(message.remainingMs)
                problemLabel.text = "완료한 문제 ${message.completedProblems}개"
                eventLabel.text = "학생 상태 갱신 · revision ${message.revision}"
                startButton.isEnabled = message.status == WireSessionStatus.READY
            }

            is StudyMessage.ProblemCompleted -> {
                problemLabel.text = "완료한 문제 ${message.totalCount}개"
                eventLabel.text = "방금 문제를 풀었다고 알렸어요"
                addConversation(
                    sender = "학생",
                    content = "풀었어 · 완료한 문제 ${message.totalCount}개",
                    sentAtEpochMs = System.currentTimeMillis(),
                )
                notifyProblemCompleted(message.totalCount)
            }

            is StudyMessage.Hello -> eventLabel.text = "${message.deviceName}과 프로토콜 연결 완료"
            is StudyMessage.Alert -> showAlert(message)
            is StudyMessage.AssetTransfer -> registerAssetTransfer(message)
            is StudyMessage.TextMessage -> showTextMessage(message)
            is StudyMessage.VoiceTransfer -> registerVoiceTransfer(message)
            is StudyMessage.StudySettings -> Unit
            is StudyMessage.BookRegionSettings -> Unit
            is StudyMessage.CameraProfileStatus -> showCameraProfileStatus(message)
            is StudyMessage.AssetRequest -> Unit
            is StudyMessage.Ack -> Unit
            is StudyMessage.StartRequest -> Unit
        }
    }

    private fun registerAssetTransfer(message: StudyMessage.AssetTransfer) {
        transferByPayloadId[message.payloadId] = message
        earlyFileUriByPayloadId.remove(message.payloadId)?.let { uri ->
            materializeAsset(message, uri)
        }
        trimIncomingFileMaps()
    }

    private fun receiveFile(event: TransportEvent.FileReceived) {
        transferByPayloadId[event.payloadId]?.let { metadata ->
            materializeAsset(metadata, event.uri)
            return
        }
        voiceTransferByPayloadId[event.payloadId]?.let { metadata ->
            materializeVoice(metadata, event.uri)
            return
        }
        earlyFileUriByPayloadId[event.payloadId] = event.uri
        trimIncomingFileMaps()
    }

    private fun materializeAsset(metadata: StudyMessage.AssetTransfer, sourceUri: String) {
        transferByPayloadId.remove(metadata.payloadId)
        fileExecutor.execute {
            val result = runCatching {
                val directory = File(cacheDir, "received-assets").apply { mkdirs() }
                val target = File(
                    directory,
                    "${metadata.payloadId}-${metadata.kind.name.lowercase()}.jpg",
                )
                val input = checkNotNull(contentResolver.openInputStream(Uri.parse(sourceUri))) {
                    "수신 파일을 열 수 없습니다"
                }
                input.use { source ->
                    target.outputStream().use { destination -> source.copyTo(destination) }
                }
                pruneReceivedAssets(directory)
                val decoded = checkNotNull(
                    BitmapFactory.decodeFile(
                        target.absolutePath,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
                    ),
                )
                target to if (metadata.kind == AssetKind.THUMBNAIL) decoded else rotateBitmap180(decoded)
            }
            runOnUiThread {
                if (activityDestroyed || isDestroyed) {
                    result.getOrNull()?.second?.recycle()
                    return@runOnUiThread
                }
                result.onSuccess { (file, bitmap) -> showReceivedAsset(metadata, file, bitmap) }
                    .onFailure { eventLabel.text = "사진 저장 실패: ${it.message.orEmpty()}" }
            }
        }
    }

    private fun showReceivedAsset(
        metadata: StudyMessage.AssetTransfer,
        file: File,
        bitmap: Bitmap,
    ) {
        when (metadata.kind) {
            AssetKind.THUMBNAIL -> {
                val previousMainBitmap = latestThumbnailBitmap
                latestThumbnail.setImageBitmap(bitmap)
                latestThumbnailBitmap = bitmap
                bookRegionOverlay.setImageSize(bitmap.width, bitmap.height)
                latestThumbnail.contentDescription =
                    "가장 최근 학습 썸네일, 촬영 ${formatCapturedAt(metadata.capturedAtEpochMs)}, 고화질 요청"
                latestThumbnail.setOnClickListener { requestBookRoi(metadata.assetId) }
                addRecentThumbnail(metadata, bitmap)
                if (previousMainBitmap != null && previousMainBitmap !== bitmap && !previousMainBitmap.isRecycled) {
                    previousMainBitmap.recycle()
                }
            }

            AssetKind.BOOK_ROI -> {
                pendingRoiAssetIds.remove(metadata.assetId)
                thumbnailLabel.text = "고화질 책 영역 수신 완료"
                showFullScreenPhoto(bitmap, "${file.name} 책 영역")
            }

            AssetKind.BOOK_CALIBRATION -> {
                if (pendingCalibrationAssetId != metadata.assetId) {
                    bitmap.recycle()
                    return
                }
                pendingCalibrationAssetId = null
                thumbnailLabel.text = "실제 ${detailZoomRatio}배 사진에서 책 영역을 지정해 주세요"
                showBookCalibrationEditor(bitmap)
            }

            AssetKind.CAMERA_COMPARE_1X,
            AssetKind.CAMERA_COMPARE_2X,
            AssetKind.CAMERA_COMPARE_3X,
            -> receiveCameraComparison(metadata, bitmap)
        }
    }

    private fun showFullScreenPhoto(bitmap: Bitmap, description: String) {
        fullScreenPhotoDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ZoomableImageView(this).apply {
            setImageBitmap(bitmap)
            contentDescription = description
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val close = Button(this).apply {
            text = "×"
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "사진 닫기"
            setOnClickListener { dialog.dismiss() }
        }
        frame.addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
            if (fullScreenPhotoDialog === dialog) fullScreenPhotoDialog = null
        }
        fullScreenPhotoDialog = dialog
        dialog.show()
    }

    private fun addRecentThumbnail(metadata: StudyMessage.AssetTransfer, bitmap: Bitmap) {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scale = (RECENT_THUMBNAIL_LONG_EDGE.toFloat() / longest).coerceAtMost(1f)
        val recentBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap.copy(bitmap.config ?: Bitmap.Config.RGB_565, false)
        }
        val image = ImageView(this).apply {
            setImageBitmap(recentBitmap)
            setBackgroundColor(0xFFE7EAF1.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
        }
        val entry = RecentThumbnail(
            assetId = metadata.assetId,
            capturedAtEpochMs = metadata.capturedAtEpochMs,
            imageView = image,
            bitmap = recentBitmap,
        )
        image.setOnClickListener { requestBookRoi(entry.assetId) }
        recentThumbnails.addFirst(entry)
        while (recentThumbnails.size > MAX_RECENT_THUMBNAILS) {
            val oldest = recentThumbnails.removeLast()
            oldest.imageView.setImageDrawable(null)
            oldest.imageView.setOnClickListener(null)
            if (!oldest.bitmap.isRecycled) oldest.bitmap.recycle()
        }
        rebuildThumbnailGrid()
        updateThumbnailDescriptions()
        thumbnailScroller.visibility = View.VISIBLE
        thumbnailScroller.post { thumbnailScroller.scrollTo(0, 0) }
    }

    private fun rebuildThumbnailGrid() {
        thumbnailStrip.removeAllViews()
        recentThumbnails.forEach { entry ->
            (entry.imageView.parent as? android.view.ViewGroup)?.removeView(entry.imageView)
            thumbnailStrip.addView(
                entry.imageView,
                GridLayout.LayoutParams().apply {
                    width = dp(92)
                    height = dp(68)
                    setMargins(0, 0, dp(6), dp(6))
                },
            )
        }
    }

    private fun updateThumbnailDescriptions() {
        recentThumbnails.forEachIndexed { index, entry ->
            entry.imageView.contentDescription =
                "최근 ${index + 1}번째 학습 썸네일, 촬영 ${formatCapturedAt(entry.capturedAtEpochMs)}, " +
                "눌러서 책 영역 고화질 요청"
        }
    }

    private fun formatCapturedAt(capturedAtEpochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(capturedAtEpochMs))

    private fun rotateBitmap180(source: Bitmap): Bitmap {
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(180f) },
            true,
        )
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun showTextMessage(message: StudyMessage.TextMessage) {
        val sender = if (message.sender == PeerRole.STUDENT) "학생" else "선생님"
        eventLabel.text = "$sender 텍스트: ${message.text.take(MAX_STATUS_TEXT_CHARS)}"
        addConversation(sender, message.text, message.sentAtEpochMs)
        if (message.sender == PeerRole.STUDENT) notifyNewStudentMessage(message.text)
    }

    private fun addConversation(
        sender: String,
        content: String,
        sentAtEpochMs: Long,
        voiceFile: File? = null,
    ) {
        conversationHistory.addLast(
            ConversationEntry(sender, content, sentAtEpochMs, voiceFile),
        )
        while (conversationHistory.size > MAX_CONVERSATION_ENTRIES) {
            conversationHistory.removeFirst()
        }
        if (::conversationButton.isInitialized) {
            conversationButton.text = "대화 ${conversationHistory.size}"
            conversationButton.contentDescription = "대화 기록 ${conversationHistory.size}개 열기"
        }
        renderConversationHistory()
    }

    private fun showConversationHistory() {
        conversationDialog?.dismiss()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        conversationList = list
        conversationScroll = scroll
        renderConversationHistory()
        conversationDialog = AlertDialog.Builder(this)
            .setTitle("대화 기록")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (conversationDialog === dialog) {
                        conversationDialog = null
                        conversationList = null
                        conversationScroll = null
                    }
                }
                dialog.show()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
    }

    private fun renderConversationHistory() {
        val list = conversationList ?: return
        list.removeAllViews()
        if (conversationHistory.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 주고받은 메시지가 없습니다"
                textSize = 14f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        } else {
            conversationHistory.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = rounded(
                        if (entry.sender == "선생님") 0xFFEAF0FF.toInt() else 0xFFEAF8F2.toInt(),
                        12f,
                    )
                }
                val whenText = DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(entry.sentAtEpochMs))
                row.addView(TextView(this).apply {
                    text = "$whenText  ${entry.sender}\n${entry.content}"
                    textSize = 14f
                    setTextColor(COLOR_TEXT)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                entry.voiceFile?.takeIf(File::isFile)?.let { file ->
                    row.addView(actionButton("듣기", COLOR_SUCCESS).apply {
                        setOnClickListener {
                            voicePlayer.play(file) { result ->
                                result.onFailure { error ->
                                    eventLabel.text = "음성 재생 실패: ${error.message.orEmpty()}"
                                }
                            }
                        }
                    }, LinearLayout.LayoutParams(dp(64), dp(38)).apply { marginStart = dp(8) })
                }
                list.addView(row, matchWrap(bottom = 7))
            }
        }
        conversationScroll?.post { conversationScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun registerVoiceTransfer(message: StudyMessage.VoiceTransfer) {
        voiceTransferByPayloadId[message.payloadId] = message
        earlyFileUriByPayloadId.remove(message.payloadId)?.let { uri ->
            materializeVoice(message, uri)
        }
        trimIncomingFileMaps()
    }

    private fun trimIncomingFileMaps() {
        while (transferByPayloadId.size > MAX_PENDING_INCOMING_FILES) {
            transferByPayloadId.remove(transferByPayloadId.keys.first())
        }
        while (voiceTransferByPayloadId.size > MAX_PENDING_INCOMING_FILES) {
            voiceTransferByPayloadId.remove(voiceTransferByPayloadId.keys.first())
        }
        while (earlyFileUriByPayloadId.size > MAX_PENDING_INCOMING_FILES) {
            earlyFileUriByPayloadId.remove(earlyFileUriByPayloadId.keys.first())
        }
    }

    private fun materializeVoice(metadata: StudyMessage.VoiceTransfer, sourceUri: String) {
        voiceTransferByPayloadId.remove(metadata.payloadId)
        fileExecutor.execute {
            val result = runCatching {
                val directory = File(cacheDir, "received-voice").apply { mkdirs() }
                val target = File(directory, "${metadata.payloadId}-voice.m4a")
                val input = checkNotNull(contentResolver.openInputStream(Uri.parse(sourceUri))) {
                    "수신 음성 파일을 열 수 없습니다"
                }
                input.use { source ->
                    target.outputStream().use { destination -> source.copyTo(destination) }
                }
                pruneReceivedVoiceMessages(directory)
                target
            }
            runOnUiThread {
                if (activityDestroyed || isDestroyed) return@runOnUiThread
                result.onSuccess { file -> showReceivedVoice(metadata, file) }
                    .onFailure { eventLabel.text = "음성 저장 실패: ${it.message.orEmpty()}" }
            }
        }
    }

    private fun showReceivedVoice(metadata: StudyMessage.VoiceTransfer, file: File) {
        if (metadata.sender != PeerRole.STUDENT) return
        latestStudentVoiceFile = file
        studentVoiceButton.visibility = View.VISIBLE
        studentVoiceButton.isEnabled = true
        studentVoiceButton.text = "학생 음성 듣기"
        eventLabel.text = "학생 음성 메시지 도착 · ${formatVoiceDuration(metadata.durationMs)}"
        addConversation(
            sender = "학생",
            content = "음성 메시지 · ${formatVoiceDuration(metadata.durationMs)}",
            sentAtEpochMs = metadata.sentAtEpochMs,
            voiceFile = file,
        )
        notifyStudentVoiceMessage(metadata.durationMs)
    }

    private fun showTextReplyDialog() {
        val input = EditText(this).apply {
            hint = "학생에게 보낼 답장"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("텍스트 답장")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("보내기", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                when {
                    text.isEmpty() -> input.error = "답장을 입력해 주세요"
                    text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_TEXT_BYTES ->
                        input.error = "UTF-8 기준 4KiB 이하로 입력해 주세요"

                    else -> {
                        val queued = reliableChannel.send(
                            StudyMessage.TextMessage(
                                messageId = UUID.randomUUID().toString(),
                                sender = PeerRole.TEACHER,
                                text = text,
                                sentAtEpochMs = System.currentTimeMillis(),
                            ),
                            SystemClock.elapsedRealtime(),
                        )
                        if (queued) {
                            addConversation("선생님", text, System.currentTimeMillis())
                            eventLabel.text = if (transportConnected) {
                                "텍스트 답장을 전송했습니다"
                            } else {
                                "텍스트 답장을 보관했습니다 · 연결되면 자동 전송"
                            }
                            dialog.dismiss()
                        } else {
                            input.error = "답장 대기열이 가득 찼습니다"
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toggleVoiceRecording() {
        if (voiceRecorder.state == VoiceMessageRecorderState.RECORDING) {
            voiceRecorder.stop().fold(
                onSuccess = { message ->
                    voiceReplyButton.text = "음성 답장"
                    queueRecordedVoice(message)
                },
                onFailure = { error ->
                    voiceReplyButton.text = "음성 답장"
                    eventLabel.text = "음성 녹음 실패: ${error.message.orEmpty()}"
                },
            )
            return
        }
        if (!recordAudioGranted()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startVoiceRecording()
    }

    private fun startVoiceRecording() {
        val outputDirectory = File(cacheDir, "outgoing-voice")
        val userMessageId = UUID.randomUUID().toString()
        voiceRecorder.start(outputDirectory, userMessageId).fold(
            onSuccess = {
                voiceReplyButton.text = "녹음 종료 · 보내기"
                eventLabel.text = "음성 답장 녹음 중… 최대 60초"
            },
            onFailure = { error ->
                voiceReplyButton.text = "음성 답장"
                eventLabel.text = "음성 녹음 시작 실패: ${error.message.orEmpty()}"
            },
        )
    }

    private fun queueRecordedVoice(message: RecordedVoiceMessage) {
        val userMessageId = message.file.nameWithoutExtension
        pendingOutgoingVoiceByUserMessageId[userMessageId] = PendingOutgoingVoice(
            userMessageId = userMessageId,
            file = message.file,
            sentAtEpochMs = System.currentTimeMillis(),
            durationMs = message.durationMs.coerceIn(1, MAX_VOICE_DURATION_MS),
        )
        addConversation(
            sender = "선생님",
            content = "음성 메시지 · ${formatVoiceDuration(message.durationMs)}",
            sentAtEpochMs = System.currentTimeMillis(),
        )
        flushPendingVoiceMessages()
        eventLabel.text = if (transportConnected) {
            "음성 답장 전송 중…"
        } else {
            "음성 답장을 보관했습니다 · 연결되면 자동 전송"
        }
    }

    private fun flushPendingVoiceMessages() {
        if (!transportConnected) return
        pendingOutgoingVoiceByUserMessageId.values.toList().forEach { pending ->
            trySendPendingVoice(pending)
        }
    }

    private fun trySendPendingVoice(pending: PendingOutgoingVoice): Boolean {
        val userMessageId = pending.userMessageId
        if (userMessageId in outgoingVoicePayloadIdByUserMessageId) return true
        if (!transportConnected || !pending.file.isFile) return false
        val payloadId = transport.sendFile(pending.file)
        if (payloadId == null) {
            scheduleOutgoingVoiceRetry(userMessageId)
            return false
        }
        val metadata = StudyMessage.VoiceTransfer(
            messageId = UUID.randomUUID().toString(),
            userMessageId = userMessageId,
            sender = PeerRole.TEACHER,
            payloadId = payloadId,
            sentAtEpochMs = pending.sentAtEpochMs,
            durationMs = pending.durationMs,
        )
        outgoingVoicePayloadIdByUserMessageId[userMessageId] = payloadId
        outgoingVoiceAttemptByPayloadId[payloadId] = OutgoingVoiceAttempt(
            userMessageId = userMessageId,
            metadata = metadata,
            fileSent = false,
        )
        return true
    }

    private fun handleOutgoingVoiceFileSent(payloadId: Long) {
        val attempt = outgoingVoiceAttemptByPayloadId[payloadId] ?: return
        outgoingVoiceAttemptByPayloadId[payloadId] = attempt.copy(fileSent = true)
        tryQueueOutgoingVoiceMetadata(payloadId)
    }

    private fun tryQueueOutgoingVoiceMetadata(payloadId: Long) {
        val attempt = outgoingVoiceAttemptByPayloadId[payloadId]?.takeIf { it.fileSent } ?: return
        val queued = reliableChannel.send(attempt.metadata, SystemClock.elapsedRealtime())
        if (!queued) {
            scheduleOutgoingVoiceRetry(attempt.userMessageId)
            return
        }
        completeOutgoingVoiceAttempt(payloadId)
    }

    private fun completeOutgoingVoiceAttempt(payloadId: Long) {
        val attempt = removeOutgoingVoiceAttempt(payloadId) ?: return
        cancelOutgoingVoiceRetry(attempt.userMessageId)
        outgoingVoiceRetryCountByUserMessageId.remove(attempt.userMessageId)
        val pending = pendingOutgoingVoiceByUserMessageId.remove(attempt.userMessageId) ?: return
        fileExecutor.execute { pending.file.delete() }
        eventLabel.text = "음성 답장을 학생에게 전송했습니다"
    }

    private fun handleOutgoingVoiceFileFailure(payloadId: Long, detail: String) {
        val attempt = removeOutgoingVoiceAttempt(payloadId) ?: return
        eventLabel.text = "음성 답장 전송 실패 · $detail"
        scheduleOutgoingVoiceRetry(attempt.userMessageId)
    }

    private fun removeOutgoingVoiceAttempt(payloadId: Long): OutgoingVoiceAttempt? {
        val attempt = outgoingVoiceAttemptByPayloadId.remove(payloadId) ?: return null
        if (outgoingVoicePayloadIdByUserMessageId[attempt.userMessageId] == payloadId) {
            outgoingVoicePayloadIdByUserMessageId.remove(attempt.userMessageId)
        }
        return attempt
    }

    private fun scheduleOutgoingVoiceRetry(userMessageId: String) {
        if (!transportConnected || activityDestroyed || userMessageId in outgoingVoiceRetryRunnableByUserMessageId) return
        val activePayloadId = outgoingVoicePayloadIdByUserMessageId[userMessageId]
        if (activePayloadId != null && outgoingVoiceAttemptByPayloadId[activePayloadId]?.fileSent != true) return
        val retryCount = outgoingVoiceRetryCountByUserMessageId[userMessageId] ?: 0
        if (retryCount >= MAX_OUTGOING_FILE_RETRIES) {
            eventLabel.text = "음성 답장 재시도를 멈춰습니다 · 재연결 시 다시 전송합니다"
            return
        }
        outgoingVoiceRetryCountByUserMessageId[userMessageId] = retryCount + 1
        val retry = Runnable {
            outgoingVoiceRetryRunnableByUserMessageId.remove(userMessageId)
            if (!transportConnected || activityDestroyed) return@Runnable
            val payloadId = outgoingVoicePayloadIdByUserMessageId[userMessageId]
            if (payloadId != null) {
                if (outgoingVoiceAttemptByPayloadId[payloadId]?.fileSent == true) {
                    tryQueueOutgoingVoiceMetadata(payloadId)
                }
                return@Runnable
            }
            pendingOutgoingVoiceByUserMessageId[userMessageId]?.let(::trySendPendingVoice)
        }
        outgoingVoiceRetryRunnableByUserMessageId[userMessageId] = retry
        handler.postDelayed(retry, OUTGOING_FILE_RETRY_DELAY_MS)
    }

    private fun cancelOutgoingVoiceRetry(userMessageId: String) {
        outgoingVoiceRetryRunnableByUserMessageId.remove(userMessageId)?.let(handler::removeCallbacks)
    }

    private fun clearOutgoingVoiceInflightForDisconnect() {
        outgoingVoiceAttemptByPayloadId
            .filterValues { !it.fileSent }
            .keys
            .toList()
            .forEach(::removeOutgoingVoiceAttempt)
        outgoingVoiceRetryRunnableByUserMessageId.values.forEach(handler::removeCallbacks)
        outgoingVoiceRetryRunnableByUserMessageId.clear()
    }

    private fun resetOutgoingVoiceRetriesForReconnect() {
        outgoingVoiceRetryCountByUserMessageId.clear()
        outgoingVoiceRetryRunnableByUserMessageId.values.forEach(handler::removeCallbacks)
        outgoingVoiceRetryRunnableByUserMessageId.clear()
        outgoingVoiceAttemptByPayloadId
            .filterValues { it.fileSent }
            .keys
            .toList()
            .forEach(::tryQueueOutgoingVoiceMetadata)
    }

    private fun playLatestStudentVoice() {
        val file = latestStudentVoiceFile ?: return
        studentVoiceButton.isEnabled = false
        studentVoiceButton.text = "학생 음성 재생 중…"
        voicePlayer.play(file) { result ->
            studentVoiceButton.isEnabled = true
            studentVoiceButton.text = "학생 음성 듣기"
            result.onFailure { error ->
                eventLabel.text = "음성 재생 실패: ${error.message.orEmpty()}"
            }
        }.onFailure { error ->
            studentVoiceButton.isEnabled = true
            studentVoiceButton.text = "학생 음성 듣기"
            eventLabel.text = "음성 재생 시작 실패: ${error.message.orEmpty()}"
        }
    }

    private fun requestBookRoi(assetId: String) {
        if (!transportConnected) {
            thumbnailLabel.text = "고화질 요청은 학생폰 연결 뒤 사용할 수 있습니다"
            return
        }
        if (!pendingRoiAssetIds.add(assetId)) {
            thumbnailLabel.text = "이미 이 사진의 고화질을 요청했습니다"
            return
        }
        val queued = reliableChannel.send(
            StudyMessage.AssetRequest(
                messageId = UUID.randomUUID().toString(),
                assetId = assetId,
                kind = AssetKind.BOOK_ROI,
            ),
            SystemClock.elapsedRealtime(),
        )
        if (!queued) pendingRoiAssetIds.remove(assetId)
        if (queued) {
            handler.postDelayed({ pendingRoiAssetIds.remove(assetId) }, ROI_REQUEST_TIMEOUT_MS)
        }
        thumbnailLabel.text = if (queued) "고화질 책 영역 요청 중…" else "요청 대기열이 가득 찼습니다"
    }

    private fun showAlert(message: StudyMessage.Alert) {
        val seconds = message.observedDurationMs / 1_000
        val text = when (message.kind) {
            AlertKind.AWAY -> "자리 판정 구역에서 ${seconds}초 동안 보이지 않음(추정)"
            AlertKind.NO_BOOK_MOVEMENT -> "책 영역 움직임이 ${seconds}초 동안 감지되지 않음"
            AlertKind.PRESENCE_RESTORED -> "자리 판정 구역에 다시 보임"
            AlertKind.BOOK_MOVEMENT_RESTORED -> "책 영역 움직임이 다시 감지됨"
        }
        eventLabel.text = text
        val enabled = when (message.kind) {
            AlertKind.AWAY -> studySettings.awayAlertEnabled
            AlertKind.NO_BOOK_MOVEMENT -> studySettings.noMovementAlertEnabled
            else -> false
        }
        if (enabled) {
            notifyStudyAlert(message.kind, text)
        }
    }

    private fun sendStartRequest() {
        val settingsQueued = reliableChannel.send(
            studySettings.toMessage(UUID.randomUUID().toString()),
            SystemClock.elapsedRealtime(),
            "study-settings",
        )
        if (!settingsQueued) {
            eventLabel.text = "설정 전송 대기열이 가득 찼습니다"
            return
        }
        val message = StudyMessage.StartRequest(
                messageId = UUID.randomUUID().toString(),
                origin = WireStartOrigin.TEACHER,
            )
        if (reliableChannel.send(message, SystemClock.elapsedRealtime())) {
            eventLabel.text = "시작 요청 전송 · 학생폰에서 5초 뒤 시작"
            startButton.isEnabled = false
        } else {
            eventLabel.text = "연결을 확인해 주세요"
        }
    }

    private fun toggleBookRegionEditing() {
        showBookRegionAndQualityDialog()
    }

    private fun sendCurrentBookProfile(): Boolean {
        if (!::bookRegionOverlay.isInitialized) return false
        val displayRegion = bookRegionOverlay.region
        return reliableChannel.send(
            StudyMessage.BookRegionSettings(
                messageId = UUID.randomUUID().toString(),
                left = 1f - displayRegion.right,
                top = 1f - displayRegion.bottom,
                right = 1f - displayRegion.left,
                bottom = 1f - displayRegion.top,
                detailCaptureMode = detailCaptureMode,
                detailZoomRatio = detailZoomRatio,
                focusTimeoutMs = focusTimeoutMs,
            ),
            SystemClock.elapsedRealtime(),
            "book-region",
        )
    }

    private fun showBookRegionAndQualityDialog() {
        if (!transportConnected) {
            eventLabel.text = "학생폰 연결 후 책 영역을 설정해 주세요"
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
        }
        container.addView(TextView(this).apply {
            text = "현재 상세 배율 ${detailZoomRatio}배 · 비교 촬영 버튼에서 변경"
            textSize = 14f
            setTextColor(COLOR_TEXT)
        }, matchWrap(bottom = 10))
        val quality = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val standard = android.widget.RadioButton(this).apply {
            text = "12MP 일반 · 권장"
            isChecked = detailCaptureMode == DetailCaptureMode.STANDARD_12_MP
        }
        val ultra = android.widget.RadioButton(this).apply {
            text = "50MP 요청 · 지원 기기만"
            isChecked = detailCaptureMode == DetailCaptureMode.ULTRA_50_MP
        }
        quality.addView(standard)
        quality.addView(ultra)
        container.addView(quality)
        container.addView(TextView(this).apply {
            text = "자동 초점 시도당 대기(초, 0.5~5.0 · 실패 시 1회 재시도)"
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }, matchWrap(top = 10))
        val focusSeconds = EditText(this).apply {
            setText("%.1f".format(focusTimeoutMs / 1_000f))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(focusSeconds, LinearLayout.LayoutParams(-1, dp(48)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("책 영역 촬영 설정")
            .setView(container)
            .setNegativeButton("취소", null)
            .setPositiveButton("영역 지정", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val focus = focusSeconds.text.toString().toFloatOrNull()
                if (focus == null || focus !in 0.5f..5f) {
                    focusSeconds.error = "0.5~5.0초로 입력해 주세요"
                    return@setOnClickListener
                }
                detailCaptureMode = if (ultra.isChecked) {
                    DetailCaptureMode.ULTRA_50_MP
                } else {
                    DetailCaptureMode.STANDARD_12_MP
                }
                focusTimeoutMs = (focus * 1_000).toLong()
                saveBookProfile(bookRegionOverlay.region)
                dialog.dismiss()
                requestFreshBookCalibration()
            }
        }
        dialog.show()
    }

    private fun requestCameraComparison() {
        if (!transportConnected) {
            eventLabel.text = "학생폰 연결 후 비교 촬영을 시작해 주세요"
            return
        }
        cameraComparisonDialog?.dismiss()
        cameraComparisonDialog = null
        clearCameraComparisonBitmaps()
        pendingComparisonAssetIds.clear()
        val kinds = listOf(
            AssetKind.CAMERA_COMPARE_1X,
            AssetKind.CAMERA_COMPARE_2X,
            AssetKind.CAMERA_COMPARE_3X,
        )
        val queuedAll = kinds.all { kind ->
            val assetId = "compare-${kind.name.lowercase()}-${UUID.randomUUID()}"
            val queued = reliableChannel.send(
                StudyMessage.AssetRequest(
                    messageId = UUID.randomUUID().toString(),
                    assetId = assetId,
                    kind = kind,
                ),
                SystemClock.elapsedRealtime(),
            )
            if (queued) pendingComparisonAssetIds[kind] = assetId
            queued
        }
        if (!queuedAll) {
            pendingComparisonAssetIds.clear()
            eventLabel.text = "비교 촬영 요청 대기열이 가득 찼습니다"
            return
        }
        thumbnailLabel.visibility = View.VISIBLE
        thumbnailLabel.text = "1배·2배·3배 초점 비교 촬영 중… 약 20초"
        cameraCompareButton.isEnabled = false
        handler.postDelayed({
            if (pendingComparisonAssetIds.isNotEmpty()) {
                pendingComparisonAssetIds.clear()
                clearCameraComparisonBitmaps()
                cameraCompareButton.isEnabled = true
                thumbnailLabel.text = "비교 촬영 응답이 끝나지 않았습니다 · 학생폰 오류 문구를 확인해 주세요"
            }
        }, CAMERA_COMPARISON_TIMEOUT_MS)
    }

    private fun receiveCameraComparison(metadata: StudyMessage.AssetTransfer, bitmap: Bitmap) {
        if (pendingComparisonAssetIds[metadata.kind] != metadata.assetId) {
            bitmap.recycle()
            return
        }
        pendingComparisonAssetIds.remove(metadata.kind)
        cameraComparisonBitmaps.remove(metadata.kind)?.takeUnless(Bitmap::isRecycled)?.recycle()
        cameraComparisonBitmaps[metadata.kind] = bitmap
        thumbnailLabel.text = "비교 사진 ${cameraComparisonBitmaps.size}/3 수신"
        if (pendingComparisonAssetIds.isEmpty() && cameraComparisonBitmaps.size == 3) {
            cameraCompareButton.isEnabled = true
            showCameraComparisonDialog()
        }
    }

    private fun showCameraComparisonDialog() {
        cameraComparisonDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(58), dp(8), dp(12))
        }
        val images = mutableListOf<ImageView>()
        listOf(
            AssetKind.CAMERA_COMPARE_1X to 1f,
            AssetKind.CAMERA_COMPARE_2X to 2f,
            AssetKind.CAMERA_COMPARE_3X to 3f,
        ).forEach { (kind, zoom) ->
            val bitmap = cameraComparisonBitmaps.getValue(kind)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), 0, dp(6), 0)
            }
            card.addView(TextView(this).apply {
                text = "${zoom.toInt()}배 · 손가락으로 확대 가능"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, dp(38)))
            val image = ZoomableImageView(this).apply {
                setImageBitmap(bitmap)
                contentDescription = "카메라 비교 ${zoom.toInt()}배 사진"
            }
            images += image
            card.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            card.addView(
                actionButton("${zoom.toInt()}배 사용 · 영역 지정", COLOR_SUCCESS).apply {
                    setOnClickListener {
                        detailZoomRatio = zoom
                        saveBookProfile(bookRegionOverlay.region)
                        dialog.dismiss()
                        eventLabel.text = "${zoom.toInt()}배 선택 · 같은 배율 사진에서 책 영역을 다시 지정합니다"
                        requestFreshBookCalibration()
                    }
                },
                LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) },
            )
            strip.addView(
                card,
                LinearLayout.LayoutParams(resources.displayMetrics.widthPixels - dp(24), -1),
            )
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(strip, FrameLayout.LayoutParams(-2, -1))
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        val close = Button(this).apply {
            text = "×"
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "비교 촬영 닫기"
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            images.forEach { it.setImageDrawable(null) }
            clearCameraComparisonBitmaps()
            if (cameraComparisonDialog === dialog) cameraComparisonDialog = null
        }
        cameraComparisonDialog = dialog
        thumbnailLabel.text = "비교 완료 · 선명한 배율을 선택해 주세요"
        dialog.show()
    }

    private fun clearCameraComparisonBitmaps() {
        cameraComparisonBitmaps.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        cameraComparisonBitmaps.clear()
    }

    private fun requestFreshBookCalibration() {
        if (!sendCurrentBookProfile()) {
            thumbnailLabel.visibility = View.VISIBLE
            thumbnailLabel.text = "상세 화질 적용 요청을 보내지 못했습니다"
            return
        }
        val assetId = "calibration-${UUID.randomUUID()}"
        val queued = reliableChannel.send(
            StudyMessage.AssetRequest(
                messageId = UUID.randomUUID().toString(),
                assetId = assetId,
                kind = AssetKind.BOOK_CALIBRATION,
            ),
            SystemClock.elapsedRealtime(),
        )
        if (queued) {
            pendingCalibrationAssetId = assetId
            thumbnailLabel.visibility = View.VISIBLE
            thumbnailLabel.text = "학생폰에서 실제 ${detailZoomRatio}배 설정 사진 촬영 중…"
            handler.postDelayed({
                if (pendingCalibrationAssetId == assetId) {
                    pendingCalibrationAssetId = null
                    thumbnailLabel.text = "설정 사진 응답이 없습니다 · 다시 시도해 주세요"
                }
            }, CALIBRATION_REQUEST_TIMEOUT_MS)
        } else {
            thumbnailLabel.visibility = View.VISIBLE
            thumbnailLabel.text = "설정 사진 요청 대기열이 가득 찼습니다"
        }
    }

    private fun showBookCalibrationEditor(bitmap: Bitmap) {
        bookCalibrationDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "실제 ${detailZoomRatio}배 책 영역 설정 사진"
        }
        val overlay = TeacherBookRegionOverlay(this).apply {
            setImageSize(bitmap.width, bitmap.height)
            setRegion(bookRegionOverlay.region)
            editingEnabled = true
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val close = Button(this).apply {
            text = "×"
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "책 영역 설정 닫기"
            setOnClickListener { dialog.dismiss() }
        }
        frame.addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        val save = actionButton("이 영역 저장 · ${detailZoomRatio}배", COLOR_SUCCESS).apply {
            setOnClickListener {
                val selectedRegion = overlay.region
                bookRegionOverlay.setRegion(selectedRegion)
                val queued = sendCurrentBookProfile()
                saveBookProfile(selectedRegion)
                thumbnailLabel.text = if (queued) {
                    "실제 ${detailZoomRatio}배 기준 책 영역과 ${detailModeLabel(detailCaptureMode)} 적용 요청 중"
                } else {
                    "연결 후 책 영역을 다시 저장해 주세요"
                }
                dialog.dismiss()
            }
        }
        frame.addView(
            save,
            FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(16)
            },
        )
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
            if (bookCalibrationDialog === dialog) bookCalibrationDialog = null
        }
        bookCalibrationDialog = dialog
        dialog.show()
    }

    private fun showCameraProfileStatus(status: StudyMessage.CameraProfileStatus) {
        val actualMegapixels = status.width.toLong() * status.height / 1_000_000.0
        thumbnailLabel.visibility = View.VISIBLE
        thumbnailLabel.text = if (status.requestedMode == status.appliedMode) {
            "${detailModeLabel(status.appliedMode)} 적용 · ${status.width}×${status.height} " +
                "(%.1fMP)".format(actualMegapixels)
        } else {
            "제조사가 외부 앱에 50MP를 제공하지 않아 ${status.width}×${status.height} " +
                "(%.1fMP)로 적용".format(actualMegapixels)
        }
    }

    private fun detailModeLabel(mode: DetailCaptureMode): String = when (mode) {
        DetailCaptureMode.STANDARD_12_MP -> "12MP 일반"
        DetailCaptureMode.ULTRA_50_MP -> "50MP 초고화질"
    }

    private fun saveBookProfile(region: DisplayBookRegion) {
        getSharedPreferences("book-profile", MODE_PRIVATE).edit()
            .putFloat("left", region.left)
            .putFloat("top", region.top)
            .putFloat("right", region.right)
            .putFloat("bottom", region.bottom)
            .putString("detailMode", detailCaptureMode.name)
            .putFloat("detailZoomRatio", detailZoomRatio)
            .putLong("focusTimeoutMs", focusTimeoutMs)
            .apply()
    }

    private fun loadDetailCaptureMode(): DetailCaptureMode = runCatching {
        DetailCaptureMode.valueOf(
            getSharedPreferences("book-profile", MODE_PRIVATE)
                .getString("detailMode", DetailCaptureMode.STANDARD_12_MP.name)
                ?: DetailCaptureMode.STANDARD_12_MP.name,
        )
    }.getOrDefault(DetailCaptureMode.STANDARD_12_MP)

    private fun loadDetailZoomRatio(): Float = getSharedPreferences("book-profile", MODE_PRIVATE)
        .getFloat("detailZoomRatio", DEFAULT_DETAIL_ZOOM_RATIO)

    private fun loadFocusTimeoutMs(): Long = getSharedPreferences("book-profile", MODE_PRIVATE)
        .getLong("focusTimeoutMs", DEFAULT_FOCUS_TIMEOUT_MS)

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun numberField(label: String, value: String, decimal: Boolean = false): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(COLOR_MUTED)
            }, matchWrap(top = 10))
            return EditText(this).apply {
                setText(value)
                inputType = if (decimal) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                selectAll()
                container.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val meditation = numberField("명상 시간(분)", studySettings.meditationMinutes.toString())
        val study = numberField("공부 시간(분)", studySettings.studyMinutes.toString())
        val rest = numberField("쉬는 시간(분)", studySettings.breakMinutes.toString())
        val countdown = numberField("교사 시작 대기(초)", studySettings.countdownSeconds.toString())
        val capture = numberField("사진 촬영 간격(초)", studySettings.captureSeconds.toString())
        val away = numberField("자리 비움 알림(초)", studySettings.awaySeconds.toString())
        val movement = numberField("책 움직임 없음 알림(초)", studySettings.noMovementSeconds.toString())
        val presence = numberField("자리 변화 감도(0~1)", studySettings.presenceThreshold.toString(), true)
        val restore = numberField("자리 복귀 감도(0~1)", studySettings.presenceRestoreThreshold.toString(), true)
        val presenceMotion = numberField(
            "미세 움직임 감도(0~1)", studySettings.presenceMotionThreshold.toString(), true,
        )
        val book = numberField("책 움직임 감도(0~1)", studySettings.bookMovementThreshold.toString(), true)
        val cooldown = numberField("같은 알림 재발송 제한(초)", studySettings.alertCooldownSeconds.toString())
        val awayEnabled = CheckBox(this).apply {
            text = "자리 이탈 알림 사용"
            isChecked = studySettings.awayAlertEnabled
            container.addView(this, matchWrap(top = 8))
        }
        val movementEnabled = CheckBox(this).apply {
            text = "책 움직임 없음 알림 사용"
            isChecked = studySettings.noMovementAlertEnabled
            container.addView(this, matchWrap(top = 4))
        }
        val soundEnabled = CheckBox(this).apply {
            text = "자리·움직임 알림음 사용"
            isChecked = studySettings.alertSoundEnabled
            container.addView(this, matchWrap(top = 4))
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("학습 설정")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = runCatching {
                    TeacherStudySettings(
                        meditationMinutes = meditation.text.toString().toInt(),
                        studyMinutes = study.text.toString().toInt(),
                        breakMinutes = rest.text.toString().toInt(),
                        countdownSeconds = countdown.text.toString().toInt(),
                        captureSeconds = capture.text.toString().toInt(),
                        awaySeconds = away.text.toString().toInt(),
                        noMovementSeconds = movement.text.toString().toInt(),
                        presenceThreshold = presence.text.toString().toFloat(),
                        presenceRestoreThreshold = restore.text.toString().toFloat(),
                        presenceMotionThreshold = presenceMotion.text.toString().toFloat(),
                        bookMovementThreshold = book.text.toString().toFloat(),
                        alertCooldownSeconds = cooldown.text.toString().toInt(),
                        awayAlertEnabled = awayEnabled.isChecked,
                        noMovementAlertEnabled = movementEnabled.isChecked,
                        alertSoundEnabled = soundEnabled.isChecked,
                    ).validated()
                }
                candidate.onSuccess {
                    studySettings = it
                    saveStudySettings(it)
                    val queued = reliableChannel.send(
                        it.toMessage(UUID.randomUUID().toString()),
                        SystemClock.elapsedRealtime(),
                        "study-settings",
                    )
                    if (!it.alertSoundEnabled) {
                        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_AWAY)
                        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_NO_MOVEMENT)
                    }
                    eventLabel.text = if (queued) {
                        "알림·촬영 설정은 즉시 적용 · 시간은 다음 시작부터 적용"
                    } else {
                        "설정 저장 완료 · 연결되면 학생폰에 적용"
                    }
                    dialog.dismiss()
                }.onFailure {
                    eventLabel.text = "설정값을 확인해 주세요: ${it.message.orEmpty()}"
                }
            }
        }
        dialog.show()
    }

    private fun loadStudySettings(): TeacherStudySettings {
        val preferences = getSharedPreferences("study-settings", MODE_PRIVATE)
        return runCatching {
            TeacherStudySettings(
                meditationMinutes = preferences.getInt("meditation", 5),
                studyMinutes = preferences.getInt("study", 40),
                breakMinutes = preferences.getInt("break", 15),
                countdownSeconds = preferences.getInt("countdown", 5),
                captureSeconds = preferences.getInt("capture", 10),
                awaySeconds = preferences.getInt("away", 10),
                noMovementSeconds = preferences.getInt("movement", 30),
                presenceThreshold = preferences.getFloat("presenceThreshold", 0.18f),
                presenceRestoreThreshold = preferences.getFloat("presenceRestoreThreshold", 0.12f),
                presenceMotionThreshold = preferences.getFloat("presenceMotionThreshold", 0.006f),
                bookMovementThreshold = preferences.getFloat("bookThreshold", 0.012f),
                alertCooldownSeconds = preferences.getInt("alertCooldownSeconds", 300),
                awayAlertEnabled = preferences.getBoolean("awayAlertEnabled", true),
                noMovementAlertEnabled = preferences.getBoolean("noMovementAlertEnabled", true),
                alertSoundEnabled = preferences.getBoolean("alertSoundEnabled", false),
            ).validated()
        }.getOrDefault(TeacherStudySettings())
    }

    private fun saveStudySettings(settings: TeacherStudySettings) {
        getSharedPreferences("study-settings", MODE_PRIVATE).edit()
            .putInt("meditation", settings.meditationMinutes)
            .putInt("study", settings.studyMinutes)
            .putInt("break", settings.breakMinutes)
            .putInt("countdown", settings.countdownSeconds)
            .putInt("capture", settings.captureSeconds)
            .putInt("away", settings.awaySeconds)
            .putInt("movement", settings.noMovementSeconds)
            .putFloat("presenceThreshold", settings.presenceThreshold)
            .putFloat("presenceRestoreThreshold", settings.presenceRestoreThreshold)
            .putFloat("presenceMotionThreshold", settings.presenceMotionThreshold)
            .putFloat("bookThreshold", settings.bookMovementThreshold)
            .putInt("alertCooldownSeconds", settings.alertCooldownSeconds)
            .putBoolean("awayAlertEnabled", settings.awayAlertEnabled)
            .putBoolean("noMovementAlertEnabled", settings.noMovementAlertEnabled)
            .putBoolean("alertSoundEnabled", settings.alertSoundEnabled)
            .apply()
    }

    private fun buildContentView(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(52))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        connectionPill = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        setConnectionState("연결 준비 중", COLOR_MUTED)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.WHITE, 16f)
            elevation = dp(2).toFloat()
        }
        val statusText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusHeader.addView(connectionPill, LinearLayout.LayoutParams(-2, -2))
        statusHeader.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        conversationButton = actionButton("대화", COLOR_MUTED).apply {
            textSize = 11f
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { showConversationHistory() }
        }
        statusHeader.addView(
            conversationButton,
            LinearLayout.LayoutParams(dp(62), dp(34)).apply { marginStart = dp(6) },
        )
        phaseLabel = TextView(this).apply {
            text = "준비 전"
            textSize = 16f
            setTextColor(COLOR_MUTED)
            setTypeface(typeface, Typeface.BOLD)
        }
        timerLabel = TextView(this).apply {
            text = "40:00"
            textSize = 38f
            setTextColor(COLOR_TEXT)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        problemLabel = TextView(this).apply {
            text = "완료한 문제 0개"
            textSize = 14f
            setTextColor(COLOR_TEXT)
        }
        eventLabel = TextView(this).apply {
            text = "학생폰 연결을 기다리는 중"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            maxLines = 2
        }
        statusText.addView(statusHeader)
        statusText.addView(phaseLabel, matchWrap(top = 5))
        statusText.addView(problemLabel, matchWrap(top = 3))
        statusText.addView(eventLabel, matchWrap(top = 4))
        statusCard.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(timerLabel)
        root.addView(statusCard, matchWrap(bottom = 8))

        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.WHITE, 14f)
        }
        latestThumbnail = ImageView(this).apply {
            setBackgroundColor(0xFFE7EAF1.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "아직 수신한 학습 사진이 없음"
        }
        bookRegionOverlay = TeacherBookRegionOverlay(this).apply {
            editingEnabled = false
            val preferences = getSharedPreferences("book-profile", MODE_PRIVATE)
            if (preferences.contains("left")) {
                setRegion(
                    DisplayBookRegion(
                        preferences.getFloat("left", 0.07f),
                        preferences.getFloat("top", 0.30f),
                        preferences.getFloat("right", 0.93f),
                        preferences.getFloat("bottom", 0.70f),
                    ),
                )
            }
        }
        val mainPhotoFrame = FrameLayout(this).apply {
            addView(latestThumbnail, FrameLayout.LayoutParams(-1, -1))
            addView(bookRegionOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        thumbnailStrip = GridLayout(this).apply {
            rowCount = 2
            orientation = GridLayout.VERTICAL
        }
        thumbnailScroller = HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            contentDescription = "최근 학습 사진 목록"
            addView(thumbnailStrip, FrameLayout.LayoutParams(-2, dp(148)))
        }
        thumbnailLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        mediaCard.addView(mainPhotoFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        mediaCard.addView(thumbnailScroller, LinearLayout.LayoutParams(-1, dp(148)).apply {
            topMargin = dp(6)
        })
        mediaCard.addView(thumbnailLabel, matchWrap(top = 4))
        root.addView(mediaCard, LinearLayout.LayoutParams(-1, 0, 1f))

        studentVoiceButton = actionButton("학생 음성 듣기", COLOR_SUCCESS).apply {
            visibility = View.GONE
            setOnClickListener { playLatestStudentVoice() }
        }
        textReplyButton = actionButton("텍스트 답장", COLOR_PRIMARY).apply {
            setOnClickListener { showTextReplyDialog() }
        }
        voiceReplyButton = actionButton("음성 답장", COLOR_MUTED).apply {
            setOnClickListener { toggleVoiceRecording() }
        }

        pairingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(0xFFFFF6D8.toInt(), 16f)
            visibility = View.GONE
        }
        pairingDigits = TextView(this).apply {
            text = "0000"
            textSize = 24f
            letterSpacing = 0.18f
            setTextColor(COLOR_TEXT)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        pairingPanel.addView(pairingDigits, LinearLayout.LayoutParams(0, -2, 1f))
        approveButton = actionButton("연결 승인", COLOR_PRIMARY).apply {
            setOnClickListener { pendingEndpointId?.let(transport::approve) }
        }
        rejectButton = actionButton("거절", COLOR_MUTED).apply {
            setOnClickListener {
                pendingEndpointId?.let(transport::reject)
                pairingPanel.visibility = View.GONE
            }
        }
        pairingPanel.addView(approveButton, LinearLayout.LayoutParams(dp(104), dp(44)))
        pairingPanel.addView(rejectButton, LinearLayout.LayoutParams(dp(72), dp(44)).apply { marginStart = dp(6) })

        startButton = actionButton("공부 시작 요청", COLOR_PRIMARY).apply {
            isEnabled = false
            setOnClickListener { sendStartRequest() }
        }
        val settingsButton = actionButton("설정", COLOR_MUTED).apply {
            setOnClickListener { showSettingsDialog() }
        }
        bookRegionButton = actionButton("책 영역 설정", COLOR_SUCCESS).apply {
            setOnClickListener { toggleBookRegionEditing() }
        }
        cameraCompareButton = actionButton("1·2·3배 비교", COLOR_WARNING).apply {
            setOnClickListener { requestCameraComparison() }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        listOf(
            startButton, settingsButton, cameraCompareButton, bookRegionButton,
            studentVoiceButton, textReplyButton, voiceReplyButton,
        )
            .forEach { button ->
                actionRow.addView(button, LinearLayout.LayoutParams(dp(128), dp(46)).apply { marginEnd = dp(6) })
            }
        val actionScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionRow, FrameLayout.LayoutParams(-2, dp(46)))
        }
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(pairingPanel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        val menuLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(actionScroller, LinearLayout.LayoutParams(0, dp(46), 0f))
        }
        val menuToggle = actionButton("⋮", COLOR_MUTED).apply {
            textSize = 22f
            contentDescription = "메뉴 열기"
            setOnClickListener {
                val opening = actionRow.visibility != View.VISIBLE
                actionRow.visibility = if (opening) View.VISIBLE else View.GONE
                actionScroller.layoutParams = LinearLayout.LayoutParams(0, dp(46), if (opening) 1f else 0f)
                text = if (opening) "×" else "⋮"
                contentDescription = if (opening) "메뉴 닫기" else "메뉴 열기"
            }
        }
        menuLine.addView(menuToggle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(5) })
        bottomPanel.addView(menuLine, LinearLayout.LayoutParams(-1, dp(46)))
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(
            bottomPanel,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(8)
            },
        )
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(
                dp(12), systemBars.top + dp(10), dp(12), systemBars.bottom + dp(52),
            )
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).let { params ->
                params.bottomMargin = systemBars.bottom + dp(8)
                bottomPanel.layoutParams = params
            }
            insets
        }
        return frame
    }

    private fun setConnectionState(text: String, color: Int) {
        connectionPill.text = text
        connectionPill.setTextColor(color)
        connectionPill.background = rounded(withAlpha(color, 28), 24f)
    }

    private fun notifyProblemCompleted(total: Int) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        val notification = android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("문제 완료")
            .setContentText("학생이 문제를 풀었습니다 · 총 ${total}개")
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_PROBLEM, notification)
    }

    private fun notifyStudyAlert(kind: AlertKind, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = when (kind) {
            AlertKind.AWAY -> "자리 확인 필요"
            AlertKind.NO_BOOK_MOVEMENT -> "책 영역 확인 필요"
            else -> "학습 상태"
        }
        val channel = if (studySettings.alertSoundEnabled) {
            NOTIFICATION_CHANNEL
        } else {
            NOTIFICATION_CHANNEL_SILENT
        }
        val notification = android.app.Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            if (kind == AlertKind.AWAY) NOTIFICATION_AWAY else NOTIFICATION_NO_MOVEMENT,
            notification,
        )
    }

    private fun notifyStudentVoiceMessage(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("학생 음성 메시지")
            .setContentText("새 음성 메시지가 도착했습니다 · ${formatVoiceDuration(durationMs)}")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_STUDENT_VOICE, notification)
    }

    private fun notifyNewStudentMessage(text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("학생 새 메시지")
            .setContentText(text.take(MAX_STATUS_TEXT_CHARS))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_STUDENT_TEXT,
            notification,
        )
    }

    private fun pruneReceivedAssets(directory: File) {
        directory.listFiles()
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_RECEIVED_ASSETS)
            .forEach(File::delete)
    }

    private fun pruneReceivedVoiceMessages(directory: File) {
        directory.listFiles()
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_RECEIVED_VOICE_MESSAGES)
            .forEach(File::delete)
    }

    private fun pruneOutgoingVoiceCache() {
        fileExecutor.execute {
            File(cacheDir, "outgoing-voice").listFiles()
                .orEmpty()
                .filter(File::isFile)
                .sortedByDescending(File::lastModified)
                .drop(MAX_STORED_OUTGOING_VOICE_MESSAGES)
                .forEach(File::delete)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "학습 이벤트",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_SILENT,
                "학습 상태 알림(무음)",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun phaseText(phase: WireSessionPhase, status: WireSessionStatus): String {
        if (status == WireSessionStatus.READY) return "시작 대기"
        if (status == WireSessionStatus.START_COUNTDOWN) return "곧 공부 시작"
        if (status == WireSessionStatus.PAUSED) return "잠시 멈춤"
        return when (phase) {
            WireSessionPhase.MEDITATION -> "명상 시간"
            WireSessionPhase.STUDY -> "공부 시간"
            WireSessionPhase.BREAK -> "쉬는 시간"
            WireSessionPhase.COMPLETE -> "오늘 공부 완료"
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) + 999) / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun formatVoiceDuration(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(1) + 999) / 1_000
        return "${seconds}초"
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
        background = statefulRounded(color, 14f)
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

    private fun wrapWrap(bottom: Int = 0) = LinearLayout.LayoutParams(-2, -2).apply {
        bottomMargin = dp(bottom)
    }

    private fun weightWrap(weight: Float, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, -2, weight).apply {
            marginStart = dp(start)
            marginEnd = dp(end)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PendingOutgoingVoice(
        val userMessageId: String,
        val file: File,
        val sentAtEpochMs: Long,
        val durationMs: Long,
    )

    private data class OutgoingVoiceAttempt(
        val userMessageId: String,
        val metadata: StudyMessage.VoiceTransfer,
        val fileSent: Boolean,
    )

    private data class RecentThumbnail(
        val assetId: String,
        val capturedAtEpochMs: Long,
        val imageView: ImageView,
        val bitmap: Bitmap,
    )

    private data class ConversationEntry(
        val sender: String,
        val content: String,
        val sentAtEpochMs: Long,
        val voiceFile: File? = null,
    )

    private data class TeacherStudySettings(
        val meditationMinutes: Int = 5,
        val studyMinutes: Int = 40,
        val breakMinutes: Int = 15,
        val countdownSeconds: Int = 5,
        val captureSeconds: Int = 10,
        val awaySeconds: Int = 10,
        val noMovementSeconds: Int = 30,
        val presenceThreshold: Float = 0.18f,
        val presenceRestoreThreshold: Float = 0.12f,
        val presenceMotionThreshold: Float = 0.006f,
        val bookMovementThreshold: Float = 0.012f,
        val alertCooldownSeconds: Int = 300,
        val awayAlertEnabled: Boolean = true,
        val noMovementAlertEnabled: Boolean = true,
        val alertSoundEnabled: Boolean = false,
    ) {
        fun validated() = apply {
            require(meditationMinutes in 0..1_440) { "명상 시간은 0~1440분" }
            require(studyMinutes in 1..1_440) { "공부 시간은 1~1440분" }
            require(breakMinutes in 1..1_440) { "쉬는 시간은 1~1440분" }
            require(countdownSeconds in 1..60) { "시작 대기는 1~60초" }
            require(captureSeconds in 1..3_600) { "촬영 간격은 1~3600초" }
            require(awaySeconds in 1..3_600) { "자리 알림은 1~3600초" }
            require(noMovementSeconds in 1..3_600) { "움직임 알림은 1~3600초" }
            require(presenceThreshold.isFinite() && presenceThreshold in 0f..1f) { "자리 감도는 0~1" }
            require(presenceRestoreThreshold.isFinite() && presenceRestoreThreshold in 0f..presenceThreshold) {
                "자리 복귀 감도는 0~자리 변화 감도"
            }
            require(presenceMotionThreshold.isFinite() && presenceMotionThreshold in 0f..1f) {
                "미세 움직임 감도는 0~1"
            }
            require(bookMovementThreshold.isFinite() && bookMovementThreshold in 0f..1f) { "책 감도는 0~1" }
            require(alertCooldownSeconds in 0..3_600) { "재알림 제한은 0~3600초" }
        }

        fun toMessage(messageId: String) = StudyMessage.StudySettings(
            messageId = messageId,
            meditationDurationMs = meditationMinutes * 60_000L,
            studyDurationMs = studyMinutes * 60_000L,
            breakDurationMs = breakMinutes * 60_000L,
            teacherCountdownMs = countdownSeconds * 1_000L,
            captureIntervalMs = captureSeconds * 1_000L,
            awayAfterMs = awaySeconds * 1_000L,
            noMovementAfterMs = noMovementSeconds * 1_000L,
            presenceThreshold = presenceThreshold,
            bookMovementThreshold = bookMovementThreshold,
            presenceRestoreThreshold = presenceRestoreThreshold,
            presenceMotionThreshold = presenceMotionThreshold,
            alertCooldownMs = alertCooldownSeconds * 1_000L,
            awayAlertEnabled = awayAlertEnabled,
            noMovementAlertEnabled = noMovementAlertEnabled,
            alertSoundEnabled = alertSoundEnabled,
        )
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 400
        private const val REQUEST_RECORD_AUDIO = 401
        private const val NOTIFICATION_CHANNEL = "study-events"
        private const val NOTIFICATION_CHANNEL_SILENT = "study-events-silent"
        private const val NOTIFICATION_PROBLEM = 101
        private const val NOTIFICATION_AWAY = 102
        private const val NOTIFICATION_NO_MOVEMENT = 103
        private const val NOTIFICATION_STUDENT_VOICE = 104
        private const val NOTIFICATION_STUDENT_TEXT = 105
        private const val MAX_RECEIVED_ASSETS = 40
        private const val MAX_RECENT_THUMBNAILS = 12
        private const val RECENT_THUMBNAIL_LONG_EDGE = 480
        private const val MAX_CONVERSATION_ENTRIES = 100
        private const val MAX_RECEIVED_VOICE_MESSAGES = 20
        private const val MAX_STORED_OUTGOING_VOICE_MESSAGES = 20
        private const val MAX_PENDING_INCOMING_FILES = 64
        private const val MAX_OUTGOING_FILE_RETRIES = 3
        private const val OUTGOING_FILE_RETRY_DELAY_MS = 2_000L
        private const val ROI_REQUEST_TIMEOUT_MS = 15_000L
        private const val CALIBRATION_REQUEST_TIMEOUT_MS = 25_000L
        private const val CAMERA_COMPARISON_TIMEOUT_MS = 75_000L
        private const val DEFAULT_DETAIL_ZOOM_RATIO = 2f
        private const val DEFAULT_FOCUS_TIMEOUT_MS = 2_000L
        private const val MAX_MESSAGE_TEXT_BYTES = 4 * 1024
        private const val MAX_STATUS_TEXT_CHARS = 120
        private const val MAX_VOICE_DURATION_MS = 60_000L
        private const val COLOR_BACKGROUND = 0xFFF5F7FC.toInt()
        private const val COLOR_TEXT = 0xFF172033.toInt()
        private const val COLOR_MUTED = 0xFF65708A.toInt()
        private const val COLOR_PRIMARY = 0xFF3457D5.toInt()
        private const val COLOR_SUCCESS = 0xFF1B8A5A.toInt()
        private const val COLOR_WARNING = 0xFF9A6A00.toInt()
        private const val COLOR_DANGER = 0xFFB33A3A.toInt()
    }
}

private data class DisplayBookRegion(
    val left: Float = 0.07f,
    val top: Float = 0.30f,
    val right: Float = 0.93f,
    val bottom: Float = 0.70f,
)

private class TeacherBookRegionOverlay(context: android.content.Context) : View(context) {
    var region: DisplayBookRegion = DisplayBookRegion()
        private set
    var editingEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.rgb(255, 196, 61)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 196, 61)
    }
    private var mode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var imageWidth = 0
    private var imageHeight = 0

    val hasImageSize: Boolean get() = imageWidth > 0 && imageHeight > 0

    fun setImageSize(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
        invalidate()
    }

    fun setRegion(value: DisplayBookRegion) {
        region = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!editingEnabled || !hasImageSize) return
        val rect = rect()
        canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
        listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.left to rect.bottom,
            rect.right to rect.bottom,
        ).forEach { (x, y) -> canvas.drawCircle(x, y, dp(10f), handlePaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editingEnabled || width == 0 || height == 0 || !hasImageSize) return false
        val content = imageContentRect()
        val x = event.x.coerceIn(content.left, content.right)
        val y = event.y.coerceIn(content.top, content.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hit(x, y, rect())
                if (mode == DragMode.NONE) return false
                lastX = x; lastY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DragMode.NONE) return false
                update((x - lastX) / content.width(), (y - lastY) / content.height())
                lastX = x; lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = mode != DragMode.NONE
                mode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return handled
            }
        }
        return false
    }

    private fun imageContentRect(): RectF {
        val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        return RectF(left, top, left + drawnWidth, top + drawnHeight)
    }

    private fun rect(): RectF {
        val content = imageContentRect()
        return RectF(
            content.left + region.left * content.width(),
            content.top + region.top * content.height(),
            content.left + region.right * content.width(),
            content.top + region.bottom * content.height(),
        )
    }

    private fun hit(x: Float, y: Float, rect: RectF): DragMode {
        val r = dp(36f)
        fun closeTo(px: Float, py: Float) = (x - px) * (x - px) + (y - py) * (y - py) <= r * r
        if (closeTo(rect.left, rect.top)) return DragMode.TOP_LEFT
        if (closeTo(rect.right, rect.top)) return DragMode.TOP_RIGHT
        if (closeTo(rect.left, rect.bottom)) return DragMode.BOTTOM_LEFT
        if (closeTo(rect.right, rect.bottom)) return DragMode.BOTTOM_RIGHT
        return if (rect.contains(x, y)) DragMode.MOVE else DragMode.NONE
    }

    private fun update(dx: Float, dy: Float) {
        val min = 0.12f
        var left = region.left; var top = region.top; var right = region.right; var bottom = region.bottom
        when (mode) {
            DragMode.MOVE -> {
                val moveX = dx.coerceIn(-left, 1f - right)
                val moveY = dy.coerceIn(-top, 1f - bottom)
                left += moveX; right += moveX; top += moveY; bottom += moveY
            }
            DragMode.TOP_LEFT -> { left = (left + dx).coerceIn(0f, right - min); top = (top + dy).coerceIn(0f, bottom - min) }
            DragMode.TOP_RIGHT -> { right = (right + dx).coerceIn(left + min, 1f); top = (top + dy).coerceIn(0f, bottom - min) }
            DragMode.BOTTOM_LEFT -> { left = (left + dx).coerceIn(0f, right - min); bottom = (bottom + dy).coerceIn(top + min, 1f) }
            DragMode.BOTTOM_RIGHT -> { right = (right + dx).coerceIn(left + min, 1f); bottom = (bottom + dy).coerceIn(top + min, 1f) }
            DragMode.NONE -> return
        }
        region = DisplayBookRegion(left, top, right, bottom)
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
}

/** FIT_CENTER image that supports a second-stage pinch zoom and drag. */
private class ZoomableImageView(context: android.content.Context) : ImageView(context) {
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val drawMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                val applied = nextZoom / zoom
                zoom = nextZoom
                drawMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clampAndApply()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        super.setImageBitmap(bitmap)
        post { resetToFitCenter() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFitCenter()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && zoom > 1f) {
                    drawMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    clampAndApply()
                }
                // Keep the reference point current during a pinch as well, so
                // lifting one finger cannot cause a large one-frame pan jump.
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }
        }
        return true
    }

    private fun resetToFitCenter() {
        val item = drawable ?: return
        if (width <= 0 || height <= 0 || item.intrinsicWidth <= 0 || item.intrinsicHeight <= 0) return
        val baseScale = minOf(
            width.toFloat() / item.intrinsicWidth,
            height.toFloat() / item.intrinsicHeight,
        )
        val left = (width - item.intrinsicWidth * baseScale) / 2f
        val top = (height - item.intrinsicHeight * baseScale) / 2f
        drawMatrix.setScale(baseScale, baseScale)
        drawMatrix.postTranslate(left, top)
        zoom = 1f
        imageMatrix = drawMatrix
    }

    private fun clampAndApply() {
        val item = drawable ?: return
        val bounds = RectF(0f, 0f, item.intrinsicWidth.toFloat(), item.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> width / 2f - bounds.centerX()
            bounds.left > 0f -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> height / 2f - bounds.centerY()
            bounds.top > 0f -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }
}
