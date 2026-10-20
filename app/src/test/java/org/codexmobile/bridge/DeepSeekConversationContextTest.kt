package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekConversationContextTest {
    @Test
    fun prioritizesSelectedMessageAndKeepsContextHiddenFromDraft() {
        val messages = (0 until 40).map { index ->
            ChatMessage(
                id = "message-$index",
                role = if (index % 2 == 0) "user" else "assistant",
                text = "第 $index 条消息 " + "内容".repeat(600),
            )
        }

        val context = buildDeepSeekConversationContext(
            threadId = "thread-1",
            projectName = "项目甲",
            threadTitle = "会话乙",
            messages = messages,
            selectedMessageId = "message-7",
        )

        assertTrue(context.messages.any { it.id == "message-7" && it.selectedSource })
        assertTrue(context.messages.any { it.id == "message-39" })
        assertTrue(context.messageCount <= 30)
        assertTrue(context.characterCount <= 28_000)
        assertTrue(context.asHiddenSystemMessage().contains("项目：项目甲"))
        assertEquals(
            "关于下面这段内容，我想继续提问：\n\n> 选中的一句话\n\n我的问题是：",
            deepSeekQuoteDraft("选中的一句话"),
        )
    }

    @Test
    fun defaultsToNewestMessagesWhenNothingWasSelected() {
        val messages = (0 until 40).map { index ->
            ChatMessage("message-$index", "assistant", "message $index")
        }

        val context = buildDeepSeekConversationContext(
            threadId = "thread-1",
            projectName = "项目",
            threadTitle = "会话",
            messages = messages,
        )

        assertEquals("message-10", context.messages.first().id)
        assertEquals("message-39", context.messages.last().id)
    }
}
