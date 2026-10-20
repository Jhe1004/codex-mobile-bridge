package org.codexmobile.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekProtocolTest {
    @Test
    fun requestUsesCurrentFlashAliasWithoutEmbeddingCredentials() {
        val body = DeepSeekProtocol.requestBody(
            listOf(
                DeepSeekMessage("1", "user", "测试问题"),
                DeepSeekMessage("2", "assistant", "测试答案"),
            ),
        )
        val json = JSONObject(body)

        assertEquals("deepseek-flash", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals("enabled", json.getJSONObject("thinking").getString("type"))
        assertEquals("high", json.getString("reasoning_effort"))
        assertFalse(body.contains("sk-"))
    }

    @Test
    fun parsesReasoningContentAndCompletion() {
        val reasoning = DeepSeekProtocol.parseStreamData(
            """{"choices":[{"delta":{"reasoning_content":"thinking"},"finish_reason":null}]}""",
        )
        val content = DeepSeekProtocol.parseStreamData(
            """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
        )

        assertEquals("thinking", reasoning?.reasoning)
        assertEquals("answer", content?.content)
        assertTrue(content?.finished == true)
        assertTrue(DeepSeekProtocol.parseStreamData("[DONE]")?.finished == true)
    }

    @Test
    fun turnsSensitiveApiErrorsIntoReadableMessages() {
        assertEquals(
            "DeepSeek API 密钥无效，请重新设置。",
            DeepSeekProtocol.readableError(401, "{}"),
        )
        assertEquals(
            "DeepSeek 返回错误：bad request",
            DeepSeekProtocol.readableError(400, """{"error":{"message":"bad request"}}"""),
        )
    }
}
