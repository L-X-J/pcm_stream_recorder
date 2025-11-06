import 'dart:async';

import 'package:flutter/services.dart';

/// 轻量级原生录音插件
/// 提供 PCM 实时回调与 WAV 文件录制能力
class PcmStreamRecorder {
  static const MethodChannel _methodChannel = MethodChannel(
    'pcm_stream_recorder',
  );
  static const EventChannel _eventChannel = EventChannel(
    'pcm_stream_recorder/audio_stream',
  );

  Stream<Uint8List>? _audioStream;
  StreamSubscription<dynamic>? _audioSubscription;
  StreamSubscription<Uint8List>? _tempSubscription;
  StreamController<Uint8List>? _streamController;
  bool _enableLog = false;

  void _log(String message) {
    if (_enableLog) {
      // ignore: avoid_print
      print('[PcmStreamRecorder] $message');
    }
  }

  /// 检查录音权限
  /// 返回 true 表示已授予权限，false 表示未授予
  Future<bool> checkPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('checkPermission');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 请求录音权限
  /// 返回 true 表示权限已授予，false 表示权限被拒绝
  /// 注意：Android 平台需要使用 permission_handler 插件处理权限
  Future<bool> requestPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>(
        'requestPermission',
      );
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 开始录音
  /// [sampleRate] 采样率，默认 16000 Hz
  /// [channels] 声道数，1=单声道，2=立体声，默认 1
  /// [bufferSize] 缓冲区大小（帧数），默认 1600
  /// 返回 `Stream<Uint8List>` 用于接收 PCM 数据流
  Future<Stream<Uint8List>> start({
    int sampleRate = 16000,
    int channels = 1,
    int bufferSize = 1600,
    bool enableLog = false,
  }) async {
    try {
      _enableLog = enableLog;
      // 创建 StreamController 用于转发数据
      _streamController = StreamController<Uint8List>.broadcast();

      // 创建 EventChannel stream
      _audioStream = _eventChannel.receiveBroadcastStream().map<Uint8List>((
        dynamic event,
      ) {
        if (event is Uint8List) {
          return event;
        } else if (event is List<int>) {
          return Uint8List.fromList(event);
        } else {
          throw Exception('无效的音频数据格式');
        }
      });

      // 关键修复：先创建一个 subscription 来触发 iOS 的 onListen
      // receiveBroadcastStream() 创建 stream 不会立即触发 onListen，
      // 只有实际调用 listen() 才会触发。我们需要先触发 onListen，
      // 确保 iOS 端的 eventSink 已设置，然后再启动录音。
      // 使用 StreamController 转发数据，确保所有 listener 都能收到数据
      var receiveCount = 0;
      _tempSubscription = _audioStream!.listen(
        (data) {
          receiveCount++;
          // 调试日志：前几次接收时打印
          if (_enableLog && receiveCount <= 5) {
            _log('📥 Dart 端收到 PCM 数据 #$receiveCount，大小: ${data.length} bytes');
          }
          // 将数据转发到 StreamController
          if (!_streamController!.isClosed) {
            _streamController!.add(data);
          }
        },
        onError: (error) {
          if (_enableLog) {
            _log('❌ EventChannel 错误: $error');
          }
          // 转发错误
          if (!_streamController!.isClosed) {
            _streamController!.addError(error);
          }
        },
        onDone: () {
          if (_enableLog) {
            _log('✅ EventChannel stream 已完成');
          }
          // 转发完成事件
          if (!_streamController!.isClosed) {
            _streamController!.close();
          }
        },
        cancelOnError: false,
      );

      // 等待 onListen 被触发（iOS 端会立即同步调用 onListen 并设置 eventSink）
      // 给原生端一点时间完成 eventSink 的设置
      await Future.delayed(const Duration(milliseconds: 50));

      // 然后启动录音（此时 iOS 端的 eventSink 应该已经设置好了）
      final result = await _methodChannel.invokeMethod<bool>('start', {
        'sampleRate': sampleRate,
        'channels': channels,
        'bufferSize': bufferSize,
        'enableLog': enableLog,
      });

      if (result != true) {
        // 如果启动失败，清理资源
        await _tempSubscription?.cancel();
        _tempSubscription = null;
        await _streamController?.close();
        _streamController = null;
        throw Exception('启动录音失败');
      }

      // 返回 StreamController 的 stream，调用者可以安全地订阅
      return _streamController!.stream;
    } catch (e) {
      await _streamController?.close();
      _streamController = null;
      throw Exception('启动录音失败: $e');
    }
  }

  /// 停止录音
  Future<bool> stop() async {
    try {
      _audioSubscription?.cancel();
      _audioSubscription = null;
      _tempSubscription?.cancel();
      _tempSubscription = null;
      await _streamController?.close();
      _streamController = null;
      _audioStream = null;

      final result = await _methodChannel.invokeMethod<bool>('stop');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 暂停录音
  /// 注意：iOS 平台不支持真正的暂停，会停止数据采集
  Future<bool> pause() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('pause');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 恢复录音
  Future<bool> resume() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('resume');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 检查是否正在录音
  Future<bool> isRecording() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isRecording');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 释放资源
  Future<void> dispose() async {
    await stop();
  }
}
