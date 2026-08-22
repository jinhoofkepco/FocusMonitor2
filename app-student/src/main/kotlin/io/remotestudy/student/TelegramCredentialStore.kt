package io.remotestudy.student

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class TelegramCredentials(
    val botToken: String,
    val chatId: Long,
    val chatLabel: String,
)

internal class TelegramCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(credentials: TelegramCredentials) {
        require(credentials.botToken.isNotBlank() && credentials.chatId != 0L)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(credentials.botToken.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putLong(KEY_CHAT_ID, credentials.chatId)
                .putString(KEY_CHAT_LABEL, credentials.chatLabel)
                .commit(),
        ) { "텔레그램 설정을 저장하지 못했습니다" }
    }

    fun load(): TelegramCredentials? = runCatching {
        val encrypted = Base64.decode(preferences.getString(KEY_TOKEN, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(preferences.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val chatId = preferences.getLong(KEY_CHAT_ID, 0L).takeIf { it != 0L } ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        TelegramCredentials(
            botToken = cipher.doFinal(encrypted).toString(Charsets.UTF_8),
            chatId = chatId,
            chatLabel = preferences.getString(KEY_CHAT_LABEL, "텔레그램") ?: "텔레그램",
        )
    }.getOrNull()

    fun clear() {
        preferences.edit().clear().commit()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "telegram-credentials"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "focusmonitor2.telegram.bot-token.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_TOKEN = "token_ciphertext"
        const val KEY_IV = "token_iv"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_CHAT_LABEL = "chat_label"
    }
}
