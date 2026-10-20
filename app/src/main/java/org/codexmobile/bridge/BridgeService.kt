package org.codexmobile.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BridgeService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var superIslandController: HyperOs4SuperIslandController? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodexMobile:BridgeConnection")
            .apply { setReferenceCounted(false) }
        updateWakeLock(offline = false)
        val channelId = "codex_bridge_connection"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Codex 手机连接", NotificationManager.IMPORTANCE_LOW))
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_codex)
            .setContentTitle("Codex Mobile 正在连接")
            .setContentText("保持与 Mac Codex 的实时通道")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(2001, notification)
        BridgeClient.start(this)
        lifecycleScope.launch {
            BridgeClient.state.collectLatest { state ->
                updateWakeLock(state.offlineMode)
                manager.notify(
                    2001,
                    NotificationCompat.Builder(this@BridgeService, channelId)
                        .setSmallIcon(R.drawable.ic_stat_codex)
                        .setContentTitle(if (state.offlineMode) "Codex Mobile 离线阅读" else "Codex Mobile 正在连接")
                        .setContentText(if (state.offlineMode) "只显示手机缓存，点击 App 可重新连接" else "保持与 Mac Codex 的实时通道")
                        .setOngoing(true)
                        .setContentIntent(pendingIntent)
                        .build(),
                )
            }
        }
        superIslandController = HyperOs4SuperIslandController(this, lifecycleScope).also { it.start() }
    }

    override fun onDestroy() {
        superIslandController?.stop()
        superIslandController = null
        BridgeClient.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        BridgeClient.ensureConnected(this)
        return START_STICKY
    }

    private fun updateWakeLock(offline: Boolean) {
        val lock = wakeLock ?: return
        if (offline) {
            if (lock.isHeld) lock.release()
        } else if (!lock.isHeld) {
            lock.acquire()
        }
    }
}
