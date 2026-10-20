package org.codexmobile.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object ProjectCacheLayout {
    private const val ROOT_DIRECTORY = "project_cache_v2"

    fun root(context: Context): File = File(context.filesDir, ROOT_DIRECTORY).apply { mkdirs() }

    fun projectDirectory(root: File, projectId: String): File =
        File(root, "project-${cacheId(projectId.ifBlank { UNASSIGNED_PROJECT })}")

    fun conversationDirectory(root: File, projectId: String): File =
        File(projectDirectory(root, projectId), "conversations")

    fun previewDirectory(root: File, projectId: String, threadId: String): File =
        File(File(projectDirectory(root, projectId), "files"), "thread-${cacheId(threadId)}")

    fun conversationFile(root: File, projectId: String, threadId: String): File =
        File(conversationDirectory(root, projectId), "${cacheId(threadId)}.bin")

    fun cacheId(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8)).take(32)

    private const val UNASSIGNED_PROJECT = "__unassigned_project__"
}

internal data class RemovedProjectCacheScopes(
    val projectIds: Set<String>,
    val threadsByProject: Map<String, Set<String>>,
) {
    val threadIds: Set<String> = threadsByProject.values.flatten().toSet()
}

internal fun confirmedRemovalIds(observed: Set<String>, previouslyObserved: Set<String>): Set<String> =
    observed.intersect(previouslyObserved)

internal fun removedProjectCacheScopes(
    previousProjects: List<ProjectSummary>,
    previousThreads: List<ThreadSummary>,
    currentProjects: List<ProjectSummary>,
    currentThreads: List<ThreadSummary>,
): RemovedProjectCacheScopes {
    val currentProjectIds = currentProjects.mapTo(mutableSetOf(), ProjectSummary::id)
    val currentThreadProjects = currentThreads.associate { it.id to it.projectId }
    val deletedProjects = previousProjects.map(ProjectSummary::id).filterNot(currentProjectIds::contains).toSet()
    val removedThreads = previousThreads.filter { previous ->
        previous.projectId in deletedProjects || currentThreadProjects[previous.id] != previous.projectId
    }
    return RemovedProjectCacheScopes(
        projectIds = deletedProjects,
        threadsByProject = removedThreads.groupBy(ThreadSummary::projectId)
            .mapValues { (_, threads) -> threads.mapTo(mutableSetOf(), ThreadSummary::id) },
    )
}

internal class ProjectCacheCoordinator private constructor(private val context: Context) {
    private val root = ProjectCacheLayout.root(context)
    private val preferences = context.getSharedPreferences(INDEX_PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun reconcile(
        fallbackProjects: List<ProjectSummary>,
        fallbackThreads: List<ThreadSummary>,
        currentProjects: List<ProjectSummary>,
        currentThreads: List<ThreadSummary>,
    ): Boolean {
        val stored = loadIndex()
        val previousProjects = stored?.first ?: fallbackProjects
        val previousThreads = stored?.second ?: fallbackThreads
        val observed = removedProjectCacheScopes(previousProjects, previousThreads, currentProjects, currentThreads)
        val previousPendingProjects = preferences.getStringSet(PENDING_PROJECTS_KEY, emptySet()).orEmpty()
        val previousPendingThreads = preferences.getStringSet(PENDING_THREADS_KEY, emptySet()).orEmpty()
        val observedThreadKeys = observed.threadsByProject.flatMap { (projectId, threadIds) ->
            threadIds.map { threadId -> threadKey(projectId, threadId) }
        }.toSet()
        val confirmedProjectIds = confirmedRemovalIds(observed.projectIds, previousPendingProjects)
        val confirmedThreadKeys = confirmedRemovalIds(observedThreadKeys, previousPendingThreads)
        val confirmedThreadIds = confirmedThreadKeys.mapTo(mutableSetOf()) { it.substringAfter(THREAD_KEY_SEPARATOR) }
        val confirmedProjectRoots = previousProjects
            .filter { it.id in confirmedProjectIds }
            .map(ProjectSummary::rootPath)
            .filter(String::isNotBlank)

        confirmedProjectIds.forEach { projectId ->
            ProjectCacheLayout.projectDirectory(root, projectId).deleteRecursively()
        }
        confirmedThreadKeys.forEach { key ->
            val projectId = key.substringBefore(THREAD_KEY_SEPARATOR)
            val threadId = key.substringAfter(THREAD_KEY_SEPARATOR)
            ProjectCacheLayout.conversationFile(root, projectId, threadId).delete()
            ProjectCacheLayout.previewDirectory(root, projectId, threadId).deleteRecursively()
        }
        FilePreviewCache.removeMetadata(context, confirmedProjectIds, confirmedThreadIds)
        FilePreviewCache.removeLegacyProjects(context, confirmedProjectRoots)
        ConversationCacheStore.deleteLegacyThreads(context, confirmedThreadIds)

        val confirmedAny = confirmedProjectIds.isNotEmpty() || confirmedThreadKeys.isNotEmpty()
        val retainedProjects = previousProjects.filter { it.id in observed.projectIds && it.id !in confirmedProjectIds }
        val retainedThreadKeys = observedThreadKeys - confirmedThreadKeys
        val retainedThreads = previousThreads.filter { threadKey(it.projectId, it.id) in retainedThreadKeys }
        saveIndex(
            projects = (currentProjects + retainedProjects).distinctBy(ProjectSummary::id),
            threads = (currentThreads + retainedThreads).distinctBy { threadKey(it.projectId, it.id) },
        )
        preferences.edit()
            .putStringSet(PENDING_PROJECTS_KEY, observed.projectIds - confirmedProjectIds)
            .putStringSet(PENDING_THREADS_KEY, observedThreadKeys - confirmedThreadKeys)
            .apply()
        return confirmedAny
    }

    private fun threadKey(projectId: String, threadId: String): String =
        "$projectId$THREAD_KEY_SEPARATOR$threadId"

    private fun loadIndex(): Pair<List<ProjectSummary>, List<ThreadSummary>>? {
        val raw = preferences.getString(INDEX_KEY, null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            val projects = value.optJSONArray("projects") ?: JSONArray()
            val threads = value.optJSONArray("threads") ?: JSONArray()
            Pair(
                buildList {
                    for (index in 0 until projects.length()) {
                        val item = projects.getJSONObject(index)
                        add(ProjectSummary(item.getString("id"), "", item.optString("rootPath"), 0, index))
                    }
                },
                buildList {
                    for (index in 0 until threads.length()) {
                        val item = threads.getJSONObject(index)
                        add(ThreadSummary(item.getString("id"), "", "", "", 0, item.getString("projectId")))
                    }
                },
            )
        }.getOrNull()
    }

    private fun saveIndex(projects: List<ProjectSummary>, threads: List<ThreadSummary>) {
        val value = JSONObject()
            .put("projects", JSONArray().apply {
                projects.forEach { put(JSONObject().put("id", it.id).put("rootPath", it.rootPath)) }
            })
            .put("threads", JSONArray().apply {
                threads.forEach { put(JSONObject().put("id", it.id).put("projectId", it.projectId)) }
            })
        preferences.edit().putString(INDEX_KEY, value.toString()).apply()
    }

    companion object {
        private const val INDEX_PREFERENCES = "project_cache_index_v2"
        private const val INDEX_KEY = "catalog"
        private const val PENDING_PROJECTS_KEY = "pending_removed_projects"
        private const val PENDING_THREADS_KEY = "pending_removed_threads"
        private const val THREAD_KEY_SEPARATOR = "\u001f"

        fun forContext(context: Context): ProjectCacheCoordinator =
            ProjectCacheCoordinator(context.applicationContext)
    }
}
