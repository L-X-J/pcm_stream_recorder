package com.yuanqu.pcm_stream_recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Android 播放音频捕获的源端策略保护器。
 *
 * `AudioPlaybackCaptureConfiguration` 只负责声明捕获端要匹配哪些 usage/uid；
 * 源端是否真的把 PCM 混入 remote-submix，还受当前 UID 的
 * `AudioManager.setAllowedCapturePolicy()` 约束。部分音频库或系统默认值可能会把
 * 进程策略停在 `ALLOW_CAPTURE_BY_SYSTEM`，此时 MediaProjection 能启动、AudioRecord
 * 也能读帧，但第三方 App 只能读到全 0 PCM。
 */
internal object PlaybackAudioCapturePolicy {
    private const val TAG = "PlaybackAudioCapture"

    /**
     * 确保当前 UID 允许被普通 MediaProjection 捕获。
     *
     * @param context 用于获取 AudioManager 的上下文。
     * @param enableLog 是否输出诊断日志。
     * @return 策略设置前后的快照；系统版本过低或 AudioManager 不可用时返回对应说明。
     */
    fun ensureAllowCaptureByAll(context: Context, enableLog: Boolean): Snapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Snapshot(
                supported = false,
                before = UNKNOWN_POLICY,
                after = UNKNOWN_POLICY,
                message = "Android 10 以下不支持播放音频捕获策略",
            )
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return Snapshot(
                supported = false,
                before = UNKNOWN_POLICY,
                after = UNKNOWN_POLICY,
                message = "AudioManager 不可用",
            )

        return try {
            val before = audioManager.allowedCapturePolicy
            if (before != AudioAttributes.ALLOW_CAPTURE_BY_ALL) {
                audioManager.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            }
            val after = audioManager.allowedCapturePolicy
            val snapshot = Snapshot(
                supported = true,
                before = before,
                after = after,
                message = "playback capture policy before=$before after=$after",
            )
            if (enableLog) {
                Log.d(TAG, snapshot.message)
            }
            snapshot
        } catch (error: Exception) {
            Log.w(TAG, "设置播放音频捕获策略失败", error)
            Snapshot(
                supported = false,
                before = UNKNOWN_POLICY,
                after = UNKNOWN_POLICY,
                message = "设置播放音频捕获策略失败: ${error.message}",
            )
        }
    }

    /** UID 捕获策略快照。 */
    data class Snapshot(
        val supported: Boolean,
        val before: Int,
        val after: Int,
        val message: String,
    )

    private const val UNKNOWN_POLICY = -1
}
