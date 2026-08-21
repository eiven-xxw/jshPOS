import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/features/shift/infrastructure/local_pos_shift_application_service.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';
import 'package:sqlite3/sqlite3.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  group('T2-POS-010 班次现金与钱箱事实', () {
    test('现金存取、钱箱请求和关班使用同一理论现金且可幂等恢复', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();

      final cashIn = fixture.service.recordShiftCashMovement(
        commandId: '01K2A000000000000000000031',
        idempotencyKey: 'gate7b-cash-in-key-0001',
        shiftId: shift.shiftId,
        movementType: ShiftCashMovementType.cashIn,
        amountMinor: 500,
        reasonCode: 'FLOAT_TOPUP',
        reasonText: '虚构备用金补充',
        authorizationRef: 'SESSION_AUTH_REF_123456',
        expectedVersion: 1,
        occurredAt: fixture.now,
      );
      final replay = fixture.service.recordShiftCashMovement(
        commandId: '01K2A000000000000000000031',
        idempotencyKey: 'gate7b-cash-in-key-0001',
        shiftId: shift.shiftId,
        movementType: ShiftCashMovementType.cashIn,
        amountMinor: 500,
        reasonCode: 'FLOAT_TOPUP',
        reasonText: '虚构备用金补充',
        authorizationRef: 'SESSION_AUTH_REF_123456',
        expectedVersion: 1,
        occurredAt: fixture.now,
      );
      final cashOut = fixture.service.recordShiftCashMovement(
        commandId: '01K2A000000000000000000032',
        idempotencyKey: 'gate7b-safe-drop-key-01',
        shiftId: shift.shiftId,
        movementType: ShiftCashMovementType.safeDrop,
        amountMinor: 200,
        reasonCode: 'SAFE_DROP',
        reasonText: '虚构缴款',
        authorizationRef: 'SESSION_AUTH_REF_123456',
        expectedVersion: 2,
        occurredAt: fixture.now,
      );
      final drawer = fixture.service.requestNoSaleDrawer(
        commandId: '01K2A000000000000000000033',
        idempotencyKey: 'gate7b-drawer-key-00001',
        shiftId: shift.shiftId,
        reasonCode: 'CHANGE_REQUEST',
        reasonText: '虚构换零请求',
        authorizationRef: 'SESSION_AUTH_REF_123456',
        expectedVersion: 3,
        occurredAt: fixture.now,
      );
      final closed = fixture.service.closeShift(
        commandId: '01K2A000000000000000000034',
        idempotencyKey: 'gate7b-close-key-00001',
        shiftId: shift.shiftId,
        actualCashMinor: 10300,
        expectedVersion: 4,
        occurredAt: fixture.now,
      );

      expect(cashIn.theoreticalCashMinor, 10500);
      expect(replay.duplicate, isTrue);
      expect(cashOut.signedAmountMinor, -200);
      expect(drawer.deviceExecutionStatus, 'BLOCKED_EXTERNAL');
      expect(drawer.theoreticalCashMinor, 10300);
      expect(closed.differenceMinor, 0);
      expect(fixture.count('local_shift_cash_movement'), 2);
      expect(fixture.count('local_drawer_event'), 1);
      expect(
        fixture.scalar(
          "SELECT device_execution_status FROM local_drawer_event",
        ),
        'BLOCKED_EXTERNAL',
      );
    });

    test('故障使现金事实、班次版本、Outbox、审计和幂等结果整体回滚', () {
      var armed = false;
      final fixture = _Fixture(
        failureInjector: (point) {
          if (armed && point == 'shift.cash-movement.persisted') {
            throw StateError('synthetic disk interruption');
          }
        },
      );
      addTearDown(fixture.close);
      final shift = fixture.open();
      final outboxBefore = fixture.count('local_outbox');
      armed = true;

      expect(
        () => fixture.service.recordShiftCashMovement(
          commandId: '01K2A000000000000000000035',
          idempotencyKey: 'gate7b-rollback-key-001',
          shiftId: shift.shiftId,
          movementType: ShiftCashMovementType.cashOut,
          amountMinor: 100,
          reasonCode: 'CASH_OUT',
          reasonText: '虚构失败注入',
          authorizationRef: 'SESSION_AUTH_REF_123456',
          expectedVersion: 1,
          occurredAt: fixture.now,
        ),
        throwsStateError,
      );
      expect(fixture.count('local_shift_cash_movement'), 0);
      expect(fixture.scalar('SELECT record_version FROM local_shift'), 1);
      expect(
        fixture.scalar('SELECT theoretical_cash_minor FROM local_shift'),
        10000,
      );
      expect(fixture.count('local_outbox'), outboxBefore);
    });

    test('班次现金与钱箱事实拒绝覆盖更新或物理删除', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      fixture.service.recordShiftCashMovement(
        commandId: '01K2A000000000000000000036',
        idempotencyKey: 'gate7b-immutable-key-01',
        shiftId: shift.shiftId,
        movementType: ShiftCashMovementType.cashIn,
        amountMinor: 100,
        reasonCode: 'FLOAT_TOPUP',
        reasonText: '虚构不可变测试',
        authorizationRef: 'SESSION_AUTH_REF_123456',
        expectedVersion: 1,
        occurredAt: fixture.now,
      );
      expect(
        () => fixture.database.database.execute(
          "UPDATE local_shift_cash_movement SET reason_text='篡改'",
        ),
        throwsA(isA<SqliteException>()),
      );
      expect(
        () => fixture.database.database.execute(
          'DELETE FROM local_shift_cash_movement',
        ),
        throwsA(isA<SqliteException>()),
      );
    });

    test('本地应用端口使用已认证员工权限并拒绝无权限调用', () async {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      final allowed = LocalPosShiftApplicationService(
        database: fixture.database,
        checkout: fixture.service,
        ulids: UlidGenerator(random: Random(8), now: () => fixture.now),
        configVersion: 1,
        permissions: const {
          PosPermission.cashManage,
          PosPermission.drawerNoSale,
        },
        authorizationRef: 'SESSION_AUTH_REF_123456',
        now: () => fixture.now,
      );
      final denied = LocalPosShiftApplicationService(
        database: fixture.database,
        checkout: fixture.service,
        ulids: UlidGenerator(random: Random(9), now: () => fixture.now),
        configVersion: 1,
        now: () => fixture.now,
      );

      final result = await allowed.recordCashMovement(
        shiftId: shift.shiftId,
        movementType: ShiftCashMovementType.cashIn,
        amount: '1.00',
        reasonCode: 'FLOAT_TOPUP',
        reasonText: '虚构应用端口权限测试',
        idempotencyKey: 'gate7b-app-permission-001',
      );
      expect(result.theoreticalCashMinor, 10100);
      await expectLater(
        denied.requestNoSaleDrawer(
          shiftId: shift.shiftId,
          reasonCode: 'CHANGE_REQUEST',
          reasonText: '不得执行',
          idempotencyKey: 'gate7b-app-denied-key-01',
        ),
        throwsA(
          isA<PosSessionFailure>().having(
            (error) => error.code,
            'code',
            'PERMISSION_DENIED',
          ),
        ),
      );
      expect(fixture.count('local_drawer_event'), 0);
    });
  });
}

final class _Fixture {
  _Fixture({FailureInjector? failureInjector})
    : database = PosLocalDatabase.inMemory(
        binding,
        failureInjector: failureInjector,
      ) {
    service = CheckoutLocalService(
      localDatabase: database,
      ulids: UlidGenerator(random: Random(20260821), now: () => now),
      shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 0),
    );
  }

  final now = DateTime.parse('2026-08-21T03:00:00Z');
  final PosLocalDatabase database;
  late final CheckoutLocalService service;

  ShiftResult open() => service.openShift(
    commandId: '01K2A000000000000000000021',
    idempotencyKey: 'gate7b-open-shift-0001',
    businessDate: '2026-08-21',
    openingCashMinor: 10000,
    configVersion: 1,
    occurredAt: now,
  );

  int count(String table) =>
      database.database
              .select('SELECT COUNT(*) value FROM $table')
              .single['value']!
          as int;
  Object? scalar(String sql) =>
      database.database.select(sql).single.values.first;
  void close() => database.close();
}
