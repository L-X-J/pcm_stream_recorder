## 问题研判
- 现象：进入页面媒体播放正常；启动录音卡住；后台再回来录音启动，但媒体改走扬声器/听筒。
- 高概率根因（Android 为主）：
  - `MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION` 会触发通话路由，默认倾向听筒/扬声器，若未显式管理 `isSpeakerphoneOn` 与外设检测，容易误路由到听筒（参考实践与文档 [1][2][5]）。
  - 开启 AEC/NS 本身不应改变输出路由，但它们通常配合 COMMUNICATION 模式，若未配套路由守护，易出现“边播边录”下路由漂移。
  - 录音“启动卡住”多见于 EventChannel 未 onListen 就启动、或 `AudioRecord.startRecording()` 在设备路由/权限状态不稳定时阻塞；Dart 侧已提前监听，但原生侧仍可能在 COMMUNICATION 切换时短暂卡顿。
- iOS 侧：已优化为 WebRTC 模式保持（`voiceChat/videoChat`），其余使用 `videoRecording + defaultToSpeaker`，避免误走听筒；需与 Android 同步治理策略以一致化体验。

## 修复思路
- 路由策略：
  - 启动录音前检测输出设备（耳机/蓝牙/SCO），有外设则关闭扬声器，保持系统自动路由；无外设则仅在非 WebRTC 模式下默认扬声器，避免听筒。
  - 仅在需要系统级 AEC 时启用 `MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION`；否则使用 `MODE_NORMAL` + `MIC`，降低被判定为“通话场景”的概率。
  - Android 14+ 在非 SCO 场景下可调用 `clearCommunicationDevice()` 清理通话设备锁定（保留 WebRTC 兼容）。
- 启动鲁棒性：
  - 在原生侧增加设备路由稳定性检查（读取 `getDevices(GET_DEVICES_OUTPUTS)` 后再启动录音），必要时加 50–100ms 微延迟以避开切换瞬时。
  - 启动失败/超时快速返回明确错误码，避免“无响应”。
- WebRTC 兼容：
  - 检测当前是否处于 `MODE_IN_COMMUNICATION` 或 iOS `voiceChat/videoChat`，在这种情况下不覆盖其模式，仅做被动路由守护（扬声器开关随外设）。
  - 蓝牙 SCO 场景保留通话设备，不调用 `clearCommunicationDevice()`。

## 具体改动（代码位点）
- Android：
  - `android/src/main/kotlin/com/yuanqu/pcm_stream_recorder/PcmStreamRecorderPlugin.kt`
    - 启动前检测输出设备并管理 `isSpeakerphoneOn`，在非 SCO 的 Android 14+ 清理通话设备（现有实现基础上增强路由守护）：145–173, 170–173
    - 将模式设置改为“按需”策略：仅在明确需要系统 AEC 时设为 `MODE_IN_COMMUNICATION`，否则保留现有模式或设为 `MODE_NORMAL`（新增开关）
    - 音源选择策略：默认 `MIC`，仅在启用系统 AEC 时用 `VOICE_COMMUNICATION`（新增开关）
    - 启动鲁棒性：检测稳定后再 `startRecording()`；失败时返回 `INIT_FAILED/START_FAILED` 明确错误
  - `android/src/main/kotlin/com/yuanqu/pcm_stream_recorder/AudioFocusManagerPlugin.kt`
    - 保持 `resetAudioFocus`，用于从 WebRTC/通话场景返回后的回退；必要时在录音停止后调用
- iOS：
  - `ios/Classes/PcmStreamRecorderPlugin.swift`
    - 已实现：WebRTC 模式保持，其他使用 `videoRecording + defaultToSpeaker`；继续维持不强制覆盖输出路由的策略（57–92, 176–214）

## 验证与回归
- 场景用例：
  - 耳机/蓝牙 A2DP 连接：播放视频/mp3，启动录音 → 仍走耳机/蓝牙；停止录音 → 路由不变
  - 无外设：播放视频/mp3，启动录音 → 走扬声器，不会走听筒
  - 蓝牙通话（SCO）：启动录音 → 保持通话设备路由；停止录音 → 可通过 `resetAudioFocus` 回到媒体模式
  - WebRTC 通话页面：启动录音 → 不改动其模式与路由
- 指标：
  - 启动耗时 < 200ms；失败返回明确错误码
  - 路由切换无“卡住”或“误到听筒”现象

## 风险与兼容
- 设备差异：部分机型对 COMMUNICATION 模式极为敏感；通过“按需 + 路由守护”减少副作用
- API 差异：`setPreferredDevice`（Android 9+）与 `clearCommunicationDevice`（Android 14+）按版本分支处理

## 执行计划
- 增加 Android 端可配置开关：`useSystemAEC`（默认关闭），驱动模式与音源选择
- 强化路由守护实现与启动鲁棒性，加入详细日志以定位路由与设备状态
- iOS 保持现逻辑，仅补充日志与极端情况保护
- 编写仪表测试脚本（Flutter 端）跑通 4 个关键场景并收集日志，完成后汇总结果

[1] https://stackoverflow.com/questions/44410837/how-to-set-default-mode-headset-in-android
[2] https://stackoverflow.com/questions/2119060/android-getting-audio-to-play-through-earpiece
[5] https://www.forasoft.com/blog/article/implement-audio-output-switching-on-android-575