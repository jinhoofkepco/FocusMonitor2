package io.remotestudy.teacher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Keeps the private Nearby process foreground while the teacher checks another app. */
class TeacherKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "원격 공부 연결 유지",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "선생님 앱이 다른 화면에 있을 때 학생폰 연결을 유지합니다"
                setSound(null, null)
                enableVibration(false)
            },
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TeacherActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("학생폰 연결 유지 중")
            .setContentText("다른 앱을 보는 동안에도 사진과 메시지를 받습니다")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL_ID = "teacher-connection-keepalive"
        private const val NOTIFICATION_ID = 210
    }
}
