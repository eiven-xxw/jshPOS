import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/app/jshpos_app.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  testWidgets('renders the Gate 2 locked shell and device boundary status', (
    tester,
  ) async {
    const gateway = _FakeDeviceGateway();

    await tester.pumpWidget(const JshposApp(deviceGateway: gateway));
    await tester.pumpAndSettle();

    expect(find.text('鲸熵汇收银系统'), findsOneWidget);
    expect(find.text('T2 Gate 2 本地现金闭环'), findsOneWidget);
    expect(find.textContaining('等待可信设备激活'), findsOneWidget);
    expect(find.textContaining('ACME POS-01'), findsOneWidget);
  });
}

class _FakeDeviceGateway implements PosDeviceGateway {
  const _FakeDeviceGateway();

  @override
  Future<DeviceSnapshot> snapshot() async => DeviceSnapshot(
    metadata: const DeviceMetadata(
      manufacturer: 'ACME',
      model: 'POS-01',
      androidRelease: '14',
      androidSdk: 34,
      adapterVersion: '0.1.0',
    ),
    capabilities: {DeviceCapability.receiptPrinter},
  );
}
