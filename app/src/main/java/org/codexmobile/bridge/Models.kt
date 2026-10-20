package org.codexmobile.bridge

enum class ThreadActivity {
    RUNNING,
    UNREAD,
    IDLE,
}

enum class ConversationContentState {
    NONE,
    LOADING_CACHE,
    CACHED_CURRENT,
    SYNCING,
    LIVE,
}

enum class ConversationSendReadiness {
    READY,
    OFFLINE_MODE,
    NO_THREAD,
    RELAY_OFFLINE,
    MAC_OFFLINE,
    CODEX_NOT_READY,
    CONTENT_NOT_SYNCED,
    THREAD_PREPARING,
    THREAD_NOT_PREPARED,
    DELIVERY_UNCONFIRMED,
    TURN_RUNNING,
    SEND_PENDING,
}

internal fun conversationSendReadiness(state: BridgeUiState): ConversationSendReadiness {
    val thread = state.selectedThread ?: return ConversationSendReadiness.NO_THREAD
    return when {
        state.offlineMode -> ConversationSendReadiness.OFFLINE_MODE
        !state.relayConnected -> ConversationSendReadiness.RELAY_OFFLINE
        !state.macConnected -> ConversationSendReadiness.MAC_OFFLINE
        !state.appServerReady -> ConversationSendReadiness.CODEX_NOT_READY
        state.conversationContentState != ConversationContentState.LIVE -> ConversationSendReadiness.CONTENT_NOT_SYNCED
        state.preparingThreadId == thread.id -> ConversationSendReadiness.THREAD_PREPARING
        state.preparedThreadId != thread.id -> ConversationSendReadiness.THREAD_NOT_PREPARED
        state.pendingOutgoing != null -> ConversationSendReadiness.DELIVERY_UNCONFIRMED
        state.sendPending -> ConversationSendReadiness.SEND_PENDING
        state.activeTurnId != null || thread.activity == ThreadActivity.RUNNING -> ConversationSendReadiness.TURN_RUNNING
        else -> ConversationSendReadiness.READY
    }
}

internal fun deliveryFailureIsAmbiguous(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("连接中断") ||
        normalized.contains("请求超时") ||
        normalized.contains("timeout") ||
        normalized.contains("timed out") ||
        normalized.contains("connection reset") ||
        normalized.contains("broken pipe")
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
    val contentRevision: String = "",
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
    val clientId: String = "",
)

data class PendingOutgoingMessage(
    val threadId: String,
    val message: ChatMessage,
)

data class MessageMergeResult(
    val messages: List<ChatMessage>,
    val pendingOutgoing: PendingOutgoingMessage?,
)

internal fun mergeServerMessages(
    threadId: String,
    serverMessages: List<ChatMessage>,
    pendingOutgoing: PendingOutgoingMessage?,
): MessageMergeResult {
    val pending = pendingOutgoing?.takeIf { it.threadId == threadId }
        ?: return MessageMergeResult(serverMessages, pendingOutgoing)
    val acknowledged = serverMessages.any { message ->
        message.role == "user" &&
            message.clientId.isNotBlank() &&
            message.clientId == pending.message.clientId
    }
    return if (acknowledged) {
        MessageMergeResult(serverMessages, null)
    } else {
        MessageMergeResult(serverMessages + pending.message, pending)
    }
}

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

enum class AttachmentUploadStatus {
    HASHING,
    UPLOADING,
    READY,
    FAILED,
}

data class ComposerAttachment(
    val localId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val uploadedBytes: Long = 0,
    val remoteUploadId: String? = null,
    val status: AttachmentUploadStatus = AttachmentUploadStatus.HASHING,
    val error: String? = null,
)

data class FilePreview(
    val localPath: String,
    val sourcePath: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val line: Int? = null,
    val fragment: String? = null,
)

data class BridgeUiState(
    val connection: String = "正在启动…",
    val offlineMode: Boolean = false,
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
    val conversationContentState: ConversationContentState = ConversationContentState.NONE,
    val loadedContentRevision: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val pendingOutgoing: PendingOutgoingMessage? = null,
    val streamingText: String = "",
    val activeTurnId: String? = null,
    val lastTurnCompletedAtMs: Long? = null,
    val sendPending: Boolean = false,
    val approval: PendingApproval? = null,
    val fileTransfer: FileTransferState? = null,
    val filePreview: FilePreview? = null,
    val composerAttachments: List<ComposerAttachment> = emptyList(),
    val manualSyncVisible: Boolean = false,
    val manualSyncInProgress: Boolean = false,
    val manualSyncCompleted: Int = 0,
    val manualSyncTotal: Int = 0,
    val manualSyncFailed: Int = 0,
    val manualSyncChanged: Int = 0,
    val error: String? = null,
)
