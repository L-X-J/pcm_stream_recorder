import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'pcm_stream_recorder_platform_interface.dart';

/// An implementation of [PcmStreamRecorderPlatform] that uses method channels.
class MethodChannelPcmStreamRecorder extends PcmStreamRecorderPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('pcm_stream_recorder');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
