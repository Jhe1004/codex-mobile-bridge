package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekQuoteDraftTest {
    @Test
    fun quotesEverySelectedLineAndLeavesQuestionPrompt() {
        assertEquals(
            "关于下面这段内容，我想继续提问：\n\n> 第一行\n> 第二行\n\n我的问题是：",
            deepSeekQuoteDraft("第一行\n第二行"),
        )
    }
}
