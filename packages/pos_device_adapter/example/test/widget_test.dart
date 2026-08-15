import 'package:flutter_test/flutter_test.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';
import 'package:pos_device_adapter_example/main.dart';

void main() {
  testWidgets('renders a deterministic adapter snapshot', (tester) async {
    await tester.pumpWidget(const AdapterExample(gateway: _FakeGateway()));
    await tester.pumpAndSettle();

    expect(find.text('ACME POS-01'), findsOneWidget);
  });
}

class _FakeGateway implements PosDeviceGateway {
  const _FakeGateway();

  @override
  Future<DeviceSnapshot> snapshot() async => const DeviceSnapshot(
    metadata: DeviceMetadata(
      manufacturer: 'ACME',
      model: 'POS-01',
      androidRelease: '14',
      androidSdk: 34,
      adapterVersion: '0.1.0',
    ),
    capabilities: {},
  );
}
