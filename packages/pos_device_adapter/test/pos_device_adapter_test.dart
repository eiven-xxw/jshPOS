import 'package:flutter_test/flutter_test.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';
import 'package:pos_device_adapter/pos_device_adapter_platform_interface.dart';

void main() {
  final initialPlatform = PosDeviceAdapterPlatform.instance;

  tearDown(() => PosDeviceAdapterPlatform.instance = initialPlatform);

  test(
    'maps the versioned native snapshot without losing capabilities',
    () async {
      PosDeviceAdapterPlatform.instance = _FakePlatform();

      final snapshot = await const PosDeviceAdapter().snapshot();

      expect(snapshot.metadata.model, 'POS-01');
      expect(snapshot.metadata.androidSdk, 34);
      expect(
        snapshot.capabilities,
        contains(const DeviceCapability('vendor.example.custom_sensor')),
      );
    },
  );

  test('rejects an incompatible contract version', () {
    expect(
      () => DeviceSnapshot.fromMap(const {
        'contractVersion': '2.0',
        'metadata': <Object?, Object?>{},
        'capabilities': <Object?>[],
      }),
      throwsFormatException,
    );
  });
}

class _FakePlatform extends PosDeviceAdapterPlatform
    with MockPlatformInterfaceMixin {
  @override
  Future<Map<Object?, Object?>> snapshot() async => {
    'contractVersion': '1.0',
    'metadata': <Object?, Object?>{
      'manufacturer': 'ACME',
      'model': 'POS-01',
      'androidRelease': '14',
      'androidSdk': 34,
      'adapterVersion': '0.1.0',
    },
    'capabilities': <Object?>['vendor.example.custom_sensor'],
  };
}
