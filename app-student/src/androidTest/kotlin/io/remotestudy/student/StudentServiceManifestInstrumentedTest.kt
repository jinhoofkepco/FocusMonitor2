package io.remotestudy.student

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentServiceManifestInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun removingRecentTaskStopsStudyService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, StudentStudyService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(service.flags and ServiceInfo.FLAG_STOP_WITH_TASK != 0)
    }

    @Suppress("DEPRECATION")
    @Test fun removingAppTaskActuallyStopsRunningForegroundService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TelegramCredentialStore(context)
        context.stopService(Intent(context, StudentStudyService::class.java))
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        assertTrue(waitUntil { !isServiceRunning(context) })
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        store.save(
            TelegramCredentials(
                botToken = "123456789:abcdefghijklmnopqrstuvwxyz_ABCDEFGH",
                chatId = 987654321L,
                chatLabel = "task-removal-test",
            ),
        )
        try {
            context.startActivity(
                Intent(context, StudentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            assertTrue(waitUntil { isServiceRunning(context) })
            assertTrue(waitUntil { context.getSystemService(ActivityManager::class.java).appTasks.isNotEmpty() })
            // A running ServiceRecord can be observed just before startForeground() is committed.
            // Give the real Activity-started foreground-service transaction time to settle.
            Thread.sleep(750L)
            context.getSystemService(ActivityManager::class.java).appTasks.first().finishAndRemoveTask()

            assertTrue(waitUntil(timeoutMs = 5_000L) { !isServiceRunning(context) })
        } finally {
            context.stopService(Intent(context, StudentStudyService::class.java))
            store.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == StudentStudyService::class.java.name }

    private fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(50L)
        }
        return condition()
    }
}
