## 问题定位
- 卡顿发生在 Dart 的 `stop()` 调用处：`lib/pcm_stream_recorder.dart:173`，对应 iOS 原生的 `stop` 处理。
- iOS 端实现位于 `ios/Classes/PcmStreamRecorderPlugin.swift`：
  - 停止流程在 `stopRecording(result:)` `ios/Classes/PcmStreamRecorderPlugin.swift:657-681`。
  - 关键的会话释放在 `restoreAudioSession()` `ios/Classes/PcmStreamRecorderPlugin.swift:684-759`，其中 `AVAudioSession.setActive(false, options: .notifyOthersOnDeactivation)` 为同步、可阻塞操作，普遍会在主线程造成 UI 卡顿。
- 目前 `result(true)` 在恢复会话之后才返回，进一步拉长 Dart 侧 `await` 的等待时间。

## 方案总览
- 核心目标：让耗时的音频会话释放在后台线程执行，避免阻塞 iOS 主线程，从而消除 Flutter UI 绘制卡顿；同时保证 `stop()` 语义完整（停止录制后再返回）。
- 具体策略：
  1) 将停止管线（移除 tap、停止 `AVAudioEngine`、恢复 `AVAudioSession`）迁移到后台串行队列执行；仅在完成后切回主线程返回 `result(true)`。
  2) 保证在会话释放前，所有 I/O 已完全停止；必要时加入极短延迟（50–100ms）以避免“正在运行 I/O 时取消激活”的同步等待峰值。
  3) 减少会话恢复中的多余操作：仅在启动阶段确实改动过 `category/mode/options` 时再恢复；避免频繁的 `setPreferredSampleRate` 回退；最小化同步 API 调用次数。
  4) 提供可选的“快速停止”路径：仅停止 `AVAudioEngine` 并保持 `AVAudioSession` 激活，由应用上层在需要时再主动释放；此路径几乎零卡顿，但需要业务确认兼容性。
  5) 为后续验证加入耗时埋点日志，量化优化效果（预期从 ~1200ms 降到 <150–250ms，并且不再阻塞 UI）。

## 具体修改
- 后台执行停止：
  - 在 `handle(_:result:)` 的 `case "stop"` 中，改为将停止逻辑包裹进后台串行队列 `DispatchQueue(label: "pcm.recorder.stop", qos: .userInitiated).async { ... }`；停止完成后 `DispatchQueue.main.async { result(true) }`。
  - 后台逻辑步骤：
    - `inputNode?.removeTap(onBus: 0)`、`audioEngine?.stop()`、`audioEngine = nil`、`inputNode = nil`。
    - 可选：`usleep(50_000)` 或 `DispatchQueue.global().asyncAfter(deadline: .now()+0.05)`，确保硬件 I/O完全停下。
    - 调用 `AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)`；仅在 `configureAudioSession(...)` 曾更改过 `category/mode/options` 时才恢复到原始值，避免冗余同步调用。
- 语义保持：
  - 仍在停止完成后才返回 `result(true)`，因此 Dart 侧 `await stop()` 语义不变，业务无需改动。
- 可选“快速停止”：
  - 新增一个可选参数或方法（例如 `stop(fast: true)`），只停止 `AVAudioEngine`，立刻返回；把会话恢复改为后台、非阻塞任务或由上层触发。此选项供需要极致流畅的场景使用。

## 验证方法
- 在插件内对关键节点打点：记录 `removeTap`、`engine.stop`、`setActive(false)` 的耗时，打印总耗时；对比优化前后日志。
- 在示例工程中快速滑动/动画操作，观察是否仍出现 UI 丢帧；同时统计 `stop()` 总耗时分布（P50/P90）。
- 压测耳机/蓝牙等路由场景，确认不会出现“未停止 I/O 即取消激活”的错误日志。

## 风险与兼容性
- 后台线程释放需要保证线程安全；Apple 文档与业界实践均表明 `AVAudioSession.setActive` 可在后台队列调用，但必须确保先停止 I/O。
- 若上层（如 WebRTC）同时管理会话，需谨慎选择“最小恢复”策略，避免反复覆盖 `category/mode/options`。
- 如果选择“快速停止”路径，需确认保持会话激活不会影响其他模块的音频策略。

## 参考资料
- AVAudioSession 的激活/取消激活在主线程上会引发卡顿，建议后台队列调用：[Stack Overflow 1](https://stackoverflow.com/questions/18419249/ios-audiosessionsetactive-blocking-main-thread)；[Stack Overflow 2](https://stackoverflow.com/questions/31090320/ios-avaudiosession-ducking-is-slow-and-synchronous)
- 取消激活前确保停止所有 I/O，避免同步等待与错误：[Stack Overflow 3](https://stackoverflow.com/questions/42432997/avaudiosession-error-deactivating-an-audio-session-that-has-running-i-o)；[Stack Overflow 4](https://stackoverflow.com/questions/24114323/ios8-avaudiosession-setactive-error)
- 实务上添加极短延迟以确保 I/O 停止后再取消激活，可减小阻塞峰值：[Stack Overflow 2](https://stackoverflow.com/questions/24114323/ios8-avaudiosession-setactive-error)（回答中有示例）

请确认是否按上述方案执行：优先采用“后台串行队列停止＋最小恢复”的改动；若需要极致速度，再开启“快速停止”可选路径。