import 'pos_device_adapter_platform_interface.dart';
import 'src/device_contract.dart';

export 'pos_device_adapter_platform_interface.dart' show PosDeviceGateway;
export 'src/device_contract.dart';

final class PosDeviceAdapter implements PosDeviceGateway {
  const PosDeviceAdapter();

  @override
  Future<DeviceSnapshot> snapshot() =>
      PosDeviceAdapterPlatform.instance.snapshot().then(DeviceSnapshot.fromMap);
}
