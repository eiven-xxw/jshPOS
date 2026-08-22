import 'dart:convert';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';
import 'package:sqlite3/sqlite3.dart';

const _binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  group('T2-ORD-004 成交前取消', () {
    test('订单、行、取消事实、审计、Outbox和幂等结果同事务固化', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      final basket = fixture.basket();

      final first = fixture.service.cancelBasket(
        commandId: '01K2A000000000000000000031',
        idempotencyKey: 'gate7b-order-cancel-0001',
        basket: basket,
        shiftId: shift.shiftId,
        reasonCode: 'CUSTOMER_CANCEL',
        reasonText: '虚构顾客在付款前取消',
        occurredAt: fixture.now,
      );
      final replay = fixture.service.cancelBasket(
        commandId: '01K2A000000000000000000031',
        idempotencyKey: 'gate7b-order-cancel-0001',
        basket: basket,
        shiftId: shift.shiftId,
        reasonCode: 'CUSTOMER_CANCEL',
        reasonText: '虚构顾客在付款前取消',
        occurredAt: fixture.now,
      );

      expect(first.dispositionType, 'CANCEL_BEFORE_COMPLETION');
      expect(first.effectiveStatus, 'CANCELLED');
      expect(replay.duplicate, isTrue);
      expect(replay.dispositionId, first.dispositionId);
      expect(fixture.scalar('SELECT status FROM local_order'), 'CANCELLED');
      expect(fixture.count('local_order_line'), 1);
      expect(fixture.count('local_order_disposition'), 1);
      expect(
        fixture.scalar(
          "SELECT event_type FROM local_outbox WHERE event_type='order.cancelled.v1'",
        ),
        'order.cancelled.v1',
      );
      expect(
        fixture.scalar(
          "SELECT action_code FROM local_audit_event WHERE action_code='ORDER_CANCELLED'",
        ),
        'ORDER_CANCELLED',
      );
      expect(
        () => fixture.database.database.execute(
          "UPDATE local_order_disposition SET reason_text='篡改'",
        ),
        throwsA(isA<SqliteException>()),
      );
      expect(
        () => fixture.database.database.execute(
          'DELETE FROM local_order_disposition',
        ),
        throwsA(isA<SqliteException>()),
      );
    });

    test('同幂等键异内容失败关闭，取消墓碑阻止后到成交', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      final basket = fixture.basket();
      fixture.service.cancelBasket(
        commandId: '01K2A000000000000000000032',
        idempotencyKey: 'gate7b-order-cancel-0002',
        basket: basket,
        shiftId: shift.shiftId,
        reasonCode: 'CUSTOMER_CANCEL',
        reasonText: '虚构原始原因',
        occurredAt: fixture.now,
      );

      expect(
        () => fixture.service.cancelBasket(
          commandId: '01K2A000000000000000000033',
          idempotencyKey: 'gate7b-order-cancel-0002',
          basket: basket,
          shiftId: shift.shiftId,
          reasonCode: 'OPERATOR_CANCEL',
          reasonText: '虚构篡改原因',
          occurredAt: fixture.now,
        ),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'IDEMPOTENCY_KEY_REUSED',
          ),
        ),
      );
      expect(
        () => fixture.service.completeCashSale(
          fixture.sale(shift.shiftId, basket),
        ),
        throwsA(isA<PosDomainException>()),
      );
      expect(fixture.count('local_cash_payment'), 0);
    });

    test('取消故障使订单状态、处置、Outbox、审计和幂等整体回滚', () {
      var armed = false;
      final fixture = _Fixture(
        failureInjector: (point) {
          if (armed && point == 'order.cancelled') {
            throw StateError('synthetic cancellation interruption');
          }
        },
      );
      addTearDown(fixture.close);
      final shift = fixture.open();
      final outboxBefore = fixture.count('local_outbox');
      armed = true;

      expect(
        () => fixture.service.cancelBasket(
          commandId: '01K2A000000000000000000034',
          idempotencyKey: 'gate7b-order-cancel-0003',
          basket: fixture.basket(),
          shiftId: shift.shiftId,
          reasonCode: 'CUSTOMER_CANCEL',
          reasonText: '虚构失败注入',
          occurredAt: fixture.now,
        ),
        throwsStateError,
      );
      expect(fixture.count('local_order'), 0);
      expect(fixture.count('local_order_disposition'), 0);
      expect(fixture.count('local_outbox'), outboxBefore);
      expect(fixture.count('local_audit_event'), 1); // 仅保留开班审计
      expect(fixture.count('local_idempotency'), 1); // 仅保留开班幂等
    });

    test('挂单可取消且不能再取单', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      final basket = fixture.basket();
      fixture.service.suspendBasket(
        commandId: '01K2A000000000000000000035',
        idempotencyKey: 'gate7b-hold-before-cancel',
        basket: basket,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );

      fixture.service.cancelPersistedOrder(
        commandId: '01K2A000000000000000000036',
        idempotencyKey: 'gate7b-held-cancel-0001',
        orderId: basket.orderId,
        shiftId: shift.shiftId,
        reasonCode: 'CUSTOMER_CANCEL',
        reasonText: '虚构挂单取消',
        occurredAt: fixture.now,
      );

      expect(fixture.scalar('SELECT status FROM local_order'), 'CANCELLED');
      expect(
        () => fixture.service.resumeBasket(
          commandId: '01K2A000000000000000000037',
          idempotencyKey: 'gate7b-resume-cancelled',
          orderId: basket.orderId,
          shiftId: shift.shiftId,
          occurredAt: fixture.now,
        ),
        throwsA(isA<PosDomainException>()),
      );
    });
  });

  group('T2-ORD-004 成交后反向处置', () {
    test('完成订单只追加退货路由并沿用不可变成交快照摘要', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      final sale = fixture.service.completeCashSale(
        fixture.sale(shift.shiftId, fixture.basket()),
      );
      final statusBefore = fixture.scalar('SELECT status FROM local_order');
      final snapshotBefore = fixture.scalar(
        'SELECT snapshot_sha256 FROM local_order',
      );
      final cashBefore = fixture.count('local_cash_payment');

      final route = fixture.service.routeCompletedOrder(
        commandId: '01K2A000000000000000000038',
        idempotencyKey: 'gate7b-return-route-0001',
        orderId: sale.orderId,
        actionShiftId: shift.shiftId,
        routeCode: 'RETURN_REFUND_REQUIRED',
        reasonCode: 'CUSTOMER_RETURN',
        reasonText: '虚构顾客原单退货',
        occurredAt: fixture.now,
      );
      final payload = (jsonDecode(
        fixture.scalar(
              "SELECT payload_json FROM local_outbox WHERE event_type='order.reversal-routed.v1'",
            )!
            as String,
      ) as Map).cast<String, Object?>();

      expect(route.dispositionType, 'RETURN_REFUND_REQUIRED');
      expect(route.effectiveStatus, statusBefore);
      expect(fixture.scalar('SELECT status FROM local_order'), statusBefore);
      expect(
        fixture.scalar('SELECT snapshot_sha256 FROM local_order'),
        snapshotBefore,
      );
      expect(fixture.count('local_cash_payment'), cashBefore);
      expect(payload['orderSnapshotSha256'], snapshotBefore);
      expect(payload['effectiveStatus'], statusBefore);
    });

    test('已成交订单不能伪装成取消，异常补偿缺少授权时失败关闭', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final shift = fixture.open();
      final basket = fixture.basket();
      fixture.service.completeCashSale(fixture.sale(shift.shiftId, basket));

      expect(
        () => fixture.service.cancelPersistedOrder(
          commandId: '01K2A000000000000000000039',
          idempotencyKey: 'gate7b-illegal-cancel-01',
          orderId: basket.orderId,
          shiftId: shift.shiftId,
          reasonCode: 'OPERATOR_CANCEL',
          reasonText: '不得覆盖成交',
          occurredAt: fixture.now,
        ),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'ORDER_CANCELLATION_BLOCKED',
          ),
        ),
      );
      expect(
        () => fixture.service.routeCompletedOrder(
          commandId: '01K2A000000000000000000040',
          idempotencyKey: 'gate7b-compensate-no-auth',
          orderId: basket.orderId,
          actionShiftId: shift.shiftId,
          routeCode: 'EXPLICIT_COMPENSATION_REQUIRED',
          reasonCode: 'SYNC_ANOMALY',
          reasonText: '虚构同步异常',
          occurredAt: fixture.now,
        ),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'ORDER_DISPOSITION_INVALID',
          ),
        ),
      );
      expect(fixture.count('local_order_disposition'), 0);
    });
  });

  test('SQLite V13迁移和摘要历史完整', () {
    final fixture = _Fixture();
    addTearDown(fixture.close);
    expect(
      fixture.database.database
          .select('PRAGMA user_version')
          .single
          .values
          .first,
      13,
    );
    expect(fixture.count('local_schema_history'), 13);
    expect(
      fixture.scalar(
        "SELECT COUNT(*) FROM sqlite_master WHERE name='local_order_disposition'",
      ),
      1,
    );
  });
}

final class _Fixture {
  _Fixture({FailureInjector? failureInjector})
    : database = PosLocalDatabase.inMemory(
        _binding,
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

  Basket basket() => Basket(
    orderId: '01K2A000000000000000000051',
    localOrderNo: 'SYN-G7B-0001',
    lines: [
      BasketLine(
        lineId: '01K2A000000000000000000052',
        lineNo: 1,
        quote: PriceQuote.fromVerifiedPackage(
          skuId: '701',
          skuCode: 'SYN-SKU-001',
          productName: '虚构瓶装水',
          unitId: '301',
          unitCode: 'PCS',
          unitPriceMinor: 1299,
          priceSource: 'TENANT_BASE',
          barcode: '6900000000012',
        ),
        quantity: '1.000000',
      ),
    ],
  );

  CashSaleCommand sale(String shiftId, Basket basket) => CashSaleCommand(
    commandId: '01K2A000000000000000000061',
    idempotencyKey: 'gate7b-cash-sale-0001',
    basket: basket,
    shiftId: shiftId,
    businessDate: '2026-08-21',
    catalogVersion: 1,
    priceVersion: 1,
    industryTemplateVersion: 'CONVENIENCE.1',
    tenderedAmountMinor: 2000,
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
