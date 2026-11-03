package com.aidigua.musiccast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aidigua.musiccast.MainActivity
import com.aidigua.musiccast.R

/**
 * 前台保活服务。
 *
 * 为什么需要：投送期间 App 一旦被系统冻结（后台限制 / Doze / 厂商省电策略），
 * NanoHTTPD 服务器就停供，电视立刻断流。前台服务是 Android 官方允许的
 * "我正在干活，别杀我"机制。
 *
 * 具体做了三件事：
 *  1. `startForeground` + `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`，把优先级提到前台；
 *  2. 持一个 **PARTIAL_WAKE_LOCK**，防止 CPU 在投送过程中休眠；
 *  3. 通知里放"停止投送"按钮，用户可一键收工（避免常驻。
 *
 * 生命周期跟随音乐投送：开始投送时 start，停止投送 / 退出时 stop。
 * 注意：minSdk 24，所以 SDK_INT >= 29 才需要指定 foregroundServiceType。
 */
class CastKeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        const val CHANNEL_ID = "musiccast_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aidigua.musiccast.START_KEEPALIVE"
        const val ACTION_STOP = "com.aidigua.musiccast.STOP_KEEPALIVE"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DEVICE = "extra_device"

        fun start(context: Context, title: String, device: String) {
            val i = Intent(context, CastKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DEVICE, device)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure { Log.w(TAG, "启动保活服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CastKeepAliveService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }.onFailure { Log.w(TAG, "停止保活服务失败", it) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "正在投送"
        val device = intent?.getStringExtra(EXTRA_DEVICE) ?: "DLNA 设备"
        val notification = buildNotification(title, device)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        // START_STICKY：被系统杀掉后尽量重启（Android 12+ 受后台限制会打折，但无害）
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicCast:cast").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 最长 6 小时，避免忘记释放
        }
    }

    private fun buildNotification(title: String, device: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CastKeepAliveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText("正在投送到 $device · 本地服务器保持运行")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "停止投送", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "投送状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "投送期间的常驻状态，保证本地媒体服务器不被回收"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun shutdown() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }
}
