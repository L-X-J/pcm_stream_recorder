# pcm_stream_recorder Example

此示例展示如何使用 `pcm_stream_recorder` 插件完成麦克风录音、暂停/恢复、保存为 WAV 文件并回放的完整流程。

## 功能概览

- 申请麦克风权限（Android/iOS）。
- 控制录音：开始、暂停、恢复、停止。
- 实时监听原生 PCM 数据并计算音量等级。
- 将录音保存为 16-bit PCM 的 WAV 文件。
- 使用 `audioplayers` 播放最近一次录制的音频。

## 运行步骤

1. **安装依赖**

   ```bash
   cd example
   fvm flutter pub get
   ```

2. **配置权限**

   - Android 已在 `AndroidManifest.xml` 中声明 `RECORD_AUDIO` 权限。
   - iOS 已在 `Info.plist` 中增加 `NSMicrophoneUsageDescription`，可按需要调整提示文案。

3. **运行示例**

   ```bash
   fvm flutter run
   ```

4. 在界面中点击“请求权限”后即可尝试录音并播放。

## 注意事项

- iOS 平台无法做到真正的“暂停录音”，调用暂停方法会停止当前采集，这是底层平台限制。
- 示例使用 `path_provider` 将录音文件保存到 App 文档目录，必要时可改为其他存储位置。
- 录音数据为原始 PCM 流，如需上传或二次处理，可在 `_handleAudioData` 中追加相关逻辑。
