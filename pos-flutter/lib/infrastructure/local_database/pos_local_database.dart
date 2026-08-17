import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import '../../features/checkout/domain/checkout_models.dart';
import 'gate2_schema.dart';
import 's3_sync_schema.dart';
import 's9_promotion_schema.dart';
import 's9_manual_schema.dart';
import 's9_transaction_schema.dart';

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
    var version =
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
      version = Gate2Schema.version;
    }
    if (version == Gate2Schema.version) {
      transaction(() {
        database.execute(S3SyncSchema.v2);
        final checksum = sha256
            .convert(utf8.encode(S3SyncSchema.v2))
            .toString();
        database.execute(
          'INSERT INTO local_schema_history(version,description,checksum_sha256,installed_at) VALUES(2,?,?,?)',
          [
            'sprint3-formal-pos-sync',
            checksum,
            DateTime.now().toUtc().toIso8601String(),
          ],
        );
        database.execute('PRAGMA user_version=${S3SyncSchema.version}');
      });
      version = S3SyncSchema.version;
    }
    if (version == S3SyncSchema.version) {
      _migrateToPromotionV3();
      version = S9PromotionSchema.version;
    }
    if (version == S9PromotionSchema.version) {
      _migrateToPromotionV4();
      version = S9ManualSchema.version;
    }
    if (version == S9ManualSchema.version) {
      _migrateToPromotionV5();
      version = S9TransactionSchema.version;
    }
    if (version != S9TransactionSchema.version) {
      throw StateError('LOCAL_SCHEMA_UNSUPPORTED: $version');
    }
    _verifySchemaChecksum();
    _bindDevice();
  }

  void _verifySchemaChecksum() {
    final expected = <int, String>{
      Gate2Schema.version: sha256
          .convert(utf8.encode(Gate2Schema.v1))
          .toString(),
      S3SyncSchema.version: sha256
          .convert(utf8.encode(S3SyncSchema.v2))
          .toString(),
      S9PromotionSchema.version: sha256
          .convert(utf8.encode(S9PromotionSchema.v3))
          .toString(),
      S9ManualSchema.version: sha256
          .convert(utf8.encode(S9ManualSchema.v4))
          .toString(),
      S9TransactionSchema.version: sha256
          .convert(utf8.encode(S9TransactionSchema.v5))
          .toString(),
    };
    for (final entry in expected.entries) {
      final rows = database.select(
        'SELECT checksum_sha256 FROM local_schema_history WHERE version=?',
        [entry.key],
      );
      if (rows.length != 1 || rows.single['checksum_sha256'] != entry.value) {
        throw StateError(
          'LOCAL_DB_INTEGRITY_FAILED: schema checksum mismatch at v${entry.key}',
        );
      }
    }
  }

  void _migrateToPromotionV3() {
    database.execute('PRAGMA foreign_keys=OFF');
    try {
      transaction(() {
        database.execute(S9PromotionSchema.v3);
        final checksum = sha256
            .convert(utf8.encode(S9PromotionSchema.v3))
            .toString();
        database.execute(
          'INSERT INTO local_schema_history(version,description,checksum_sha256,installed_at) VALUES(3,?,?,?)',
          [
            'gate5a-promotion-package-quote-snapshot',
            checksum,
            DateTime.now().toUtc().toIso8601String(),
          ],
        );
        database.execute('PRAGMA user_version=${S9PromotionSchema.version}');
      });
    } finally {
      database.execute('PRAGMA foreign_keys=ON');
    }
    final violations = database.select('PRAGMA foreign_key_check');
    if (violations.isNotEmpty) {
      throw StateError(
        'LOCAL_DB_INTEGRITY_FAILED: foreign key violations after v3',
      );
    }
  }

  void _migrateToPromotionV4() {
    transaction(() {
      database.execute(S9ManualSchema.v4);
      final checksum = sha256
          .convert(utf8.encode(S9ManualSchema.v4))
          .toString();
      database.execute(
        'INSERT INTO local_schema_history(version,description,checksum_sha256,installed_at) VALUES(4,?,?,?)',
        [
          'gate5a-manual-promotion-authorization',
          checksum,
          DateTime.now().toUtc().toIso8601String(),
        ],
      );
      database.execute('PRAGMA user_version=${S9ManualSchema.version}');
    });
    final violations = database.select('PRAGMA foreign_key_check');
    if (violations.isNotEmpty) {
      throw StateError(
        'LOCAL_DB_INTEGRITY_FAILED: foreign key violations after v4',
      );
    }
  }

  void _migrateToPromotionV5() {
    transaction(() {
      database.execute(S9TransactionSchema.v5);
      final checksum = sha256
          .convert(utf8.encode(S9TransactionSchema.v5))
          .toString();
      database.execute(
        'INSERT INTO local_schema_history(version,description,checksum_sha256,installed_at) VALUES(5,?,?,?)',
        [
          'gate5a-transaction-allocation-refund',
          checksum,
          DateTime.now().toUtc().toIso8601String(),
        ],
      );
      database.execute('PRAGMA user_version=${S9TransactionSchema.version}');
    });
    final violations = database.select('PRAGMA foreign_key_check');
    if (violations.isNotEmpty) {
      throw StateError(
        'LOCAL_DB_INTEGRITY_FAILED: foreign key violations after v5',
      );
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
