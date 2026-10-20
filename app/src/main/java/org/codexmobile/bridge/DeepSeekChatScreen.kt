package org.codexmobile.bridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeepSeekChatScreen(
    controller: DeepSeekChatController,
    selectedQuote: String?,
    onQuoteConsumed: () -> Unit,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()
    var showKeyDialog by remember { mutableStateOf(!state.hasApiKey) }
    var confirmClear by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(selectedQuote) {
        selectedQuote?.takeIf(String::isNotBlank)?.let { quote ->
            controller.updateDraft(deepSeekQuoteDraft(quote))
            onQuoteConsumed()
        }
    }
    val lastItem = state.messages.size + if (state.streamingText.isNotBlank() || state.thinking) 1 else 0
    LaunchedEffect(lastItem, state.streamingText) {
        if (lastItem > 0) listState.scrollToItem(lastItem)
    }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(DEEPSEEK_MODEL_LABEL, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !state.hasApiKey -> "需要设置 API 密钥"
                                state.attachedContext != null -> "已附带 Codex 上下文 · 手机直连"
                                else -> "手机直连 · 不依赖 Mac"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.hasApiKey) Color(0xFF10A37F) else Color(0xFFA3A3A3),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回 Codex 会话")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteOutline, "新建 DeepSeek 对话")
                    }
                    IconButton(onClick = { showKeyDialog = true }) {
                        Icon(Icons.Default.Settings, "设置 DeepSeek API 密钥")
                    }
                    if (state.sending) {
                        IconButton(onClick = controller::cancel) {
                            Icon(Icons.Default.Close, "停止 DeepSeek 回复")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                state.attachedContext?.let { context ->
                    Surface(
                        color = Color(0xFF151515),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "已附带 ${context.messageCount} 条 Codex 上下文",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFF2F2F2),
                                )
                                Text(
                                    "${context.projectName} · ${context.threadTitle}（后台发送，不占输入框）",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF8E8E8E),
                                )
                            }
                            IconButton(onClick = controller::detachContext, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Default.Close, "不再附带 Codex 上下文", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Surface(
                    color = Color.Black,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color(0xFF333333)),
                    shadowElevation = 5.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = state.draft,
                            onValueChange = controller::updateDraft,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (state.hasApiKey) "向 DeepSeek 提问" else "先在右上角设置 API 密钥",
                                    color = Color(0xFF9A9A9A),
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 8,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                        val canSend = state.hasApiKey && state.draft.isNotBlank() && !state.sending
                        IconButton(
                            onClick = { controller.send(state.draft) },
                            enabled = canSend,
                            modifier = Modifier
                                .padding(start = 4.dp, bottom = 5.dp)
                                .size(42.dp)
                                .background(if (canSend) Color.White else Color(0xFF2F2F2F), CircleShape),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                "发送给 DeepSeek",
                                tint = if (canSend) Color.Black else Color(0xFF777777),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 72.dp, start = 18.dp, end = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("DeepSeek", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "由手机直接连接 DeepSeek。即使 Mac 已关机，仍然可以独立对话。",
                            color = Color(0xFFA3A3A3),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
            items(state.messages, key = DeepSeekMessage::id) { message ->
                DeepSeekMessageBubble(message) { target -> uriHandler.openUri(target) }
            }
            if (state.thinking && state.streamingText.isBlank()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF10A37F))
                        Text("DeepSeek 正在思考…", color = Color(0xFFA3A3A3), modifier = Modifier.padding(start = 9.dp))
                    }
                }
            } else if (state.streamingText.isNotBlank()) {
                item {
                    DeepSeekMessageBubble(
                        DeepSeekMessage("streaming", "assistant", state.streamingText),
                    ) { target -> uriHandler.openUri(target) }
                }
            }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showKeyDialog) {
        DeepSeekApiKeyDialog(
            hasExistingKey = state.hasApiKey,
            preferredName = state.preferredName,
            onDismiss = { showKeyDialog = false },
            onSave = { key, preferredName ->
                if (controller.saveSettings(key, preferredName)) showKeyDialog = false
            },
            onDelete = if (state.hasApiKey) {
                {
                    controller.deleteApiKey()
                    showKeyDialog = false
                }
            } else null,
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("新建 DeepSeek 对话？") },
            text = { Text("手机中当前的 DeepSeek 对话记录将被清空，API 密钥会保留。") },
            confirmButton = {
                Button(onClick = {
                    controller.clearConversation()
                    confirmClear = false
                }) { Text("清空并新建") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DeepSeekMessageBubble(message: DeepSeekMessage, onLink: (String) -> Unit) {
    val mine = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (mine) 0.84f else 1f)
                .background(if (mine) Color(0xFF202020) else Color.Transparent, RoundedCornerShape(20.dp))
                .padding(if (mine) 14.dp else 8.dp),
        ) {
            SelectionContainer {
                MarkdownText(
                    text = message.text,
                    color = Color(0xFFF5F5F5),
                    codeSurface = Color(0xFF1A1A1A),
                    quoteSurface = Color(0xFF121212),
                    onLink = onLink,
                )
            }
        }
    }
}

@Composable
private fun DeepSeekApiKeyDialog(
    hasExistingKey: Boolean,
    preferredName: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var key by remember { mutableStateOf("") }
    var name by remember(preferredName) { mutableStateOf(preferredName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek API 密钥") },
        text = {
            Column {
                Text(
                    if (hasExistingKey) "已在 Android 系统安全区保存密钥。输入新密钥可替换。"
                    else "密钥只会加密保存在这台手机中，不会发给 Mac 或写入 GitHub。",
                    color = Color(0xFFA3A3A3),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("粘贴 API 密钥") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("称呼（可选）") },
                    supportingText = { Text("通常直接回答；确实需要称呼时使用这里的名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(key, name) },
                enabled = hasExistingKey || key.isNotBlank(),
            ) { Text("安全保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete) { Text("删除密钥") }
                    Spacer(Modifier.size(6.dp))
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

internal fun deepSeekQuoteDraft(selectedText: String): String {
    val quoted = selectedText.trim().lines().joinToString("\n") { "> $it" }
    return "关于下面这段内容，我想继续提问：\n\n$quoted\n\n我的问题是："
}
