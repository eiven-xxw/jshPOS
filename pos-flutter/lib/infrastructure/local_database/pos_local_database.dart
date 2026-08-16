import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import '../../features/checkout/domain/checkout_models.dart';
import 'gate2_schema.dart';

typedef FailureInjector = void Function(String checkpoint);

final class PosLocalDatabase {
  PosLocalDatabase._(this.database, this.binding, this._failureInjector);

  factory PosLocalDatabase.inMemory(
    TrustedDeviceBinding binding, {
    FailureInjector? failureInjector,
  }) {
    final database = sqlite3.openInMemory();
    final result = PosLocalDatabase._(database, binding, failureInjector);
    try {
      result._initialize();
      return result;
    } catch (_) {
      database.close();
      rethrow;
    }
  }

  factory PosLocalDatabase.openPath(
    String path,
    TrustedDeviceBinding binding, {
    FailureInjector? failureInjector,
  }) {
    final database = sqlite3.open(path);
    final result = PosLocalDatabase._(database, binding, failureInjector);
    try {
      result._initialize();
      return result;
    } catch (_) {
      database.close();
      rethrow;
    }
  }

  final Database database;
  final TrustedDeviceBinding binding;
  final FailureInjector? _failureInjector;

  void _initialize() {
    binding.validate();
    database.execute('PRAGMA foreign_keys=ON');
    database.execute('PRAGMA journal_mode=WAL');
    database.execute('PRAGMA synchronous=FULL');
    database.execute('PRAGMA busy_timeout=5000');
    final quick = database.select('PRAGMA quick_check').single.values.first;
    if (quick != 'ok') {
      throw StateError('LOCAL_DB_INTEGRITY_FAILED: $quick');
    }
    final version =
        database.select('PRAGMA user_version').single.values.first! as int;
    if (version == 0) {
      transaction(() {
        database.execute(Gate2Schema.v1);
        final checksum = sha256.convert(utf8.encode(Gate2Schema.v1)).toString();
        database.execute(
          'INSERT INTO local_schema_history(version,description,checksum_sha256,installed_at) VALUES(1,?,?,?)',
          [
            'gate2-local-order-cash',
            checksum,
            DateTime.now().toUtc().toIso8601String(),
          ],
        );
        database.execute('PRAGMA user_version=${Gate2Schema.version}');
      });
    } else if (version != Gate2Schema.version) {
      throw StateError('LOCAL_SCHEMA_UNSUPPORTED: $version');
    }
    _verifySchemaChecksum();
    _bindDevice();
  }

  void _verifySchemaChecksum() {
    final expected = sha256.convert(utf8.encode(Gate2Schema.v1)).toString();
    final rows = database.select(
      'SELECT checksum_sha256 FROM local_schema_history WHERE version=?',
      [Gate2Schema.version],
    );
    if (rows.length != 1 || rows.single['checksum_sha256'] != expected) {
      throw StateError('LOCAL_DB_INTEGRITY_FAILED: schema checksum mismatch');
    }
  }

  void _bindDevice() {
    final rows = database.select(
      'SELECT * FROM local_device_binding WHERE singleton_id=1',
    );
    if (rows.isEmpty) {
      database.execute(
        'INSERT INTO local_device_binding(singleton_id,tenant_id,store_id,terminal_id,cashier_id,cashier_name,store_timezone,next_device_sequence) VALUES(1,?,?,?,?,?,?,1)',
        [
          binding.tenantId,
          binding.storeId,
          binding.terminalId,
          binding.cashierId,
          binding.cashierName,
          binding.storeTimezone,
        ],
      );
      return;
    }
    final row = rows.single;
    if (row['tenant_id'] != binding.tenantId ||
        row['store_id'] != binding.storeId ||
        row['terminal_id'] != binding.terminalId ||
        row['cashier_id'] != binding.cashierId) {
      throw StateError('TENANT_CONTEXT_REQUIRED: database binding mismatch');
    }
  }

  T transaction<T>(T Function() body) {
    database.execute('BEGIN IMMEDIATE');
    try {
      final result = body();
      database.execute('COMMIT');
      return result;
    } catch (_) {
      database.execute('ROLLBACK');
      rethrow;
    }
  }

  int nextDeviceSequence() {
    final current =
        database.select(
              'SELECT next_device_sequence FROM local_device_binding WHERE singleton_id=1 AND tenant_id=?',
              [binding.tenantId],
            ).single['next_device_sequence']!
            as int;
    database.execute(
      'UPDATE local_device_binding SET next_device_sequence=next_device_sequence+1 WHERE singleton_id=1 AND tenant_id=?',
      [binding.tenantId],
    );
    return current;
  }

  void checkpoint(String name) => _failureInjector?.call(name);

  void close() => database.close();
}
