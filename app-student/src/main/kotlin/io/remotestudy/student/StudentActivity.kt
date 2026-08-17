package io.remotestudy.student

import android.Manifest
import android.app.Activity
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
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.remotestudy.telegram.NormalizedBookRegion
import java.io.File

class StudentActivity : Activity() {
    private lateinit var image: BookRegionImageView
    private lateinit var state: TextView
    private var displayedBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRegion: NormalizedBookRegion? = null
    private val sendRegion = Runnable {
        pendingRegion?.let(::sendRegionToService)
        pendingRegion = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            state.text = intent?.getStringExtra(StudentStudyService.EXTRA_TEXT) ?: state.text
            intent?.getStringExtra(StudentStudyService.EXTRA_FILE)?.let(::showImage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContent())
        loadSavedRegion()
        newestOriginal()?.let(::showImage)
        requestPermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(StudentStudyService.ACTION_STATE)
                addAction(StudentStudyService.ACTION_CAPTURE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        displayedBitmap?.recycle()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startService()
            else state.text = "카메라·마이크 권한이 필요합니다"
        }
    }

    private fun buildContent(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
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
                    text = if (BuildConfig.TELEGRAM_BOT_TOKEN.isBlank()) {
                        "텔레그램 설정 필요\nlocal.properties에 봇 토큰과 chat_id를 넣으세요"
                    } else "서비스 시작 중"
                    textSize = 20f
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
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startService() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startService() {
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
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return
        displayedBitmap?.recycle()
        displayedBitmap = bitmap
        image.setImageBitmap(bitmap)
    }

    private companion object { const val REQUEST_PERMISSIONS = 401 }
}
