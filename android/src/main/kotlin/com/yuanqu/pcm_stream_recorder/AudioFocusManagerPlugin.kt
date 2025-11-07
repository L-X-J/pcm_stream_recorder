package com.yuanqu.pcm_stream_recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** 独立的音频焦点管理插件
 * 用于管理音频焦点，特别是在从 WebRTC 等通信场景返回后恢复音频播放
 */
class AudioFocusManagerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private var context: Context? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "audio_focus_manager")
        methodChannel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "resetAudioFocus" -> {
                resetAudioFocus(result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    /// 重置音频焦点
    /// 用于在从其他页面（如 WebRTC 聊天页面）返回后，立即恢复音频播放的正常状态
    /// 将音频焦点重置为适合媒体播放的模式
    private fun resetAudioFocus(result: Result) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            result.error(
                "AUDIO_MANAGER_NULL",
                "无法获取 AudioManager",
                null
            )
            return
        }

        try {
            // 1. 清除通信设备（API 34+）- 优先执行，确保立即清除
            // 这对于从 WebRTC 等通信场景返回后恢复音频路由非常重要
            // 参考：https://developer.android.com/reference/android/media/AudioManager#clearCommunicationDevice()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    audioManager.clearCommunicationDevice()
                    android.util.Log.d("AudioFocusManager", "✅ 已清除通信设备")
                } catch (e: Exception) {
                    android.util.Log.w("AudioFocusManager", "清除通信设备失败: ${e.message}")
                }
            }

            // 2. 立即设置音频模式为正常模式，适合媒体播放
            // 这个操作会立即生效，不需要等待
            audioManager.mode = AudioManager.MODE_NORMAL
            android.util.Log.d("AudioFocusManager", "✅ 音频模式已设置为 MODE_NORMAL")

            // 3. 不强制设置扬声器，让系统根据连接的设备自动选择
            // 如果检测到耳机，系统会自动路由到耳机
            audioManager.isSpeakerphoneOn = false

            // 4. 释放当前的音频焦点（如果有的话）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 跳过释放步骤，直接重新请求焦点
                // abandonAudioFocusRequest 需要非空参数，我们直接重新请求即可
            } else {
                // API 24-25 使用旧方法
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }

            // 5. 立即重新请求音频焦点，使用适合媒体播放的配置
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用 AudioFocusRequest
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .build()

                audioManager.requestAudioFocus(audioFocusRequest)
            } else {
                // API 24-25 使用旧方法
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android.util.Log.d("AudioFocusManager", "✅ 已重置音频焦点为媒体播放模式")
                result.success(true)
            } else {
                android.util.Log.w("AudioFocusManager", "⚠️ 音频焦点请求被拒绝，但模式已设置")
                // 即使焦点请求被拒绝，也返回成功，因为我们已经设置了正确的模式
                // 模式设置会立即生效
                result.success(true)
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioFocusManager", "❌ 重置音频焦点失败: ${e.message}", e)
            result.error(
                "RESET_AUDIO_FOCUS_FAILED",
                "重置音频焦点失败: ${e.message}",
                e.toString()
            )
        }
    }
}

