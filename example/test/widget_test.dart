// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:pcm_stream_recorder_example/main.dart';

void main() {
  testWidgets('Example renders controls', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());

    expect(find.text('PCM Stream Recorder Sample'), findsOneWidget);
    expect(find.text('开始录音'), findsOneWidget);
    expect(find.text('请求权限'), findsOneWidget);
  });
}
