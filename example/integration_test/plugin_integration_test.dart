// This is a basic Flutter integration test.
//
// Since integration tests run in a full Flutter application, they can interact
// with the host side of a plugin implementation, unlike Dart unit tests.
//
// For more information about Flutter integration tests, please see
// https://flutter.dev/to/integration-testing

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:pcm_stream_recorder/pcm_stream_recorder.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('PcmStreamRecorder can be instantiated', (
    WidgetTester tester,
  ) async {
    final recorder = PcmStreamRecorder();
    expect(recorder, isNotNull);
  });
}
