package io.remotestudy.student

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramCredentialStoreInstrumentedTest {
    private val store = TelegramCredentialStore(ApplicationProvider.getApplicationContext())

    @After fun cleanUp() = store.clear()

    @Test fun tokenRoundTripsThroughAndroidKeystoreAndClearRemovesIt() {
        val expected = TelegramCredentials(
            botToken = "123456789:abcdefghijklmnopqrstuvwxyz_ABCDEFGH",
            chatId = 987654321L,
            chatLabel = "테스트 사용자",
        )
        store.save(expected)

        assertEquals(expected, store.load())
        val rawValues = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("telegram-credentials", android.content.Context.MODE_PRIVATE)
            .all.values.map { value -> value.toString() }
        assertFalse(rawValues.any { it.contains(expected.botToken) })
        store.clear()
        assertNull(store.load())
    }
}
