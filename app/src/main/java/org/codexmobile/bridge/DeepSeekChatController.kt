package org.codexmobile.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class DeepSeekUiState(
    val messages: List<DeepSeekMessage> = emptyList(),
    val draft: String = "",
    val streamingText: String = "",
    val thinking: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val hasApiKey: Boolean = false,
    val attachedContext: DeepSeekConversationContext? = null,
)

internal class DeepSeekChatController(context: Context) {
    private val appContext = context.applicationContext
    private val credentialStore = DeepSeekCredentialStore(appContext)
    private val chatStore = DeepSeekChatStore(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val requestGeneration = AtomicLong(0L)
    private var activeCall: Call? = null
    private var currentSessionKey = STANDALONE_SESSION
    private val mutableState = MutableStateFlow(
        DeepSeekUiState(
            messages = chatStore.load(currentSessionKey),
            draft = chatStore.loadDraft(currentSessionKey),
            hasApiKey = credentialStore.load().isNotBlank(),
        ),
    )
    val state: StateFlow<DeepSeekUiState> = mutableState.asStateFlow()

    fun saveApiKey(apiKey: String): Boolean {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) return false
        credentialStore.save(normalized)
        mutableState.update { it.copy(hasApiKey = true, error = null) }
        return true
    }

    fun deleteApiKey() {
        cancel()
        credentialStore.clear()
        mutableState.update { it.copy(hasApiKey = false, error = null) }
    }

    fun attachContext(context: DeepSeekConversationContext) {
        cancel()
        val nextSessionKey = context.threadId.ifBlank { STANDALONE_SESSION }
        if (nextSessionKey == currentSessionKey) {
            mutableState.update { it.copy(attachedContext = context, error = null) }
            return
        }
        val previousState = mutableState.value
        var nextMessages = chatStore.load(nextSessionKey)
        var nextDraft = chatStore.loadDraft(nextSessionKey)
        if (
            currentSessionKey == STANDALONE_SESSION &&
            nextMessages.isEmpty() &&
            nextDraft.isBlank() &&
            (previousState.messages.isNotEmpty() || previousState.draft.isNotBlank())
        ) {
            // 0.27.0 had one global DeepSeek chat. Preserve it by moving it into the
            // first Codex chat opened after this per-chat storage upgrade.
            nextMessages = previousState.messages
            nextDraft = previousState.draft
            chatStore.save(nextSessionKey, nextMessages)
            chatStore.saveDraft(nextSessionKey, nextDraft)
            chatStore.clear(STANDALONE_SESSION)
            chatStore.saveDraft(STANDALONE_SESSION, "")
        }
        currentSessionKey = nextSessionKey
        mutableState.update {
            it.copy(
                messages = nextMessages,
                draft = nextDraft,
                streamingText = "",
                thinking = false,
                sending = false,
                error = null,
                attachedContext = context,
            )
        }
    }

    fun detachContext() {
        mutableState.update { it.copy(attachedContext = null) }
    }

    fun clearConversation() {
        cancel()
        chatStore.clear(currentSessionKey)
        chatStore.saveDraft(currentSessionKey, "")
        mutableState.update { it.copy(messages = emptyList(), draft = "", streamingText = "", error = null) }
    }

    fun updateDraft(text: String) {
        chatStore.saveDraft(currentSessionKey, text)
        mutableState.update { it.copy(draft = text) }
    }

    fun send(text: String) {
        val prompt = text.trim()
        val apiKey = credentialStore.load()
        if (prompt.isBlank() || mutableState.value.sending) return
        if (apiKey.isBlank()) {
            mutableState.update { it.copy(hasApiKey = false, error = "请先设置 DeepSeek API 密钥。") }
            return
        }

        val userMessage = DeepSeekMessage(UUID.randomUUID().toString(), "user", prompt)
        val requestMessages = mutableState.value.messages + userMessage
        chatStore.save(currentSessionKey, requestMessages)
        chatStore.saveDraft(currentSessionKey, "")
        mutableState.update {
            it.copy(
                messages = requestMessages,
                draft = "",
                streamingText = "",
                thinking = true,
                sending = true,
                error = null,
                hasApiKey = true,
            )
        }

        val generation = requestGeneration.incrementAndGet()
        val request = Request.Builder()
            .url(DEEPSEEK_CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(
                DeepSeekProtocol.requestBody(requestMessages, mutableState.value.attachedContext)
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (generation != requestGeneration.get()) return
                finishWithError(
                    generation,
                    if (e.message?.contains("Unable to resolve host", ignoreCase = true) == true) {
                        "手机当前无法连接 DeepSeek，请检查网络。"
                    } else {
                        "DeepSeek 连接失败：${e.message ?: "未知网络错误"}"
                    },
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    if (generation != requestGeneration.get()) return
                    if (!result.isSuccessful) {
                        finishWithError(
                            generation,
                            DeepSeekProtocol.readableError(result.code, result.body.string()),
                        )
                        return
                    }
                    val source = result.body.source()
                    while (!source.exhausted() && generation == requestGeneration.get()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val delta = DeepSeekProtocol.parseStreamData(line.removePrefix("data:").trim()) ?: continue
                        if (delta.reasoning.isNotEmpty()) {
                            mutableState.update { it.copy(thinking = true) }
                        }
                        if (delta.content.isNotEmpty()) {
                            mutableState.update {
                                it.copy(
                                    streamingText = it.streamingText + delta.content,
                                    thinking = false,
                                )
                            }
                        }
                        if (delta.finished) break
                    }
                    finishSuccess(generation)
                }
            }
        })
    }

    fun cancel() {
        requestGeneration.incrementAndGet()
        activeCall?.cancel()
        activeCall = null
        val current = mutableState.value
        val partial = current.streamingText.trim()
        val messages = if (current.sending && partial.isNotBlank()) {
            current.messages + DeepSeekMessage(UUID.randomUUID().toString(), "assistant", partial)
        } else {
            current.messages
        }
        chatStore.save(currentSessionKey, messages)
        mutableState.value = current.copy(
            messages = messages,
            streamingText = "",
            thinking = false,
            sending = false,
        )
    }

    fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun finishSuccess(generation: Long) {
        if (generation != requestGeneration.get()) return
        val current = mutableState.value
        val answer = current.streamingText.trim()
        if (answer.isBlank()) {
            finishWithError(generation, "DeepSeek 未返回可显示的答案。")
            return
        }
        val messages = current.messages + DeepSeekMessage(UUID.randomUUID().toString(), "assistant", answer)
        chatStore.save(currentSessionKey, messages)
        activeCall = null
        mutableState.value = current.copy(
            messages = messages,
            streamingText = "",
            thinking = false,
            sending = false,
            error = null,
        )
    }

    private fun finishWithError(generation: Long, message: String) {
        if (generation != requestGeneration.get()) return
        activeCall = null
        mutableState.update {
            it.copy(
                thinking = false,
                sending = false,
                error = message,
            )
        }
    }

    companion object {
        private const val STANDALONE_SESSION = "standalone"
    }
}

private class DeepSeekCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(VALUE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String {
        val iv = preferences.getString(IV_KEY, null) ?: return ""
        val encrypted = preferences.getString(VALUE_KEY, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            clear()
            ""
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "deepseek_credentials_v1"
        private const val IV_KEY = "iv"
        private const val VALUE_KEY = "value"
        private const val KEY_ALIAS = "CodexMobileBridgeDeepSeekApiKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private class DeepSeekChatStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(sessionKey: String): List<DeepSeekMessage> {
        val raw = preferences.getString(historyKey(sessionKey), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val role = item.optString("role")
                    val text = item.optString("text")
                    if ((role == "user" || role == "assistant") && text.isNotBlank()) {
                        add(
                            DeepSeekMessage(
                                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                                role = role,
                                text = text,
                                createdAt = item.optLong("createdAt", 0L),
                            ),
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(sessionKey: String, messages: List<DeepSeekMessage>) {
        val kept = messages.takeLast(MAX_SAVED_MESSAGES)
        val array = JSONArray().apply {
            kept.forEach { message ->
                put(
                    JSONObject()
                        .put("id", message.id)
                        .put("role", message.role)
                        .put("text", message.text)
                        .put("createdAt", message.createdAt),
                )
            }
        }
        preferences.edit().putString(historyKey(sessionKey), array.toString()).apply()
    }

    fun clear(sessionKey: String) {
        preferences.edit().remove(historyKey(sessionKey)).apply()
    }

    fun loadDraft(sessionKey: String): String = preferences.getString(draftKey(sessionKey), "").orEmpty()

    fun saveDraft(sessionKey: String, text: String) {
        val key = draftKey(sessionKey)
        preferences.edit().apply {
            if (text.isEmpty()) remove(key) else putString(key, text)
        }.apply()
    }

    private fun historyKey(sessionKey: String): String = if (sessionKey == STANDALONE_SESSION) {
        HISTORY_KEY
    } else {
        "${HISTORY_KEY}_${sessionHash(sessionKey)}"
    }

    private fun draftKey(sessionKey: String): String = if (sessionKey == STANDALONE_SESSION) {
        DRAFT_KEY
    } else {
        "${DRAFT_KEY}_${sessionHash(sessionKey)}"
    }

    private fun sessionHash(sessionKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFERENCES_NAME = "deepseek_chat_v1"
        private const val HISTORY_KEY = "messages"
        private const val DRAFT_KEY = "draft"
        private const val MAX_SAVED_MESSAGES = 80
        private const val STANDALONE_SESSION = "standalone"
    }
}
