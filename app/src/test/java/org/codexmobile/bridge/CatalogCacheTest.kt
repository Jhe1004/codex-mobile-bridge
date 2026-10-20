package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class CatalogCacheTest {
    @Test
    fun restoresProjectsAndNormalizesStaleRunningThreads() {
        val root = Files.createTempDirectory("catalog-cache-test").toFile()
        val file = root.resolve("catalog.bin")
        try {
            val store = CatalogCacheStore(file)
            val projects = listOf(ProjectSummary("project-1", "项目", "/fixture", 1, 0))
            val threads = listOf(
                ThreadSummary(
                    id = "thread-1",
                    title = "旧会话",
                    preview = "预览",
                    cwd = "/fixture",
                    updatedAt = 10L,
                    projectId = "project-1",
                    activity = ThreadActivity.RUNNING,
                    completionRevision = "done-1",
                    contentRevision = "file:10:20",
                )
            )
            store.save(projects, threads)
            val restored = store.load()
            assertEquals(projects, restored?.projects)
            assertEquals(ThreadActivity.IDLE, restored?.threads?.single()?.activity)
            assertEquals("file:10:20", restored?.threads?.single()?.contentRevision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAndDeletesCorruptCatalog() {
        val root = Files.createTempDirectory("catalog-cache-corrupt-test").toFile()
        val file = root.resolve("catalog.bin")
        try {
            file.writeText("not-a-catalog")
            assertNull(CatalogCacheStore(file).load())
            assertEquals(false, file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
