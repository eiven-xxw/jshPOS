/// Semantic version of the Flutter-to-Android device contract.
const deviceAdapterContractVersion = '1.0';

/// Extensible capability identifier. Unknown vendor capabilities remain valid.
final class DeviceCapability {
  const DeviceCapability(this.id);

  final String id;

  static const receiptPrinter = DeviceCapability('receipt_printer');
  static const barcodeScanner = DeviceCapability('barcode_scanner');
  static const weighingScale = DeviceCapability('weighing_scale');
  static const cashDrawer = DeviceCapability('cash_drawer');
  static const customerDisplay = DeviceCapability('customer_display');
  static const nfcReader = DeviceCapability('nfc_reader');
  static const serialPort = DeviceCapability('serial_port');
  static const gpio = DeviceCapability('gpio');

  @override
  bool operator ==(Object other) => other is DeviceCapability && other.id == id;

  @override
  int get hashCode => id.hashCode;

  @override
  String toString() => id;
}

final class DeviceMetadata {
  const DeviceMetadata({
    required this.manufacturer,
    required this.model,
    required this.androidRelease,
    required this.androidSdk,
    required this.adapterVersion,
  });

  factory DeviceMetadata.fromMap(Map<Object?, Object?> value) {
    return DeviceMetadata(
      manufacturer: _requiredString(value, 'manufacturer'),
      model: _requiredString(value, 'model'),
      androidRelease: _requiredString(value, 'androidRelease'),
      androidSdk: _requiredInt(value, 'androidSdk'),
      adapterVersion: _requiredString(value, 'adapterVersion'),
    );
  }

  final String manufacturer;
  final String model;
  final String androidRelease;
  final int androidSdk;
  final String adapterVersion;
}

final class DeviceSnapshot {
  const DeviceSnapshot({required this.metadata, required this.capabilities});

  factory DeviceSnapshot.fromMap(Map<Object?, Object?> value) {
    final contractVersion = _requiredString(value, 'contractVersion');
    if (contractVersion != deviceAdapterContractVersion) {
      throw FormatException(
        'Unsupported device contract $contractVersion; '
        'expected $deviceAdapterContractVersion.',
      );
    }
    final metadata = value['metadata'];
    final capabilities = value['capabilities'];
    if (metadata is! Map<Object?, Object?> || capabilities is! List<Object?>) {
      throw const FormatException('Malformed device adapter snapshot.');
    }
    return DeviceSnapshot(
      metadata: DeviceMetadata.fromMap(metadata),
      capabilities: capabilities
          .whereType<String>()
          .map(DeviceCapability.new)
          .toSet(),
    );
  }

  final DeviceMetadata metadata;
  final Set<DeviceCapability> capabilities;
}

String _requiredString(Map<Object?, Object?> value, String key) {
  final result = value[key];
  if (result is String && result.isNotEmpty) {
    return result;
  }
  throw FormatException(
    'Device adapter field $key must be a non-empty string.',
  );
}

int _requiredInt(Map<Object?, Object?> value, String key) {
  final result = value[key];
  if (result is int) {
    return result;
  }
  throw FormatException('Device adapter field $key must be an integer.');
}
