package com.yuanqu.pcm_stream_recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.*

/** 高性能原生录音插件 - Android 实现
 * 使用 AudioRecord 实现录音，支持边播边录和回声消除
 */
class PcmStreamRecorderPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler, ActivityAware {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var context: Context? = null
    private var originalAudioMode: Int = AudioManager.MODE_NORMAL
    private var originalSpeakerphoneOn: Boolean = false
    private var shouldRestoreMediaPlaybackState: Boolean = false
    
    // 录音配置
    private var sampleRate: Int = 16000
    private var channels: Int = 1
    private var bufferSize: Int = 1600
    
    // 音频效果器
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // 音频设备回调 (API 23+)
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var playbackAudioCapturePlugin: PlaybackAudioCapturePlugin? = null

    /**
     * 判断设备类型是否代表用户显式接入的外部音频路由。
     *
     * 该判断只服务 ASR 边播边录路由：外部耳机存在时必须关闭扬声器路由，避免
     * 详情页启动 `MODE_IN_COMMUNICATION` 后视频声音被系统切到手机外放。
     */
    private fun isExternalAudioDeviceType(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            else -> false
        }
    }

    /**
     * 判断输出设备是否是外部播放设备。
     *
     * `GET_DEVICES_OUTPUTS` 已经限定了输出方向，因此这里不再依赖 `isSink`。部分
     * Android 厂商 ROM 在通信模式切换期间会给设备方向返回不稳定状态，继续检查
     * `isSink` 会误判“无耳机”，进而错误打开扬声器。
     */
    private fun isExternalOutputDevice(device: AudioDeviceInfo): Boolean {
        return isExternalAudioDeviceType(device.type)
    }

    /**
     * 使用旧路由开关作为设备枚举失败时的兜底。
     *
     * 这些 API 已被标记废弃，但在部分 Android 机型上比设备列表更早反映耳机连接
     * 状态；只用作“禁止打开 speakerphone”的保护信号，不作为最终输出选择。
     */
    @Suppress("DEPRECATION")
    private fun hasLegacyExternalRouteSignal(audioManager: AudioManager): Boolean {
        return audioManager.isBluetoothA2dpOn ||
            audioManager.isBluetoothScoOn ||
            audioManager.isWiredHeadsetOn
    }

    /**
     * 判断系统当前是否存在任何外部音频路由。
     *
     * Android 在媒体输出、通信输出和旧路由状态之间存在时序差异；只要任一路径
     * 表明耳机存在，就不应该打开 speakerphone。
     */
    private fun hasExternalAudioRoute(audioManager: AudioManager): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.any { isExternalOutputDevice(it) }) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            audioManager.availableCommunicationDevices.any { isExternalAudioDeviceType(it.type) }
        ) {
            return true
        }
        return hasLegacyExternalRouteSignal(audioManager)
    }

    /**
     * 生成路由快照日志，便于真机复测时确认系统枚举到了哪些设备。
     */
    @Suppress("DEPRECATION")
    private fun describeAudioRoute(audioManager: AudioManager): String {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(prefix = "[", postfix = "]") { "${it.type}:${it.productName}" }
        val communicationDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .joinToString(prefix = "[", postfix = "]") { "${it.type}:${it.productName}" }
        } else {
            "[]"
        }
        val currentCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { "${it.type}:${it.productName}" } ?: "null"
        } else {
            "null"
        }
        return "mode=${audioManager.mode}, speaker=${audioManager.isSpeakerphoneOn}, " +
            "a2dp=${audioManager.isBluetoothA2dpOn}, sco=${audioManager.isBluetoothScoOn}, " +
            "wired=${audioManager.isWiredHeadsetOn}, outputs=$outputs, " +
            "communication=$communicationDevices, currentCommunication=$currentCommunicationDevice"
    }

    /**
     * 为通信音频路由排序。
     *
     * ASR 使用系统 AEC 时会进入通信模式；Android 12+ 需要优先指定通信设备，
     * 否则系统可能沿用 speakerphone。带麦的有线/蓝牙设备优先级最高，纯媒体
     * 输出作为兜底交给系统媒体路由处理。
     */
    private fun communicationRoutePriority(device: AudioDeviceInfo): Int {
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 2
            AudioDeviceInfo.TYPE_USB_HEADSET -> 3
            AudioDeviceInfo.TYPE_HEARING_AID -> 4
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 5
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 6
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> 7
            AudioDeviceInfo.TYPE_USB_DEVICE -> 8
            else -> 100
        }
    }

    /**
     * 选择 Android 12+ 可用的外部通信设备。
     *
     * `getDevices(GET_DEVICES_OUTPUTS)` 能说明“有耳机”，但通信模式真正使用的是
     * `availableCommunicationDevices`；两者必须分开看，避免把 A2DP 输出误当成可
     * 直接指定的通话设备。
     */
    private fun findPreferredCommunicationDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }
        return audioManager.availableCommunicationDevices
            .filter { isExternalAudioDeviceType(it.type) }
            .minByOrNull { communicationRoutePriority(it) }
    }

    /**
     * 应用 ASR 录音期间的播放路由策略。
     *
     * 有外部输出时关闭 speakerphone，并在 Android 12+ 显式指定或清理通信设备；
     * 无外部输出时才启用扬声器，避免通信模式默认落到听筒。该函数在启动录音和
     * 设备热插拔时复用，保证详情页 ASR 与视频播放走同一套系统路由规则。
     */
    private fun applyAsrAudioRouting(audioManager: AudioManager) {
        val hasExternalOutput = hasExternalAudioRoute(audioManager)
        android.util.Log.d("PcmStreamRecorder", "ASR路由快照: ${describeAudioRoute(audioManager)}")

        if (hasExternalOutput) {
            if (audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val preferredDevice = findPreferredCommunicationDevice(audioManager)
                if (preferredDevice != null) {
                    val currentDevice = audioManager.communicationDevice
                    if (currentDevice?.id != preferredDevice.id) {
                        val routed = audioManager.setCommunicationDevice(preferredDevice)
                        android.util.Log.d(
                            "PcmStreamRecorder",
                            "检测到外部输出，设置通信设备: type=${preferredDevice.type}, routed=$routed"
                        )
                    } else {
                        android.util.Log.d(
                            "PcmStreamRecorder",
                            "检测到外部输出，通信设备已是外部设备: type=${preferredDevice.type}"
                        )
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    android.util.Log.d("PcmStreamRecorder", "检测到外部输出，清理通信设备并交还系统路由")
                }
            }
            android.util.Log.d("PcmStreamRecorder", "检测到外部输出设备，已关闭扬声器路由")
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            if (!audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = true
            }
            android.util.Log.d("PcmStreamRecorder", "无外部输出设备，默认扬声器以避免走听筒")
        }
    }
    
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "pcm_stream_recorder")
        methodChannel.setMethodCallHandler(this)
        
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "pcm_stream_recorder/audio_stream")
        eventChannel.setStreamHandler(this)
        playbackAudioCapturePlugin = PlaybackAudioCapturePlugin(
            flutterPluginBinding.applicationContext,
            flutterPluginBinding.binaryMessenger
        )
    }
    
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        playbackAudioCapturePlugin?.dispose()
        playbackAudioCapturePlugin = null
        stopRecordingInternal()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        playbackAudioCapturePlugin?.onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        playbackAudioCapturePlugin?.onDetachedFromActivityForConfigChanges()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        playbackAudioCapturePlugin?.onReattachedToActivityForConfigChanges(binding)
    }

    override fun onDetachedFromActivity() {
        playbackAudioCapturePlugin?.onDetachedFromActivity()
    }
    
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "checkPermission" -> {
                result.success(checkPermission())
            }
            "requestPermission" -> {
                // Android 权限需要 Flutter 侧使用 permission_handler 处理
                result.success(false)
            }
            "start" -> {
                val args = call.arguments as? Map<String, Any> ?: emptyMap<String, Any>()
                startRecording(
                    sampleRate = (args["sampleRate"] as? Number)?.toInt() ?: 16000,
                    channels = (args["channels"] as? Number)?.toInt() ?: 1,
                    bufferSize = (args["bufferSize"] as? Number)?.toInt() ?: 1600,
                    useSystemAEC = (args["useSystemAEC"] as? Boolean) ?: false,
                    result = result
                )
            }
            "stop" -> {
                result.success(stopRecordingInternal())
            }
            "pause" -> {
                result.success(pauseRecording())
            }
            "resume" -> {
                result.success(resumeRecording())
            }
            "isRecording" -> {
                result.success(isRecording)
            }
            "prepareAudioSession" -> {
                prepareAudioSession(result)
            }
            "restoreAudioSession" -> {
                restoreAudioSession(result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }
    
    /// 检查录音权限
    private fun checkPermission(): Boolean {
        // Android 权限检查需要 Flutter 侧处理
        // 这里返回 true，实际权限检查在 Flutter 侧使用 permission_handler
        return true
    }

    /// 记录进入 ASR 前的音频状态。
    /// 这个准备阶段不主动切换路由，只负责把“媒体播放模式”的基线保存下来，
    /// 方便页面退出或录音失败时回到正常的媒体扬声器链路。
    private fun prepareAudioSession(result: Result) {
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
            shouldRestoreMediaPlaybackState = false
            originalAudioMode = audioManager.mode
            originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
            android.util.Log.d(
                "PcmStreamRecorder",
                "已记录音频会话基线: mode=$originalAudioMode, speakerphone=$originalSpeakerphoneOn"
            )
            result.success(true)
        } catch (e: Exception) {
            result.error(
                "PREPARE_AUDIO_SESSION_FAILED",
                "记录音频会话基线失败: ${e.message}",
                e.toString()
            )
        }
    }

    /// 恢复到媒体播放会话。
    /// Android 上的“媒体扬声器”语义并不是简单恢复到旧状态，而是明确回到
    /// `MODE_NORMAL + 非 speakerphone 通话路由`，让系统重新按媒体规则分发到
    /// 内置扬声器或已连接耳机。这里既会立即执行一次，也会通知 stop 流程在
    /// 异步释放录音资源后再次兜底，避免页面刚退出时还残留在通话链路。
    private fun restoreAudioSession(result: Result) {
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
            shouldRestoreMediaPlaybackState = true
            restoreMediaPlaybackState(audioManager)
            result.success(true)
        } catch (e: Exception) {
            result.error(
                "RESTORE_AUDIO_SESSION_FAILED",
                "恢复媒体播放会话失败: ${e.message}",
                e.toString()
            )
        }
    }
    
    /// 开始录音
    private fun startRecording(
        sampleRate: Int,
        channels: Int,
        bufferSize: Int,
        useSystemAEC: Boolean,
        result: Result
    ) {
        if (isRecording) {
            result.error(
                "ALREADY_RECORDING",
                "录音已在进行中",
                null
            )
            return
        }
        
        this.sampleRate = sampleRate
        this.channels = channels
        this.bufferSize = bufferSize
        
        try {
            // 计算声道配置
            val channelConfig = if (channels == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
            
            // 计算音频格式
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
            // 计算缓冲区大小
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                audioFormat
            )
            
            val recordBufferSize = maxOf(bufferSize * 2, minBufferSize * 2) // 16-bit = 2 bytes

            // 保存并配置音频管理器，确保不会干扰系统的音频路由
            // 使用 MODE_IN_COMMUNICATION 以支持边播边录（播放视频同时录音）
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                // 保存原始状态
                originalAudioMode = audioManager.mode
                originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
                shouldRestoreMediaPlaybackState = false
                
                try {
                    if (useSystemAEC) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    } else {
                        audioManager.mode = originalAudioMode
                    }
                    applyAsrAudioRouting(audioManager)
                } catch (e: Exception) {
                    android.util.Log.w("PcmStreamRecorder", "设置音频模式失败: ${e.message}")
                }

                // 注册音频设备回调 (API 23+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioDeviceCallback = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                            android.util.Log.d("PcmStreamRecorder", "检测到音频设备添加")
                            updateAudioRouting(audioManager)
                        }

                        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                            android.util.Log.d("PcmStreamRecorder", "检测到音频设备移除")
                            updateAudioRouting(audioManager)
                        }
                    }
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
                }
            }
            
            // 创建 AudioRecord (minSdk = 24，直接使用 AudioRecord.Builder)
            // 注意：AudioRecord.Builder 在 API 23+ 支持 setAudioAttributes
            // 但为了更好的兼容性和避免路由问题，我们使用 AudioSource
            val audioFormatBuilder = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(audioFormat)
                .setChannelMask(channelConfig)
            
            @Suppress("DEPRECATION")
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(
                    if (useSystemAEC) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.MIC
                )
                .setAudioFormat(audioFormatBuilder.build())
                .setBufferSizeInBytes(recordBufferSize)
                .build()
            
            // 检查状态
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                result.error(
                    "INIT_FAILED",
                    "AudioRecord 初始化失败",
                    null
                )
                return
            }
            
            if (useSystemAEC) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        val aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                        aec?.enabled = true
                        acousticEchoCanceler = aec
                    }
                } catch (_: Exception) {}
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        val ns = NoiseSuppressor.create(audioRecord.audioSessionId)
                        ns?.enabled = true
                        noiseSuppressor = ns
                    }
                } catch (_: Exception) {}
            }
            
            // 启动录音
            audioRecord.startRecording()

            // AudioRecord 启动后部分系统才会刷新 availableCommunicationDevices，
            // 因此这里再应用一次路由策略，避免详情页首帧视频声音短暂落到外放。
            if (audioManager != null) {
                try {
                    applyAsrAudioRouting(audioManager)
                } catch (e: Exception) {
                    android.util.Log.w("PcmStreamRecorder", "启动录音后更新音频路由失败: ${e.message}")
                }
            }
            
            this.audioRecord = audioRecord
            this.isRecording = true
            
            // 启动录音线程
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudio(audioRecord, recordBufferSize)
            }
            
            android.util.Log.d("PcmStreamRecorder", "录音已启动")
            android.util.Log.d("PcmStreamRecorder", "采样率: ${sampleRate}Hz, 声道: ${channels}, 缓冲区: ${recordBufferSize}")
            android.util.Log.d("PcmStreamRecorder", "AEC 状态: ${acousticEchoCanceler != null}, NS 状态: ${noiseSuppressor != null}")
            
            result.success(true)
        } catch (e: Exception) {
            result.error(
                "START_FAILED",
                "启动录音失败: ${e.message}",
                e.toString()
            )
        }
    }
    
    /// 录音循环
    private suspend fun recordAudio(audioRecord: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        
        while (isRecording && audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            try {
                val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                
                if (bytesRead > 0) {
                    // AudioRecord 返回的数据已经是 PCM 16-bit 小端序格式，直接使用
                    val pcmData = buffer.copyOf(bytesRead)
                    
                    // EventChannel 必须在主线程调用，切换到主线程发送数据
                    withContext(Dispatchers.Main) {
                        eventSink?.success(pcmData)
                    }
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    // 录音被停止
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    // 缓冲区大小错误
                    break
                }
            } catch (e: Exception) {
                // 发送错误（需要在主线程）
                withContext(Dispatchers.Main) {
                    eventSink?.error("RECORD_ERROR", e.message, null)
                }
                break
            }
        }
    }
    
    /// 停止录音
    private fun stopRecordingInternal(): Boolean {
        if (!isRecording) {
            return false
        }
        
        isRecording = false
        
        // 取消录音任务
        recordingJob?.cancel()
        recordingJob = null
        
        // 捕获当前引用，准备异步释放
        val recordToRelease = audioRecord
        val aecToRelease = acousticEchoCanceler
        val nsToRelease = noiseSuppressor
        
        // 立即清空引用（主线程快速完成）
        audioRecord = null
        acousticEchoCanceler = null
        noiseSuppressor = null

        // 注销音频设备回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
        }
        
        // 在子线程中执行耗时的释放操作，避免阻塞主线程
        CoroutineScope(Dispatchers.IO).launch {
            // 释放 AudioRecord（可能耗时 50-200ms）
            try {
                recordToRelease?.stop()
                recordToRelease?.release()
                android.util.Log.d("PcmStreamRecorder", "AudioRecord 已在后台释放")
            } catch (e: Exception) {
                android.util.Log.w("PcmStreamRecorder", "释放 AudioRecord 失败: ${e.message}")
            }
            
            // 释放音频效果器
            try {
                aecToRelease?.release()
                nsToRelease?.release()
                android.util.Log.d("PcmStreamRecorder", "音频效果器已在后台释放")
            } catch (e: Exception) {
                android.util.Log.w("PcmStreamRecorder", "释放音频效果器失败: ${e.message}")
            }
            
            // 延迟后恢复音频管理器状态（确保资源完全释放）
            delay(100)
            withContext(Dispatchers.Main) {
                val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    try {
                        if (shouldRestoreMediaPlaybackState) {
                            restoreMediaPlaybackState(audioManager)
                            shouldRestoreMediaPlaybackState = false
                        } else {
                            audioManager.mode = originalAudioMode
                            audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
                            android.util.Log.d(
                                "PcmStreamRecorder",
                                "已恢复音频管理器原始状态: mode=$originalAudioMode, speakerphone=$originalSpeakerphoneOn"
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PcmStreamRecorder", "恢复音频管理器状态失败: ${e.message}")
                    }
                }
            }
        }
        
        return true
    }
    
    /// 暂停录音（Android 不支持真正的暂停，这里停止读取）
    private fun pauseRecording(): Boolean {
        if (!isRecording) {
            return false
        }
        
        // 暂停录音任务（实际上停止读取数据）
        recordingJob?.cancel()
        recordingJob = null
        
        return true
    }
    
    /// 恢复录音
    private fun resumeRecording(): Boolean {
        if (!isRecording || audioRecord == null) {
            return false
        }
        
        val record = audioRecord ?: return false
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2
        
        // 重新启动录音任务
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            recordAudio(record, bufferSize)
        }
        
        return true
    }
    
    // MARK: - EventChannel.StreamHandler
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }
    
    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    /**
     * 动态更新音频路由。
     *
     * 设备热插拔时复用启动录音时的路由策略，避免耳机连接状态变化后仍保留
     * Android 通信模式中的 speakerphone 或旧 communication device。
     */
    private fun updateAudioRouting(audioManager: AudioManager) {
        try {
            applyAsrAudioRouting(audioManager)
        } catch (e: Exception) {
            android.util.Log.w("PcmStreamRecorder", "路由变更更新失败: ${e.message}")
        }
    }

    /// 应用“媒体扬声器”会话。
    /// 这里不强制打开 speakerphone，而是清理通信态残留后回到 `MODE_NORMAL`，
    /// 让系统按媒体播放规则决定是走内置扬声器还是已连接耳机，并主动把
    /// 音频焦点重新声明为媒体播放，减少刚退出 ASR 页面时还残留在通话态的概率。
    private fun restoreMediaPlaybackState(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Exception) {
                android.util.Log.w("PcmStreamRecorder", "清除通信设备失败: ${e.message}")
            }
        }

        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            android.util.Log.d("PcmStreamRecorder", "已恢复到媒体播放会话（MODE_NORMAL + AUDIOFOCUS_GAIN）")
        } else {
            android.util.Log.w("PcmStreamRecorder", "媒体音频焦点请求未获授权，但已切回 MODE_NORMAL")
        }
    }
}
