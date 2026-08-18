package io.remotestudy.student

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.remotestudy.telegram.NormalizedBookRegion
import io.remotestudy.telegram.TelegramSetupChat
import io.remotestudy.telegram.TelegramSetupClient
import java.io.File
import java.util.concurrent.Executors

class StudentActivity : Activity() {
    private lateinit var image: BookRegionImageView
    private lateinit var state: TextView
    private val credentialStore by lazy { TelegramCredentialStore(this) }
    private val setupExecutor = Executors.newSingleThreadExecutor { Thread(it, "telegram-setup") }
    private var displayedBitmap: Bitmap? = null
    private var mainScreen = false
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRegion: NormalizedBookRegion? = null
    private val sendRegion = Runnable {
        pendingRegion?.let(::sendRegionToService)
        pendingRegion = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!mainScreen) return
            state.text = intent?.getStringExtra(StudentStudyService.EXTRA_TEXT) ?: state.text
            intent?.getStringExtra(StudentStudyService.EXTRA_FILE)?.let(::showImage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showAppropriateScreen()
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiverIfNeeded()
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        setupExecutor.shutdownNow()
        displayedBitmap?.recycle()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startStudyService()
            else state.text = "카메라·마이크 권한이 필요합니다"
        }
    }

    private fun showAppropriateScreen() {
        val credentials = effectiveCredentials()
        if (credentials == null) showSetupScreen() else showMainScreen(credentials)
    }

    private fun showSetupScreen() {
        mainScreen = false
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val tokenInput = EditText(this).apply {
            hint = "BotFather가 준 봇 토큰 붙여넣기"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            textSize = 15f
        }
        val setupState = TextView(this).apply {
            text = "1. 텔레그램에서 새 봇에게 /연결 을 보내세요.\n2. 아래에 BotFather 토큰을 붙여넣고 채팅 찾기를 누르세요."
            textSize = 17f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        val findButton = Button(this).apply {
            text = "내 텔레그램 채팅 찾기"
            setOnClickListener {
                val token = tokenInput.text.toString().trim()
                if (token.isEmpty()) {
                    setupState.text = "봇 토큰을 먼저 붙여넣으세요"
                    return@setOnClickListener
                }
                isEnabled = false
                setupState.text = "봇과 선생님 채팅을 확인하는 중…\n최대 20초 정도 걸릴 수 있습니다."
                setupExecutor.execute {
                    val result = runCatching {
                        val client = TelegramSetupClient()
                        val botName = client.verifyBot(token)
                        botName to client.findConnectionChats(token)
                    }
                    handler.post {
                        isEnabled = true
                        result.onSuccess { (botName, chats) ->
                            when {
                                chats.isEmpty() -> setupState.text =
                                    "@$botName 봇은 확인했지만 선생님 채팅을 찾지 못했습니다. " +
                                        "그 봇에게 /connect 를 새로 보낸 직후 다시 누르세요."
                                chats.size == 1 -> confirmChat(token, botName, chats.first(), setupState, this)
                                else -> chooseChat(token, botName, chats, setupState, this)
                            }
                        }.onFailure {
                            setupState.text = "연결 실패: ${friendlySetupError(it)}"
                        }
                    }
                }
            }
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.rgb(245, 247, 250))
                addView(LinearLayout(this@StudentActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(80), dp(40), dp(80), dp(40))
                    addView(TextView(this@StudentActivity).apply {
                        text = "텔레그램 연결"
                        textSize = 28f
                        setTextColor(Color.rgb(17, 24, 39))
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(setupState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
                    addView(tokenInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
                    addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
                    addView(TextView(this@StudentActivity).apply {
                        text = "토큰은 이 학생폰의 Android Keystore로 암호화되어 저장되며 GitHub나 텔레그램으로 다시 전송되지 않습니다."
                        textSize = 13f
                        setTextColor(Color.GRAY)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
                })
            },
        )
    }

    private fun chooseChat(
        token: String,
        botName: String,
        chats: List<TelegramSetupChat>,
        setupState: TextView,
        button: Button,
    ) {
        val labels = chats.map(::chatLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("내 채팅을 선택하세요")
            .setItems(labels) { _, index -> confirmChat(token, botName, chats[index], setupState, button) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmChat(
        token: String,
        botName: String,
        chat: TelegramSetupChat,
        setupState: TextView,
        button: Button,
    ) {
        AlertDialog.Builder(this)
            .setTitle("이 텔레그램이 맞습니까?")
            .setMessage("봇: @$botName\n사용자: ${chatLabel(chat)}")
            .setPositiveButton("연결") { _, _ -> completeSetup(token, chat, setupState, button) }
            .setNegativeButton("아니요", null)
            .show()
    }

    private fun completeSetup(token: String, chat: TelegramSetupChat, setupState: TextView, button: Button) {
        button.isEnabled = false
        setupState.text = "연결 시험 메시지를 보내는 중…"
        setupExecutor.execute {
            val result = runCatching {
                TelegramSetupClient().sendConnectionTest(token, chat.chatId)
                stopService(Intent(this, StudentStudyService::class.java))
                purgeOldTelegramData()
                credentialStore.save(TelegramCredentials(token, chat.chatId, chatLabel(chat)))
            }
            handler.post {
                button.isEnabled = true
                result.onSuccess {
                    AlertDialog.Builder(this)
                        .setTitle("연결 완료")
                        .setMessage("텔레그램에 연결 완료 메시지가 도착했습니다. 이제 /start 로 시작할 수 있습니다.")
                        .setPositiveButton("학생 화면 열기") { _, _ -> recreate() }
                        .setCancelable(false)
                        .show()
                }.onFailure { setupState.text = "저장 실패: ${friendlySetupError(it)}" }
            }
        }
    }

    private fun showMainScreen(credentials: TelegramCredentials) {
        mainScreen = true
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(17, 24, 39))
            image = BookRegionImageView(this@StudentActivity).apply {
                setBackgroundColor(Color.BLACK)
                contentDescription = "최신 전체 사진과 책 영역"
                onRegionChanged = { region ->
                    pendingRegion = region
                    handler.removeCallbacks(sendRegion)
                    handler.postDelayed(sendRegion, 350L)
                }
            }
            addView(image, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(LinearLayout(this@StudentActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14), dp(12), 0, dp(12))
                state = TextView(this@StudentActivity).apply {
                    text = "${credentials.chatLabel}\n텔레그램 서비스 시작 중"
                    textSize = 19f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }
                addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(TextView(this@StudentActivity).apply {
                    text = "초록 사각형의 변을 끌어 책 영역을 정하세요.\n공부 시작과 정지는 텔레그램에서만 제어합니다."
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(Button(this@StudentActivity).apply {
                    text = "촬영 서비스 종료"
                    textSize = 12f
                    setOnClickListener {
                        startService(Intent(this@StudentActivity, StudentStudyService::class.java).setAction(StudentStudyService.ACTION_STOP_SERVICE))
                        state.text = "서비스 종료 요청"
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
                addView(Button(this@StudentActivity).apply {
                    text = "텔레그램 연결 초기화"
                    textSize = 12f
                    setOnClickListener { confirmResetTelegram() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT))
        })
        loadSavedRegion()
        newestOriginal()?.let(::showImage)
        registerStateReceiverIfNeeded()
        requestPermissionsAndStart()
    }

    private fun confirmResetTelegram() {
        AlertDialog.Builder(this)
            .setTitle("텔레그램 연결 초기화")
            .setMessage("현재 봇 설정과 미전송 사진·메시지를 삭제하고 설정 화면으로 돌아갑니다.")
            .setPositiveButton("초기화") { _, _ ->
                stopService(Intent(this, StudentStudyService::class.java))
                credentialStore.clear()
                handler.postDelayed({ purgeOldTelegramData(); recreate() }, 500L)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun effectiveCredentials(): TelegramCredentials? = credentialStore.load()
        ?: if (BuildConfig.TELEGRAM_BOT_TOKEN.isNotBlank() && BuildConfig.TELEGRAM_CHAT_ID != 0L) {
            TelegramCredentials(BuildConfig.TELEGRAM_BOT_TOKEN, BuildConfig.TELEGRAM_CHAT_ID, "빌드에 설정된 텔레그램")
        } else null

    private fun chatLabel(chat: TelegramSetupChat): String = buildString {
        append(chat.displayName)
        chat.username?.let { append(" (@").append(it).append(')') }
        append(" · ID ").append(chat.chatId)
    }

    private fun friendlySetupError(error: Throwable): String = when {
        error.message?.contains("Unauthorized", ignoreCase = true) == true -> "토큰이 틀렸습니다. BotFather 토큰을 다시 복사하세요."
        error.message?.contains("Conflict", ignoreCase = true) == true ->
            "이 봇을 다른 앱이 동시에 확인 중입니다. 다른 봇 프로그램을 끄고 1분 뒤 다시 누르세요."
        else -> error.message ?: "인터넷 연결을 확인하세요"
    }

    private fun purgeOldTelegramData() {
        File(filesDir, "telegram-report").deleteRecursively()
    }

    private fun registerStateReceiverIfNeeded() {
        if (!mainScreen || receiverRegistered || isFinishing) return
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(StudentStudyService.ACTION_STATE)
                addAction(StudentStudyService.ACTION_CAPTURE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startStudyService() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startStudyService() {
        ContextCompat.startForegroundService(this, Intent(this, StudentStudyService::class.java))
    }

    private fun sendRegionToService(region: NormalizedBookRegion) {
        getSharedPreferences("student-study", MODE_PRIVATE).edit()
            .putFloat("region_left", region.left).putFloat("region_top", region.top)
            .putFloat("region_right", region.right).putFloat("region_bottom", region.bottom).apply()
        startService(
            Intent(this, StudentStudyService::class.java)
                .setAction(StudentStudyService.ACTION_UPDATE_REGION)
                .putExtra(StudentStudyService.EXTRA_LEFT, region.left)
                .putExtra(StudentStudyService.EXTRA_TOP, region.top)
                .putExtra(StudentStudyService.EXTRA_RIGHT, region.right)
                .putExtra(StudentStudyService.EXTRA_BOTTOM, region.bottom),
        )
    }

    private fun loadSavedRegion() {
        val preferences = getSharedPreferences("student-study", MODE_PRIVATE)
        image.setRegion(
            NormalizedBookRegion(
                preferences.getFloat("region_left", NormalizedBookRegion.DEFAULT.left),
                preferences.getFloat("region_top", NormalizedBookRegion.DEFAULT.top),
                preferences.getFloat("region_right", NormalizedBookRegion.DEFAULT.right),
                preferences.getFloat("region_bottom", NormalizedBookRegion.DEFAULT.bottom),
            ),
        )
    }

    private fun newestOriginal(): String? = File(filesDir, "telegram-report/originals")
        .listFiles()?.filter(File::isFile)?.maxByOrNull(File::lastModified)?.absolutePath

    private fun showImage(path: String) {
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return
        displayedBitmap?.recycle()
        displayedBitmap = bitmap
        image.setImageBitmap(bitmap)
    }

    private companion object { const val REQUEST_PERMISSIONS = 401 }
}
