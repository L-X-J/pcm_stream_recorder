package com.yuanqu.pcm_stream_recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
    
    // 录音配置
    private var sampleRate: Int = 16000
    private var channels: Int = 1
    private var bufferSize: Int = 1600
    
    // 音频效果器
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
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
            
            // 创建 AudioRecord (minSdk = 24，直接使用 AudioRecord.Builder)
            // 注意：AudioRecord.Builder 不支持 setAudioAttributes()，回声消除通过 AudioSource.VOICE_COMMUNICATION 启用
            val audioFormatBuilder = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(audioFormat)
                .setChannelMask(channelConfig)
            
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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
            
            // 启用回声消除（如果支持）
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    val aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                    aec?.enabled = true
                    acousticEchoCanceler = aec
                }
            } catch (e: Exception) {
                // 忽略错误，某些设备可能不支持
            }
            
            // 启用噪声抑制（如果支持）
            try {
                if (NoiseSuppressor.isAvailable()) {
                    val ns = NoiseSuppressor.create(audioRecord.audioSessionId)
                    ns?.enabled = true
                    noiseSuppressor = ns
                }
            } catch (e: Exception) {
                // 忽略错误，某些设备可能不支持
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
        
        // 停止 AudioRecord
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // 忽略错误
        }
        audioRecord = null
        
        // 释放音频效果器
        try {
            acousticEchoCanceler?.release()
            noiseSuppressor?.release()
        } catch (e: Exception) {
            // 忽略错误
        }
        acousticEchoCanceler = null
        noiseSuppressor = null
        
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
