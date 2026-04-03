我已经检查了 Android 端的实现代码 `android/src/main/kotlin/com/yuanqu/pcm_stream_recorder/PcmStreamRecorderPlugin.kt`。

**当前状态：**
Android 端目前**没有**专门监听录音过程中音频路由变化（如插拔耳机）的逻辑。
- 它仅在 `startRecording` 开始时配置一次 `AudioRecord`。
- 它根据 `useSystemAEC` 设置 `AudioManager.mode`。
- 它在启动时检查外部输出设备来设置 `isSpeakerphoneOn`。

**潜在问题：**
虽然 Android 的 `AudioRecord` 在路由切换方面通常比 iOS 的 `AVAudioEngine` 更稳健（系统通常会自动处理从内置麦克风到耳机麦克风的切换），但仍存在隐患：
1. **状态不一致**：插件在启动时根据“当时是否连接耳机”强制设置了 `isSpeakerphoneOn`。如果在录音过程中插入耳机，之前的设置可能导致音频路由行为不符合预期（例如声音仍从扬声器发出，或者录音源未切换）。
2. **缺乏动态响应**：应用无法感知硬件变动并做出调整。

**改进计划：**
为了确保 Android 端像 iOS 修复后一样稳健，我将实现 `AudioDeviceCallback`（API 23+ 支持）来监听设备连接变化。

1. **添加 `AudioDeviceCallback`**：在 `startRecording` 中注册回调，在 `stopRecording` 中注销。
2. **处理设备变更**：在回调方法 (`onAudioDevicesAdded`, `onAudioDevicesRemoved`) 中：
    - 重新检测是否有外部设备（耳机/蓝牙）。
    - 动态更新 `audioManager.isSpeakerphoneOn` 的状态。
    - 打印日志以方便调试路由切换。

**注意**：与 iOS 不同，Android 不需要“销毁并重建 `AudioRecord`”，因为 Android 系统会在底层自动处理输入源的无缝切换，除非发生读取错误（现有代码已处理读取异常）。我们要做的主要是确保 `AudioManager` 的路由策略（如是否强制免提）能随硬件状态即时更新。

**操作**：
修改 `android/src/main/kotlin/com/yuanqu/pcm_stream_recorder/PcmStreamRecorderPlugin.kt`，添加 `AudioDeviceCallback` 以支持动态路由更新。