# pcm_stream_recorder

一个轻量级 Flutter 音频插件，聚焦实时语音处理场景，提供 **麦克风 PCM 实时流回调**、**WAV 文件录制** 与 **当前播放音频功率捕获** 能力。

## 功能特点

- ✅ 支持 Android（AudioRecord）与 iOS（AVAudioEngine）双平台
- ✅ PCM 实时数据流回调，可直接接入 ASR、Vad、降噪等链路
- ✅ 原生线程录音，支持回声消除与噪声抑制（取决于设备能力）
- ✅ 一键保存 16-bit PCM 为标准 WAV 文件
- ✅ 独立捕获当前播放音频功率，可用于 NSFW、静音和播放状态检测
- ✅ 示例包含权限处理、音量检测与本地回放

## 麦克风录音

麦克风录音使用既有 `PcmStreamRecorder` API 和通道：

- MethodChannel：`pcm_stream_recorder`
- EventChannel：`pcm_stream_recorder/audio_stream`

该链路面向 ASR、IM 语音、AEC 等录麦场景，输出原始 16-bit PCM。

### iOS 音频会话边界

iOS 录音链路在 `prepareAudioSession()` / `switchToAsrSession()` 中统一切到 `playAndRecord + voiceChat`，用于支持边播边录与回声抑制场景。插件层负责音频路由策略：

- 检测到有线耳机、蓝牙耳机、USB 音频或车载音频时，不启用 `defaultToSpeaker`，清除输出覆盖并允许 HFP 与 A2DP 蓝牙路由。
- 未检测到外部输出设备时，启用 `defaultToSpeaker` 并显式 `overrideOutputAudioPort(.speaker)`，避免 ASR 会话默认走听筒。
- 路由变化时会刷新 ASR 会话选项、重新应用输出策略并重建输入 tap，业务层不应在页面或 service 中重复强制切换扬声器。

### Android 音频会话边界

Android ASR 在启用系统 AEC 时会进入 `MODE_IN_COMMUNICATION + VOICE_COMMUNICATION`，插件层负责避免通信模式污染媒体播放路由：

- 检测到有线耳机、蓝牙耳机、BLE 耳机、USB 音频或助听设备时，关闭 `speakerphone`。
- Android 12+ 会优先通过 `setCommunicationDevice()` 指向可用外部通信设备；无可指定设备时清理 communication device，把媒体输出交还系统路由。
- 未检测到外部输出设备时才启用 `speakerphone`，避免通信模式默认走听筒。
- 录音启动后和音频设备热插拔时都会重新应用同一套路由策略。

### 快速上手

1. **安装依赖**

   ```bash
   fvm flutter pub add pcm_stream_recorder
   ```

2. **请求权限并启动录音**

   ```dart
   final recorder = PcmStreamRecorder();

    final hasPermission = await recorder.requestPermission();
    if (!hasPermission) {
      return;
    }

    final pcmStream = await recorder.start(sampleRate: 16000);
    final subscription = pcmStream.listen((chunk) {
      // chunk 即为 Uint8List 格式的 16-bit PCM 数据
    });
   ```

3. **停止录音并释放资源**

   ```dart
   await recorder.stop();
   await recorder.dispose();
   ```

更多使用示例请参考 `example/`。

## 播放音频捕获

播放音频捕获使用独立 API 和独立通道，不复用麦克风录音链路：

- MethodChannel：`pcm_stream_recorder/playback_audio`
- EventChannel：`pcm_stream_recorder/playback_audio_power_stream`

该能力只输出实时功率，不输出原始 PCM，也不采集屏幕画面：

- Android：API 29+ 使用 `AudioPlaybackCaptureConfiguration + AudioRecord`，启动时会请求 MediaProjection 授权，但不会创建 `VirtualDisplay`。
- iOS：使用 ReplayKit `RPScreenRecorder.startCapture`，只处理 `RPSampleBufferType.audioApp`，忽略视频帧和麦克风音频。

```dart
final supported = await PlaybackAudioCapture.isSupported();
if (!supported) {
  return;
}

final subscription = PlaybackAudioCapture.powerStream.listen((power) {
  // power.rms / power.db / power.linearPower / power.duty
  // 可用于播放音频功率、静音或 NSFW 检测。
});

await PlaybackAudioCapture.startPowerCapture(
  sampleRate: 16000,
  channels: 1,
  frameMs: 100,
);

await PlaybackAudioCapture.stop();
await subscription.cancel();
```

`PlaybackAudioPower` 字段固定为：

- `source`
- `rms`
- `db`
- `linearPower`
- `duty`
- `sampleRate`
- `channels`
- `capturing`
- `message`

功率换算规则：

```text
rms = sqrt(sum(sample^2) / n)
db = 20 * log10(max(rms, 1e-8))
linearPower = clamp((db + 60) / 60, 0, 1)
duty = linearPower * 100
```

## 示例应用

```bash
cd example
fvm flutter pub get
fvm flutter run
```

示例展示了麦克风实时波形、WAV 保存、本地播放，以及播放音频功率捕获面板。
