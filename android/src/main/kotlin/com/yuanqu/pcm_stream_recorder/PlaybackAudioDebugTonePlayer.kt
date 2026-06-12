package com.yuanqu.pcm_stream_recorder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * 播放音频捕获链路的受控诊断音源。
 *
 * 该类只在调用方显式传入调试参数时启动，用一个本 App 内部的 `AudioTrack`
 * 持续播放 440Hz 正弦波。音轨使用 `USAGE_MEDIA` 和 `ALLOW_CAPTURE_BY_ALL`，
 * 正好满足 Android 官方 AudioPlaybackCapture 的源端条件。这样可以把问题拆成：
 *
 * 1. 如果这个 tone 能被捕获，说明 MediaProjection/AudioRecord 链路正常；
 * 2. 如果 WebView 仍是 0，问题集中在 WebView 或网页播放器的捕获政策/DRM/usage；
 * 3. 如果这个 tone 也捕不到，才继续查系统授权、前台服务、ROM 策略或捕获端配置。
 */
internal class PlaybackAudioDebugTonePlayer {
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    /**
     * 开始播放诊断音。
     *
     * @param scope 调用方生命周期内的协程作用域。停止捕获或插件销毁时必须调用
     * [stop] 释放 AudioTrack。
     */
    fun start(scope: CoroutineScope) {
        if (audioTrack != null || playbackJob != null) {
            return
        }
        val track = createAudioTrack()
        audioTrack = track
        playbackJob = scope.launch(Dispatchers.IO) {
            playTone(track)
        }
    }

    /**
     * 停止诊断音并释放底层 AudioTrack。
     *
     * 该方法可重复调用；捕获启动失败、stop 或 dispose 都可以安全进入。
     */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        val track = audioTrack
        audioTrack = null
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        try {
            track?.flush()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
    }

    /**
     * 创建符合 AudioPlaybackCapture 源端要求的媒体音轨。
     *
     * 这里显式设置 allowed capture policy，避免宿主 App 或 ROM 默认策略影响诊断结论。
     */
    private fun createAudioTrack(): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(attributesBuilder.build())
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 在后台循环写入正弦波 PCM16。
     *
     * 选择较高音量但不满幅，避免削波影响 RMS 诊断。写入阻塞由 AudioTrack 控制，
     * 协程取消后会在下一次循环退出。
     */
    private suspend fun playTone(track: AudioTrack) {
        val buffer = ShortArray(FRAME_SAMPLES)
        var phase = 0.0
        val phaseStep = 2.0 * PI * TONE_FREQUENCY_HZ / SAMPLE_RATE

        track.play()
        while (currentCoroutineContext().isActive) {
            for (index in buffer.indices) {
                buffer[index] = (sin(phase) * Short.MAX_VALUE * AMPLITUDE).toInt().toShort()
                phase += phaseStep
                if (phase >= 2.0 * PI) {
                    phase -= 2.0 * PI
                }
            }
            track.write(buffer, 0, buffer.size)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val BYTES_PER_SAMPLE = 2
        const val TONE_FREQUENCY_HZ = 440.0
        const val AMPLITUDE = 0.45
    }
}
