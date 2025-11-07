import Flutter
import AVFoundation

/// 独立的音频焦点管理插件
/// 用于管理音频焦点，特别是在从 WebRTC 等通信场景返回后恢复音频播放
public class AudioFocusManagerPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let methodChannel = FlutterMethodChannel(
      name: "audio_focus_manager",
      binaryMessenger: registrar.messenger()
    )
    let instance = AudioFocusManagerPlugin()
    registrar.addMethodCallDelegate(instance, channel: methodChannel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "resetAudioFocus":
      resetAudioFocus(result: result)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  /// 重置音频焦点
  /// 用于在从其他页面（如 WebRTC 聊天页面）返回后，立即恢复音频播放的正常状态
  /// 将音频会话重置为适合媒体播放的模式
  private func resetAudioFocus(result: @escaping FlutterResult) {
    let audioSession = AVAudioSession.sharedInstance()
    
    do {
      // 1. 先停用音频会话，通知其他应用可以恢复音频
      // 这个操作会立即生效
      try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
      
      // 2. 立即设置为播放模式，适合视频播放等媒体播放场景
      // 使用 .mixWithOthers 选项，允许与其他音频混合播放
      // 这个操作会立即生效，不需要等待
      try audioSession.setCategory(
        .playback,
        mode: .default,
        options: [.mixWithOthers]
      )
      
      // 3. 清除输出路由覆盖，让系统自动选择正确的输出设备
      // 这个操作会立即生效
      try audioSession.overrideOutputAudioPort(.none)
      
      // 4. 重新激活音频会话
      // 这个操作会立即生效，音频路由会立即切换
      try audioSession.setActive(true)
      
      print("[AudioFocusManager] ✅ 已重置音频焦点为播放模式，立即生效")
      result(true)
    } catch {
      print("[AudioFocusManager] ❌ 重置音频焦点失败: \(error.localizedDescription)")
      result(FlutterError(
        code: "RESET_AUDIO_FOCUS_FAILED",
        message: "重置音频焦点失败: \(error.localizedDescription)",
        details: error.localizedDescription
      ))
    }
  }
}

