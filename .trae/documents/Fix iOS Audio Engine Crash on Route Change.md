用户建议在路由切换时“移除旧的，初始化新的”，这是一种更彻底的解决方案，能有效避免引擎内部状态残留导致的问题。

我将修改 `ios/Classes/PcmStreamRecorderPlugin.swift` 中的 `reconfigureInputTap` 方法，采取**销毁重建**的策略，而不是重置。

**具体步骤：**
1. **停止并销毁旧引擎**：
   - 停止当前的 `audioEngine`。
   - 移除旧 `inputNode` 上的 Tap。
   - 将 `audioEngine` 和 `inputNode` 引用置为 `nil`，确保旧对象被释放。

2. **创建并配置新引擎**：
   - 实例化一个新的 `AVAudioEngine`。
   - 获取新的 `inputNode`。
   - 重新调用 `installTap`，在新节点上安装 Tap（这会自动适配当前的硬件格式）。

3. **启动新引擎**：
   - 调用 `start()` 启动新引擎。
   - 更新 `self.audioEngine` 和 `self.inputNode` 引用。

这种“彻底重建”的方法可以最大程度地规避 `Failed to initialize active nodes` (-10868) 错误，因为它完全避开了旧引擎可能存在的无效状态。