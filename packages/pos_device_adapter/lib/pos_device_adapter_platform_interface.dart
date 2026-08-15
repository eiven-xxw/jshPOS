import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'src/device_contract.dart';
import 'src/method_channel_pos_device_adapter.dart';

abstract interface class PosDeviceGateway {
  Future<DeviceSnapshot> snapshot();
}

abstract class PosDeviceAdapterPlatform extends PlatformInterface {
  PosDeviceAdapterPlatform() : super(token: _token);

  static final Object _token = Object();
  static PosDeviceAdapterPlatform _instance = MethodChannelPosDeviceAdapter();

  static PosDeviceAdapterPlatform get instance => _instance;

  static set instance(PosDeviceAdapterPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<Map<Object?, Object?>> snapshot() {
    throw UnimplementedError('snapshot() has not been implemented.');
  }
}
