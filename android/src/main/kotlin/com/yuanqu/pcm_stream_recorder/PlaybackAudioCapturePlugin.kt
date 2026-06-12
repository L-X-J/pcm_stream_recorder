package com.yuanqu.pcm_stream_recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 当前播放音频捕获插件。
 *
 * 该类独立注册播放音频 MethodChannel/EventChannel，避免和麦克风 PCM 录音共享
 * EventSink、AudioRecord 或音频会话状态。Android 侧通过 MediaProjection 授权
 * 创建 AudioPlaybackCaptureConfiguration，只读取音频，不创建 VirtualDisplay。
 */
class PlaybackAudioCapturePlugin(
    private val context: Context,
    messenger: io.flutter.plugin.common.BinaryMessenger
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {

    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var eventSink: EventChannel.EventSink? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingConfig: CaptureConfig? = null
    private var pendingStartToken: Int = 0
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var isCapturing = false
    private var enableLog = false
    private var startToken: Int = 0
    private val debugTonePlayer = PlaybackAudioDebugTonePlayer()

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    /** 释放通道、协程和正在运行的捕获资源。 */
    fun dispose() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        activityBinding?.removeActivityResultListener(this)
        stopInternal(sendState = false)
        scope.cancel()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isSupported" -> result.success(isSupported())
            "startPowerCapture" -> startPowerCapture(call, result)
            "stop" -> result.success(stopInternal(sendState = true))
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            return false
        }

        val result = pendingResult
        val config = pendingConfig
        val token = pendingStartToken
        pendingResult = null
        pendingConfig = null
        pendingStartToken = 0

        if (result == null || config == null) {
            return true
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            emitState(config, false, "用户拒绝播放音频捕获授权")
            result.error("PERMISSION_DENIED", "用户拒绝播放音频捕获授权", null)
            return true
        }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            result.error("MEDIA_PROJECTION_UNAVAILABLE", "无法获取 MediaProjectionManager", null)
            return true
        }

        startProjectionAfterForegroundService(manager, resultCode, data, config, token, result)
        return true
    }

    /**
     * 启动 MediaProjection 类型前台服务后再创建投屏令牌。
     *
     * Android 10+ 要求 `getMediaProjection()` 调用发生在 mediaProjection 前台服务
     * 已经运行之后；Android 14+ 会在这里强制校验 service type 和对应权限。该方法
     * 把授权回调后的启动流程串成一个异步步骤，避免 Service 还没执行到
     * `startForeground()` 就提前创建 MediaProjection。
     */
    private fun startProjectionAfterForegroundService(
        manager: MediaProjectionManager,
        resultCode: Int,
        data: Intent,
        config: CaptureConfig,
        token: Int,
        result: MethodChannel.Result,
    ) {
        try {
            PlaybackAudioCaptureForegroundService.Controller.start(context)
        } catch (e: Exception) {
            emitState(config, false, "播放音频捕获前台服务启动失败")
            result.error(
                "FOREGROUND_SERVICE_START_FAILED",
                "启动播放音频捕获前台服务失败: ${e.message}",
                e.toString(),
            )
            return
        }

        scope.launch {
            val serviceReady = waitForProjectionForegroundService()
            if (!serviceReady) {
                PlaybackAudioCaptureForegroundService.Controller.stop(context)
                emitState(config, false, "播放音频捕获前台服务未就绪")
                result.error(
                    "FOREGROUND_SERVICE_TIMEOUT",
                    "播放音频捕获前台服务未能进入 mediaProjection 前台状态",
                    null,
                )
                return@launch
            }
            if (!isStartTokenCurrent(token)) {
                PlaybackAudioCaptureForegroundService.Controller.stop(context)
                result.error("CANCELLED", "播放音频捕获已取消", null)
                return@launch
            }

            try {
                val projection = manager.getMediaProjection(resultCode, data)
                if (projection == null) {
                    PlaybackAudioCaptureForegroundService.Controller.stop(context)
                    result.error("MEDIA_PROJECTION_NULL", "MediaProjection 创建失败", null)
                    return@launch
                }
                startAudioRecord(projection, config, result)
            } catch (e: Exception) {
                PlaybackAudioCaptureForegroundService.Controller.stop(context)
                emitState(config, false, "播放音频捕获启动失败")
                result.error("START_FAILED", "启动播放音频捕获失败: ${e.message}", e.toString())
            }
        }
    }

    /** 判断当前 Android 版本是否支持 AudioPlaybackCapture。 */
    private fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /** 校验参数并触发 MediaProjection 授权流程。 */
    private fun startPowerCapture(call: MethodCall, result: MethodChannel.Result) {
        if (!isSupported()) {
            result.error("UNSUPPORTED", "Android 10(API 29) 以下不支持播放音频捕获", null)
            return
        }
        if (isCapturing || pendingResult != null) {
            result.error("ALREADY_CAPTURING", "播放音频捕获已在进行中", null)
            return
        }

        val args = call.arguments as? Map<*, *> ?: emptyMap<String, Any>()
        val config = CaptureConfig(
            sampleRate = ((args["sampleRate"] as? Number)?.toInt() ?: 16000).coerceAtLeast(1),
            channels = ((args["channels"] as? Number)?.toInt() ?: 1).let { if (it == 2) 2 else 1 },
            frameMs = ((args["frameMs"] as? Number)?.toInt() ?: 100).coerceIn(10, 1000),
            debugNativeTone = args["debugNativeTone"] as? Boolean ?: false,
        )
        enableLog = args["enableLog"] as? Boolean ?: false
        val policySnapshot = PlaybackAudioCapturePolicy.ensureAllowCaptureByAll(
            context = context,
            enableLog = enableLog,
        )
        if (policySnapshot.supported &&
            policySnapshot.after != AudioAttributes.ALLOW_CAPTURE_BY_ALL
        ) {
            log(
                "播放音频捕获策略仍受限: before=${policySnapshot.before}, " +
                    "after=${policySnapshot.after}"
            )
        }

        val currentActivity = activity
        if (currentActivity == null) {
            result.error("ACTIVITY_UNAVAILABLE", "播放音频捕获需要 Activity 才能请求系统授权", null)
            return
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            result.error("MEDIA_PROJECTION_UNAVAILABLE", "无法获取 MediaProjectionManager", null)
            return
        }

        val token = nextStartToken()
        pendingResult = result
        pendingConfig = config
        pendingStartToken = token
        currentActivity.startActivityForResult(
            createMediaProjectionConsentIntent(manager),
            REQUEST_MEDIA_PROJECTION,
        )
    }

    /**
     * 创建 MediaProjection 系统授权 Intent。
     *
     * Android 14 引入单 App / 整屏共享选择流。播放音频捕获只需要一个
     * MediaProjection 授权令牌，不创建 VirtualDisplay，也不消费画面；这里在
     * Android 14+ 明确请求默认显示器配置，尽量减少系统让用户额外选择共享范围。
     * 用户授权本身仍是系统强制要求，不能也不应该绕过。
     */
    private fun createMediaProjectionConsentIntent(
        manager: MediaProjectionManager,
    ): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /** 使用已授权的 MediaProjection 创建只采集播放音频的 AudioRecord。 */
    private fun startAudioRecord(
        projection: MediaProjection,
        config: CaptureConfig,
        result: MethodChannel.Result
    ) {
        val channelMask = if (config.channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(config.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            projection.stop()
            PlaybackAudioCaptureForegroundService.Controller.stop(context)
            result.error("INVALID_AUDIO_FORMAT", "系统不支持当前播放音频捕获格式", null)
            return
        }

        val frameBytes = config.sampleRate * config.channels * BYTES_PER_SAMPLE * config.frameMs / 1000
        val bufferSize = max(minBuffer * 2, frameBytes * 2)
        val captureConfig = buildPlaybackCaptureConfig(projection)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            projection.stop()
            PlaybackAudioCaptureForegroundService.Controller.stop(context)
            result.error("INIT_FAILED", "播放音频 AudioRecord 初始化失败", null)
            return
        }

        this.mediaProjection = projection
        this.audioRecord = record
        this.isCapturing = true

        try {
            record.startRecording()
        } catch (e: Exception) {
            this.mediaProjection = null
            this.audioRecord = null
            this.isCapturing = false
            record.release()
            projection.stop()
            PlaybackAudioCaptureForegroundService.Controller.stop(context)
            result.error("START_RECORDING_FAILED", "启动播放音频 AudioRecord 失败: ${e.message}", e.toString())
            return
        }
        if (config.debugNativeTone) {
            debugTonePlayer.start(scope)
        }
        emitState(config, true, "capturing")
        captureJob = scope.launch(Dispatchers.IO) {
            capturePowerLoop(record, config, frameBytes)
        }
        log(
            "播放音频捕获已启动: ${config.sampleRate}Hz, channels=${config.channels}, " +
                "frameMs=${config.frameMs}, uid=${Process.myUid()}, " +
                "debugNativeTone=${config.debugNativeTone}, " +
                "usages=${captureConfig.matchingUsages.joinToString()}, " +
                "uidFilter=${captureConfig.matchingUids.joinToString().ifBlank { "none" }}"
        )
        result.success(true)
    }

    /**
     * 构建播放音频捕获配置。
     *
     * Android 官方只允许捕获 `USAGE_MEDIA`、`USAGE_GAME` 和 `USAGE_UNKNOWN`
     * 等可捕获用途的播放流。这里刻意不调用 `addMatchingUid()`：WebView 的
     * 媒体播放可能由隔离 renderer 进程创建 AudioTrack，音频归属 UID 不一定等于
     * 宿主 App 的 `Process.myUid()`。强行限定 UID 会让系统授权和 AudioRecord
     * 都正常运行，但匹配不到真实播放源，最终只读到全 0 PCM。
     */
    private fun buildPlaybackCaptureConfig(
        projection: MediaProjection,
    ): AudioPlaybackCaptureConfiguration {
        return AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
    }

    /**
     * 等待前台服务完成 `startForeground()`。
     *
     * `startForegroundService()` 只是把启动请求投递给系统，Service 的
     * `onStartCommand()` 会稍后在主线程运行。这里轮询一个短窗口，确保随后调用
     * `getMediaProjection()` 时系统已经看到 mediaProjection 类型前台服务。
     */
    private suspend fun waitForProjectionForegroundService(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + FOREGROUND_SERVICE_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (PlaybackAudioCaptureForegroundService.Controller.isRunning()) {
                return true
            }
            delay(FOREGROUND_SERVICE_READY_POLL_MS)
        }
        return PlaybackAudioCaptureForegroundService.Controller.isRunning()
    }

    /** 按 frameMs 聚合 PCM 并向 Flutter 推送功率数据。 */
    private suspend fun capturePowerLoop(record: AudioRecord, config: CaptureConfig, frameBytes: Int) {
        val readBuffer = ByteArray(max(frameBytes, 1024))
        val accumulator = PowerAccumulator(config)

        while (isCapturing && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = try {
                record.read(readBuffer, 0, readBuffer.size)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    eventSink?.error("CAPTURE_READ_FAILED", "读取播放音频失败: ${e.message}", e.toString())
                }
                break
            }

            if (bytesRead > 0) {
                accumulator.addPcm16(readBuffer, bytesRead)
                if (accumulator.bytes >= frameBytes) {
                    val payload = accumulator.toPayload(true, "capturing")
                    accumulator.reset()
                    withContext(Dispatchers.Main) {
                        eventSink?.success(payload)
                    }
                }
            } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                break
            }
        }
    }

    /** 停止捕获并释放 AudioRecord/MediaProjection。 */
    private fun stopInternal(sendState: Boolean): Boolean {
        val wasCapturing = isCapturing || pendingResult != null
        invalidateStartToken()
        isCapturing = false
        pendingResult?.error("CANCELLED", "播放音频捕获已取消", null)
        pendingResult = null
        pendingConfig = null
        pendingStartToken = 0

        captureJob?.cancel()
        captureJob = null
        debugTonePlayer.stop()

        val record = audioRecord
        val projection = mediaProjection
        audioRecord = null
        mediaProjection = null

        // 播放捕获的释放必须在 stop/detach 路径中确定完成，不能依赖可能已被
        // dispose() 取消的协程作用域，否则会残留 MediaProjection 或 AudioRecord。
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        PlaybackAudioCaptureForegroundService.Controller.stop(context)

        if (sendState) {
            emitState(CaptureConfig(), false, "stopped")
        }
        return wasCapturing
    }

    /** 发送捕获状态帧，保证 UI 在启动/停止/拒绝时有明确状态。 */
    private fun emitState(config: CaptureConfig, capturing: Boolean, message: String) {
        eventSink?.success(
            mapOf(
                "source" to SOURCE,
                "rms" to 0.0,
                "db" to -160.0,
                "linearPower" to 0.0,
                "duty" to 0.0,
                "sampleRate" to config.sampleRate,
                "channels" to config.channels,
                "capturing" to capturing,
                "message" to message,
            )
        )
    }

    private fun log(message: String) {
        if (enableLog) {
            Log.d("PlaybackAudioCapture", message)
        }
    }

    /** 生成一次启动令牌，用于让 stop/dispose 失效迟到的授权回调。 */
    private fun nextStartToken(): Int {
        startToken += 1
        return startToken
    }

    /** 当前异步启动令牌是否仍然有效。 */
    private fun isStartTokenCurrent(token: Int): Boolean {
        return token != 0 && token == startToken
    }

    /** 失效所有未完成的异步启动流程。 */
    private fun invalidateStartToken() {
        startToken += 1
    }

    /** 捕获配置。 */
    private data class CaptureConfig(
        val sampleRate: Int = 16000,
        val channels: Int = 1,
        val frameMs: Int = 100,
        val debugNativeTone: Boolean = false,
    )

    /** PCM16 功率聚合器。 */
    private class PowerAccumulator(private val config: CaptureConfig) {
        var bytes: Int = 0
            private set
        private var squareSum: Double = 0.0
        private var samples: Int = 0
        private var nonZeroSamples: Int = 0
        private var peak: Double = 0.0
        private var readChunks: Int = 0

        /** 追加小端 PCM16 数据。 */
        fun addPcm16(buffer: ByteArray, length: Int) {
            readChunks++
            var index = 0
            while (index + 1 < length) {
                val low = buffer[index].toInt() and 0xff
                val high = buffer[index + 1].toInt()
                val sample = (high shl 8) or low
                val normalized = sample / 32768.0
                squareSum += normalized * normalized
                if (sample != 0) {
                    nonZeroSamples++
                }
                peak = max(peak, kotlin.math.abs(normalized))
                samples++
                index += 2
            }
            bytes += length
        }

        /** 将累计窗口转换成平台通道 payload。 */
        fun toPayload(capturing: Boolean, message: String): Map<String, Any> {
            val rms = if (samples > 0) sqrt(squareSum / samples) else 0.0
            val db = 20.0 * log10(max(rms, 1e-8))
            val linearPower = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0)
            val diagnosticMessage = if (rms > 0.0) {
                message
            } else {
                "capturing_zero_pcm reads=$readChunks samples=$samples nz=$nonZeroSamples peak=${"%.4f".format(peak)}"
            }
            return mapOf(
                "source" to SOURCE,
                "rms" to rms,
                "db" to db,
                "linearPower" to linearPower,
                "duty" to linearPower * 100.0,
                "sampleRate" to config.sampleRate,
                "channels" to config.channels,
                "capturing" to capturing,
                "message" to diagnosticMessage,
            )
        }

        /** 清空当前聚合窗口。 */
        fun reset() {
            bytes = 0
            squareSum = 0.0
            samples = 0
            nonZeroSamples = 0
            peak = 0.0
            readChunks = 0
        }
    }

    private companion object {
        const val METHOD_CHANNEL_NAME = "pcm_stream_recorder/playback_audio"
        const val EVENT_CHANNEL_NAME = "pcm_stream_recorder/playback_audio_power_stream"
        const val REQUEST_MEDIA_PROJECTION = 54829
        const val BYTES_PER_SAMPLE = 2
        const val SOURCE = "android_playback_capture"
        const val FOREGROUND_SERVICE_READY_TIMEOUT_MS = 3_000L
        const val FOREGROUND_SERVICE_READY_POLL_MS = 50L
    }
}
