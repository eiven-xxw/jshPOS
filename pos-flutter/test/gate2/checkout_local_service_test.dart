import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';
import 'package:sqlite3/sqlite3.dart';

const bindingA = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);

const bindingB = TrustedDeviceBinding(
  tenantId: 'TENANT_B',
  storeId: '2101',
  terminalId: '01K2B000000000000000000011',
  cashierId: '201',
  cashierName: 'Synthetic Bob',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  group('formal Gate 2 SQLite local transaction', () {
    test('commits order, cash, ledger, print, outbox, audit and idempotency atomically', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift();
      final command = fixture.sale(shift.shiftId);

      final result = fixture.service.completeCashSale(command);

      expect(result.receivableAmountMinor, 1299);
      expect(result.tenderedAmountMinor, 2000);
      expect(result.changeAmountMinor, 701);
      expect(fixture.count('local_order'), 1);
      expect(fixture.count('local_order_line'), 1);
      expect(fixture.count('local_order_state_history'), 4);
      expect(fixture.count('local_cash_payment'), 1);
      expect(fixture.count('local_cash_ledger'), 1);
      expect(fixture.count('local_print_job'), 1);
      expect(
        fixture.count('local_outbox'),
        4,
      ); // shift plus three cash-order facts
      expect(fixture.count('local_audit_event'), 2);
      expect(fixture.count('local_idempotency'), 2);
      expect(fixture.scalar("SELECT status FROM local_order"), 'COMPLETED');
      expect(
        fixture.scalar(
          "SELECT status FROM local_outbox WHERE event_type='order.completed.v1'",
        ),
        'PENDING',
      );
    });

    test('cash command hash matches the server golden vector', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final command = fixture.sale('01K2A000000000000000000021');

      expect(
        command.requestHash(bindingA),
        '60337986451e5a511783f4d77eaac27598fef47f997336a4bbb599c25fd68e5a',
      );
    });

    test('same idempotency key and hash returns original result without a second effect', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final command = fixture.sale(fixture.openShift().shiftId);
      final first = fixture.service.completeCashSale(command);
      final second = fixture.service.completeCashSale(command);

      expect(second.duplicate, isTrue);
      expect(second.paymentId, first.paymentId);
      expect(fixture.count('local_order'), 1);
      expect(fixture.count('local_cash_payment'), 1);
      expect(fixture.count('local_cash_ledger'), 1);
    });

    test('same idempotency key with changed cash tender is rejected', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift();
      fixture.service.completeCashSale(fixture.sale(shift.shiftId));
      final changed = fixture.sale(shift.shiftId, tendered: 2500);

      expect(
        () => fixture.service.completeCashSale(changed),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'IDEMPOTENCY_KEY_REUSED',
          ),
        ),
      );
      expect(fixture.count('local_cash_payment'), 1);
    });

    for (final checkpoint in [
      'order.snapshot',
      'cash.payment',
      'cash.ledger',
      'print.queued',
      'outbox.appended',
      'audit.appended',
      'idempotency.saved',
    ]) {
      test('failure at $checkpoint rolls the complete cash sale back', () {
        var armed = '';
        final fixture = Fixture(
          failureInjector: (point) {
            if (point == armed) {
              throw StateError('synthetic disk failure at $point');
            }
          },
        );
        addTearDown(fixture.close);
        final shift = fixture.openShift();
        final beforeOutbox = fixture.count('local_outbox');
        final beforeAudit = fixture.count('local_audit_event');
        final beforeIdempotency = fixture.count('local_idempotency');
        armed = checkpoint;

        expect(
          () => fixture.service.completeCashSale(fixture.sale(shift.shiftId)),
          throwsStateError,
        );
        expect(fixture.count('local_order'), 0);
        expect(fixture.count('local_cash_payment'), 0);
        expect(fixture.count('local_cash_ledger'), 0);
        expect(fixture.count('local_print_job'), 0);
        expect(fixture.count('local_outbox'), beforeOutbox);
        expect(fixture.count('local_audit_event'), beforeAudit);
        expect(fixture.count('local_idempotency'), beforeIdempotency);
      });
    }

    test('SQLite FULL rolls back every cash-order fact', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift();
      final lineIds = UlidGenerator(random: Random(99), now: () => fixture.now);
      final basket = Basket(
        orderId: '01K2A000000000000000000032',
        localOrderNo: 'A-T1-000002',
        lines: List.generate(
          500,
          (index) => BasketLine(
            lineId: lineIds.next(),
            lineNo: index + 1,
            quote: PriceQuote.fromVerifiedPackage(
              skuId: '701',
              skuCode: 'A-SKU-001',
              productName: List.filled(200, 'X').join(),
              unitId: '301',
              unitCode: 'PCS',
              unitPriceMinor: 1,
              priceSource: 'TENANT_BASE',
            ),
            quantity: '1',
          ),
        ),
      );
      final pages = fixture.scalar('PRAGMA page_count')! as int;
      fixture.database.database.execute('PRAGMA max_page_count=$pages');

      expect(
        () => fixture.service.completeCashSale(
          fixture.sale(shift.shiftId, tendered: 500, basket: basket),
        ),
        throwsA(isA<SqliteException>()),
      );
      expect(fixture.count('local_order'), 0);
      expect(fixture.count('local_cash_payment'), 0);
      expect(fixture.count('local_outbox'), 1);
    });

    test('suspend and resume preserve the same draft and exact lines', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift();
      final basket = fixture.basket();

      fixture.service.suspendBasket(
        commandId: '01K2A000000000000000000061',
        idempotencyKey: 'suspend-order-key-01',
        basket: basket,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );
      expect(basket.suspended, isTrue);
      fixture.service.suspendBasket(
        commandId: '01K2A000000000000000000061',
        idempotencyKey: 'suspend-order-key-01',
        basket: basket,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );
      final resumed = fixture.service.resumeBasket(
        commandId: '01K2A000000000000000000062',
        idempotencyKey: 'resume-order-key-001',
        orderId: basket.orderId,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );
      final replayed = fixture.service.resumeBasket(
        commandId: '01K2A000000000000000000062',
        idempotencyKey: 'resume-order-key-001',
        orderId: basket.orderId,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );

      expect(resumed.suspended, isFalse);
      expect(replayed.orderId, resumed.orderId);
      expect(resumed.orderId, basket.orderId);
      expect(resumed.lines.single.quantity.canonical, '1');
      expect(resumed.grossAmountMinor, 1299);
      final result = fixture.service.completeCashSale(
        fixture.sale(shift.shiftId, basket: resumed),
      );
      expect(result.receivableAmountMinor, 1299);
      expect(fixture.count('local_order'), 1);
      expect(
        fixture.scalar(
          "SELECT COUNT(*) FROM local_outbox WHERE event_type IN ('order.suspended.v1','order.resumed.v1')",
        ),
        2,
      );
      expect(
        fixture.scalar(
          "SELECT record_version FROM local_order WHERE order_id='${basket.orderId}'",
        ),
        6,
      );
    });

    test('completed snapshot and amounts reject direct tampering', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift();
      fixture.service.completeCashSale(fixture.sale(shift.shiftId));

      expect(
        () => fixture.database.database.execute(
          'UPDATE local_order SET receivable_amount_minor=1 WHERE tenant_id=?',
          [bindingA.tenantId],
        ),
        throwsA(isA<SqliteException>()),
      );
      expect(
        fixture.scalar('SELECT receivable_amount_minor FROM local_order'),
        1299,
      );
    });

    test('a draft cannot cross its frozen shift or business-day boundary', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift();
      final basket = fixture.basket();
      fixture.service.suspendBasket(
        commandId: '01K2A000000000000000000061',
        idempotencyKey: 'suspend-order-key-01',
        basket: basket,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );
      final resumed = fixture.service.resumeBasket(
        commandId: '01K2A000000000000000000062',
        idempotencyKey: 'resume-order-key-001',
        orderId: basket.orderId,
        shiftId: shift.shiftId,
        occurredAt: fixture.now,
      );

      expect(
        () => fixture.service.completeCashSale(
          fixture.sale(
            shift.shiftId,
            basket: resumed,
            businessDate: '2026-08-17',
          ),
        ),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'SHIFT_NOT_OPEN',
          ),
        ),
      );
      expect(fixture.scalar('SELECT status FROM local_order'), 'DRAFT');
    });

    test('close shift recomputes theoretical cash from immutable ledger', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift(openingCash: 5000);
      fixture.service.completeCashSale(fixture.sale(shift.shiftId));
      final version =
          fixture.scalar('SELECT record_version FROM local_shift')! as int;

      final closed = fixture.service.closeShift(
        commandId: '01K2A000000000000000000081',
        idempotencyKey: 'close-shift-key-0001',
        shiftId: shift.shiftId,
        actualCashMinor: 6299,
        expectedVersion: version,
        occurredAt: fixture.now,
      );

      expect(closed.status, 'CLOSED');
      expect(closed.theoreticalCashMinor, 6299);
      expect(closed.differenceMinor, 0);
    });

    test('difference above threshold requires a different supervisor', () {
      final fixture = Fixture();
      addTearDown(fixture.close);
      final shift = fixture.openShift(openingCash: 5000);

      expect(
        () => fixture.service.closeShift(
          commandId: '01K2A000000000000000000081',
          idempotencyKey: 'close-shift-key-0001',
          shiftId: shift.shiftId,
          actualCashMinor: 5001,
          expectedVersion: 1,
          occurredAt: fixture.now,
        ),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'SHIFT_DIFFERENCE_APPROVAL_REQUIRED',
          ),
        ),
      );
      final approval = fixture.service.approveShiftDifference(
        commandId: '01K2A000000000000000000091',
        idempotencyKey: 'approve-shift-key-01',
        shiftId: shift.shiftId,
        actualCashMinor: 5001,
        expectedVersion: 1,
        reasonCode: 'COUNT_CONFIRMED',
        reasonText: 'Synthetic supervisor confirmed the count',
        supervisor: SupervisorSession.fromTrustedAuthentication(
          supervisorId: '102',
          supervisorName: 'Synthetic Supervisor',
          authProofRef: 'auth:synthetic:0001',
          authenticatedAt: fixture.now.subtract(const Duration(minutes: 1)),
        ),
        occurredAt: fixture.now,
      );
      expect(fixture.count('local_shift_approval'), 1);

      final closed = fixture.service.closeShift(
        commandId: '01K2A000000000000000000082',
        idempotencyKey: 'close-shift-key-0002',
        shiftId: shift.shiftId,
        actualCashMinor: 5001,
        expectedVersion: 1,
        occurredAt: fixture.now,
        approvalId: approval.approvalId,
      );
      expect(closed.differenceMinor, 1);
    });
  });

  test('database binding refuses a second fictional tenant', () {
    final directory = Directory.systemTemp.createTempSync('jshpos-gate2-');
    final path = '${directory.path}${Platform.pathSeparator}pos.sqlite3';
    addTearDown(() => directory.deleteSync(recursive: true));
    PosLocalDatabase.openPath(path, bindingA).close();
    expect(() => PosLocalDatabase.openPath(path, bindingB), throwsStateError);
  });

  test('schema migration is repeatable and contains no T1 probe table', () {
    final directory = Directory.systemTemp.createTempSync('jshpos-gate2-');
    final path = '${directory.path}${Platform.pathSeparator}pos.sqlite3';
    addTearDown(() => directory.deleteSync(recursive: true));
    PosLocalDatabase.openPath(path, bindingA).close();
    final reopened = PosLocalDatabase.openPath(path, bindingA);
    addTearDown(reopened.close);
    expect(
      reopened.database.select('PRAGMA user_version').single.values.first,
      7,
    );
    expect(
      reopened.database.select('PRAGMA quick_check').single.values.first,
      'ok',
    );
    expect(
      reopened.database
          .select(
            "SELECT COUNT(*) value FROM sqlite_master WHERE name LIKE 'syn_%'",
          )
          .single['value'],
      0,
    );
  });
}

final class Fixture {
  Fixture({FailureInjector? failureInjector})
    : database = PosLocalDatabase.inMemory(
        bindingA,
        failureInjector: failureInjector,
      ) {
    service = CheckoutLocalService(
      localDatabase: database,
      ulids: UlidGenerator(random: Random(20260816), now: () => now),
      shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 0),
    );
  }

  final DateTime now = DateTime.utc(2026, 8, 16, 9);
  final PosLocalDatabase database;
  late final CheckoutLocalService service;

  ShiftResult openShift({int openingCash = 0}) => service.openShift(
    commandId: '01K2A000000000000000000021',
    idempotencyKey: 'open-shift-key-0001',
    businessDate: '2026-08-16',
    openingCashMinor: openingCash,
    configVersion: 1,
    occurredAt: now,
  );

  Basket basket() => Basket(
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
        quantity: '1.000000',
      ),
    ],
  );

  CashSaleCommand sale(
    String shiftId, {
    int tendered = 2000,
    Basket? basket,
    String businessDate = '2026-08-16',
  }) => CashSaleCommand(
    commandId: '01K2A000000000000000000051',
    idempotencyKey: 'cash-order-key-0001',
    basket: basket ?? this.basket(),
    shiftId: shiftId,
    businessDate: businessDate,
    catalogVersion: 1,
    priceVersion: 1,
    industryTemplateVersion: 'CONVENIENCE.1',
    tenderedAmountMinor: tendered,
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
