package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewCacheTest {
    @Test
    fun cacheKeySeparatesThreadsAndPaths() {
        val first = stableCacheKey("thread-a", "/project/report.html")
        assertEquals(first, stableCacheKey("thread-a", "/project/report.html"))
        assertNotEquals(first, stableCacheKey("thread-b", "/project/report.html"))
        assertNotEquals(first, stableCacheKey("thread-a", "/project/other.html"))
    }

    @Test
    fun cachedFileNameUsesFullContentHashAndSafeName() {
        val hash = "a".repeat(64)
        val name = cacheFileName(hash, "四阶段 总览?.html")

        assertTrue(name.startsWith("$hash-"))
        assertEquals("$hash-四阶段_总览_.html", name)
    }

    @Test
    fun cachedFileNameFitsAndroidFileSystems() {
        val name = cacheFileName("b".repeat(64), "很长".repeat(100) + ".html")

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 225)
    }
}
