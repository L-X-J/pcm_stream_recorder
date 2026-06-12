import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/services.dart';

/// 当前播放音频捕获的实时功率数据。
///
/// 该模型只描述聚合后的功率指标，不暴露原始 PCM。字段均带安全默认值，
/// 让原生端在异常、停止或系统限制场景下也可以用同一结构回传状态。
class PlaybackAudioPower {
  /// 创建播放音频功率数据。
  const PlaybackAudioPower({
    required this.source,
    required this.rms,
    required this.db,
    required this.linearPower,
    required this.duty,
    required this.sampleRate,
    required this.channels,
    required this.capturing,
    required this.message,
  });

  /// 原生捕获来源，例如 `android_playback_capture` 或 `ios_replaykit_audio_app`。
  final String source;

  /// 归一化 RMS，范围通常为 0 到 1。
  final double rms;

  /// 分贝值，使用 `20 * log10(max(rms, 1e-8))` 计算。
  final double db;

  /// 映射到 0 到 1 的线性能量，用于 UI 或业务阈值。
  final double linearPower;

  /// 映射到 0 到 100 的占空比式功率值。
  final double duty;

  /// 原生捕获使用的采样率。
  final int sampleRate;

  /// 原生捕获使用的声道数。
  final int channels;

  /// 当前是否处于捕获状态。
  final bool capturing;

  /// 原生端状态说明或失败原因。
  final String message;

  /// 从平台通道 Map 安全解析功率数据。
  ///
  /// 原生端或测试桩可能传入缺字段、空值或非预期数字类型。该方法会尽量
  /// 保留可用信息，并用默认值补齐缺失字段，避免状态流因为单帧脏数据中断。
  static PlaybackAudioPower fromMap(Map<dynamic, dynamic>? map) {
    final data = map ?? const <dynamic, dynamic>{};
    return PlaybackAudioPower(
      source: _stringValue(data['source']),
      rms: _doubleValue(data['rms']),
      db: _doubleValue(data['db'], defaultValue: -160),
      linearPower: _clamp01(_doubleValue(data['linearPower'])),
      duty: _doubleValue(data['duty']).clamp(0, 100).toDouble(),
      sampleRate: math.max(0, _intValue(data['sampleRate'])),
      channels: math.max(0, _intValue(data['channels'])),
      capturing: _boolValue(data['capturing']),
      message: _stringValue(data['message']),
    );
  }

  static String _stringValue(Object? value) {
    return value is String ? value : '';
  }

  static double _doubleValue(Object? value, {double defaultValue = 0}) {
    if (value is num) {
      final doubleValue = value.toDouble();
      return doubleValue.isFinite ? doubleValue : defaultValue;
    }
    return defaultValue;
  }

  static int _intValue(Object? value) {
    return value is num ? value.toInt() : 0;
  }

  static bool _boolValue(Object? value) {
    return value is bool ? value : false;
  }

  static double _clamp01(double value) {
    return value.clamp(0, 1).toDouble();
  }
}

/// 当前播放音频捕获入口。
///
/// 该 API 使用独立 MethodChannel 和 EventChannel，与麦克风录音的 PCM 通道
/// 完全隔离，避免 NSFW 检测等播放音频功率采样影响既有 ASR、IM 录音或 AEC 链路。
class PlaybackAudioCapture {
  static const MethodChannel _methodChannel = MethodChannel(
    'pcm_stream_recorder/playback_audio',
  );
  static const EventChannel _eventChannel = EventChannel(
    'pcm_stream_recorder/playback_audio_power_stream',
  );

  static Stream<PlaybackAudioPower>? _powerStream;

  /// 实时播放音频功率流。
  ///
  /// 原生端只推送聚合后的功率数据，不推送视频画面或原始 PCM。调用
  /// [startPowerCapture] 前订阅该流可以确保首帧状态不会丢失。
  static Stream<PlaybackAudioPower> get powerStream {
    return _powerStream ??=
        _eventChannel.receiveBroadcastStream().map((dynamic event) {
      if (event is Map<dynamic, dynamic>) {
        return PlaybackAudioPower.fromMap(event);
      }
      return const PlaybackAudioPower(
        source: '',
        rms: 0,
        db: -160,
        linearPower: 0,
        duty: 0,
        sampleRate: 0,
        channels: 0,
        capturing: false,
        message: '无效的播放音频功率数据',
      );
    });
  }

  /// 检查当前平台是否支持播放音频捕获。
  ///
  /// Android 需要 API 29+；iOS 需要 ReplayKit capture 可用。该方法只做能力检查，
  /// 不触发系统授权弹窗。
  static Future<bool> isSupported() async {
    try {
      return await _methodChannel.invokeMethod<bool>('isSupported') ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 开始捕获当前播放音频并输出功率流。
  ///
  /// Android 会触发 MediaProjection 授权弹窗；iOS 会启动 ReplayKit capture。
  /// [frameMs] 控制功率聚合窗口，过小会增加通道压力，过大则降低检测响应速度。
  /// 返回 `true` 表示原生捕获已启动或授权流程已成功完成；失败时原生端会抛出
  /// 带明确 code/message 的 [PlatformException]。
  static Future<bool> startPowerCapture({
    int sampleRate = 16000,
    int channels = 1,
    int frameMs = 100,
    bool enableLog = false,
    bool debugNativeTone = false,
  }) async {
    final result = await _methodChannel.invokeMethod<bool>(
      'startPowerCapture',
      <String, Object>{
        'sampleRate': sampleRate,
        'channels': channels,
        'frameMs': frameMs,
        'enableLog': enableLog,
        'debugNativeTone': debugNativeTone,
      },
    );
    return result ?? false;
  }

  /// 停止播放音频捕获并释放原生资源。
  static Future<bool> stop() async {
    try {
      return await _methodChannel.invokeMethod<bool>('stop') ?? false;
    } on PlatformException {
      return false;
    }
  }
}
