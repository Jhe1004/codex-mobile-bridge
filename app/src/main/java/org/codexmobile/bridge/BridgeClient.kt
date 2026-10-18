package org.codexmobile.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    @Volatile private var activeTransferId: String? = null
    @Volatile private var activeTransferFile: File? = null
    private var reconnectDelay = 1_000L
    private const val SEQUENCE_PREFS = "relay_sequence_v2"
    @Volatile private var sequenceKey = "default"

    fun start(context: Context) {
        appContext = context.applicationContext
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch { connectionLoop() }
    }

    fun ensureConnected(context: Context) {
        start(context)
        if (lastRelayMessageAtMs > 0L && SystemClock.elapsedRealtime() - lastRelayMessageAtMs > 35_000L) {
            forceReconnect()
        }
    }

    fun forceReconnect() {
        _state.value = _state.value.copy(connection = "正在重新连接…", relayConnected = false)
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

    fun refreshHome() {
        if (webSocket == null || !_state.value.relayConnected || !_state.value.macConnected) {
            forceReconnect()
            return
        }
        refreshThreads()
        request("status", JSONObject()) { result ->
            _state.value = _state.value.copy(appServerReady = result.optBoolean("appServerReady"))
        }
    }

    fun toggleProject(projectId: String) {
        val expanded = _state.value.expandedProjectIds.toMutableSet()
        if (!expanded.add(projectId)) expanded.remove(projectId)
        _state.value = _state.value.copy(expandedProjectIds = expanded)
    }

    fun selectThread(thread: ThreadSummary) {
        _state.value = _state.value.copy(
            selectedThread = thread,
            messages = emptyList(),
            streamingText = "",
            lastTurnCompletedAtMs = null,
            sendPending = false,
            preparingThreadId = thread.id,
            preparedThreadId = null,
            error = null,
        )
        prepareThread(thread.id)
        refreshSelectedThread()
    }

    private fun prepareThread(threadId: String) {
        request(
            "thread.prepare",
            JSONObject().put("threadId", threadId),
            onError = { message ->
                Log.w("CodexMobile", "Thread preheat failed for $threadId: $message")
                if (_state.value.selectedThread?.id == threadId) {
                    _state.value = _state.value.copy(preparingThreadId = null, preparedThreadId = null)
                }
            },
        ) { result ->
            if (_state.value.selectedThread?.id == threadId && result.optBoolean("ready")) {
                _state.value = _state.value.copy(
                    preparingThreadId = null,
                    preparedThreadId = threadId,
                )
            }
        }
    }

    fun refreshSelectedThread() {
        val threadId = _state.value.selectedThread?.id ?: return
        if (_state.value.activeTurnId != null) return
        request("thread.latest", JSONObject().put("threadId", threadId).put("limit", 120)) { result ->
            parseLatestMessages(threadId, result)
        }
    }

    fun refreshSelectedSession() {
        val threadId = _state.value.selectedThread?.id ?: return
        if (_state.value.preparingThreadId != threadId) {
            _state.value = _state.value.copy(
                preparingThreadId = threadId,
                preparedThreadId = null,
                error = null,
            )
            prepareThread(threadId)
        }
        refreshSelectedThread()
    }

    fun backToThreads() {
        _state.value = _state.value.copy(
            selectedThread = null,
            messages = emptyList(),
            streamingText = "",
            activeTurnId = null,
            lastTurnCompletedAtMs = null,
            sendPending = false,
            preparingThreadId = null,
            preparedThreadId = null,
            error = null,
        )
    }

    fun newThread(project: ProjectSummary) {
        request("thread.start", JSONObject().put("ephemeral", false).put("cwd", project.rootPath)) { result ->
            val thread = result.optJSONObject("thread") ?: return@request
            val summary = threadSummary(thread, project.id)
            _state.value = _state.value.copy(selectedThread = summary, messages = emptyList())
            refreshThreads()
        }
    }

    fun send(text: String, onAccepted: () -> Unit = {}) {
        val thread = _state.value.selectedThread ?: return
        val trimmed = text.trim()
        if (trimmed.isBlank() || _state.value.sendPending || _state.value.activeTurnId != null) return
        _state.value = _state.value.copy(
            sendPending = true,
            streamingText = "",
            lastTurnCompletedAtMs = null,
            error = null,
        )
        request(
            "turn.start",
            JSONObject().put("threadId", thread.id).put("text", trimmed),
            onError = { message ->
                _state.value = _state.value.copy(sendPending = false, error = message)
            },
        ) { result ->
            val turnId = result.optJSONObject("turn")?.optString("id").orEmpty()
            val user = ChatMessage(UUID.randomUUID().toString(), "user", trimmed)
            _state.value = _state.value.copy(
                messages = _state.value.messages + user,
                activeTurnId = turnId.ifBlank { null },
                sendPending = false,
                preparingThreadId = null,
                preparedThreadId = thread.id,
                error = null,
            )
            mainHandler.post(onAccepted)
        }
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

    fun downloadFile(target: String) {
        val threadId = _state.value.selectedThread?.id ?: return
        if (_state.value.fileTransfer != null) return
        val transferId = UUID.randomUUID().toString()
        activeTransferId = transferId
        _state.value = _state.value.copy(
            fileTransfer = FileTransferState("正在读取文件信息…", 0, 0),
            error = null,
        )
        val payload = JSONObject().put("threadId", threadId).put("path", target)
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
            val context = requireNotNull(appContext)
            val directory = File(context.cacheDir, "mobile_files").apply { mkdirs() }
            val safeName = meta.name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"), "_").ifBlank { "download.bin" }
            val destination = File(directory, "${meta.sha256.take(12)}-$safeName")
            if (destination.exists()) destination.delete()
            destination.createNewFile()
            activeTransferFile = destination
            _state.value = _state.value.copy(fileTransfer = FileTransferState(meta.name, 0, meta.size))
            if (meta.size == 0L) finishFileTransfer(transferId, meta, destination, MessageDigest.getInstance("SHA-256"))
            else fetchFileChunk(transferId, threadId, target, meta, destination, 0L, MessageDigest.getInstance("SHA-256"))
        }
    }

    fun closeFilePreview() {
        _state.value = _state.value.copy(filePreview = null)
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
                finishFileTransfer(transferId, meta, destination, digest)
            } else {
                fetchFileChunk(transferId, threadId, target, meta, destination, nextOffset, digest)
            }
        }
    }

    private fun finishFileTransfer(transferId: String, meta: FileMetadata, destination: File, digest: MessageDigest) {
        if (activeTransferId != transferId) return
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!actualHash.equals(meta.sha256, ignoreCase = true) || destination.length() != meta.size) {
            destination.delete()
            failFileTransfer(transferId, "文件完整性校验失败，请重新点击链接")
            return
        }
        activeTransferId = null
        activeTransferFile = null
        Log.i("CodexFileTransfer", "name=${meta.name} bytes=${meta.size} sha256Verified=true")
        _state.value = _state.value.copy(
            fileTransfer = null,
            filePreview = FilePreview(
                localPath = destination.absolutePath,
                sourcePath = meta.sourcePath,
                name = meta.name,
                mimeType = meta.mimeType,
                size = meta.size,
                line = meta.line,
            ),
            error = null,
        )
    }

    private fun failFileTransfer(transferId: String, message: String) {
        if (activeTransferId != transferId) return
        activeTransferId = null
        activeTransferFile?.delete()
        activeTransferFile = null
        _state.value = _state.value.copy(fileTransfer = null, error = message)
    }

    private suspend fun connectionLoop() {
        while (true) {
            try {
                _state.value = _state.value.copy(connection = "正在连接 Mac…", error = null)
                connectOnce()
                reconnectDelay = 1_000L
                while (webSocket != null) {
                    delay(10_000)
                    if (webSocket == null) break
                    val silentForMs = SystemClock.elapsedRealtime() - lastRelayMessageAtMs
                    if (silentForMs > 35_000L) {
                        Log.w("CodexMobile", "Relay heartbeat timed out after ${silentForMs}ms")
                        forceReconnect()
                        break
                    }
                    request("status", JSONObject()) {}
                }
            } catch (t: Throwable) {
                Log.e("CodexMobile", "Connection loop failed", t)
                _state.value = _state.value.copy(
                    connection = "连接中断，正在重试…",
                    relayConnected = false,
                    macConnected = false,
                    error = t.message,
                )
            } finally {
                closeTransport()
            }
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
                _state.value = _state.value.copy(connection = "连接失败", relayConnected = false, error = t.message)
                webSocket = null
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
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
        pending.clear()
        pendingErrors.clear()
        if (_state.value.sendPending) {
            _state.value = _state.value.copy(sendPending = false, error = "连接中断，消息尚未发送，请重试")
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
        val message = JSONObject()
            .put("type", "request")
            .put("requestId", id)
            .put("action", action)
            .put("payload", payload)
        if (webSocket?.send(message.toString()) != true) {
            pending.remove(id)
            pendingErrors.remove(id)?.invoke("当前未连接")
        }
    }

    private fun handleMessage(message: JSONObject) {
        val sequence = message.optLong("seq", 0L)
        if (sequence > 0L) {
            appContext?.getSharedPreferences(SEQUENCE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putLong(sequenceKey, sequence)?.apply()
        }
        when (message.optString("type")) {
            "relay.ready" -> {
                val mac = message.optBoolean("macConnected")
                _state.value = _state.value.copy(macConnected = mac, connection = if (mac) "已连接" else "Mac 离线")
                refreshThreads()
                _state.value.selectedThread?.id?.let(::prepareThread)
                request("status", JSONObject()) { result ->
                    _state.value = _state.value.copy(appServerReady = result.optBoolean("appServerReady"))
                }
            }
            "relay.status" -> _state.value = _state.value.copy(macConnected = message.optBoolean("macConnected"))
            "response" -> {
                val id = message.optString("requestId")
                val callback = pending.remove(id)
                val errorCallback = pendingErrors.remove(id)
                if (message.optBoolean("ok")) callback?.invoke(message.optJSONObject("result") ?: JSONObject())
                else errorCallback?.invoke(message.optJSONObject("error")?.optString("message") ?: "请求失败")
            }
            "snapshot" -> if (message.optString("snapshot") == "thread.list") {
                parseThreads(message.optJSONObject("result") ?: JSONObject())
            }
            "event" -> handleEvent(message.optString("event"), message.optJSONObject("data") ?: JSONObject())
            "status" -> if (message.optString("component") == "app-server") {
                _state.value = _state.value.copy(appServerReady = message.optBoolean("ok"))
            }
        }
    }

    private fun handleEvent(event: String, data: JSONObject) {
        val selectedId = _state.value.selectedThread?.id
        when (event) {
            "turn/started" -> if (data.optString("threadId") == selectedId) {
                _state.value = _state.value.copy(
                    activeTurnId = data.optJSONObject("turn")?.optString("id"),
                    lastTurnCompletedAtMs = null,
                )
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
                    }
                }
            }
            "turn/completed" -> if (data.optString("threadId") == selectedId) {
                _state.value = _state.value.copy(
                    activeTurnId = null,
                    streamingText = "",
                    sendPending = false,
                    lastTurnCompletedAtMs = System.currentTimeMillis(),
                )
                notifyCompleted()
                refreshThreads()
                refreshSelectedThread()
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

    private fun parseThreads(result: JSONObject) {
        val array = result.optJSONArray("data") ?: JSONArray()
        val items = buildList {
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
        val selectedProject = previous.selectedProject?.let { selected ->
            projects.firstOrNull { it.id == selected.id } ?: selected
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
        )
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
        )
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
        val array = result.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("text")
                if (text.isBlank()) continue
                add(
                    ChatMessage(
                        id = item.optString("id", "$requestedThreadId-$i"),
                        role = item.optString("role", "assistant"),
                        text = text,
                        detail = if (item.optBoolean("truncated")) "内容过长，已截取" else "",
                    )
                )
            }
        }
        _state.value = _state.value.copy(messages = messages)
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

    data class HtmlResource(val bytes: ByteArray, val mimeType: String)

    private const val MAX_HTML_RESOURCE_BYTES = 20L * 1024L * 1024L
    private const val HTML_RESOURCE_TIMEOUT_SECONDS = 30L
}
