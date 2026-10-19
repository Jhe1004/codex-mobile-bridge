package org.codexmobile.bridge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        setContent { CodexMobileApp(onBatterySettings = ::openBatterySettings) }
    }

    override fun onStart() {
        super.onStart()
        BridgeClient.ensureConnected(this)
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}

private val Ink = Color(0xFF0D0D0D)
private val Paper = Color(0xFFFFFFFF)
private val SoftSurface = Color(0xFFF7F7F8)
private val SoftSurfaceStrong = Color(0xFFF1F1F1)
private val OutlineSoft = Color(0xFFE5E5E5)
private val Muted = Color(0xFF6B6B6B)
private val Accent = Color(0xFF10A37F)
private val UnreadBlue = Color(0xFF3B82F6)

private val ChatGptColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Muted,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = Ink,
    outline = OutlineSoft,
    error = Color(0xFFC7372F),
)

internal enum class ConnectionHealth {
    HEALTHY,
    DEGRADED,
    DISCONNECTED,
}

internal enum class SessionConnectionHealth {
    READY,
    PREPARING,
    UNHEALTHY,
}

internal fun connectionHealth(
    relayConnected: Boolean,
    macConnected: Boolean,
    appServerReady: Boolean,
): ConnectionHealth = when {
    relayConnected && macConnected && appServerReady -> ConnectionHealth.HEALTHY
    relayConnected -> ConnectionHealth.DEGRADED
    else -> ConnectionHealth.DISCONNECTED
}

internal fun sessionConnectionHealth(
    relayConnected: Boolean,
    macConnected: Boolean,
    appServerReady: Boolean,
    threadId: String?,
    preparingThreadId: String?,
    preparedThreadId: String?,
): SessionConnectionHealth = when {
    !relayConnected || !macConnected || !appServerReady || threadId == null -> SessionConnectionHealth.UNHEALTHY
    preparedThreadId == threadId -> SessionConnectionHealth.READY
    preparingThreadId == threadId -> SessionConnectionHealth.PREPARING
    else -> SessionConnectionHealth.UNHEALTHY
}

@Composable
private fun HeartbeatStatusIndicator(state: BridgeUiState, onClick: () -> Unit) {
    val health = connectionHealth(state.relayConnected, state.macConnected, state.appServerReady)
    val color = when (health) {
        ConnectionHealth.HEALTHY -> Accent
        ConnectionHealth.DEGRADED -> Color(0xFFD99020)
        ConnectionHealth.DISCONNECTED -> Color(0xFFC7372F)
    }
    val description = when (health) {
        ConnectionHealth.HEALTHY -> "连接正常：手机、Mac 和 Codex 均在线"
        ConnectionHealth.DEGRADED -> "连接不完整：手机已连接，但 Mac 或 Codex 暂时不可用"
        ConnectionHealth.DISCONNECTED -> "连接已断开"
    }
    val transition = rememberInfiniteTransition(label = "connection-heartbeat")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (health == ConnectionHealth.HEALTHY) 650 else 1_200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heartbeat-pulse",
    )
    val visiblePulse = if (health == ConnectionHealth.DISCONNECTED) 0.45f else pulse

    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "$description，点击刷新" },
    ) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(
                color = color.copy(alpha = 0.08f + visiblePulse * 0.15f),
                radius = size.minDimension * (0.36f + visiblePulse * 0.08f),
            )
            val waveform = Path().apply {
                moveTo(size.width * 0.10f, size.height * 0.53f)
                lineTo(size.width * 0.30f, size.height * 0.53f)
                lineTo(size.width * 0.40f, size.height * 0.37f)
                lineTo(size.width * 0.50f, size.height * 0.72f)
                lineTo(size.width * 0.61f, size.height * 0.28f)
                lineTo(size.width * 0.70f, size.height * 0.53f)
                lineTo(size.width * 0.90f, size.height * 0.53f)
            }
            drawPath(
                path = waveform,
                color = color.copy(alpha = 0.55f + visiblePulse * 0.45f),
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawCircle(
                color = color,
                radius = 2.2.dp.toPx(),
                center = center,
                alpha = visiblePulse,
            )
        }
    }
}

@Composable
private fun SessionHeartbeatIndicator(state: BridgeUiState, onClick: () -> Unit) {
    val health = sessionConnectionHealth(
        relayConnected = state.relayConnected,
        macConnected = state.macConnected,
        appServerReady = state.appServerReady,
        threadId = state.selectedThread?.id,
        preparingThreadId = state.preparingThreadId,
        preparedThreadId = state.preparedThreadId,
    )
    val color = when (health) {
        SessionConnectionHealth.READY -> Accent
        SessionConnectionHealth.PREPARING -> Color(0xFFD99020)
        SessionConnectionHealth.UNHEALTHY -> Color(0xFFC7372F)
    }
    val description = when (health) {
        SessionConnectionHealth.READY -> "会话连接健康，点击刷新会话"
        SessionConnectionHealth.PREPARING -> "会话正在准备，点击重新检查"
        SessionConnectionHealth.UNHEALTHY -> "会话连接不健康，点击重新连接并刷新"
    }
    val transition = rememberInfiniteTransition(label = "session-heartbeat")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (health == SessionConnectionHealth.READY) 650 else 1_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "session-heartbeat-pulse",
    )
    val visiblePulse = if (health == SessionConnectionHealth.UNHEALTHY) 0.45f else pulse

    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(
                color = color.copy(alpha = 0.08f + visiblePulse * 0.15f),
                radius = size.minDimension * (0.36f + visiblePulse * 0.08f),
            )
            val waveform = Path().apply {
                moveTo(size.width * 0.10f, size.height * 0.53f)
                lineTo(size.width * 0.30f, size.height * 0.53f)
                lineTo(size.width * 0.40f, size.height * 0.37f)
                lineTo(size.width * 0.50f, size.height * 0.72f)
                lineTo(size.width * 0.61f, size.height * 0.28f)
                lineTo(size.width * 0.70f, size.height * 0.53f)
                lineTo(size.width * 0.90f, size.height * 0.53f)
            }
            drawPath(
                path = waveform,
                color = color.copy(alpha = 0.55f + visiblePulse * 0.45f),
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawCircle(
                color = color,
                radius = 2.2.dp.toPx(),
                center = center,
                alpha = visiblePulse,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexMobileApp(onBatterySettings: () -> Unit) {
    val state by BridgeClient.state.collectAsState()
    MaterialTheme(colorScheme = ChatGptColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            when {
                state.filePreview != null -> FilePreviewScreen(state.filePreview!!)
                state.selectedThread != null -> ChatScreen(state)
                else -> ProjectTreeScreen(state, onBatterySettings)
            }
            state.fileTransfer?.let { transfer ->
                val progress = if (transfer.totalBytes > 0) transfer.receivedBytes.toFloat() / transfer.totalBytes else 0f
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("正在从 Mac 传输") },
                    text = {
                        Column {
                            Text(transfer.name, maxLines = 2)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(
                                if (transfer.totalBytes > 0) "${transfer.receivedBytes / 1024} KB / ${transfer.totalBytes / 1024} KB" else "正在读取文件信息…",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = { OutlinedButton(onClick = BridgeClient::cancelFileTransfer) { Text("取消") } },
                )
            }
            state.approval?.let { approval ->
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Codex 需要确认") },
                    text = { Column { Text(approval.method, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(approval.description) } },
                    confirmButton = { Button(onClick = { BridgeClient.answerApproval(true) }) { Text("允许") } },
                    dismissButton = { OutlinedButton(onClick = { BridgeClient.answerApproval(false) }) { Text("拒绝") } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectTreeScreen(state: BridgeUiState, onBatterySettings: () -> Unit) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Column { Text("Codex", fontWeight = FontWeight.SemiBold, color = Ink); Text(state.connection, style = MaterialTheme.typography.labelSmall, color = if (state.relayConnected && state.macConnected) Accent else Muted) } },
                actions = {
                    HeartbeatStatusIndicator(state, onClick = BridgeClient::refreshHome)
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                if (!state.relayConnected || !state.macConnected || !state.appServerReady) {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, OutlineSoft),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("  ${state.connection}", fontWeight = FontWeight.SemiBold)
                            }
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                            OutlinedButton(onClick = BridgeClient::forceReconnect, modifier = Modifier.padding(top = 8.dp)) { Text("立即重连") }
                            OutlinedButton(onClick = onBatterySettings, modifier = Modifier.padding(top = 8.dp)) { Text("允许后台持续运行") }
                        }
                    }
                }
            }
            item {
                Text(
                    "项目与会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            state.projects.forEach { project ->
                val expanded = project.id in state.expandedProjectIds
                val projectThreads = state.threads.filter { it.projectId == project.id }
                val runningCount = projectThreads.count { it.activity == ThreadActivity.RUNNING }
                val unreadCount = projectThreads.count { it.activity == ThreadActivity.UNREAD }
                val projectStatus = buildList {
                    add("${projectThreads.size} 个会话")
                    if (runningCount > 0) add("$runningCount 个运行中")
                    if (unreadCount > 0) add("$unreadCount 个未读")
                }.joinToString(" · ")
                item(key = "project-${project.id}") {
                    Surface(
                        color = SoftSurface,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.weight(1f).clickable { BridgeClient.toggleProject(project.id) }.padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (expanded) "⌄" else "›", style = MaterialTheme.typography.titleLarge, color = Muted)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(project.name, fontWeight = FontWeight.SemiBold, color = Ink)
                                    Text(projectStatus, style = MaterialTheme.typography.labelSmall, color = if (runningCount + unreadCount > 0) Accent else Muted)
                                }
                            }
                            if (project.rootPath.isNotBlank()) {
                                IconButton(onClick = { BridgeClient.newThread(project) }) { Icon(Icons.Default.Add, "在这个项目中新建会话", tint = Ink) }
                            }
                        }
                    }
                }
                if (expanded) {
                    items(projectThreads, key = { "thread-${it.id}" }) { thread ->
                        Surface(
                            onClick = { BridgeClient.selectThread(thread) },
                            color = Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(start = 42.dp, end = 14.dp, top = 1.dp, bottom = 1.dp).fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    thread.title,
                                    color = Ink,
                                    maxLines = 2,
                                    fontWeight = if (thread.activity == ThreadActivity.UNREAD) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.size(10.dp))
                                ThreadActivityIndicator(thread.activity)
                            }
                        }
                    }
                    if (projectThreads.isEmpty()) {
                        item(key = "empty-${project.id}") {
                            Text("暂无会话", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 58.dp, top = 6.dp, bottom = 10.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ThreadActivityIndicator(activity: ThreadActivity) {
    val color = when (activity) {
        ThreadActivity.RUNNING -> Accent
        ThreadActivity.UNREAD -> UnreadBlue
        ThreadActivity.IDLE -> Muted
    }
    val label = when (activity) {
        ThreadActivity.RUNNING -> "运行中"
        ThreadActivity.UNREAD -> "新回复"
        ThreadActivity.IDLE -> "空闲"
    }
    val alpha = if (activity == ThreadActivity.RUNNING) {
        val transition = rememberInfiniteTransition(label = "thread-activity")
        val runningPulse by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "thread-running-pulse",
        )
        runningPulse
    } else {
        1f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "会话状态：$label" },
    ) {
        Box(
            Modifier
                .size(if (activity == ThreadActivity.IDLE) 6.dp else 8.dp)
                .alpha(alpha)
                .background(color, CircleShape),
        )
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (activity == ThreadActivity.IDLE) FontWeight.Normal else FontWeight.SemiBold,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: BridgeUiState) {
    var input by remember { mutableStateOf("") }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        BridgeClient.addAttachments(uris)
    }
    val listState = rememberLazyListState()
    val threadId = state.selectedThread?.id
    val threadStatus = when {
        !state.relayConnected || !state.macConnected -> state.connection
        state.preparingThreadId == threadId -> "正在准备会话…"
        state.preparedThreadId == threadId -> "会话已就绪"
        else -> state.connection
    }
    val hasStreamingMessage = state.streamingText.isNotBlank()
    val hasErrorMessage = state.error != null
    val hasConversationContent = state.messages.isNotEmpty() || hasStreamingMessage || hasErrorMessage
    val lastItemIndex = state.messages.size + (if (hasStreamingMessage) 1 else 0) + (if (hasErrorMessage) 1 else 0)
    var initialBottomShown by remember(threadId) { mutableStateOf(false) }
    LaunchedEffect(threadId, lastItemIndex, state.streamingText) {
        if (hasConversationContent) {
            // Instant positioning avoids the visible top-to-bottom animation that caused dizziness.
            // The final spacer is the true last item, so long messages also open at their bottom.
            listState.scrollToItem(lastItemIndex)
            initialBottomShown = true
        }
    }
    LaunchedEffect(state.selectedThread?.id) {
        while (true) {
            delay(5_000)
            BridgeClient.refreshSelectedThread()
        }
    }
    BackHandler(onBack = BridgeClient::backToThreads)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Column { Text(state.selectedThread?.title ?: "任务", maxLines = 1, fontWeight = FontWeight.SemiBold, color = Ink); Text(threadStatus, style = MaterialTheme.typography.labelSmall, color = if (state.preparedThreadId == threadId) Accent else Muted) } },
                navigationIcon = { IconButton(onClick = BridgeClient::backToThreads) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    SessionHeartbeatIndicator(state, onClick = BridgeClient::refreshSelectedSession)
                    if (state.activeTurnId != null) IconButton(onClick = BridgeClient::stopTurn) { Icon(Icons.Default.Close, "停止") }
                },
            )
        },
        bottomBar = {
            // Keyboard insets belong to the whole page. Applying them inside this Row would
            // enlarge the composer itself and create a large blank gap below the text field.
            Row(Modifier.fillMaxWidth().background(Paper).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Surface(
                    color = Paper,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, OutlineSoft),
                    shadowElevation = 5.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.composerAttachments.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.composerAttachments.forEach { attachment ->
                                    ComposerAttachmentChip(attachment, onRemove = { BridgeClient.removeAttachment(attachment.localId) })
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            val canAttach = state.activeTurnId == null && !state.sendPending && state.composerAttachments.size < 10
                            IconButton(
                                onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                                enabled = canAttach,
                                modifier = Modifier.padding(bottom = 5.dp),
                            ) { Icon(Icons.Default.AttachFile, "添加图片或文件", tint = if (canAttach) Ink else Muted) }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("询问 Codex", color = Color(0xFF9A9A9A)) },
                                shape = RoundedCornerShape(24.dp),
                                maxLines = 6,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            )
                            val attachmentsReady = state.composerAttachments.isNotEmpty() &&
                                state.composerAttachments.all { it.status == AttachmentUploadStatus.READY }
                            val canSend = (input.isNotBlank() || attachmentsReady) &&
                                state.activeTurnId == null && !state.sendPending &&
                                state.composerAttachments.none { it.status != AttachmentUploadStatus.READY }
                            IconButton(
                                onClick = { BridgeClient.send(input) { input = "" } },
                                enabled = canSend,
                                modifier = Modifier
                                    .padding(start = 4.dp, bottom = 5.dp)
                                    .size(42.dp)
                                    .background(if (canSend) Ink else Color(0xFFD7D7D7), CircleShape),
                            ) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Color.White) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .alpha(if (initialBottomShown || !hasConversationContent) 1f else 0f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { MessageBubble(it) }
            if (state.streamingText.isNotBlank()) item { MessageBubble(ChatMessage("streaming", "assistant", state.streamingText)) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ComposerAttachmentChip(attachment: ComposerAttachment, onRemove: () -> Unit) {
    val statusText = when (attachment.status) {
        AttachmentUploadStatus.HASHING -> "正在校验"
        AttachmentUploadStatus.UPLOADING -> if (attachment.size > 0) {
            "${(attachment.uploadedBytes * 100 / attachment.size).coerceIn(0, 100)}%"
        } else "正在上传"
        AttachmentUploadStatus.READY -> "已就绪"
        AttachmentUploadStatus.FAILED -> attachment.error ?: "上传失败"
    }
    Surface(
        color = if (attachment.status == AttachmentUploadStatus.FAILED) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else SoftSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (attachment.status == AttachmentUploadStatus.FAILED) MaterialTheme.colorScheme.error.copy(alpha = 0.35f) else OutlineSoft),
        modifier = Modifier.widthIn(max = 220.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(attachment.name, maxLines = 1, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(statusText, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = if (attachment.status == AttachmentUploadStatus.FAILED) MaterialTheme.colorScheme.error else Muted)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, "移除 ${attachment.name}", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == "user"
    val tool = message.role == "tool"
    val uriHandler = LocalUriHandler.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (mine) 0.84f else 1f)
                .background(
                    if (mine) SoftSurfaceStrong else if (tool) SoftSurface else Color.Transparent,
                    RoundedCornerShape(20.dp),
                )
                .padding(if (mine || tool) 14.dp else 8.dp)
        ) {
            Column {
                if (tool) Text("工具 · ${message.detail}", style = MaterialTheme.typography.labelSmall, color = Muted)
                SelectionContainer {
                    MarkdownText(
                        text = message.text,
                        color = Ink,
                        codeSurface = Color(0xFFF1F1F1),
                        quoteSurface = Color(0xFFF7F7F7),
                        onLink = { target ->
                            if (target.startsWith("https://") || target.startsWith("http://")) uriHandler.openUri(target)
                            else BridgeClient.downloadFile(target)
                        },
                    )
                }
                if (!tool && message.detail.isNotBlank()) Text(message.detail, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
