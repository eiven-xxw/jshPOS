import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const _binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);
final _now = DateTime.utc(2026, 8, 16, 9);

Future<void> main(List<String> arguments) async {
  final mode = arguments.isEmpty ? 'driver' : arguments.first;
  if (mode == 'driver') {
    await _runDriver();
    return;
  }
  if (arguments.length != 2) {
    stderr.writeln('mode and database path are required');
    exitCode = 64;
    return;
  }
  switch (mode) {
    case 'kill-before-commit':
      _killBeforeCommit(arguments[1]);
    case 'kill-after-commit':
      _killAfterCommit(arguments[1]);
    case 'verify-rollback':
      _verify(arguments[1], committed: false);
    case 'verify-durable':
      _verify(arguments[1], committed: true);
    default:
      throw ArgumentError.value(mode, 'mode');
  }
}

Future<void> _runDriver() async {
  final directory = Directory.systemTemp.createTempSync('jshpos-gate2-kill-');
  try {
    final source = File.fromUri(Platform.script).path;
    final rollbackPath =
        '${directory.path}${Platform.pathSeparator}rollback.sqlite3';
    final durablePath =
        '${directory.path}${Platform.pathSeparator}durable.sqlite3';
    final before = await Process.run(Platform.resolvedExecutable, [
      'run',
      source,
      'kill-before-commit',
      rollbackPath,
    ]);
    if (before.exitCode == 0) {
      throw StateError('kill-before-commit worker exited successfully');
    }
    await _expectSuccess(source, 'verify-rollback', rollbackPath);
    final after = await Process.run(Platform.resolvedExecutable, [
      'run',
      source,
      'kill-after-commit',
      durablePath,
    ]);
    if (after.exitCode == 0) {
      throw StateError('kill-after-commit worker exited successfully');
    }
    await _expectSuccess(source, 'verify-durable', durablePath);
    stdout.writeln(
      jsonEncode({
        'fixture': 'gate2-process-termination',
        'syntheticOnly': true,
        'killBeforeCommit': 'ROLLED_BACK',
        'killAfterCommit': 'DURABLE',
        'quickCheck': 'ok',
      }),
    );
  } finally {
    directory.deleteSync(recursive: true);
  }
}

Future<void> _expectSuccess(String source, String mode, String path) async {
  final result = await Process.run(Platform.resolvedExecutable, [
    'run',
    source,
    mode,
    path,
  ]);
  if (result.exitCode != 0) {
    throw StateError('$mode failed: ${result.stdout}\n${result.stderr}');
  }
}

Never _terminate(int code) {
  Process.killPid(pid, ProcessSignal.sigkill);
  exit(code);
}

void _killBeforeCommit(String path) {
  var armed = false;
  final database = PosLocalDatabase.openPath(
    path,
    _binding,
    failureInjector: (checkpoint) {
      if (armed && checkpoint == 'outbox.appended') {
        _terminate(91);
      }
    },
  );
  final service = _service(database);
  final shift = _open(service);
  armed = true;
  service.completeCashSale(_sale(shift.shiftId));
  throw StateError('termination checkpoint was not reached');
}

void _killAfterCommit(String path) {
  final database = PosLocalDatabase.openPath(path, _binding);
  final service = _service(database);
  final shift = _open(service);
  service.completeCashSale(_sale(shift.shiftId));
  _terminate(92);
}

void _verify(String path, {required bool committed}) {
  final database = PosLocalDatabase.openPath(path, _binding);
  try {
    final expected = committed ? 1 : 0;
    for (final table in [
      'local_order',
      'local_order_line',
      'local_cash_payment',
      'local_cash_ledger',
      'local_print_job',
    ]) {
      final count = database.database
          .select('SELECT COUNT(*) value FROM $table')
          .single['value'];
      if (count != expected) {
        throw StateError('$table expected $expected, got $count');
      }
    }
    final quick = database.database
        .select('PRAGMA quick_check')
        .single
        .values
        .first;
    if (quick != 'ok') throw StateError('quick_check failed: $quick');
  } finally {
    database.close();
  }
}

CheckoutLocalService _service(PosLocalDatabase database) =>
    CheckoutLocalService(
      localDatabase: database,
      ulids: UlidGenerator(random: Random(20260816), now: () => _now),
      shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 0),
    );

ShiftResult _open(CheckoutLocalService service) => service.openShift(
  commandId: '01K2A000000000000000000021',
  idempotencyKey: 'open-shift-key-0001',
  businessDate: '2026-08-16',
  openingCashMinor: 0,
  configVersion: 1,
  occurredAt: _now,
);

CashSaleCommand _sale(String shiftId) => CashSaleCommand(
  commandId: '01K2A000000000000000000051',
  idempotencyKey: 'cash-order-key-0001',
  basket: Basket(
    orderId: '01K2A000000000000000000031',
    localOrderNo: 'A-T1-000001',
    lines: [
      BasketLine(
        lineId: '01K2A000000000000000000041',
        lineNo: 1,
        quote: PriceQuote.fromVerifiedPackage(
          skuId: '701',
          skuCode: 'A-SKU-001',
          productName: 'Synthetic Water',
          unitId: '301',
          unitCode: 'PCS',
          unitPriceMinor: 1299,
          priceSource: 'TENANT_BASE',
          barcode: '001234',
        ),
        quantity: '1',
      ),
    ],
  ),
  shiftId: shiftId,
  businessDate: '2026-08-16',
  catalogVersion: 1,
  priceVersion: 1,
  industryTemplateVersion: 'CONVENIENCE.1',
  tenderedAmountMinor: 2000,
  occurredAt: _now,
);
