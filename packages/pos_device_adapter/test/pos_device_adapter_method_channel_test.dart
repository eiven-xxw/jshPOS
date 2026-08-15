import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_device_adapter/src/method_channel_pos_device_adapter.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel(deviceAdapterChannelName);
  final platform = MethodChannelPosDeviceAdapter();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getSnapshot');
          return <Object?, Object?>{
            'contractVersion': '1.0',
            'metadata': <Object?, Object?>{
              'manufacturer': 'ACME',
              'model': 'POS-01',
              'androidRelease': '14',
              'androidSdk': 34,
              'adapterVersion': '0.1.0',
            },
            'capabilities': <Object?>['receipt_printer'],
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('uses the versioned private method channel', () async {
    final snapshot = await platform.snapshot();
    expect(snapshot['contractVersion'], '1.0');
  });
}
