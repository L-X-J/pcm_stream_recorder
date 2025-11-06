# pcm_stream_recorder

一个轻量级 Flutter 录音插件，聚焦实时语音处理场景，提供 **PCM 实时流回调** 与 **WAV 文件录制** 能力。

## 功能特点

- ✅ 支持 Android（AudioRecord）与 iOS（AVAudioEngine）双平台
- ✅ PCM 实时数据流回调，可直接接入 ASR、Vad、降噪等链路
- ✅ 原生线程录音，支持回声消除与噪声抑制（取决于设备能力）
- ✅ 一键保存 16-bit PCM 为标准 WAV 文件
- ✅ 示例包含权限处理、音量检测与本地回放

## 快速上手

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

## 示例应用

```bash
cd example
fvm flutter pub get
fvm flutter run
```

示例展示了实时波形、WAV 保存和本地播放等常见流程。

