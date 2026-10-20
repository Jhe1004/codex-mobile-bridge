package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProjectCacheLayoutTest {
    @Test
    fun separatesProjectsAndThreadsWithoutExposingTheirNames() {
        val root = Files.createTempDirectory("project-cache-layout-test").toFile()
        try {
            val first = ProjectCacheLayout.previewDirectory(root, "project-a", "thread-a")
            val second = ProjectCacheLayout.previewDirectory(root, "project-b", "thread-a")
            assertNotEquals(first, second)
            assertFalse(first.path.contains("project-a"))
            assertFalse(first.path.contains("thread-a"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun identifiesDeletedProjectsDeletedThreadsAndMovedThreads() {
        val previousProjects = listOf(project("a"), project("b"))
        val previousThreads = listOf(thread("a1", "a"), thread("a2", "a"), thread("b1", "b"))
        val currentProjects = listOf(project("b"), project("c"))
        val currentThreads = listOf(thread("a2", "c"), thread("b1", "b"))

        val removed = removedProjectCacheScopes(previousProjects, previousThreads, currentProjects, currentThreads)

        assertEquals(setOf("a"), removed.projectIds)
        assertEquals(setOf("a1", "a2"), removed.threadsByProject["a"])
        assertTrue("b1" !in removed.threadIds)
    }

    @Test
    fun requiresTwoConsecutiveCompleteCatalogsBeforeDeletion() {
        val firstObservation = setOf("deleted-project")
        assertTrue(confirmedRemovalIds(firstObservation, emptySet()).isEmpty())
        assertEquals(firstObservation, confirmedRemovalIds(firstObservation, firstObservation))
        assertTrue(confirmedRemovalIds(emptySet(), firstObservation).isEmpty())
    }

    private fun project(id: String) = ProjectSummary(id, id, "/$id", 0, 0)
    private fun thread(id: String, projectId: String) = ThreadSummary(id, id, "", "/$projectId", 0, projectId)
}
