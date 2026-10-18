package org.codexmobile.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class BridgeService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodexMobile:BridgeConnection")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        val channelId = "codex_bridge_connection"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Codex 手机连接", NotificationManager.IMPORTANCE_LOW))
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Codex Mobile 正在连接")
            .setContentText("保持与 Mac Codex 的实时通道")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(2001, notification)
        BridgeClient.start(this)
    }

    override fun onDestroy() {
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
}
