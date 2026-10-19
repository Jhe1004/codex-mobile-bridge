package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewKindTest {
    private fun preview(name: String, mime: String) = FilePreview("/tmp/$name", "/tmp/$name", name, mime, 1)

    @Test
    fun recognizesCommonInternalPreviewFormats() {
        assertEquals(PreviewKind.IMAGE, previewKind(preview("photo.HEIC", "application/octet-stream")))
        assertEquals(PreviewKind.SVG, previewKind(preview("plot.svg", "image/svg+xml")))
        assertEquals(PreviewKind.PDF, previewKind(preview("paper.pdf", "application/pdf")))
        assertEquals(PreviewKind.HTML, previewKind(preview("report.html", "text/html")))
        assertEquals(PreviewKind.TEXT, previewKind(preview("table.csv", "text/csv")))
        assertEquals(PreviewKind.MEDIA, previewKind(preview("clip.mp4", "video/mp4")))
    }

    @Test
    fun officeAndArchivesUseExternalApps() {
        assertEquals(PreviewKind.EXTERNAL, previewKind(preview("paper.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
        assertEquals(PreviewKind.EXTERNAL, previewKind(preview("bundle.zip", "application/zip")))
    }
}
