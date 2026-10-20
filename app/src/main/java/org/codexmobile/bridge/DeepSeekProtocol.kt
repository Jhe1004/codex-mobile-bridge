package org.codexmobile.bridge

import org.json.JSONArray
import org.json.JSONObject

internal const val DEEPSEEK_MODEL = "deepseek-flash"
internal const val DEEPSEEK_MODEL_LABEL = "DeepSeek V4.1 Flash"
internal const val DEEPSEEK_CHAT_URL = "https://api.deepseek.com/chat/completions"

internal data class DeepSeekMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

internal data class DeepSeekStreamDelta(
    val reasoning: String = "",
    val content: String = "",
    val finished: Boolean = false,
)

internal object DeepSeekProtocol {
    fun requestBody(
        messages: List<DeepSeekMessage>,
        context: DeepSeekConversationContext? = null,
    ): String {
        val apiMessages = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "请用简体中文清楚、直接地回答用户。用 Markdown 组织需要分层的内容。"),
            )
            context?.takeIf { it.messages.isNotEmpty() }?.let { hiddenContext ->
                put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", hiddenContext.asHiddenSystemMessage()),
                )
            }
            messages
                .filter { it.role == "user" || it.role == "assistant" }
                .takeLast(MAX_CONTEXT_MESSAGES)
                .forEach { message ->
                    put(JSONObject().put("role", message.role).put("content", message.text))
                }
        }
        return JSONObject()
            .put("model", DEEPSEEK_MODEL)
            .put("messages", apiMessages)
            .put("stream", true)
            .put("thinking", JSONObject().put("type", "enabled"))
            .put("reasoning_effort", "high")
            .toString()
    }

    fun parseStreamData(data: String): DeepSeekStreamDelta? {
        if (data == "[DONE]") return DeepSeekStreamDelta(finished = true)
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: JSONObject()
        return DeepSeekStreamDelta(
            reasoning = delta.optString("reasoning_content"),
            content = delta.optString("content"),
            finished = !choice.isNull("finish_reason"),
        )
    }

    fun readableError(code: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return when {
            code == 401 -> "DeepSeek API 密钥无效，请重新设置。"
            code == 402 -> "DeepSeek 账户余额不足。"
            code == 429 -> "DeepSeek 当前请求过多，请稍后再试。"
            apiMessage.isNotBlank() -> "DeepSeek 返回错误：$apiMessage"
            else -> "DeepSeek 请求失败（HTTP $code）。"
        }
    }

    private const val MAX_CONTEXT_MESSAGES = 40
}
