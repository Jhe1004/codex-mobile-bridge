package org.codexmobile.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Base64
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object BridgeClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var connectionJob: Job? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var lastRelayMessageAtMs = 0L
    private val pending = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val pendingErrors = ConcurrentHashMap<String, (String) -> Unit>()
    private val pendingTimeouts = ConcurrentHashMap<String, Job>()
    private val cancelledAttachmentIds = ConcurrentHashMap.newKeySet<String>()
    private val latestSyncInFlight = ConcurrentHashMap.newKeySet<String>()
    private val recentSyncInFlight = AtomicBoolean(false)
    private val manualSyncQueued = AtomicBoolean(false)
    private val selectedCacheGeneration = AtomicLong(0)
    @Volatile private var manualSyncId: String? = null
    @Volatile private var conversationCache: ConversationCacheStore? = null
    @Volatile private var catalogCache: CatalogCacheStore? = null
    private val catalogRestoreStarted = AtomicBoolean(false)
    @Volatile private var activeTransferId: String? = null
    @Volatile private var activeTransferFile: File? = null
    private var reconnectDelay = 1_000L
    private const val SEQUENCE_PREFS = "relay_sequence_v2"
    // v2 replaces the old updatedAt-based cursor with stable completed-turn IDs.
    // Starting a fresh baseline also clears false unread badges created by v1.
    private const val THREAD_READ_PREFS = "thread_read_state_v2"
    private const val THREAD_READ_INITIALIZED = "__initialized__"
    private const val RECENT_SYNC_PREFS = "recent_thread_sync_v1"
    @Volatile private var sequenceKey = "default"
    @Volatile private var lastSeenSequence = 0L

    fun start(context: Context) {
        appContext = context.applicationContext
        if (conversationCache == null) conversationCache = ConversationCacheStore.forContext(context.applicationContext)
        if (catalogCache == null) catalogCache = CatalogCacheStore.forContext(context.applicationContext)
        if (catalogRestoreStarted.compareAndSet(false, true)) {
            scope.launch {
                val cached = catalogCache?.load() ?: return@launch
                _state.update { current ->
                    if (current.projects.isNotEmpty() || current.threads.isNotEmpty()) current else current.copy(
                        projects = cached.projects.sortedBy { it.order },
                        threads = cached.threads,
                        expandedProjectIds = cached.projects.map { it.id }.toSet(),
                        projectExpansionInitialized = cached.projects.isNotEmpty(),
                    )
                }
            }
        }
        if (_state.value.offlineMode) return
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch { connectionLoop() }
    }

    fun ensureConnected(context: Context) {
        start(context)
        if (_state.value.offlineMode) return
        if (lastRelayMessageAtMs > 0L && SystemClock.elapsedRealtime() - lastRelayMessageAtMs > 35_000L) {
            enterOfflineMode("连接超时，已切换为离线阅读")
        }
    }

    fun forceReconnect() {
        resumeOnline()
    }

    fun resumeOnline() {
        val context = appContext ?: return
        _state.value = _state.value.copy(
            offlineMode = false,
            connection = "正在重新连接 Mac…",
            relayConnected = false,
            macConnected = false,
            appServerReady = false,
            error = null,
        )
        if (connectionJob?.isActive == true) return
        connectionJob = null
        start(context)
    }

    private fun enterOfflineMode(reason: String) {
        if (_state.value.offlineMode) return
        Log.i("CodexMobile", "Entering offline reading mode: $reason")
        _state.value = _state.value.copy(
            offlineMode = true,
            connection = "离线阅读",
            relayConnected = false,
            macConnected = false,
            appServerReady = false,
            preparingThreadId = null,
            preparedThreadId = null,
            manualSyncVisible = false,
            manualSyncInProgress = false,
            activeTurnId = null,
            threads = _state.value.threads.map { thread ->
                if (thread.activity == ThreadActivity.RUNNING) thread.copy(activity = ThreadActivity.IDLE) else thread
            },
            selectedThread = _state.value.selectedThread?.let { thread ->
                if (thread.activity == ThreadActivity.RUNNING) thread.copy(activity = ThreadActivity.IDLE) else thread
            },
            error = null,
        )
        webSocket?.cancel()
        webSocket = null
    }

    fun stop() {
        connectionJob?.cancel()
        closeTransport()
        _state.value = _state.value.copy(connection = "已停止", relayConnected = false)
    }

    fun refreshThreads() = request("thread.list", JSONObject()) { result ->
        parseThreads(result)
    }

    fun refreshRecentThreads(manual: Boolean = false) {
        val current = _state.value
        if (current.offlineMode) {
            if (manual) resumeOnline()
            return
        }
        if (!current.relayConnected || !current.macConnected || !current.appServerReady) {
            if (manual) failManualSync("Mac 或 Codex 尚未连接")
            return
        }
        if (!recentSyncInFlight.compareAndSet(false, true)) {
            if (manual) {
                manualSyncQueued.set(true)
                _state.value = _state.value.copy(
                    manualSyncVisible = true,
                    manualSyncInProgress = true,
                    manualSyncCompleted = 0,
                    manualSyncTotal = 0,
                    manualSyncFailed = 0,
                    manualSyncChanged = 0,
                )
            }
            return
        }
        val context = appContext
        if (context == null) {
            recentSyncInFlight.set(false)
            if (manual) failManualSync("应用尚未初始化")
            return
        }
        val syncId = UUID.randomUUID().toString()
        if (manual) {
            manualSyncQueued.set(false)
            manualSyncId = syncId
            _state.value = _state.value.copy(
                manualSyncVisible = true,
                manualSyncInProgress = true,
                manualSyncCompleted = 0,
                manualSyncTotal = 0,
                manualSyncFailed = 0,
                manualSyncChanged = 0,
                error = null,
            )
        }
        val preferences = context.getSharedPreferences(RECENT_SYNC_PREFS, Context.MODE_PRIVATE)
        val knownRevisions = JSONObject()
        preferences.all.forEach { (threadId, value) ->
            if (value is String && threadId.isNotBlank() && value.isNotBlank()) {
                knownRevisions.put(threadId, value)
            }
        }
        request(
            "thread.recent.sync",
            JSONObject()
                .put("knownRevisions", knownRevisions)
                .put("manual", manual)
                .put("syncId", syncId),
            onError = { message ->
                recentSyncInFlight.set(false)
                Log.w("CodexMobile", "Recent thread synchronization failed: $message")
                if (manual) failManualSync(message)
                runQueuedManualSync()
            },
        ) { result ->
            recentSyncInFlight.set(false)
            parseRecentThreadSync(result)
            if (manual) finishManualSync(syncId, result.optJSONObject("sync") ?: JSONObject())
            runQueuedManualSync()
        }
    }

    fun refreshHome() {
        if (_state.value.offlineMode) {
            resumeOnline()
            return
        }
        if (webSocket == null || !_state.value.relayConnected || !_state.value.macConnected) {
            forceReconnect()
            return
        }
        _state.value = _state.value.copy(
            manualSyncVisible = true,
            manualSyncInProgress = true,
            manualSyncCompleted = 0,
            manualSyncTotal = 0,
            manualSyncFailed = 0,
            manualSyncChanged = 0,
            error = null,
        )
        if (_state.value.appServerReady) {
            refreshRecentThreads(manual = true)
        } else {
            request("status", JSONObject(), onError = ::failManualSync) { result ->
                val ready = result.optBoolean("appServerReady")
                _state.value = _state.value.copy(appServerReady = ready)
                if (ready) refreshRecentThreads(manual = true)
                else failManualSync("Codex 尚未就绪")
            }
        }
    }

    private fun runQueuedManualSync() {
        if (manualSyncQueued.compareAndSet(true, false)) refreshRecentThreads(manual = true)
    }

    private fun failManualSync(message: String) {
        Log.w("CodexMobile", "Manual recent sync failed: $message")
        manualSyncId = null
        _state.value = _state.value.copy(
            manualSyncVisible = false,
            manualSyncInProgress = false,
            error = message,
        )
    }

    private fun finishManualSync(syncId: String, sync: JSONObject) {
        if (manualSyncId != syncId) return
        val total = sync.optInt("checkedCount")
        val failed = sync.optInt("failedCount")
        Log.i(
            "CodexMobile",
            "Manual recent sync completed total=$total changed=${sync.optInt("changedCount")} failed=$failed",
        )
        _state.value = _state.value.copy(
            manualSyncVisible = true,
            manualSyncInProgress = false,
            manualSyncCompleted = total,
            manualSyncTotal = total,
            manualSyncFailed = failed,
            manualSyncChanged = sync.optInt("changedCount"),
        )
        scope.launch {
            delay(2_000)
            if (manualSyncId == syncId && !_state.value.manualSyncInProgress) {
                manualSyncId = null
                _state.value = _state.value.copy(manualSyncVisible = false)
            }
        }
    }

    fun toggleProject(projectId: String) {
        val expanded = _state.value.expandedProjectIds.toMutableSet()
        if (!expanded.add(projectId)) expanded.remove(projectId)
        _state.value = _state.value.copy(expandedProjectIds = expanded)
    }

    fun selectThread(thread: ThreadSummary) {
        markThreadRead(thread)
        val selected = thread.copy(activity = if (thread.activity == ThreadActivity.UNREAD) ThreadActivity.IDLE else thread.activity)
        val cacheGeneration = selectedCacheGeneration.incrementAndGet()
        _state.value = _state.value.copy(
            threads = _state.value.threads.map { if (it.id == thread.id) selected else it },
            selectedThread = selected,
            messages = emptyList(),
            pendingOutgoing = null,
            streamingText = "",
            lastTurnCompletedAtMs = null,
            sendPending = false,
            preparingThreadId = null,
            preparedThreadId = null,
            conversationContentState = ConversationContentState.LOADING_CACHE,
            loadedContentRevision = "",
            error = null,
        )
        scope.launch {
            val cached = conversationCache?.load(selected.id)
            if (selectedCacheGeneration.get() != cacheGeneration || _state.value.selectedThread?.id != selected.id) return@launch
            if (cached != null) {
                _state.update { current ->
                    if (current.selectedThread?.id != selected.id) current else current.copy(messages = cached.messages)
                }
            }
            if (_state.value.offlineMode) {
                _state.update { current ->
                    if (current.selectedThread?.id != selected.id) current else current.copy(
                        conversationContentState = if (cached != null) {
                            ConversationContentState.CACHED_CURRENT
                        } else {
                            ConversationContentState.NONE
                        },
                        loadedContentRevision = cached?.contentRevision.orEmpty(),
                        error = if (cached == null) "这条会话尚未保存到手机，联网后打开一次即可离线阅读" else null,
                    )
                }
                return@launch
            }
            if (cacheMatchesThread(cached, selected) && selected.activity != ThreadActivity.RUNNING) {
                _state.update { current ->
                    if (current.selectedThread?.id != selected.id) current else current.copy(
                        conversationContentState = ConversationContentState.CACHED_CURRENT,
                        loadedContentRevision = cached?.contentRevision.orEmpty(),
                    )
                }
                // Render the disk cache immediately, then verify it against
                // Codex. A restored or compacted chat may have continued in a
                // replacement rollout even when the original file is unchanged.
                refreshSelectedThread(showSyncing = false)
            } else {
                _state.update { current ->
                    if (current.selectedThread?.id != selected.id) current else current.copy(
                        conversationContentState = ConversationContentState.SYNCING,
                        loadedContentRevision = cached?.contentRevision.orEmpty(),
                    )
                }
                refreshSelectedThread(showSyncing = true)
            }
        }
        if (!_state.value.offlineMode) prepareSelectedThread()
    }

    private fun prepareSelectedThread() {
        val current = _state.value
        val threadId = current.selectedThread?.id ?: return
        if (!current.relayConnected || !current.macConnected || !current.appServerReady ||
            current.activeTurnId != null || current.sendPending ||
            current.preparedThreadId == threadId || current.preparingThreadId == threadId
        ) return

        _state.value = current.copy(
            preparingThreadId = threadId,
            preparedThreadId = null,
            error = null,
        )
        request(
            "thread.prepare",
            JSONObject().put("threadId", threadId),
            onError = { message ->
                _state.update { latest ->
                    if (latest.selectedThread?.id != threadId) latest else latest.copy(
                        preparingThreadId = null,
                        preparedThreadId = null,
                        error = "会话尚未准备好：$message",
                    )
                }
            },
        ) { result ->
            _state.update { latest ->
                if (latest.selectedThread?.id != threadId ||
                    !latest.relayConnected || !latest.macConnected || !latest.appServerReady ||
                    !result.optBoolean("ready")
                ) latest else latest.copy(
                    preparingThreadId = null,
                    preparedThreadId = threadId,
                    error = null,
                )
            }
        }
    }

    private fun updateAppServerReadiness(ready: Boolean) {
        val previous = _state.value.appServerReady
        _state.update { current ->
            current.copy(
                appServerReady = ready,
                preparingThreadId = if (ready) current.preparingThreadId else null,
                preparedThreadId = if (ready) current.preparedThreadId else null,
            )
        }
        if (ready && !previous) {
            refreshSelectedThread(showSyncing = true)
            prepareSelectedThread()
        }
    }

    fun refreshSelectedThread(showSyncing: Boolean = false) {
        if (_state.value.offlineMode) return
        val threadId = _state.value.selectedThread?.id ?: return
        if (!latestSyncInFlight.add(threadId)) return
        if (showSyncing) {
            _state.update { current ->
                if (current.selectedThread?.id == threadId) current.copy(conversationContentState = ConversationContentState.SYNCING) else current
            }
        }
        val knownRevision = _state.value.loadedContentRevision
        request(
            "thread.latest",
            JSONObject().put("threadId", threadId).put("limit", 120).put("knownRevision", knownRevision),
            onError = { message ->
                latestSyncInFlight.remove(threadId)
                if (_state.value.selectedThread?.id == threadId) {
                    _state.value = _state.value.copy(
                        conversationContentState = if (_state.value.loadedContentRevision.isNotBlank()) {
                            ConversationContentState.CACHED_CURRENT
                        } else {
                            ConversationContentState.NONE
                        },
                        error = message,
                    )
                }
            },
        ) { result ->
            latestSyncInFlight.remove(threadId)
            parseLatestMessages(threadId, result)
        }
    }

    fun refreshSelectedSession() {
        if (_state.value.selectedThread == null) return
        if (_state.value.offlineMode) {
            resumeOnline()
            return
        }
        _state.value = _state.value.copy(error = null)
        refreshSelectedThread(showSyncing = true)
    }

    fun backToThreads() {
        selectedCacheGeneration.incrementAndGet()
        val current = _state.value
        current.composerAttachments.forEach { attachment ->
            if (attachment.status == AttachmentUploadStatus.HASHING ||
                attachment.status == AttachmentUploadStatus.UPLOADING
            ) {
                cancelledAttachmentIds.add(attachment.localId)
            }
            attachment.remoteUploadId?.let { uploadId ->
                current.selectedThread?.id?.let { threadId ->
                    request(
                        "file.upload.discard",
                        JSONObject().put("threadId", threadId).put("uploadId", uploadId),
                        onError = {},
                    ) {}
                }
            }
        }
        _state.value = current.copy(
            selectedThread = null,
            messages = emptyList(),
            pendingOutgoing = null,
            streamingText = "",
            activeTurnId = null,
            lastTurnCompletedAtMs = null,
            sendPending = false,
            preparingThreadId = null,
            preparedThreadId = null,
            conversationContentState = ConversationContentState.NONE,
            loadedContentRevision = "",
            composerAttachments = emptyList(),
            error = null,
        )
    }

    fun newThread(project: ProjectSummary) {
        if (_state.value.offlineMode) return
        request("thread.start", JSONObject().put("ephemeral", false).put("cwd", project.rootPath)) { result ->
            val thread = result.optJSONObject("thread") ?: return@request
            val summary = threadSummary(thread, project.id)
            _state.value = _state.value.copy(
                selectedThread = summary,
                messages = emptyList(),
                pendingOutgoing = null,
                conversationContentState = ConversationContentState.LIVE,
                loadedContentRevision = summary.contentRevision,
            )
            prepareSelectedThread()
            refreshThreads()
        }
    }

    fun send(text: String, onAccepted: () -> Unit = {}) {
        val thread = _state.value.selectedThread ?: return
        val trimmed = text.trim()
        val attachments = _state.value.composerAttachments
        val readyAttachments = attachments.filter { it.status == AttachmentUploadStatus.READY && it.remoteUploadId != null }
        if ((trimmed.isBlank() && readyAttachments.isEmpty()) ||
            attachments.any { it.status != AttachmentUploadStatus.READY } ||
            conversationSendReadiness(_state.value) != ConversationSendReadiness.READY
        ) return
        val attachmentSummary = readyAttachments.joinToString("\n") { "- ${it.name}" }
        val visibleText = when {
            readyAttachments.isEmpty() -> trimmed
            trimmed.isBlank() -> "请查看我附加的文件：\n$attachmentSummary"
            else -> "$trimmed\n\n附件：\n$attachmentSummary"
        }
        val clientUserMessageId = UUID.randomUUID().toString()
        val optimisticMessage = ChatMessage(
            id = "local-$clientUserMessageId",
            role = "user",
            text = visibleText,
            detail = "正在发送…",
            clientId = clientUserMessageId,
        )
        _state.value = _state.value.copy(
            messages = _state.value.messages + optimisticMessage,
            pendingOutgoing = PendingOutgoingMessage(thread.id, optimisticMessage),
            sendPending = true,
            preparingThreadId = thread.id,
            preparedThreadId = null,
            streamingText = "",
            lastTurnCompletedAtMs = null,
            error = null,
        )
        request(
            "turn.start",
            JSONObject()
                .put("threadId", thread.id)
                .put("text", trimmed)
                .put("clientUserMessageId", clientUserMessageId)
                .put("attachmentIds", JSONArray(readyAttachments.mapNotNull { it.remoteUploadId })),
            onError = { message ->
                val current = _state.value
                val ambiguousDelivery = deliveryFailureIsAmbiguous(message)
                _state.value = current.copy(
                    messages = if (ambiguousDelivery) {
                        current.messages.map { existing ->
                            if (existing.clientId == clientUserMessageId) {
                                existing.copy(detail = "发送状态未知，正在重新核对…")
                            } else existing
                        }
                    } else {
                        current.messages.filterNot { it.clientId == clientUserMessageId }
                    },
                    pendingOutgoing = if (ambiguousDelivery) current.pendingOutgoing else {
                        current.pendingOutgoing?.takeUnless { it.message.clientId == clientUserMessageId }
                    },
                    sendPending = false,
                    preparingThreadId = null,
                    preparedThreadId = null,
                    error = if (ambiguousDelivery) {
                        "$message；消息不会从手机消失，重新连接后将自动核对是否送达"
                    } else message,
                )
            },
        ) { result ->
            val turnId = result.optJSONObject("turn")?.optString("id").orEmpty()
            val current = _state.value
            val acceptedMessage = optimisticMessage.copy(detail = "")
            _state.value = current.copy(
                messages = current.messages.map { message ->
                    if (message.clientId == clientUserMessageId) acceptedMessage else message
                },
                pendingOutgoing = PendingOutgoingMessage(thread.id, acceptedMessage),
                activeTurnId = turnId.ifBlank { current.activeTurnId },
                sendPending = false,
                preparingThreadId = null,
                preparedThreadId = thread.id,
                composerAttachments = emptyList(),
                error = null,
            )
            persistConversation(_state.value.selectedThread, _state.value.messages)
            mainHandler.post(onAccepted)
        }
    }

    fun addAttachments(uris: List<Uri>) {
        val threadId = _state.value.selectedThread?.id ?: return
        val availableSlots = (MAX_ATTACHMENTS_PER_TURN - _state.value.composerAttachments.size).coerceAtLeast(0)
        if (availableSlots == 0) {
            _state.value = _state.value.copy(error = "一次最多发送 $MAX_ATTACHMENTS_PER_TURN 个附件")
            return
        }
        uris.take(availableSlots).forEach { uri -> scope.launch { uploadAttachment(threadId, uri) } }
        if (uris.size > availableSlots) {
            _state.value = _state.value.copy(error = "一次最多发送 $MAX_ATTACHMENTS_PER_TURN 个附件")
        }
    }

    fun removeAttachment(localId: String) {
        val attachment = _state.value.composerAttachments.firstOrNull { it.localId == localId } ?: return
        if (attachment.status == AttachmentUploadStatus.HASHING ||
            attachment.status == AttachmentUploadStatus.UPLOADING
        ) {
            cancelledAttachmentIds.add(localId)
        }
        _state.update { current ->
            current.copy(composerAttachments = current.composerAttachments.filterNot { it.localId == localId })
        }
        val threadId = _state.value.selectedThread?.id
        val remoteId = attachment.remoteUploadId
        if (threadId != null && remoteId != null) {
            request("file.upload.discard", JSONObject().put("threadId", threadId).put("uploadId", remoteId), onError = {}) {}
        }
    }

    private suspend fun uploadAttachment(threadId: String, uri: Uri) {
        val context = appContext ?: return
        val localId = UUID.randomUUID().toString()
        var remoteUploadId: String? = null
        updateAttachment(
            ComposerAttachment(
                localId = localId,
                name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件",
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                size = 0,
            ),
            append = true,
        )
        try {
            val metadata = inspectAttachment(context, uri)
            if (metadata.size > MAX_UPLOAD_BYTES) throw IllegalArgumentException("${metadata.name} 超过 100 MB 上传上限")
            if (cancelledAttachmentIds.contains(localId)) return
            updateAttachment(
                ComposerAttachment(localId = localId, name = metadata.name, mimeType = metadata.mimeType, size = metadata.size),
            )
            if (_state.value.selectedThread?.id != threadId) throw IllegalStateException("会话已经切换，请重新选择文件")
            val begin = requestAwait(
                "file.upload.begin",
                JSONObject()
                    .put("threadId", threadId)
                    .put("name", metadata.name)
                    .put("mimeType", metadata.mimeType)
                    .put("size", metadata.size)
                    .put("sha256", metadata.sha256),
            )
            val uploadId = begin.getString("uploadId")
            remoteUploadId = uploadId
            val chunkBytes = begin.optInt("chunkBytes", DEFAULT_UPLOAD_CHUNK_BYTES).coerceIn(1, DEFAULT_UPLOAD_CHUNK_BYTES)
            updateAttachmentById(localId) { it.copy(remoteUploadId = uploadId, status = AttachmentUploadStatus.UPLOADING) }
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                val buffer = ByteArray(chunkBytes)
                var offset = 0L
                while (true) {
                    if (cancelledAttachmentIds.contains(localId)) throw IllegalStateException("上传已取消")
                    if (_state.value.selectedThread?.id != threadId) throw IllegalStateException("会话已经切换，请重新选择文件")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    requestAwait(
                        "file.upload.chunk",
                        JSONObject()
                            .put("threadId", threadId)
                            .put("uploadId", uploadId)
                            .put("offset", offset)
                            .put("data", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)),
                    )
                    offset += count
                    updateAttachmentById(localId) { it.copy(uploadedBytes = offset) }
                }
            } ?: throw IllegalArgumentException("无法读取所选文件")
            requestAwait(
                "file.upload.finish",
                JSONObject().put("threadId", threadId).put("uploadId", uploadId),
            )
            updateAttachmentById(localId) {
                it.copy(uploadedBytes = it.size, status = AttachmentUploadStatus.READY, error = null)
            }
        } catch (error: Throwable) {
            remoteUploadId?.let { uploadId ->
                runCatching {
                    requestAwait("file.upload.discard", JSONObject().put("threadId", threadId).put("uploadId", uploadId))
                }
            }
            if (cancelledAttachmentIds.contains(localId)) return
            val message = error.message?.takeIf { it.isNotBlank() } ?: "文件上传失败"
            val existing = _state.value.composerAttachments.firstOrNull { it.localId == localId }
            if (existing == null) {
                updateAttachment(
                    ComposerAttachment(
                        localId = localId,
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件",
                        mimeType = "application/octet-stream",
                        size = 0,
                        status = AttachmentUploadStatus.FAILED,
                        error = message,
                    ),
                    append = true,
                )
            } else {
                updateAttachmentById(localId) { it.copy(status = AttachmentUploadStatus.FAILED, error = message) }
            }
        } finally {
            cancelledAttachmentIds.remove(localId)
        }
    }

    private fun updateAttachment(attachment: ComposerAttachment, append: Boolean = false) {
        _state.update { current ->
            current.copy(
                composerAttachments = if (append) current.composerAttachments + attachment else {
                    current.composerAttachments.map { if (it.localId == attachment.localId) attachment else it }
                },
                error = null,
            )
        }
    }

    private fun updateAttachmentById(localId: String, transform: (ComposerAttachment) -> ComposerAttachment) {
        _state.update { current ->
            current.copy(
                composerAttachments = current.composerAttachments.map { if (it.localId == localId) transform(it) else it },
            )
        }
    }

    private suspend fun requestAwait(action: String, payload: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        request(
            action,
            payload,
            onError = { message -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) },
        ) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }

    private fun inspectAttachment(context: Context, uri: Uri): AttachmentMetadata {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "attachment.bin"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            val buffer = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                actualSize += count
                if (actualSize > MAX_UPLOAD_BYTES) throw IllegalArgumentException("$name 超过 100 MB 上传上限")
                digest.update(buffer, 0, count)
            }
        } ?: throw IllegalArgumentException("无法读取所选文件")
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return AttachmentMetadata(
            name = name.replace(Regex("[\\/\\u0000-\\u001f]"), "_").take(160).ifBlank { "attachment.bin" },
            mimeType = context.contentResolver.getType(uri)?.take(160) ?: mimeTypeFromName(name),
            size = actualSize,
            sha256 = sha256,
        )
    }

    fun stopTurn() {
        val threadId = _state.value.selectedThread?.id ?: return
        val turnId = _state.value.activeTurnId ?: return
        request("turn.interrupt", JSONObject().put("threadId", threadId).put("turnId", turnId)) {}
    }

    fun answerApproval(approve: Boolean) {
        val approval = _state.value.approval ?: return
        val result = JSONObject().put("decision", if (approve) "accept" else "decline")
        request("appServer.respond", JSONObject().put("id", approval.id).put("result", result)) {
            _state.value = _state.value.copy(approval = null)
        }
    }

    fun downloadFile(target: String, forceRefresh: Boolean = false) {
        val threadId = _state.value.selectedThread?.id ?: return
        if (_state.value.fileTransfer != null) return
        val context = requireNotNull(appContext)
        val requestTarget = parseFileRequestTarget(target)
        if (requestTarget.path.isBlank()) return
        if (!forceRefresh) {
            FilePreviewCache.find(context, threadId, requestTarget.path)?.let { cached ->
                _state.value = _state.value.copy(
                    filePreview = cached.copy(fragment = requestTarget.fragment),
                    error = null,
                )
                return
            }
        }
        val transferId = UUID.randomUUID().toString()
        activeTransferId = transferId
        _state.value = _state.value.copy(
            fileTransfer = FileTransferState("正在读取文件信息…", 0, 0),
            error = null,
        )
        val payload = JSONObject().put("threadId", threadId).put("path", requestTarget.path)
        request("file.stat", payload, onError = { failFileTransfer(transferId, it) }) { result ->
            if (activeTransferId != transferId) return@request
            val meta = FileMetadata(
                sourcePath = result.getString("path"),
                name = result.getString("name"),
                mimeType = result.optString("mimeType", "application/octet-stream"),
                size = result.getLong("size"),
                mtimeMs = result.getLong("mtimeMs"),
                sha256 = result.getString("sha256"),
                line = result.optInt("line").takeIf { result.has("line") && !result.isNull("line") },
            )
            val directory = FilePreviewCache.directory(context)
            val destination = File(directory, cacheFileName(meta.sha256, meta.name))
            findVerifiedCachedFile(directory, destination, meta)?.let { cachedFile ->
                publishFilePreview(threadId, requestTarget.path, requestTarget.fragment, meta, cachedFile)
                return@request
            }
            if (destination.exists()) destination.delete()
            destination.createNewFile()
            activeTransferFile = destination
            _state.value = _state.value.copy(fileTransfer = FileTransferState(meta.name, 0, meta.size))
            if (meta.size == 0L) finishFileTransfer(
                transferId,
                threadId,
                requestTarget.path,
                requestTarget.fragment,
                meta,
                destination,
                MessageDigest.getInstance("SHA-256"),
            ) else fetchFileChunk(
                transferId,
                threadId,
                requestTarget.path,
                requestTarget.fragment,
                meta,
                destination,
                0L,
                MessageDigest.getInstance("SHA-256"),
            )
        }
    }

    fun closeFilePreview() {
        _state.value = _state.value.copy(filePreview = null)
    }

    fun refreshFilePreview() {
        val preview = _state.value.filePreview ?: return
        downloadFile(preview.sourcePath + preview.fragment?.let { "#$it" }.orEmpty(), forceRefresh = true)
    }

    /**
     * Maps an URL requested by the embedded HTML browser back to the directory that
     * contains the source HTML on the Mac. This is lexical only; the Mac bridge still
     * performs the authoritative realpath and project-root checks.
     */
    fun resolveHtmlTarget(sourcePath: String, encodedUrlPath: String, navigation: Boolean = false): String? {
        val decoded = android.net.Uri.decode(encodedUrlPath.substringBefore('?')).substringBefore('#')
        val source = runCatching { Paths.get(sourcePath).normalize() }.getOrNull() ?: return null
        val parent = source.parent ?: return null
        val macAbsolute = decoded.startsWith("/Users/") || decoded.startsWith("/Volumes/") ||
            decoded.startsWith("/private/") || decoded.startsWith("/tmp/")
        val target = runCatching {
            if (macAbsolute) Paths.get(decoded).normalize()
            else parent.resolve(decoded.removePrefix("/")).normalize()
        }.getOrNull() ?: return null
        if (!navigation && !target.startsWith(parent)) return null
        return target.toString()
    }

    /** Called only from WebView's background resource thread. */
    fun fetchHtmlResourceBlocking(sourcePath: String, encodedUrlPath: String): HtmlResource? {
        val threadId = _state.value.selectedThread?.id ?: return null
        val target = resolveHtmlTarget(sourcePath, encodedUrlPath) ?: return null
        val stat = blockingRequest(
            "file.stat",
            JSONObject().put("threadId", threadId).put("path", target),
        ) ?: return null
        val size = stat.optLong("size", -1L)
        if (size < 0L || size > MAX_HTML_RESOURCE_BYTES) return null
        val mtimeMs = stat.optLong("mtimeMs")
        val expectedHash = stat.optString("sha256")
        val output = ByteArrayOutputStream(size.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < size) {
            val result = blockingRequest(
                "file.read",
                JSONObject()
                    .put("threadId", threadId)
                    .put("path", target)
                    .put("offset", offset)
                    .put("expectedSize", size)
                    .put("expectedMtimeMs", mtimeMs),
            ) ?: return null
            val bytes = Base64.decode(result.optString("data"), Base64.DEFAULT)
            if (bytes.isEmpty() && offset < size) return null
            output.write(bytes)
            digest.update(bytes)
            val nextOffset = result.optLong("nextOffset", offset + bytes.size)
            if (nextOffset <= offset) return null
            offset = nextOffset
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (output.size().toLong() != size || !actualHash.equals(expectedHash, ignoreCase = true)) return null
        val mimeType = stat.optString("mimeType", "application/octet-stream")
        Log.i("CodexHtml", "resource=$target bytes=$size sha256Verified=true")
        return HtmlResource(output.toByteArray(), mimeType)
    }

    private fun blockingRequest(action: String, payload: JSONObject): JSONObject? {
        val latch = CountDownLatch(1)
        var result: JSONObject? = null
        request(action, payload, onError = { latch.countDown() }) {
            result = it
            latch.countDown()
        }
        if (!latch.await(HTML_RESOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        return result
    }

    fun cancelFileTransfer() {
        activeTransferId = null
        activeTransferFile?.delete()
        activeTransferFile = null
        _state.value = _state.value.copy(fileTransfer = null)
    }

    private fun fetchFileChunk(
        transferId: String,
        threadId: String,
        target: String,
        fragment: String?,
        meta: FileMetadata,
        destination: File,
        offset: Long,
        digest: MessageDigest,
    ) {
        if (activeTransferId != transferId) return
        val payload = JSONObject()
            .put("threadId", threadId)
            .put("path", target)
            .put("offset", offset)
            .put("expectedSize", meta.size)
            .put("expectedMtimeMs", meta.mtimeMs)
        request("file.read", payload, onError = { failFileTransfer(transferId, it) }) { result ->
            if (activeTransferId != transferId) return@request
            val bytes = Base64.decode(result.getString("data"), Base64.DEFAULT)
            FileOutputStream(destination, true).use { it.write(bytes) }
            digest.update(bytes)
            val nextOffset = result.getLong("nextOffset")
            _state.value = _state.value.copy(fileTransfer = FileTransferState(meta.name, nextOffset, meta.size))
            if (result.optBoolean("done") || nextOffset >= meta.size) {
                finishFileTransfer(transferId, threadId, target, fragment, meta, destination, digest)
            } else {
                fetchFileChunk(transferId, threadId, target, fragment, meta, destination, nextOffset, digest)
            }
        }
    }

    private fun finishFileTransfer(
        transferId: String,
        threadId: String,
        requestedTarget: String,
        fragment: String?,
        meta: FileMetadata,
        destination: File,
        digest: MessageDigest,
    ) {
        if (activeTransferId != transferId) return
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!actualHash.equals(meta.sha256, ignoreCase = true) || destination.length() != meta.size) {
            destination.delete()
            failFileTransfer(transferId, "文件完整性校验失败，请重新点击链接")
            return
        }
        publishFilePreview(threadId, requestedTarget, fragment, meta, destination)
    }

    private fun publishFilePreview(
        threadId: String,
        requestedTarget: String,
        fragment: String?,
        meta: FileMetadata,
        file: File,
    ) {
        activeTransferId = null
        activeTransferFile = null
        file.setLastModified(System.currentTimeMillis())
        val preview = FilePreview(
            localPath = file.absolutePath,
            sourcePath = meta.sourcePath,
            name = meta.name,
            mimeType = meta.mimeType,
            size = meta.size,
            line = meta.line,
            fragment = fragment,
        )
        appContext?.let { context ->
            FilePreviewCache.store(context, threadId, requestedTarget, preview, meta.sha256, meta.mtimeMs)
        }
        Log.i("CodexFileTransfer", "name=${meta.name} bytes=${meta.size} cacheReady=true")
        _state.value = _state.value.copy(fileTransfer = null, filePreview = preview, error = null)
    }

    private fun findVerifiedCachedFile(directory: File, destination: File, meta: FileMetadata): File? {
        if (destination.isFile && destination.length() == meta.size) return destination
        val safeSuffix = cacheFileName(meta.sha256, meta.name).substringAfter('-')
        val legacy = File(directory, "${meta.sha256.take(12)}-$safeSuffix")
        if (!legacy.isFile || legacy.length() != meta.size) return null
        val digest = MessageDigest.getInstance("SHA-256")
        legacy.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!actualHash.equals(meta.sha256, ignoreCase = true)) return null
        if (destination.exists()) destination.delete()
        return if (legacy.renameTo(destination)) destination else legacy
    }

    private fun failFileTransfer(transferId: String, message: String) {
        if (activeTransferId != transferId) return
        activeTransferId = null
        activeTransferFile?.delete()
        activeTransferFile = null
        _state.value = _state.value.copy(fileTransfer = null, error = message)
    }

    private suspend fun connectionLoop() {
        while (!_state.value.offlineMode) {
            try {
                _state.value = _state.value.copy(connection = "正在连接 Mac…", error = null)
                connectOnce()
                reconnectDelay = 1_000L
                while (webSocket != null) {
                    delay(5_000)
                    if (webSocket == null) break
                    val silentForMs = SystemClock.elapsedRealtime() - lastRelayMessageAtMs
                    if (silentForMs > 35_000L) {
                        Log.w("CodexMobile", "Relay heartbeat timed out after ${silentForMs}ms")
                        enterOfflineMode("连接超时")
                        break
                    }
                    request("status", JSONObject(), onError = {}) { result ->
                        updateAppServerReadiness(result.optBoolean("appServerReady"))
                        refreshRecentThreads()
                    }
                }
            } catch (t: Throwable) {
                Log.e("CodexMobile", "Connection loop failed", t)
                enterOfflineMode(t.message ?: "连接失败")
            } finally {
                closeTransport()
            }
            if (_state.value.offlineMode) break
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
        }
    }

    private fun connectOnce() {
        val context = requireNotNull(appContext)
        val pairing = PairingStore.load(context) ?: error("设备尚未配对，请先通过 USB 完成一次安全配对")
        val httpBuilder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
        pairing.relayCaCertificatePem?.let { certificatePem ->
            val trustManager = pinnedRelayTrustManager(certificatePem)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            httpBuilder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        val http = httpBuilder.build()
        val normalizedRelayUrl = pairing.relayUrl
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        sequenceKey = "relay_${normalizedRelayUrl.hashCode().toUInt().toString(16)}"
        val since = context.getSharedPreferences(SEQUENCE_PREFS, Context.MODE_PRIVATE).getLong(sequenceKey, 0L)
        lastSeenSequence = since
        val url = normalizedRelayUrl.toHttpUrl().newBuilder()
            .addQueryParameter("role", "android")
            .addQueryParameter("deviceId", android.os.Build.MODEL)
            .addQueryParameter("since", since.toString())
            .build()
        lastRelayMessageAtMs = SystemClock.elapsedRealtime()
        webSocket = http.newWebSocket(
            Request.Builder().url(url).header("Authorization", "Bearer ${pairing.relayToken}").build(),
            object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                lastRelayMessageAtMs = SystemClock.elapsedRealtime()
                _state.value = _state.value.copy(connection = "已连接", relayConnected = true, error = null)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                lastRelayMessageAtMs = SystemClock.elapsedRealtime()
                runCatching { handleMessage(JSONObject(text)) }
                    .onFailure { _state.value = _state.value.copy(error = it.message) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                enterOfflineMode(t.message ?: "连接失败")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                if (!_state.value.offlineMode) enterOfflineMode(reason.ifBlank { "连接已关闭" })
            }
            }
        )
    }

    private fun pinnedRelayTrustManager(certificatePem: String): X509TrustManager {
        val certificate = ByteArrayInputStream(certificatePem.toByteArray(Charsets.UTF_8)).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("codex-mobile-relay", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun closeTransport() {
        webSocket?.close(1000, "reconnect")
        webSocket = null
        val transportErrors = pendingErrors.values.toList()
        pending.clear()
        pendingErrors.clear()
        pendingTimeouts.values.forEach { it.cancel() }
        pendingTimeouts.clear()
        latestSyncInFlight.clear()
        recentSyncInFlight.set(false)
        manualSyncQueued.set(false)
        manualSyncId = null
        if (_state.value.manualSyncVisible) {
            _state.value = _state.value.copy(
                manualSyncVisible = false,
                manualSyncInProgress = false,
            )
        }
        _state.value = _state.value.copy(appServerReady = false)
        transportErrors.forEach { it("连接中断") }
        if (_state.value.sendPending) {
            val current = _state.value
            val pendingOutgoing = current.pendingOutgoing
            _state.value = current.copy(
                messages = if (pendingOutgoing == null) current.messages else current.messages.map { message ->
                    if (message.clientId == pendingOutgoing.message.clientId) {
                        message.copy(detail = "连接中断，正在确认是否送达…")
                    } else message
                },
                sendPending = false,
                preparingThreadId = null,
                preparedThreadId = null,
                error = "连接中断，正在重新核对消息是否已经送达",
            )
        } else {
            _state.value = _state.value.copy(preparingThreadId = null, preparedThreadId = null)
        }
        if (activeTransferId != null) {
            activeTransferId = null
            activeTransferFile?.delete()
            activeTransferFile = null
            _state.value = _state.value.copy(fileTransfer = null, error = "文件传输中断，请重新点击链接")
        }
    }

    private fun request(
        action: String,
        payload: JSONObject,
        onError: (String) -> Unit = { message -> _state.value = _state.value.copy(error = message) },
        onResult: (JSONObject) -> Unit,
    ) {
        if (_state.value.offlineMode) {
            onError("当前处于离线阅读模式")
            return
        }
        val id = UUID.randomUUID().toString()
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        pending[id] = { result ->
            val rttMs = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000.0
            Log.i("CodexLatency", "path=direct action=$action rttMs=${"%.3f".format(java.util.Locale.US, rttMs)}")
            onResult(result)
        }
        pendingErrors[id] = { message ->
            Log.e("CodexMobile", "Request $action failed: $message")
            onError(message)
        }
        pendingTimeouts[id] = scope.launch {
            delay(requestTimeoutMs(action))
            val callback = pending.remove(id)
            val errorCallback = pendingErrors.remove(id)
            pendingTimeouts.remove(id)
            if (callback != null) errorCallback?.invoke("请求超时，正在自动重试")
        }
        val message = JSONObject()
            .put("type", "request")
            .put("requestId", id)
            .put("action", action)
            .put("payload", payload)
        if (webSocket?.send(message.toString()) != true) {
            pendingTimeouts.remove(id)?.cancel()
            pending.remove(id)
            pendingErrors.remove(id)?.invoke("当前未连接")
        }
    }

    private fun handleMessage(message: JSONObject) {
        val sequence = message.optLong("seq", 0L)
        if (sequence > 0L) {
            val previous = lastSeenSequence
            lastSeenSequence = sequence
            appContext?.getSharedPreferences(SEQUENCE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putLong(sequenceKey, sequence)?.apply()
            if (previous > 0L && sequence > previous + 1L) {
                Log.w("CodexMobile", "Relay sequence gap: expected=${previous + 1L} actual=$sequence")
                scope.launch {
                    delay(250)
                    refreshRecentThreads()
                }
            }
        }
        when (message.optString("type")) {
            "relay.ready" -> {
                val mac = message.optBoolean("macConnected")
                if (!mac) {
                    enterOfflineMode("Mac 当前离线")
                    return
                }
                _state.value = _state.value.copy(macConnected = mac, connection = if (mac) "已连接" else "Mac 离线")
                refreshThreads()
                request("status", JSONObject()) { result ->
                    updateAppServerReadiness(result.optBoolean("appServerReady"))
                    refreshRecentThreads()
                }
            }
            "relay.status" -> {
                val wasConnected = _state.value.macConnected
                val macConnected = message.optBoolean("macConnected")
                if (!macConnected) {
                    enterOfflineMode("Mac 连接已断开")
                    return
                }
                _state.value = _state.value.copy(
                    macConnected = macConnected,
                    preparingThreadId = if (macConnected) _state.value.preparingThreadId else null,
                    preparedThreadId = if (macConnected) _state.value.preparedThreadId else null,
                )
                if (macConnected && !wasConnected) {
                    request("status", JSONObject(), onError = {}) { result ->
                        updateAppServerReadiness(result.optBoolean("appServerReady"))
                        refreshRecentThreads()
                    }
                }
            }
            "response" -> {
                val id = message.optString("requestId")
                val callback = pending.remove(id)
                val errorCallback = pendingErrors.remove(id)
                pendingTimeouts.remove(id)?.cancel()
                if (message.optBoolean("ok")) callback?.invoke(message.optJSONObject("result") ?: JSONObject())
                else errorCallback?.invoke(message.optJSONObject("error")?.optString("message") ?: "请求失败")
            }
            "snapshot" -> if (message.optString("snapshot") == "thread.list") {
                parseThreads(message.optJSONObject("result") ?: JSONObject())
            }
            "event" -> handleEvent(message.optString("event"), message.optJSONObject("data") ?: JSONObject())
            "status" -> if (message.optString("component") == "app-server") {
                updateAppServerReadiness(message.optBoolean("ok"))
            }
        }
    }

    private fun handleEvent(event: String, data: JSONObject) {
        val selectedId = _state.value.selectedThread?.id
        val eventThreadId = data.optString("threadId")
        when (event) {
            "thread.recent.sync.progress" -> {
                if (data.optString("syncId") == manualSyncId) {
                    Log.i(
                        "CodexMobile",
                        "Manual recent sync progress completed=${data.optInt("completedCount")} total=${data.optInt("totalCount")} failed=${data.optInt("failedCount")}",
                    )
                    _state.value = _state.value.copy(
                        manualSyncVisible = true,
                        manualSyncInProgress = true,
                        manualSyncCompleted = data.optInt("completedCount"),
                        manualSyncTotal = data.optInt("totalCount"),
                        manualSyncFailed = data.optInt("failedCount"),
                        manualSyncChanged = data.optInt("changedCount"),
                    )
                }
            }
            "turn/started" -> {
                updateThreadActivity(eventThreadId, ThreadActivity.RUNNING)
                if (eventThreadId == selectedId) {
                    _state.value = _state.value.copy(
                        activeTurnId = data.optJSONObject("turn")?.optString("id"),
                        lastTurnCompletedAtMs = null,
                    )
                }
            }
            "item/agentMessage/delta" -> if (data.optString("threadId") == selectedId) {
                _state.value = _state.value.copy(streamingText = _state.value.streamingText + data.optString("delta"))
            }
            "item/completed" -> if (data.optString("threadId") == selectedId) {
                val item = data.optJSONObject("item")
                if (item?.optString("type") == "agentMessage") {
                    val text = item.optString("text")
                    val id = item.optString("id", UUID.randomUUID().toString())
                    if (_state.value.messages.none { it.id == id }) {
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + ChatMessage(id, "assistant", text),
                            streamingText = "",
                        )
                        persistConversation(_state.value.selectedThread, _state.value.messages)
                    }
                }
            }
            "turn/completed" -> {
                val revision = data.optJSONObject("turn")?.optString("id")
                    .orEmpty().ifBlank { data.optString("turnId") }
                val isSelected = eventThreadId == selectedId
                updateThreadCompletion(eventThreadId, revision, isSelected)
                if (isSelected) {
                    _state.value = _state.value.copy(
                        activeTurnId = null,
                        streamingText = "",
                        sendPending = false,
                        lastTurnCompletedAtMs = System.currentTimeMillis(),
                    )
                    notifyCompleted()
                    refreshSelectedThread()
                }
                refreshThreads()
            }
            "appServer.request" -> {
                val method = data.optString("method")
                val params = data.optJSONObject("params")
                _state.value = _state.value.copy(
                    approval = PendingApproval(
                        data.opt("id"),
                        method,
                        params?.optString("reason").orEmpty().ifBlank { "Codex 请求执行需要确认的操作" },
                    )
                )
            }
        }
    }

    private fun parseThreads(result: JSONObject, allowSelectedRefresh: Boolean = true) {
        val array = result.optJSONArray("data") ?: JSONArray()
        val rawItems = buildList {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(threadSummary(it, it.optString("projectId"))) }
        }
        val projectArray = result.optJSONArray("projects") ?: JSONArray()
        val projects = buildList {
            for (i in 0 until projectArray.length()) {
                val obj = projectArray.optJSONObject(i) ?: continue
                add(
                    ProjectSummary(
                        id = obj.optString("id"),
                        name = obj.optString("name").ifBlank { "未命名项目" },
                        rootPath = obj.optJSONArray("rootPaths")?.optString(0).orEmpty(),
                        threadCount = obj.optInt("threadCount"),
                        order = obj.optInt("order", i),
                    )
                )
            }
        }
        val previous = _state.value
        val items = applyReadState(rawItems, previous.selectedThread?.id)
        val selectedProject = previous.selectedProject?.let { selected ->
            projects.firstOrNull { it.id == selected.id } ?: selected
        }
        val refreshedSelectedThread = previous.selectedThread?.let { selected ->
            items.firstOrNull { it.id == selected.id } ?: selected
        }
        val projectIds = projects.map { it.id }.toSet()
        val shouldInitializeExpansion = !previous.projectExpansionInitialized && projects.isNotEmpty()
        val expandedProjectIds = if (previous.projectExpansionInitialized) {
            previous.expandedProjectIds.intersect(projectIds)
        } else if (shouldInitializeExpansion) {
            projectIds
        } else {
            previous.expandedProjectIds
        }
        _state.value = _state.value.copy(
            threads = items,
            projects = projects.sortedBy { it.order },
            expandedProjectIds = expandedProjectIds,
            projectExpansionInitialized = previous.projectExpansionInitialized || shouldInitializeExpansion,
            selectedProject = selectedProject,
            selectedThread = refreshedSelectedThread,
        )
        scope.launch { catalogCache?.save(projects, items) }
        if (allowSelectedRefresh && refreshedSelectedThread != null &&
            previous.loadedContentRevision.isNotBlank() &&
            refreshedSelectedThread.contentRevision.isNotBlank() &&
            refreshedSelectedThread.contentRevision != previous.loadedContentRevision &&
            previous.activeTurnId == null
        ) {
            refreshSelectedThread(showSyncing = true)
        }
    }

    private fun parseRecentThreadSync(result: JSONObject) {
        result.optJSONObject("threadList")?.let { parseThreads(it, allowSelectedRefresh = false) }
        val sync = result.optJSONObject("sync") ?: return
        val entries = sync.optJSONArray("data") ?: return
        val preferences = appContext?.getSharedPreferences(RECENT_SYNC_PREFS, Context.MODE_PRIVATE) ?: return
        val editor = preferences.edit()
        val checkedThreadIds = mutableSetOf<String>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val threadId = entry.optString("threadId")
            if (threadId.isBlank()) continue
            checkedThreadIds += threadId
            if (entry.optBoolean("failed")) {
                Log.w("CodexMobile", "Recent thread failed threadId=$threadId error=${entry.optString("error")}")
                continue
            }
            val revision = entry.optString("revision")
            if (revision.isNotBlank()) editor.putString(threadId, revision)
            if (_state.value.selectedThread?.id == threadId) {
                parseLatestMessages(threadId, entry)
            } else if (!entry.optBoolean("notModified")) {
                val thread = _state.value.threads.firstOrNull { it.id == threadId } ?: continue
                val messages = chatMessages(threadId, entry.optJSONArray("messages") ?: JSONArray())
                if (revision.isNotBlank()) {
                    scope.launch { conversationCache?.save(thread.copy(contentRevision = revision), messages) }
                }
            }
        }
        preferences.all.keys.filterNot(checkedThreadIds::contains).forEach(editor::remove)
        editor.apply()
    }

    private fun threadSummary(obj: JSONObject, fallbackProjectId: String = ""): ThreadSummary {
        val id = obj.optString("id")
        val preview = userFacingPreview(obj.optString("preview"))
        val title = jsonText(obj, "name")
            .ifBlank { jsonText(obj, "title") }
            .ifBlank { preview.lineSequence().firstOrNull().orEmpty().take(48) }
            .ifBlank { "未命名任务" }
        return ThreadSummary(
            id = id,
            title = title,
            preview = preview,
            cwd = obj.optString("cwd"),
            updatedAt = obj.optLong("updatedAt", obj.optLong("updated_at", 0)),
            projectId = obj.optString("projectId").ifBlank { fallbackProjectId },
            activity = if (obj.optString("mobileActivity") == "running") ThreadActivity.RUNNING else ThreadActivity.IDLE,
            completionRevision = obj.optString("completionRevision"),
            contentRevision = obj.optString("contentRevision"),
        )
    }

    private fun applyReadState(items: List<ThreadSummary>, selectedThreadId: String?): List<ThreadSummary> {
        val context = appContext ?: return items
        val preferences = context.getSharedPreferences(THREAD_READ_PREFS, Context.MODE_PRIVATE)
        val initialized = preferences.getBoolean(THREAD_READ_INITIALIZED, false)
        val editor = preferences.edit()
        if (!initialized) editor.putBoolean(THREAD_READ_INITIALIZED, true)
        val projected = items.map { thread ->
            val revision = thread.completionRevision
            val selected = thread.id == selectedThreadId
            val knownThread = preferences.contains(thread.id)
            val seenRevision = preferences.getString(thread.id, null)
            if ((!initialized || !knownThread || selected) && revision.isNotBlank()) editor.putString(thread.id, revision)
            thread.copy(
                activity = displayedThreadActivity(
                    runtimeActivity = thread.activity,
                    completionRevision = revision,
                    seenRevision = seenRevision,
                    initialized = initialized && knownThread,
                    selected = selected,
                )
            )
        }
        editor.apply()
        return projected
    }

    private fun markThreadRead(thread: ThreadSummary) {
        if (thread.completionRevision.isBlank()) return
        appContext?.getSharedPreferences(THREAD_READ_PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(thread.id, thread.completionRevision)?.apply()
    }

    private fun updateThreadActivity(threadId: String, activity: ThreadActivity) {
        if (threadId.isBlank()) return
        _state.value = _state.value.copy(
            threads = _state.value.threads.map { if (it.id == threadId) it.copy(activity = activity) else it },
        )
    }

    private fun updateThreadCompletion(threadId: String, revision: String, selected: Boolean) {
        if (threadId.isBlank()) return
        val normalizedRevision = revision.ifBlank { "completed:${System.currentTimeMillis()}" }
        val activity = if (selected) ThreadActivity.IDLE else ThreadActivity.UNREAD
        val updatedThreads = _state.value.threads.map {
            if (it.id == threadId) it.copy(activity = activity, completionRevision = normalizedRevision) else it
        }
        val selectedThread = _state.value.selectedThread?.let {
            if (it.id == threadId) it.copy(activity = activity, completionRevision = normalizedRevision) else it
        }
        _state.value = _state.value.copy(threads = updatedThreads, selectedThread = selectedThread)
        if (selected) markThreadRead(selectedThread ?: return)
    }

    private fun jsonText(obj: JSONObject, key: String): String =
        if (!obj.has(key) || obj.isNull(key)) "" else obj.optString(key).takeUnless { it == "null" }.orEmpty()

    private fun userFacingPreview(raw: String): String {
        val requestMarkers = listOf("## My request:", "# My request:")
        val marker = requestMarkers
            .map { it to raw.lastIndexOf(it) }
            .filter { it.second >= 0 }
            .maxByOrNull { it.second }
        val body = when {
            marker != null -> raw.substring(marker.second + marker.first.length)
            raw.contains("<in-app-browser-context", ignoreCase = true) -> ""
            raw.contains("<app-context", ignoreCase = true) -> ""
            else -> raw
        }
        return body
            .lineSequence()
            .filterNot { line ->
                val text = line.trim()
                text.startsWith("<") && text.endsWith(">") &&
                    (text.contains("context", ignoreCase = true) ||
                        text.contains("instructions", ignoreCase = true))
            }
            .joinToString("\n")
            .trim()
    }

    private fun parseThread(result: JSONObject) {
        val thread = result.optJSONObject("thread") ?: return
        val messages = mutableListOf<ChatMessage>()
        val turns = thread.optJSONArray("turns") ?: JSONArray()
        for (i in 0 until turns.length()) {
            val items = turns.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val type = item.optString("type")
                when (type) {
                    "agentMessage" -> messages += ChatMessage(item.optString("id", "$i-$j"), "assistant", item.optString("text"))
                    "userMessage" -> messages += ChatMessage(item.optString("id", "$i-$j"), "user", userText(item))
                    "commandExecution" -> messages += ChatMessage(item.optString("id", "$i-$j"), "tool", item.optString("command"), item.optString("status"))
                    "fileChange" -> messages += ChatMessage(item.optString("id", "$i-$j"), "tool", "文件修改", item.optString("status"))
                }
            }
        }
        _state.value = _state.value.copy(messages = messages)
    }

    private fun parseLatestMessages(requestedThreadId: String, result: JSONObject) {
        if (_state.value.selectedThread?.id != requestedThreadId) return
        val revision = result.optString("revision").ifBlank { _state.value.selectedThread?.contentRevision.orEmpty() }
        if (result.optBoolean("notModified")) {
            _state.value = _state.value.copy(
                // The bridge has just compared the real Codex message tail,
                // so this is a verified live state rather than an assumption
                // based only on the phone's disk cache.
                conversationContentState = ConversationContentState.LIVE,
                loadedContentRevision = revision.ifBlank { _state.value.loadedContentRevision },
                error = null,
            )
            prepareSelectedThread()
            return
        }
        val messages = chatMessages(requestedThreadId, result.optJSONArray("messages") ?: JSONArray())
        val merged = mergeServerMessages(requestedThreadId, messages, _state.value.pendingOutgoing)
        val selectedThread = _state.value.selectedThread?.let { selected ->
            if (selected.id == requestedThreadId && revision.isNotBlank()) selected.copy(contentRevision = revision) else selected
        }
        _state.value = _state.value.copy(
            messages = merged.messages,
            pendingOutgoing = merged.pendingOutgoing,
            selectedThread = selectedThread,
            threads = _state.value.threads.map { thread ->
                if (thread.id == requestedThreadId && revision.isNotBlank()) thread.copy(contentRevision = revision) else thread
            },
            conversationContentState = ConversationContentState.LIVE,
            loadedContentRevision = revision,
            sendPending = false,
            error = null,
        )
        persistConversation(selectedThread, merged.messages)
        prepareSelectedThread()
    }

    private fun chatMessages(threadId: String, array: JSONArray): List<ChatMessage> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue
            add(
                ChatMessage(
                    id = item.optString("id", "$threadId-$i"),
                    role = item.optString("role", "assistant"),
                    text = text,
                    detail = if (item.optBoolean("truncated")) "内容过长，已截取" else "",
                    clientId = item.optString("clientId"),
                )
            )
        }
    }

    private fun persistConversation(thread: ThreadSummary?, messages: List<ChatMessage>) {
        if (thread == null || thread.contentRevision.isBlank()) return
        scope.launch { conversationCache?.save(thread, messages) }
    }

    private fun userText(item: JSONObject): String {
        item.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        val content = item.optJSONArray("content") ?: return ""
        return buildList {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                part.optString("text").takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("\n")
    }

    private fun notifyCompleted() {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("codex_results", "Codex 结果", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, "codex_results")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Codex 已完成")
            .setContentText(_state.value.selectedThread?.title ?: "任务已有新结果")
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(2002, notification) }
    }

    private data class FileMetadata(
        val sourcePath: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val mtimeMs: Long,
        val sha256: String,
        val line: Int?,
    )

    private data class AttachmentMetadata(
        val name: String,
        val mimeType: String,
        val size: Long,
        val sha256: String,
    )

    data class HtmlResource(val bytes: ByteArray, val mimeType: String)

    private const val MAX_HTML_RESOURCE_BYTES = 20L * 1024L * 1024L
    private const val HTML_RESOURCE_TIMEOUT_SECONDS = 30L
    private const val MAX_ATTACHMENTS_PER_TURN = 10
    private const val MAX_UPLOAD_BYTES = 100L * 1024L * 1024L
    private const val DEFAULT_UPLOAD_CHUNK_BYTES = 512 * 1024
}

private fun requestTimeoutMs(action: String): Long = when (action) {
    "thread.recent.sync" -> 180_000L
    "turn.start", "thread.prepare" -> 150_000L
    "file.upload.begin", "file.upload.chunk", "file.upload.finish",
    "file.stat", "file.read" -> 90_000L
    else -> 45_000L
}

private fun mimeTypeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "txt", "log" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "tsv" -> "text/tab-separated-values"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
