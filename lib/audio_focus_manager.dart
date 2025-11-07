import 'package:flutter/services.dart';

/// 音频焦点管理工具类
/// 用于管理音频焦点，特别是在从 WebRTC 等通信场景返回后恢复音频播放
/// 这是一个独立的工具类，不依赖于 PcmStreamRecorder
class AudioFocusManager {
  static const MethodChannel _methodChannel = MethodChannel(
    'audio_focus_manager',
  );

  /// 重置音频焦点
  /// 用于在从其他页面（如 WebRTC 聊天页面）返回后，立即恢复音频播放的正常状态
  /// 会将音频会话/音频焦点重置为适合媒体播放的模式
  /// 
  /// 返回 true 表示重置成功，false 表示失败
  /// 
  /// 示例：
  /// ```dart
  /// final manager = AudioFocusManager();
  /// final success = await manager.resetAudioFocus();
  /// if (success) {
  ///   print('音频焦点已重置，可以正常播放视频了');
  /// }
  /// ```
  static Future<bool> resetAudioFocus() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('resetAudioFocus');
      return result ?? false;
    } catch (e) {
      print('[AudioFocusManager] 重置音频焦点失败: $e');
      return false;
    }
  }
}

