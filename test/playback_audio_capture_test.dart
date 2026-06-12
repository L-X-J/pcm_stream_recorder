import 'package:flutter_test/flutter_test.dart';
import 'package:pcm_stream_recorder/playback_audio_capture.dart';

void main() {
  group('PlaybackAudioPower.fromMap', () {
    test('解析完整字段', () {
      final power = PlaybackAudioPower.fromMap(<String, Object?>{
        'source': 'android_playback_capture',
        'rms': 0.25,
        'db': -12.04,
        'linearPower': 0.8,
        'duty': 80,
        'sampleRate': 48000,
        'channels': 2,
        'capturing': true,
        'message': 'capturing',
      });

      expect(power.source, 'android_playback_capture');
      expect(power.rms, 0.25);
      expect(power.db, -12.04);
      expect(power.linearPower, 0.8);
      expect(power.duty, 80);
      expect(power.sampleRate, 48000);
      expect(power.channels, 2);
      expect(power.capturing, isTrue);
      expect(power.message, 'capturing');
    });

    test('缺字段和类型异常时使用安全默认值', () {
      final power = PlaybackAudioPower.fromMap(<String, Object?>{
        'source': 12,
        'rms': 'bad',
        'db': double.nan,
        'linearPower': 3,
        'duty': -10,
        'sampleRate': '16000',
        'channels': null,
        'capturing': 'true',
        'message': false,
      });

      expect(power.source, '');
      expect(power.rms, 0);
      expect(power.db, -160);
      expect(power.linearPower, 1);
      expect(power.duty, 0);
      expect(power.sampleRate, 0);
      expect(power.channels, 0);
      expect(power.capturing, isFalse);
      expect(power.message, '');
    });

    test('null map 可以解析为空闲状态', () {
      final power = PlaybackAudioPower.fromMap(null);

      expect(power.source, '');
      expect(power.rms, 0);
      expect(power.db, -160);
      expect(power.linearPower, 0);
      expect(power.duty, 0);
      expect(power.sampleRate, 0);
      expect(power.channels, 0);
      expect(power.capturing, isFalse);
      expect(power.message, '');
    });
  });
}
