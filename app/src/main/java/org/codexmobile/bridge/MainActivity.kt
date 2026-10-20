package org.codexmobile.bridge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Black.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Black.toArgb()),
        )
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

private val Ink = Color(0xFFF5F5F5)
private val Paper = Color(0xFF000000)
private val SoftSurface = Color(0xFF121212)
private val SoftSurfaceStrong = Color(0xFF202020)
private val OutlineSoft = Color(0xFF333333)
private val Muted = Color(0xFFA3A3A3)
private val Accent = Color(0xFF10A37F)
private val UnreadBlue = Color(0xFF60A5FA)

private val ChatGptColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Accent,
    onSecondary = Color.Black,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = Ink,
    outline = OutlineSoft,
    error = Color(0xFFFF6B6B),
)

internal enum class ConnectionHealth {
    HEALTHY,
    DEGRADED,
    DISCONNECTED,
    OFFLINE,
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
    offlineMode: Boolean = false,
): ConnectionHealth = when {
    offlineMode -> ConnectionHealth.OFFLINE
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
): SessionConnectionHealth = when {
    !relayConnected || !macConnected || !appServerReady || threadId == null -> SessionConnectionHealth.UNHEALTHY
    preparingThreadId == threadId -> SessionConnectionHealth.PREPARING
    else -> SessionConnectionHealth.READY
}

@Composable
private fun HeartbeatStatusIndicator(state: BridgeUiState, onClick: () -> Unit) {
    val health = connectionHealth(state.relayConnected, state.macConnected, state.appServerReady, state.offlineMode)
    val color = when (health) {
        ConnectionHealth.HEALTHY -> Accent
        ConnectionHealth.DEGRADED -> Color(0xFFD99020)
        ConnectionHealth.DISCONNECTED -> Color(0xFFC7372F)
        ConnectionHealth.OFFLINE -> Muted
    }
    val description = when (health) {
        ConnectionHealth.HEALTHY -> "连接正常：手机、Mac 和 Codex 均在线"
        ConnectionHealth.DEGRADED -> "连接不完整：手机已连接，但 Mac 或 Codex 暂时不可用"
        ConnectionHealth.DISCONNECTED -> "连接已断开"
        ConnectionHealth.OFFLINE -> "离线阅读模式，点击尝试重新连接"
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
    val visiblePulse = if (health == ConnectionHealth.DISCONNECTED || health == ConnectionHealth.OFFLINE) 0.45f else pulse

    IconButton(
        onClick = onClick,
        enabled = !state.manualSyncInProgress,
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
private fun ManualSyncProgress(state: BridgeUiState) {
    if (!state.manualSyncVisible) return
    val total = state.manualSyncTotal
    val completed = state.manualSyncCompleted.coerceAtMost(total.coerceAtLeast(0))
    val label = when {
        state.manualSyncInProgress && total <= 0 -> "正在准备核对最近 24 小时会话…"
        state.manualSyncInProgress -> "正在同步 $completed / $total"
        state.manualSyncFailed > 0 -> "已核对 $total 个会话，${state.manualSyncFailed} 个将自动重试"
        else -> "同步完成：已核对 $total 个，更新 ${state.manualSyncChanged} 个"
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(Paper).padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(6.dp))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = SoftSurfaceStrong,
            )
        } else if (state.manualSyncInProgress) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = SoftSurfaceStrong,
            )
        } else {
            LinearProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = SoftSurfaceStrong,
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
    )
    val color = when (health) {
        SessionConnectionHealth.READY -> if (state.offlineMode) Muted else Accent
        SessionConnectionHealth.PREPARING -> Color(0xFFD99020)
        SessionConnectionHealth.UNHEALTHY -> if (state.offlineMode) Muted else Color(0xFFC7372F)
    }
    val description = if (state.offlineMode) {
        "离线阅读模式，点击尝试重新连接"
    } else when (health) {
        SessionConnectionHealth.READY -> "会话连接健康，点击检查新内容"
        SessionConnectionHealth.PREPARING -> "正在连接会话以发送消息"
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
    val homeListState = rememberLazyListState()
    val context = LocalContext.current
    val deepSeekController = remember { DeepSeekChatController(context) }
    var deepSeekOpen by remember { mutableStateOf(false) }
    var deepSeekSelectedQuote by remember { mutableStateOf<String?>(null) }
    DisposableEffect(deepSeekController) {
        onDispose(deepSeekController::close)
    }
    MaterialTheme(colorScheme = ChatGptColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            when {
                deepSeekOpen -> DeepSeekChatScreen(
                    controller = deepSeekController,
                    selectedQuote = deepSeekSelectedQuote,
                    onQuoteConsumed = { deepSeekSelectedQuote = null },
                    onBack = { deepSeekOpen = false },
                )
                state.filePreview != null -> FilePreviewScreen(state.filePreview!!)
                state.selectedThread != null -> ChatScreen(state) { selectedText ->
                    deepSeekSelectedQuote = selectedText
                    deepSeekOpen = true
                }
                else -> ProjectTreeScreen(state, onBatterySettings, homeListState)
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
private fun ProjectTreeScreen(
    state: BridgeUiState,
    onBatterySettings: () -> Unit,
    listState: LazyListState,
) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            Column {
                TopAppBar(
                    title = { Column { Text("Codex", fontWeight = FontWeight.SemiBold, color = Ink); Text(state.connection, style = MaterialTheme.typography.labelSmall, color = if (state.relayConnected && state.macConnected) Accent else Muted) } },
                    actions = {
                        HeartbeatStatusIndicator(state, onClick = BridgeClient::refreshHome)
                    },
                )
                ManualSyncProgress(state)
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                if (state.offlineMode) {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, OutlineSoft),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("离线阅读模式", fontWeight = FontWeight.SemiBold)
                            Text("正在显示手机中已经保存的项目和会话，不会继续连接 Mac。", color = Muted, modifier = Modifier.padding(top = 6.dp))
                            OutlinedButton(onClick = BridgeClient::resumeOnline, modifier = Modifier.padding(top = 10.dp)) { Text("尝试重新连接") }
                        }
                    }
                } else if (!state.relayConnected || !state.macConnected || !state.appServerReady) {
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
                                IconButton(
                                    onClick = { BridgeClient.newThread(project) },
                                    enabled = !state.offlineMode,
                                ) { Icon(Icons.Default.Add, "在这个项目中新建会话", tint = if (state.offlineMode) Muted else Ink) }
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
private fun ChatScreen(state: BridgeUiState, onOpenDeepSeek: (String?) -> Unit) {
    val threadId = state.selectedThread?.id
    val context = LocalContext.current
    val draftStore = remember { ConversationDraftStore.forContext(context) }
    var input by remember(threadId) { mutableStateOf(draftStore.load(threadId.orEmpty())) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        BridgeClient.addAttachments(uris)
    }
    val listState = rememberLazyListState()
    val sendReadiness = conversationSendReadiness(state)
    val sessionReadyToSend = sendReadiness == ConversationSendReadiness.READY
    val composerHint = when (sendReadiness) {
        ConversationSendReadiness.READY -> "询问 Codex"
        ConversationSendReadiness.OFFLINE_MODE -> "离线草稿（联网后可发送）"
        ConversationSendReadiness.DELIVERY_UNCONFIRMED,
        ConversationSendReadiness.SEND_PENDING -> "正在确认上一条消息…"
        ConversationSendReadiness.TURN_RUNNING -> "等待当前回复完成…"
        else -> "会话准备完成后可发送"
    }
    val threadStatus = when {
        state.offlineMode -> "离线阅读 · 手机缓存"
        !state.relayConnected || !state.macConnected -> state.connection
        state.activeTurnId != null || state.selectedThread?.activity == ThreadActivity.RUNNING -> "回复生成中…"
        state.conversationContentState == ConversationContentState.LOADING_CACHE -> "正在读取本地记录…"
        state.conversationContentState == ConversationContentState.SYNCING -> "正在检查新内容…"
        state.conversationContentState == ConversationContentState.CACHED_CURRENT -> "正在核对本地记录…"
        state.preparingThreadId == threadId -> "正在准备会话…"
        sessionReadyToSend -> "会话已就绪"
        state.conversationContentState == ConversationContentState.LIVE -> "等待会话准备…"
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
    BackHandler(onBack = BridgeClient::backToThreads)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Column { Text(state.selectedThread?.title ?: "任务", maxLines = 1, fontWeight = FontWeight.SemiBold, color = Ink); Text(threadStatus, style = MaterialTheme.typography.labelSmall, color = if (sessionConnectionHealth(state.relayConnected, state.macConnected, state.appServerReady, threadId, state.preparingThreadId) == SessionConnectionHealth.READY) Accent else Muted) } },
                navigationIcon = { IconButton(onClick = BridgeClient::backToThreads) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(
                        onClick = { onOpenDeepSeek(null) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.semantics { contentDescription = "打开 DeepSeek 手机直连对话" },
                    ) {
                        Text("DeepSeek", style = MaterialTheme.typography.labelSmall, color = Accent)
                    }
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
                            val canAttach = !state.offlineMode && state.activeTurnId == null && !state.sendPending && state.composerAttachments.size < 10
                            IconButton(
                                onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                                enabled = canAttach,
                                modifier = Modifier.padding(bottom = 5.dp),
                            ) { Icon(Icons.Default.AttachFile, "添加图片或文件", tint = if (canAttach) Ink else Muted) }
                            OutlinedTextField(
                                value = input,
                                onValueChange = {
                                    input = it
                                    draftStore.save(threadId.orEmpty(), it)
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        composerHint,
                                        color = Color(0xFF9A9A9A),
                                    )
                                },
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
                                sessionReadyToSend &&
                                state.composerAttachments.none { it.status != AttachmentUploadStatus.READY }
                            IconButton(
                                onClick = {
                                    BridgeClient.send(input) {
                                        input = ""
                                        draftStore.clear(threadId.orEmpty())
                                    }
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .padding(start = 4.dp, bottom = 5.dp)
                                    .size(42.dp)
                                    .background(if (canSend) Color.White else Color(0xFF2F2F2F), CircleShape),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    "发送",
                                    tint = if (canSend) Color.Black else Color(0xFF777777),
                                )
                            }
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
            items(state.messages, key = { it.id }) { MessageBubble(it, onOpenDeepSeek) }
            if (state.streamingText.isNotBlank()) item { MessageBubble(ChatMessage("streaming", "assistant", state.streamingText), onOpenDeepSeek) }
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
private fun MessageBubble(message: ChatMessage, onOpenDeepSeek: (String?) -> Unit) {
    val mine = message.role == "user"
    val tool = message.role == "tool"
    val uriHandler = LocalUriHandler.current
    val view = LocalView.current
    val deepSeekToolbar = remember(view) { DeepSeekTextToolbar(view, onOpenDeepSeek) }
    deepSeekToolbar.onAskSelectedText = onOpenDeepSeek
    DisposableEffect(deepSeekToolbar) {
        onDispose(deepSeekToolbar::hide)
    }
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
                CompositionLocalProvider(LocalTextToolbar provides deepSeekToolbar) {
                    SelectionContainer(
                        modifier = Modifier
                            .appendTextContextMenuComponents {
                                item(DeepSeekContextMenuKey, "问 DeepSeek") {
                                    askDeepSeekFromFocusedSelection(view, onOpenDeepSeek)
                                    close()
                                }
                            }
                            .filterTextContextMenuComponents { component ->
                                component.key === DeepSeekContextMenuKey ||
                                    component.key === TextContextMenuKeys.CopyKey ||
                                    component.key === TextContextMenuKeys.SelectAllKey
                            },
                    ) {
                        MarkdownText(
                            text = message.text,
                            color = Ink,
                            codeSurface = Color(0xFF1A1A1A),
                            quoteSurface = Color(0xFF121212),
                            onLink = { target ->
                                if (target.startsWith("https://") || target.startsWith("http://")) uriHandler.openUri(target)
                                else BridgeClient.downloadFile(target)
                            },
                        )
                    }
                }
                if (!tool && message.detail.isNotBlank()) Text(message.detail, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
