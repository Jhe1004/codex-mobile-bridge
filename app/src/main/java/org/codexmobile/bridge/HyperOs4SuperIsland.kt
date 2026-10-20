package org.codexmobile.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val SUPER_ISLAND_MAX_DURATION_MS = 12L * 60L * 60L * 1_000L
private const val SUPER_ISLAND_NOTIFICATION_ID = 2040
private const val SUPER_ISLAND_CHANNEL_ID = "codex_active_session"
private const val SUPER_ISLAND_PIC_KEY = "miui.focus.pic_codex"
private const val LOG_TAG = "CodexSuperIsland"

internal data class SuperIslandCapability(
    val manufacturer: String,
    val hyperOsVersion: String,
    val islandSupported: Boolean,
    val focusProtocol: Int,
) {
    val eligible: Boolean
        get() = isXiaomiManufacturer(manufacturer) &&
            hyperOsVersion.startsWith("OS4", ignoreCase = true) &&
            islandSupported &&
            focusProtocol >= 3
}

internal enum class SuperIslandPhase {
    READY,
    PREPARING,
    WORKING,
    COMPLETED,
    APPROVAL,
    TRANSFERRING,
    DISCONNECTED,
    OFFLINE,
}

internal data class SuperIslandPresentation(
    val threadId: String,
    val threadTitle: String,
    val phase: SuperIslandPhase,
    val status: String,
    val detail: String,
    val color: String,
)

internal fun isXiaomiManufacturer(manufacturer: String): Boolean {
    val normalized = manufacturer.trim().lowercase()
    return normalized == "xiaomi" || normalized == "redmi" || normalized == "poco"
}

internal fun superIslandPresentation(
    state: BridgeUiState,
    nowMs: Long = System.currentTimeMillis(),
): SuperIslandPresentation? {
    val thread = state.selectedThread ?: return null
    val transfer = state.fileTransfer
    val completedRecently = state.lastTurnCompletedAtMs?.let { nowMs - it in 0..120_000L } == true

    val phase: SuperIslandPhase
    val status: String
    val detail: String
    val color: String
    when {
        state.offlineMode -> {
            phase = SuperIslandPhase.OFFLINE
            status = "离线阅读"
            detail = "正在显示手机缓存"
            color = "#6B6B6B"
        }
        !state.relayConnected -> {
            phase = SuperIslandPhase.DISCONNECTED
            status = "手机连接中断"
            detail = "点击打开应用并重新连接"
            color = "#D92D20"
        }
        !state.macConnected -> {
            phase = SuperIslandPhase.DISCONNECTED
            status = "Mac 离线"
            detail = "等待 Mac 恢复连接"
            color = "#D92D20"
        }
        !state.appServerReady -> {
            phase = SuperIslandPhase.DISCONNECTED
            status = "Codex 未就绪"
            detail = "正在等待桌面端服务"
            color = "#F59E0B"
        }
        state.approval != null -> {
            phase = SuperIslandPhase.APPROVAL
            status = "等待你的确认"
            detail = state.approval.description.take(42).ifBlank { "Codex 请求执行一项操作" }
            color = "#F59E0B"
        }
        transfer != null -> {
            val percent = if (transfer.totalBytes > 0L) {
                ((transfer.receivedBytes * 100L) / transfer.totalBytes).coerceIn(0L, 100L)
            } else {
                0L
            }
            phase = SuperIslandPhase.TRANSFERRING
            status = "正在传输文件"
            detail = "${transfer.name.take(24)} · $percent%"
            color = "#10A37F"
        }
        state.sendPending -> {
            phase = SuperIslandPhase.WORKING
            status = "正在发送"
            detail = "消息正在交给 Mac 上的 Codex"
            color = "#10A37F"
        }
        state.activeTurnId != null || state.streamingText.isNotBlank() -> {
            phase = SuperIslandPhase.WORKING
            status = "Codex 正在工作"
            detail = "回答生成中"
            color = "#10A37F"
        }
        completedRecently -> {
            phase = SuperIslandPhase.COMPLETED
            status = "任务已完成"
            detail = "点击查看最新结果"
            color = "#10A37F"
        }
        state.preparingThreadId == thread.id -> {
            phase = SuperIslandPhase.PREPARING
            status = "正在连接会话"
            detail = "正在为发送消息建立连接"
            color = "#F59E0B"
        }
        else -> {
            phase = SuperIslandPhase.READY
            status = "会话待命"
            detail = "手机与 Mac 连接正常"
            color = "#10A37F"
        }
    }

    return SuperIslandPresentation(
        threadId = thread.id,
        threadTitle = thread.title.ifBlank { "Codex 会话" }.take(36),
        phase = phase,
        status = status,
        detail = detail,
        color = color,
    )
}

internal class HyperOs4SuperIslandController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var collectionJob: Job? = null
    private var expiryJob: Job? = null
    private var activeThreadId: String? = null
    private var activeSinceMs: Long = 0L
    private var expiredThreadId: String? = null
    private var permissionCheckedAtMs: Long = 0L
    private var cachedFocusPermission = false

    fun start() {
        if (collectionJob != null) return
        val capability = readCapability(context)
        Log.i(
            LOG_TAG,
            "capability manufacturer=${capability.manufacturer}, os=${capability.hyperOsVersion}, " +
                "island=${capability.islandSupported}, protocol=${capability.focusProtocol}, eligible=${capability.eligible}",
        )
        if (!capability.eligible) return
        if (BuildConfig.XIAOMI_SUPER_ISLAND_APP_ID.isBlank()) {
            Log.i(LOG_TAG, "disabled: Xiaomi Super Island App ID is not configured")
            return
        }

        createChannel()
        collectionJob = scope.launch {
            BridgeClient.state
                .map(::superIslandPresentation)
                .distinctUntilChanged()
                .collect { presentation -> render(presentation) }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        expiryJob?.cancel()
        expiryJob = null
        notificationManager.cancel(SUPER_ISLAND_NOTIFICATION_ID)
        activeThreadId = null
        activeSinceMs = 0L
        expiredThreadId = null
    }

    private suspend fun render(presentation: SuperIslandPresentation?) {
        if (presentation == null) {
            notificationManager.cancel(SUPER_ISLAND_NOTIFICATION_ID)
            activeThreadId = null
            activeSinceMs = 0L
            expiredThreadId = null
            expiryJob?.cancel()
            expiryJob = null
            return
        }

        val now = System.currentTimeMillis()
        if (activeThreadId != presentation.threadId) {
            activeThreadId = presentation.threadId
            activeSinceMs = now
            expiredThreadId = null
            permissionCheckedAtMs = 0L
            expiryJob?.cancel()
            expiryJob = scope.launch {
                delay(SUPER_ISLAND_MAX_DURATION_MS)
                if (activeThreadId == presentation.threadId) {
                    expiredThreadId = presentation.threadId
                    notificationManager.cancel(SUPER_ISLAND_NOTIFICATION_ID)
                    Log.i(LOG_TAG, "ended: the active-session limit was reached")
                }
            }
        }
        if (expiredThreadId == presentation.threadId || now - activeSinceMs >= SUPER_ISLAND_MAX_DURATION_MS) {
            expiredThreadId = presentation.threadId
            notificationManager.cancel(SUPER_ISLAND_NOTIFICATION_ID)
            Log.i(LOG_TAG, "ended: the active-session limit was reached")
            return
        }

        if (!focusPermission(now)) {
            notificationManager.cancel(SUPER_ISLAND_NOTIFICATION_ID)
            Log.i(LOG_TAG, "disabled: focus notification permission is unavailable")
            return
        }

        notificationManager.notify(SUPER_ISLAND_NOTIFICATION_ID, buildNotification(presentation))
    }

    private suspend fun focusPermission(nowMs: Long): Boolean {
        if (nowMs - permissionCheckedAtMs < 60_000L) return cachedFocusPermission
        cachedFocusPermission = withContext(Dispatchers.IO) { hasFocusPermission(context) }
        permissionCheckedAtMs = nowMs
        return cachedFocusPermission
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                SUPER_ISLAND_CHANNEL_ID,
                "Codex 活跃会话",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "在小米澎湃 OS 4 超级岛显示当前 Codex 会话状态"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun buildNotification(presentation: SuperIslandPresentation): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            SUPER_ISLAND_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val extras = Bundle().apply {
            putBundle(
                "miui.focus.pics",
                Bundle().apply {
                    putParcelable(
                        SUPER_ISLAND_PIC_KEY,
                        Icon.createWithResource(context, R.drawable.ic_stat_codex),
                    )
                },
            )
        }
        val notification = NotificationCompat.Builder(context, SUPER_ISLAND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_codex)
            .setContentTitle(presentation.status)
            .setContentText("${presentation.threadTitle} · ${presentation.detail}")
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(SUPER_ISLAND_MAX_DURATION_MS)
            .setContentIntent(openApp)
            .addExtras(extras)
            .build()
        notification.extras.putString("miui.focus.param", islandJson(presentation).toString())
        return notification
    }

    private fun islandJson(presentation: SuperIslandPresentation): JSONObject {
        val picInfo = JSONObject()
            .put("type", 1)
            .put("pic", SUPER_ISLAND_PIC_KEY)
        val textInfo = JSONObject()
            .put("frontTitle", presentation.threadTitle)
            .put("title", presentation.status)
            .put("content", presentation.detail)
            .put(
                "useHighLight",
                presentation.phase != SuperIslandPhase.DISCONNECTED && presentation.phase != SuperIslandPhase.OFFLINE,
            )
        val imageTextInfo = JSONObject()
            .put("type", 1)
            .put("picInfo", picInfo)
            // Xiaomi's official template uses this exact compatibility key.
            .put("miui.focus.paramtextInfo", textInfo)
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandOrder", true)
            .put("islandTimeout", SUPER_ISLAND_MAX_DURATION_MS / 1_000L)
            .put("dismissIsland", false)
            .put("highlightColor", presentation.color)
            .put(
                "bigIslandArea",
                JSONObject()
                    .put("imageTextInfoLeft", imageTextInfo)
                    .put("picInfo", picInfo),
            )
            .put("smallIslandArea", JSONObject().put("picInfo", picInfo))
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", "codex_active_session")
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("timeout", 720)
            .put("updatable", true)
            .put("reopen", "reopen")
            .put("filterWhenNoPermission", true)
            .put("ticker", presentation.status)
            .put("tickerPic", SUPER_ISLAND_PIC_KEY)
            .put("aodTitle", presentation.status)
            .put("aodPic", SUPER_ISLAND_PIC_KEY)
            .put("param_island", island)
            .put(
                "baseInfo",
                JSONObject()
                    .put("title", presentation.status)
                    .put("content", "${presentation.threadTitle} · ${presentation.detail}")
                    .put("colorTitle", presentation.color)
                    .put("type", 2),
            )
        return JSONObject().put("param_v2", paramV2)
    }

    companion object {
        internal fun readCapability(context: Context): SuperIslandCapability {
            val hyperOsVersion = readSystemProperty("ro.mi.os.version.name")
                .ifBlank { Build.VERSION.INCREMENTAL.orEmpty() }
            val islandSupported = readBooleanSystemProperty("persist.sys.feature.island", false)
            val focusProtocol = Settings.System.getInt(
                context.contentResolver,
                "notification_focus_protocol",
                0,
            )
            return SuperIslandCapability(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                hyperOsVersion = hyperOsVersion,
                islandSupported = islandSupported,
                focusProtocol = focusProtocol,
            )
        }

        private fun hasFocusPermission(context: Context): Boolean = try {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(
                Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus",
                null,
                extras,
            )?.getBoolean("canShowFocus", false) == true
        } catch (error: Exception) {
            Log.w(LOG_TAG, "focus permission query failed", error)
            false
        }

        private fun readSystemProperty(key: String): String = try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        private fun readBooleanSystemProperty(key: String, defaultValue: Boolean): Boolean = try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod(
                "getBoolean",
                String::class.java,
                java.lang.Boolean.TYPE,
            )
            method.invoke(null, key, defaultValue) as? Boolean ?: defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }
}
