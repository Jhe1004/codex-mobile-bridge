package org.codexmobile.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairingCredentials(
    val relayUrl: String,
    val relayToken: String,
)

object PairingStore {
    private const val ALIAS = "codex_mobile_pairing_v1"
    private const val PREFS = "codex_mobile_secure"
    private const val ENTRY = "pairing"
    private const val PENDING_FILE = "pairing.json"

    fun load(context: Context): PairingCredentials? {
        importPending(context)
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null) ?: return null
        return runCatching { decode(decrypt(encoded)) }.getOrNull()
    }

    private fun importPending(context: Context) {
        val file = context.filesDir.resolve(PENDING_FILE)
        if (!file.exists()) return
        val raw = file.readText(StandardCharsets.UTF_8)
        val parsed = decode(raw)
        require(parsed.relayToken.length >= 32)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ENTRY, encrypt(raw))
            .commit()
        check(saved) { "安全配对数据保存失败" }
        check(file.delete()) { "配对数据已加密保存，但临时文件删除失败" }
    }

    private fun decode(raw: String): PairingCredentials {
        val json = JSONObject(raw)
        val relayUrl = json.optString("relayUrl", BuildConfig.RELAY_URL).trim()
        require(relayUrl.startsWith("http://") || relayUrl.startsWith("https://") ||
            relayUrl.startsWith("ws://") || relayUrl.startsWith("wss://")) {
            "relayUrl must use http, https, ws, or wss"
        }
        return PairingCredentials(relayUrl, json.getString("relayToken"))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(raw: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(raw.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }
}
