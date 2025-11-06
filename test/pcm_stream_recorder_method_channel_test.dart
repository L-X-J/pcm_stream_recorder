import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pcm_stream_recorder/pcm_stream_recorder_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  MethodChannelPcmStreamRecorder platform = MethodChannelPcmStreamRecorder();
  const MethodChannel channel = MethodChannel('pcm_stream_recorder');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        return '42';
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion', () async {
    expect(await platform.getPlatformVersion(), '42');
  });
}
