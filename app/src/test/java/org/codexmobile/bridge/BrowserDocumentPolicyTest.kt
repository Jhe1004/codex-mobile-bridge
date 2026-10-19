package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDocumentPolicyTest {
    private fun preview(name: String, mime: String) = FilePreview("/tmp/$name", "/tmp/$name", name, mime, 1)

    @Test
    fun routesHtmlAndSvgToTheImmersiveBrowser() {
        assertTrue(usesImmersiveBrowser(preview("report.html", "text/html")))
        assertTrue(usesImmersiveBrowser(preview("figure.svg", "application/octet-stream")))
        assertFalse(usesImmersiveBrowser(preview("figure.png", "image/png")))
    }

    @Test
    fun servesSvgAsANativeSvgDocumentWithoutJavaScript() {
        val policy = browserDocumentPolicy(preview("figure.svg", "application/octet-stream"))

        assertEquals("image/svg+xml", policy.mimeType)
        assertEquals("UTF-8", policy.encoding)
        assertFalse(policy.javaScriptEnabled)
        assertTrue(policy.fitDocumentOnFirstLoad)
    }

    @Test
    fun keepsHtmlBrowserBehaviorUnchanged() {
        val policy = browserDocumentPolicy(preview("report.html", "text/html"))

        assertEquals("text/html", policy.mimeType)
        assertTrue(policy.javaScriptEnabled)
        assertFalse(policy.fitDocumentOnFirstLoad)
        assertThrows(IllegalStateException::class.java) {
            browserDocumentPolicy(preview("figure.png", "image/png"))
        }
    }
}
