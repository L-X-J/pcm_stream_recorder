import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'pcm_stream_recorder_method_channel.dart';

abstract class PcmStreamRecorderPlatform extends PlatformInterface {
  /// Constructs a PcmStreamRecorderPlatform.
  PcmStreamRecorderPlatform() : super(token: _token);

  static final Object _token = Object();

  static PcmStreamRecorderPlatform _instance = MethodChannelPcmStreamRecorder();

  /// The default instance of [PcmStreamRecorderPlatform] to use.
  ///
  /// Defaults to [MethodChannelPcmStreamRecorder].
  static PcmStreamRecorderPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [PcmStreamRecorderPlatform] when
  /// they register themselves.
  static set instance(PcmStreamRecorderPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
