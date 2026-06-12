package com.yuanqu.pcm_stream_recorder

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
import android.util.Log

/**
 * 播放音频捕获专用前台服务。
 *
 * Android 10+ 的 MediaProjection 在创建投屏令牌前要求进程已经运行
 * `mediaProjection` 类型的前台服务；Android 14+ 还会校验对应的前台服务权限。
 * 该服务只负责满足系统生命周期约束，不采集音频、不持有 Flutter 通道，也不暴露
 * MediaProjection 数据，实际功率计算仍由 [PlaybackAudioCapturePlugin] 完成。
 */
class PlaybackAudioCaptureForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            startMediaProjectionForeground()
            Controller.markRunning(true)
            START_STICKY
        } catch (error: Exception) {
            Log.e(TAG, "启动播放音频捕获前台服务失败", error)
            Controller.markRunning(false)
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        Controller.markRunning(false)
        super.onDestroy()
    }

    /**
     * 以 mediaProjection 类型进入前台状态。
     *
     * 这里必须在插件调用 `MediaProjectionManager.getMediaProjection()` 之前完成。
     * 如果只启动普通 Service 或未传入 foreground service type，Android 会抛出
     * `SecurityException` 并拒绝创建 MediaProjection。
     */
    private fun startMediaProjectionForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 创建低打扰通知渠道，避免播放音频功率采样产生声音或震动。 */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 构建前台服务通知，点击后回到宿主 App。 */
    private fun buildNotification(): Notification {
        val appLabel = packageManager.getApplicationLabel(applicationInfo).toString()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_media_play

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(icon)
            .setContentTitle(appLabel)
            .setContentText(NOTIFICATION_TEXT)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private companion object {
        const val TAG = "PlaybackAudioCaptureFg"
        const val CHANNEL_ID = "playback_audio_capture"
        const val CHANNEL_NAME = "播放音频捕获"
        const val CHANNEL_DESCRIPTION = "用于保持播放音频捕获的系统前台服务"
        const val NOTIFICATION_ID = 54830
        const val NOTIFICATION_TEXT = "正在读取播放声音强度"
    }

    /**
     * 外部控制入口。
     *
     * 插件通过这些方法启动/停止服务，并轮询 [isRunning] 确认系统已经接受
     * `mediaProjection` 前台服务类型，随后才创建 MediaProjection。
     */
    object Controller {
        @Volatile
        private var running: Boolean = false

        /** 启动播放音频捕获前台服务。 */
        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, PlaybackAudioCaptureForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        /** 停止播放音频捕获前台服务。 */
        fun stop(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, PlaybackAudioCaptureForegroundService::class.java)
            appContext.stopService(intent)
            markRunning(false)
        }

        /** 当前服务是否已经完成 startForeground。 */
        fun isRunning(): Boolean = running

        /** 记录服务是否已经完成 startForeground，用于插件规避启动竞态。 */
        internal fun markRunning(value: Boolean) {
            running = value
        }
    }
}
