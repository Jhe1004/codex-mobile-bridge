package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SvgPreviewDocumentTest {
    @Test
    fun embedsSvgAsLocalBase64AndEnablesPinchZoom() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>中文 &amp; test</text></svg>"
        val html = buildSvgPreviewHtml(svg.toByteArray())

        assertTrue(html.contains("data:image/svg+xml;base64,"))
        assertTrue(html.contains("maximum-scale=8"))
        assertTrue(html.contains("user-scalable=yes"))
        assertFalse(html.contains(svg))
        val encoded = html.substringAfter("data:image/svg+xml;base64,").substringBefore('"')
        assertEquals(svg, String(Base64.getDecoder().decode(encoded)))
    }

    @Test
    fun rejectsEmptyOrOversizedSvg() {
        assertThrows(IllegalArgumentException::class.java) { buildSvgPreviewHtml(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            buildSvgPreviewHtml(ByteArray(SVG_PREVIEW_MAX_BYTES.toInt() + 1))
        }
    }
}
