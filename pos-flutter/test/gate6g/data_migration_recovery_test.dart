import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';
import 'package:sqlite3/sqlite3.dart';

const binding = TrustedDeviceBinding(
  tenantId: '900000000000000001',
  storeId: '911000000000000001',
  terminalId: '01J00000000000000000000001',
  cashierId: '940000000000000001',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test('SQLite迁移中断整体回滚且原文件可安全重试到v7', () {
    final directory = Directory.systemTemp.createTempSync('jshpos-gate6g-migration-');
    final path = '${directory.path}${Platform.pathSeparator}pos.db';
    try {
      expect(
        () => PosLocalDatabase.openPath(
          path,
          binding,
          failureInjector: (checkpoint) {
            if (checkpoint == 'migration.v1.before-version') {
              throw StateError('synthetic migration interruption');
            }
          },
        ),
        throwsA(isA<StateError>()),
      );

      final recovered = PosLocalDatabase.openPath(path, binding);
      expect(recovered.database.select('PRAGMA user_version').single.values.first, 7);
      expect(
        recovered.database.select('SELECT COUNT(*) AS value FROM local_schema_history').single['value'],
        7,
      );
      expect(recovered.database.select('PRAGMA quick_check').single.values.first, 'ok');
      recovered.close();
    } finally {
      directory.deleteSync(recursive: true);
    }
  });

  test('未知未来SQLite版本失败关闭且不执行降级迁移', () {
    final directory = Directory.systemTemp.createTempSync('jshpos-gate6g-future-');
    final path = '${directory.path}${Platform.pathSeparator}pos.db';
    try {
      final raw = sqlite3.open(path);
      raw.execute('PRAGMA user_version=999');
      raw.close();
      expect(
        () => PosLocalDatabase.openPath(path, binding),
        throwsA(predicate((error) => error.toString().contains('LOCAL_SCHEMA_UNSUPPORTED: 999'))),
      );
      final verify = sqlite3.open(path);
      expect(verify.select('PRAGMA user_version').single.values.first, 999);
      verify.close();
    } finally {
      directory.deleteSync(recursive: true);
    }
  });
}
