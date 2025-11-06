import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:pcm_stream_recorder/pcm_stream_recorder.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PCM Stream Recorder Sample',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const RecorderExamplePage(),
    );
  }
}

class RecorderExamplePage extends StatefulWidget {
  const RecorderExamplePage({super.key});

  @override
  State<RecorderExamplePage> createState() => _RecorderExamplePageState();
}

class _RecorderExamplePageState extends State<RecorderExamplePage> {
  final PcmStreamRecorder _recorder = PcmStreamRecorder();
  final AudioPlayer _audioPlayer = AudioPlayer();
  final BytesBuilder _pcmBuffer = BytesBuilder(copy: false);

  StreamSubscription<Uint8List>? _audioSubscription;
  StreamSubscription<PlayerState>? _playerStateSubscription;

  bool _hasPermission = false;
  bool _isRecording = false;
  bool _isPaused = false;
  bool _isPlaying = false;
  bool _isSaving = false;
  double _audioLevel = 0;
  String? _lastFilePath;
  String? _errorMessage;

  static const int _sampleRate = 16000;
  static const int _channels = 1;
  static const int _bitsPerSample = 16;

  @override
  void initState() {
    super.initState();
    _initPermissions();
    _playerStateSubscription = _audioPlayer.onPlayerStateChanged.listen((
      PlayerState state,
    ) {
      setState(() {
        _isPlaying = state == PlayerState.playing;
      });
    });
  }

  @override
  void dispose() {
    _audioSubscription?.cancel();
    _playerStateSubscription?.cancel();
    _audioPlayer.dispose();
    _recorder.dispose();
    super.dispose();
  }

  Future<void> _initPermissions() async {
    final status = await Permission.microphone.status;
    setState(() {
      _hasPermission = status.isGranted;
    });
  }

  Future<bool> _ensurePermission() async {
    if (_hasPermission) {
      return true;
    }
    final status = await Permission.microphone.request();
    final granted = status.isGranted;
    setState(() {
      _hasPermission = granted;
    });
    if (!granted) {
      _showSnackBar('需要麦克风权限才能录音');
    }
    return granted;
  }

  Future<void> _startRecording() async {
    if (_isRecording || _isSaving) {
      return;
    }
    final granted = await _ensurePermission();
    if (!granted) {
      return;
    }

    try {
      _pcmBuffer.clear();
      _errorMessage = null;

      final stream = await _recorder.start(
        sampleRate: _sampleRate,
        channels: _channels,
        enableLog: false,
      );

      await _audioSubscription?.cancel();
      _audioSubscription = stream.listen(
        _handleAudioData,
        onError: (Object error) {
          setState(() {
            _errorMessage = '录音数据流出错: $error';
          });
        },
        onDone: () {
          setState(() {
            _audioLevel = 0;
          });
        },
        cancelOnError: false,
      );

      setState(() {
        _isRecording = true;
        _isPaused = false;
        _audioLevel = 0;
      });
      _showSnackBar('录音已开始');
    } catch (e) {
      setState(() {
        _errorMessage = '启动录音失败: $e';
      });
      _showSnackBar('启动录音失败');
    }
  }

  Future<void> _pauseRecording() async {
    if (!_isRecording || _isPaused) {
      return;
    }
    final ok = await _recorder.pause();
    if (ok) {
      setState(() {
        _isPaused = true;
      });
    } else {
      _showSnackBar('暂停录音失败');
    }
  }

  Future<void> _resumeRecording() async {
    if (!_isRecording || !_isPaused) {
      return;
    }
    final ok = await _recorder.resume();
    if (ok) {
      setState(() {
        _isPaused = false;
      });
    } else {
      _showSnackBar('恢复录音失败');
    }
  }

  Future<void> _stopRecording() async {
    if (!_isRecording || _isSaving) {
      return;
    }
    setState(() {
      _isSaving = true;
    });
    await _audioSubscription?.cancel();
    _audioSubscription = null;
    final stopped = await _recorder.stop();
    if (!stopped) {
      setState(() {
        _isSaving = false;
      });
      _showSnackBar('停止录音失败');
      return;
    }

    final pcmBytes = _pcmBuffer.takeBytes();
    _pcmBuffer.clear();
    String? filePath;
    if (pcmBytes.isNotEmpty) {
      try {
        filePath = await _writeWavFile(pcmBytes);
        _showSnackBar('录音已保存');
      } catch (e) {
        setState(() {
          _errorMessage = '保存录音失败: $e';
        });
        _showSnackBar('保存录音失败');
      }
    } else {
      _showSnackBar('未捕获到有效的录音数据');
    }

    setState(() {
      _isRecording = false;
      _isPaused = false;
      _isSaving = false;
      _audioLevel = 0;
      _lastFilePath = filePath;
    });
  }

  Future<void> _playRecording() async {
    final path = _lastFilePath;
    if (path == null) {
      _showSnackBar('请先完成一次录音');
      return;
    }
    if (_isPlaying) {
      await _audioPlayer.stop();
    }
    try {
      await _audioPlayer.stop();
      await _audioPlayer.play(DeviceFileSource(path));
      _showSnackBar('开始播放');
    } catch (e) {
      _showSnackBar('播放失败: $e');
    }
  }

  void _handleAudioData(Uint8List chunk) {
    if (chunk.isEmpty) {
      return;
    }
    _pcmBuffer.add(chunk);

    final sampleCount = chunk.lengthInBytes ~/ 2;
    if (sampleCount == 0) {
      return;
    }

    final samples = Int16List.view(
      chunk.buffer,
      chunk.offsetInBytes,
      sampleCount,
    );

    int maxSample = 0;
    for (final value in samples) {
      final absValue = value.abs();
      if (absValue > maxSample) {
        maxSample = absValue;
      }
    }
    final level = maxSample / 32768.0;
    setState(() {
      _audioLevel = level.clamp(0.0, 1.0).toDouble();
    });
  }

  Future<String> _writeWavFile(Uint8List pcmBytes) async {
    final directory = await getApplicationDocumentsDirectory();
    final file = File(
      '${directory.path}/recording_${DateTime.now().millisecondsSinceEpoch}.wav',
    );
    final wavBytes = _buildWavFile(pcmBytes);
    await file.writeAsBytes(wavBytes, flush: true);
    return file.path;
  }

  Uint8List _buildWavFile(Uint8List pcmBytes) {
    final byteRate = _sampleRate * _channels * (_bitsPerSample ~/ 8);
    final blockAlign = _channels * (_bitsPerSample ~/ 8);
    final dataLength = pcmBytes.lengthInBytes;
    final totalLength = dataLength + 36;

    final header = ByteData(44);
    header.setUint32(0, 0x46464952, Endian.little); // RIFF
    header.setUint32(4, totalLength, Endian.little);
    header.setUint32(8, 0x45564157, Endian.little); // WAVE
    header.setUint32(12, 0x20746d66, Endian.little); // fmt
    header.setUint32(16, 16, Endian.little); // Subchunk1Size
    header.setUint16(20, 1, Endian.little); // PCM format
    header.setUint16(22, _channels, Endian.little);
    header.setUint32(24, _sampleRate, Endian.little);
    header.setUint32(28, byteRate, Endian.little);
    header.setUint16(32, blockAlign, Endian.little);
    header.setUint16(34, _bitsPerSample, Endian.little);
    header.setUint32(36, 0x61746164, Endian.little); // data
    header.setUint32(40, dataLength, Endian.little);

    final builder = BytesBuilder(copy: false)
      ..add(header.buffer.asUint8List())
      ..add(pcmBytes);
    return builder.toBytes();
  }

  void _showSnackBar(String message) {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('PCM Stream Recorder Sample')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _hasPermission ? Icons.check_circle : Icons.mic_off,
                  color: _hasPermission ? Colors.green : Colors.red,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _hasPermission ? '麦克风权限已授予' : '麦克风权限未授予',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                ElevatedButton(
                  onPressed: _ensurePermission,
                  child: const Text('请求权限'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            Text('当前状态', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _isRecording ? (_isPaused ? '已暂停录音' : '正在录音') : '未录音',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 16),
                    LinearProgressIndicator(
                      value: _audioLevel,
                      minHeight: 10,
                      backgroundColor: Colors.grey.shade300,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        _isRecording ? Colors.red : Colors.blue,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text('音量等级: ${_audioLevel.toStringAsFixed(2)}'),
                    if (_errorMessage != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        _errorMessage!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                ElevatedButton.icon(
                  onPressed: _isRecording ? null : _startRecording,
                  icon: const Icon(Icons.fiber_manual_record),
                  label: const Text('开始录音'),
                ),
                ElevatedButton.icon(
                  onPressed: _isRecording ? _stopRecording : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('停止'),
                ),
                ElevatedButton.icon(
                  onPressed: _isRecording && !_isPaused
                      ? _pauseRecording
                      : null,
                  icon: const Icon(Icons.pause),
                  label: const Text('暂停'),
                ),
                ElevatedButton.icon(
                  onPressed: _isRecording && _isPaused
                      ? _resumeRecording
                      : null,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('恢复'),
                ),
                ElevatedButton.icon(
                  onPressed: !_isRecording && _lastFilePath != null
                      ? _playRecording
                      : null,
                  icon: Icon(_isPlaying ? Icons.stop : Icons.play_circle),
                  label: Text(_isPlaying ? '停止播放' : '播放录音'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            Text('最近一次录音文件', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                border: Border.all(color: Colors.grey.shade300),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                _lastFilePath ?? '暂无录音文件',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
            const SizedBox(height: 24),
            const Text('提示：iOS 平台暂不支持真正的“暂停录音”，调用暂停会停止当前采集。'),
          ],
        ),
      ),
    );
  }
}
