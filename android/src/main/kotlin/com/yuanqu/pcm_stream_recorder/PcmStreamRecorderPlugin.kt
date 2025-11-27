package com.yuanqu.pcm_stream_recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.*

/** 高性能原生录音插件 - Android 实现
 * 使用 AudioRecord 实现录音，支持边播边录和回声消除
 */
class PcmStreamRecorderPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var context: Context? = null
    private var originalAudioMode: Int = AudioManager.MODE_NORMAL
    private var originalSpeakerphoneOn: Boolean = false
    
    // 录音配置
    private var sampleRate: Int = 16000
    private var channels: Int = 1
    private var bufferSize: Int = 1600
    
    // 音频效果器
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "pcm_stream_recorder")
        methodChannel.setMethodCallHandler(this)
        
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "pcm_stream_recorder/audio_stream")
        eventChannel.setStreamHandler(this)
    }
    
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        stopRecordingInternal()
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
                
                try {
                    if (useSystemAEC) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    } else {
                        audioManager.mode = originalAudioMode
                    }
                    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val hasSco = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    val hasExternalOutput = outputs.any { device ->
                        when (device.type) {
                            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                            AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                            AudioDeviceInfo.TYPE_USB_HEADSET,
                            AudioDeviceInfo.TYPE_HEARING_AID -> true
                            else -> false
                        }
                    } || hasSco
                    if (hasExternalOutput) {
                        if (audioManager.isSpeakerphoneOn) {
                            audioManager.isSpeakerphoneOn = false
                        }
                        if (!hasSco && Build.VERSION.SDK_INT >= 34) {
                            try {
                                audioManager.clearCommunicationDevice()
                            } catch (_: Exception) {}
                        }
                        android.util.Log.d("PcmStreamRecorder", "检测到外部输出设备，保持系统路由（耳机/蓝牙）")
                    } else {
                        if (!audioManager.isSpeakerphoneOn) {
                            audioManager.isSpeakerphoneOn = true
                        }
                        android.util.Log.d("PcmStreamRecorder", "无外部输出设备，默认扬声器以避免走听筒")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PcmStreamRecorder", "设置音频模式失败: ${e.message}")
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
                        audioManager.mode = originalAudioMode
                        audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
                        android.util.Log.d("PcmStreamRecorder", "已恢复音频管理器原始状态: mode=$originalAudioMode, speakerphone=$originalSpeakerphoneOn")
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
}
