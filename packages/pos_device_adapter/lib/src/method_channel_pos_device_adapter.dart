import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../pos_device_adapter_platform_interface.dart';

const deviceAdapterChannelName = 'com.jingshanghui.pos/device_adapter/v1';

class MethodChannelPosDeviceAdapter extends PosDeviceAdapterPlatform {
  MethodChannelPosDeviceAdapter({MethodChannel? methodChannel})
    : methodChannel =
          methodChannel ?? const MethodChannel(deviceAdapterChannelName);

  @visibleForTesting
  final MethodChannel methodChannel;

  @override
  Future<Map<Object?, Object?>> snapshot() async {
    final result = await methodChannel.invokeMethod<Map<Object?, Object?>>(
      'getSnapshot',
    );
    if (result == null) {
      throw PlatformException(
        code: 'EMPTY_DEVICE_SNAPSHOT',
        message: 'The Android device adapter returned no snapshot.',
      );
    }
    return result;
  }
}
