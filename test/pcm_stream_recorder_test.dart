import 'package:flutter_test/flutter_test.dart';
import 'package:pcm_stream_recorder/pcm_stream_recorder_platform_interface.dart';
import 'package:pcm_stream_recorder/pcm_stream_recorder_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockPcmStreamRecorderPlatform
    with MockPlatformInterfaceMixin
    implements PcmStreamRecorderPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final PcmStreamRecorderPlatform initialPlatform = PcmStreamRecorderPlatform.instance;

  test('$MethodChannelPcmStreamRecorder is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelPcmStreamRecorder>());
  });
}
