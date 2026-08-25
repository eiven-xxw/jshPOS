import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/features/shift/infrastructure/local_pos_shift_application_service.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);
final now = DateTime.parse('2026-08-21T02:00:00Z');

void main() {
  test(
    'formal shift service opens and closes through Checkout Owner',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final ulids = UlidGenerator(random: Random(31), now: () => now);
      var synchronizationCount = 0;
      final service = LocalPosShiftApplicationService(
        database: database,
        checkout: CheckoutLocalService(
          localDatabase: database,
          ulids: ulids,
          shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 500),
        ),
        ulids: ulids,
        configVersion: 1,
        synchronizeAfterClose: () async {
          synchronizationCount += 1;
        },
        now: () => now,
      );

      final opened = await service.open(
        businessDate: '2026-08-21',
        openingCash: '100.00',
        idempotencyKey: 'open-shift:gate6g-002',
      );
      await service.close(
        shiftId: opened.shiftId,
        actualCash: '100.00',
        idempotencyKey: 'close-shift:gate6g-002',
      );

      final row = database.database.select('SELECT * FROM local_shift').single;
      expect(opened.status, 'OPEN');
      expect(row['status'], 'CLOSED');
      expect(row['difference_minor'], 0);
      expect(synchronizationCount, 1);
      expect(
        database.database
            .select(
              "SELECT COUNT(*) c FROM local_outbox WHERE stream_code='shift.event'",
            )
            .single['c'],
        2,
      );
    },
  );

  test('关班后同步失败保留已提交事实和原 Outbox 身份', () async {
    final database = PosLocalDatabase.inMemory(binding);
    addTearDown(database.close);
    final ulids = UlidGenerator(random: Random(32), now: () => now);
    final service = LocalPosShiftApplicationService(
      database: database,
      checkout: CheckoutLocalService(
        localDatabase: database,
        ulids: ulids,
        shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 500),
      ),
      ulids: ulids,
      configVersion: 1,
      synchronizeAfterClose: () async => throw StateError('synthetic network'),
      now: () => now,
    );

    final opened = await service.open(
      businessDate: '2026-08-21',
      openingCash: '100.00',
      idempotencyKey: 'open-shift:gate6g-sync-failure',
    );
    await service.close(
      shiftId: opened.shiftId,
      actualCash: '100.00',
      idempotencyKey: 'close-shift:gate6g-sync-failure',
    );

    expect(
      database.database
          .select('SELECT status FROM local_shift')
          .single['status'],
      'CLOSED',
    );
    final closeEvent = database.database
        .select(
          "SELECT event_id,status FROM local_outbox WHERE event_type='shift.closed.v1'",
        )
        .single;
    expect(closeEvent['status'], 'PENDING');
    expect(closeEvent['event_id'], isNotEmpty);
  });
}
