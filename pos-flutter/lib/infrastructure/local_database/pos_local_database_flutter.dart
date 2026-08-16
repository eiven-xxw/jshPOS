import 'package:path_provider/path_provider.dart';

import '../../features/checkout/domain/checkout_models.dart';
import 'pos_local_database.dart';

/// Flutter platform wrapper. The database core remains usable by standalone
/// process-termination fixtures without importing dart:ui.
Future<PosLocalDatabase> openDefaultPosLocalDatabase(
  TrustedDeviceBinding binding, {
  FailureInjector? failureInjector,
}) async {
  final directory = await getApplicationSupportDirectory();
  return PosLocalDatabase.openPath(
    '${directory.path}/jshpos_gate2.sqlite3',
    binding,
    failureInjector: failureInjector,
  );
}
