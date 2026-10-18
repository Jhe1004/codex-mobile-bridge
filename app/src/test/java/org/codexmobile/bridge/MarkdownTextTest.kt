package org.codexmobile.bridge

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun parsesCommonMarkdownBlocks() {
        val source = """
            # 标题

            - 第一项
            2. 第二项

            > 引用内容

            ```kotlin
            val answer = 42
            ```

            | 名称 | 状态 |
            | --- | --- |
            | 桥接 | 正常 |
        """.trimIndent()

        val blocks = parseMarkdown(source)

        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.text == "标题" })
        assertTrue(blocks.any { it is MarkdownBlock.Bullet && it.text == "第一项" })
        assertTrue(blocks.any { it is MarkdownBlock.Numbered && it.number == "2" })
        assertTrue(blocks.any { it is MarkdownBlock.Quote && it.text == "引用内容" })
        assertTrue(blocks.any { it is MarkdownBlock.Code && it.language == "kotlin" && it.code.contains("answer") })
        assertTrue(blocks.any { it is MarkdownBlock.Table && it.rows.first()[1] == "正常" })
    }

    @Test
    fun rendersInlineStylesAndLinksWithoutMarkdownMarkers() {
        val rendered = inlineMarkdown(
            "**加粗**、`代码`和[本地文件](/tmp/a.txt)",
            Color.Black,
            Color.Blue,
            Color.LightGray,
        )

        assertEquals("加粗、代码和本地文件", rendered.text)
        assertEquals("/tmp/a.txt", rendered.getStringAnnotations("LINK", 0, rendered.length).single().item)
        assertTrue(rendered.spanStyles.size >= 3)
    }

    @Test
    fun automaticallyLinksWebUrls() {
        val rendered = inlineMarkdown(
            "查看 https://example.com/test。",
            Color.Black,
            Color.Blue,
            Color.LightGray,
        )

        assertEquals("https://example.com/test", rendered.getStringAnnotations("LINK", 0, rendered.length).single().item)
    }
}
