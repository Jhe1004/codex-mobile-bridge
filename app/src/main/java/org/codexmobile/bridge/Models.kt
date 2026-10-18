package org.codexmobile.bridge

enum class ThreadActivity {
    RUNNING,
    UNREAD,
    IDLE,
}

internal fun displayedThreadActivity(
    runtimeActivity: ThreadActivity,
    completionRevision: String,
    seenRevision: String?,
    initialized: Boolean,
    selected: Boolean,
): ThreadActivity = when {
    runtimeActivity == ThreadActivity.RUNNING -> ThreadActivity.RUNNING
    !initialized || selected || completionRevision.isBlank() || seenRevision == completionRevision -> ThreadActivity.IDLE
    else -> ThreadActivity.UNREAD
}

data class ThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val cwd: String,
    val updatedAt: Long,
    val projectId: String,
    val activity: ThreadActivity = ThreadActivity.IDLE,
    val completionRevision: String = "",
)

data class ProjectSummary(
    val id: String,
    val name: String,
    val rootPath: String,
    val threadCount: Int,
    val order: Int,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val detail: String = "",
)

data class PendingApproval(
    val id: Any,
    val method: String,
    val description: String,
)

data class FileTransferState(
    val name: String,
    val receivedBytes: Long,
    val totalBytes: Long,
)

data class FilePreview(
    val localPath: String,
    val sourcePath: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val line: Int? = null,
)

data class BridgeUiState(
    val connection: String = "正在启动…",
    val relayConnected: Boolean = false,
    val macConnected: Boolean = false,
    val appServerReady: Boolean = false,
    val projects: List<ProjectSummary> = emptyList(),
    val threads: List<ThreadSummary> = emptyList(),
    val expandedProjectIds: Set<String> = emptySet(),
    val projectExpansionInitialized: Boolean = false,
    val selectedProject: ProjectSummary? = null,
    val selectedThread: ThreadSummary? = null,
    val preparingThreadId: String? = null,
    val preparedThreadId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val activeTurnId: String? = null,
    val lastTurnCompletedAtMs: Long? = null,
    val sendPending: Boolean = false,
    val approval: PendingApproval? = null,
    val fileTransfer: FileTransferState? = null,
    val filePreview: FilePreview? = null,
    val error: String? = null,
)
