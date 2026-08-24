import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/tender/application/pos_tender_application_service.dart';
import 'package:jshpos_pos/features/tender/application/pos_tender_controller.dart';
import 'package:jshpos_pos/features/tender/domain/pos_tender_models.dart';
import 'package:jshpos_pos/features/tender/infrastructure/local_pos_tender_application_service.dart';
import 'package:jshpos_pos/features/tender/presentation/pos_tender_page.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const _binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K7A000000000000000000001',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);
const _shift = '01K7A000000000000000000002';
const _order = '01K7A000000000000000000003';
const _hash =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
final _at = DateTime.utc(2026, 8, 22, 2);

void main() {
  test(
    'SQLite V13 freezes plan, allocations, audit and Outbox atomically',
    () async {
      final database = _database();
      addTearDown(database.close);
      final service = _service(database);

      final result = await service.freeze(
        source: _source(),
        allocations: _allocations(),
      );

      expect(
        database.database.select('PRAGMA user_version').single.values.first,
        16,
      );
      expect(result.status, PosTenderPlanStatus.frozen);
      expect(result.allocations.map((item) => item.amountMinor), [1000, 299]);
      expect(_count(database, 'local_tender_plan'), 1);
      expect(_count(database, 'local_tender_allocation'), 2);
      expect(_count(database, 'local_tender_event'), 1);
      expect(
        database.database
            .select(
              "SELECT COUNT(*) value FROM local_outbox WHERE event_type='tender.plan-frozen.v1' AND status='PENDING'",
            )
            .single['value'],
        1,
      );
      final duplicate = await service.freeze(
        source: _source(),
        allocations: _allocations(),
      );
      expect(duplicate.planRef, result.planRef);
      expect(duplicate.duplicate, isTrue);
      expect(_count(database, 'local_tender_plan'), 1);
    },
  );

  test(
    'migration/freeze failure rolls back plan, event and outbox together',
    () async {
      var fail = false;
      final database = PosLocalDatabase.inMemory(
        _binding,
        failureInjector: (checkpoint) {
          if (fail && checkpoint == 'tender.freeze.before-commit') {
            throw StateError('fixed-seed-disk-failure');
          }
        },
      );
      addTearDown(database.close);
      _seedShift(database);
      fail = true;

      await expectLater(
        _service(database)
            .freeze(source: _source(), allocations: _allocations()),
        throwsA(isA<StateError>()),
      );
      expect(_count(database, 'local_tender_plan'), 0);
      expect(_count(database, 'local_tender_allocation'), 0);
      expect(
        database.database
            .select(
              "SELECT COUNT(*) value FROM local_outbox WHERE event_type='tender.plan-frozen.v1'",
            )
            .single['value'],
        0,
      );
    },
  );

  test(
    'electronic collection fails closed and preserves original allocation',
    () async {
      final database = _database();
      addTearDown(database.close);
      final service = _service(database);
      final plan = await service.freeze(
        source: _source(),
        allocations: _allocations(),
      );

      await expectLater(
        service.collect(
          planRef: plan.planRef,
          allocationRef: plan.allocations.first.allocationRef,
        ),
        throwsA(
          isA<PosTenderFailure>().having(
            (error) => error.code,
            'code',
            'PAYMENT_EXTERNAL_BLOCKED',
          ),
        ),
      );
      final refreshed = await service.find(plan.planRef);
      expect(
        refreshed.allocations.first.status,
        PosTenderAllocationStatus.planned,
      );
      expect(
        database.database
            .select(
              "SELECT COUNT(*) value FROM local_tender_event WHERE event_type='BLOCKED_EXTERNAL'",
            )
            .single['value'],
        1,
      );
    },
  );

  test(
    'same order with changed frozen source is rejected as content mismatch',
    () async {
      final database = _database();
      addTearDown(database.close);
      final service = _service(database);
      await service.freeze(source: _source(), allocations: _allocations());

      await expectLater(
        service.freeze(
          source: const PosTenderSource(
            orderRef: _order,
            orderSnapshotSha256: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
            storeRef: '1101',
            terminalRef: '01K7A000000000000000000001',
            shiftRef: _shift,
            businessDate: '2026-08-22',
            receivableAmountMinor: 1299,
          ),
          allocations: _allocations(),
        ),
        throwsA(
          isA<PosTenderFailure>().having(
            (error) => error.code,
            'code',
            'PAY-IDEMPOTENCY-001',
          ),
        ),
      );
    },
  );

  test('rules reject reordered cash and non-conserving allocations', () {
    expect(
      () => PosTenderRules.validate(_source(), [
        const PosTenderAllocationDraft(
          sequenceNo: 1,
          tenderType: PosTenderType.cash,
          amountMinor: 299,
        ),
        const PosTenderAllocationDraft(
          sequenceNo: 2,
          tenderType: PosTenderType.electronic,
          amountMinor: 1000,
        ),
      ]),
      throwsA(isA<PosTenderFailure>()),
    );
    expect(
      () => PosTenderRules.validate(_source(), [
        const PosTenderAllocationDraft(
          sequenceNo: 1,
          tenderType: PosTenderType.electronic,
          amountMinor: 999,
        ),
        const PosTenderAllocationDraft(
          sequenceNo: 2,
          tenderType: PosTenderType.cash,
          amountMinor: 299,
        ),
      ]),
      throwsA(isA<PosTenderFailure>()),
    );
  });

  test('Dart digest matches the shared Java golden vector', () async {
    final document = jsonDecode(
      await File('../contracts/t2/gate7b-pay004/tender-golden-vectors-v1.json')
          .readAsString(),
    ) as Map<String, Object?>;
    final vector =
        (document['cases']! as List<Object?>).first as Map<String, Object?>;
    final source = PosTenderSource(
      orderRef: vector['orderId']! as String,
      orderSnapshotSha256: vector['orderSnapshotSha256']! as String,
      storeRef: vector['storeId']! as String,
      terminalRef: vector['terminalId']! as String,
      shiftRef: vector['shiftId']! as String,
      businessDate: vector['businessDate']! as String,
      receivableAmountMinor: vector['receivableAmountMinor']! as int,
      currency: vector['currency']! as String,
    );
    final allocations = (vector['allocations']! as List<Object?>)
        .map((raw) {
          final allocation = raw! as Map<String, Object?>;
          return PosTenderAllocationIdentity(
            allocationRef: allocation['allocationId']! as String,
            sequenceNo: allocation['sequenceNo']! as int,
            tenderType: allocation['tenderType'] == 'CASH'
                ? PosTenderType.cash
                : PosTenderType.electronic,
            amountMinor: allocation['amountMinor']! as int,
          );
        })
        .toList(growable: false);

    expect(
      PosTenderDigest.planContentSha256(
        planRef: vector['planId']! as String,
        source: source,
        allocations: allocations,
      ),
      vector['expectedContentSha256'],
    );
  });

  testWidgets('page freezes exact shares and shows external block safely', (
    tester,
  ) async {
    final service = _PageFakeTenderService();
    final controller = PosTenderController(service: service, source: _source());
    await tester.pumpWidget(
      MaterialApp(home: PosTenderPage(controller: controller)),
    );

    expect(find.byKey(const Key('tenderSafetyBoundary')), findsOneWidget);
    await tester.tap(find.byKey(const Key('addElectronicAllocation')));
    await tester.pump();
    expect(find.byKey(const Key('electronicAmount-2')), findsOneWidget);
    await tester.tap(find.byKey(const Key('freezeTenderPlan')));
    await tester.pumpAndSettle();
    expect(service.lastFreezeAllocationCount, 3);
    expect(find.byKey(const Key('tenderPlanStatus')), findsOneWidget);
    // 可信上下文与离线边界增加后，份额按钮需要按真实用户路径滚动到可见区域。
    await tester.ensureVisible(find.byKey(const Key('collectTender-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('collectTender-1')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('tenderSafeError')), findsOneWidget);
    expect(find.textContaining('PAYMENT_EXTERNAL_BLOCKED'), findsOneWidget);
    expect(service.collectCount, 1);
  });
}

PosLocalDatabase _database() {
  final database = PosLocalDatabase.inMemory(_binding);
  _seedShift(database);
  return database;
}

void _seedShift(PosLocalDatabase database) {
  database.database.execute(
    '''INSERT INTO local_shift(shift_id,tenant_id,store_id,terminal_id,cashier_id,cashier_name_snapshot,
       business_date,store_timezone,config_version,status,currency,opening_cash_minor,theoretical_cash_minor,
       opened_at,record_version) VALUES(?,?,?,?,?,?,?,'Asia/Shanghai',1,'OPEN','CNY',0,0,?,1)''',
    [
      _shift,
      _binding.tenantId,
      _binding.storeId,
      _binding.terminalId,
      _binding.cashierId,
      _binding.cashierName,
      '2026-08-22',
      _at.toIso8601String(),
    ],
  );
}

LocalPosTenderApplicationService _service(PosLocalDatabase database) =>
    LocalPosTenderApplicationService(
      database: database,
      ulids: UlidGenerator(random: Random(17), now: () => _at),
      now: () => _at,
    );

PosTenderSource _source() => const PosTenderSource(
  orderRef: _order,
  orderSnapshotSha256: _hash,
  storeRef: '1101',
  terminalRef: '01K7A000000000000000000001',
  shiftRef: _shift,
  businessDate: '2026-08-22',
  receivableAmountMinor: 1299,
);

List<PosTenderAllocationDraft> _allocations() => const [
  PosTenderAllocationDraft(
    sequenceNo: 1,
    tenderType: PosTenderType.electronic,
    amountMinor: 1000,
  ),
  PosTenderAllocationDraft(
    sequenceNo: 2,
    tenderType: PosTenderType.cash,
    amountMinor: 299,
  ),
];

int _count(PosLocalDatabase database, String table) =>
    database.database
            .select('SELECT COUNT(*) value FROM $table')
            .single['value']!
        as int;

final class _PageFakeTenderService implements PosTenderApplicationService {
  int collectCount = 0;
  int lastFreezeAllocationCount = 0;
  PosTenderPlanView? plan;

  @override
  Future<PosTenderPlanView> freeze({
    required PosTenderSource source,
    required List<PosTenderAllocationDraft> allocations,
  }) async {
    lastFreezeAllocationCount = allocations.length;
    plan = PosTenderPlanView(
      planRef: '01K7A000000000000000000011',
      orderRef: source.orderRef,
      status: PosTenderPlanStatus.frozen,
      receivableAmountMinor: source.receivableAmountMinor,
      succeededAmountMinor: 0,
      occupiedAmountMinor: 0,
      currency: 'CNY',
      allocations: const [
        PosTenderAllocationView(
          allocationRef: '01K7A000000000000000000012',
          sequenceNo: 1,
          tenderType: PosTenderType.electronic,
          status: PosTenderAllocationStatus.planned,
          amountMinor: 650,
        ),
        PosTenderAllocationView(
          allocationRef: '01K7A000000000000000000013',
          sequenceNo: 2,
          tenderType: PosTenderType.cash,
          status: PosTenderAllocationStatus.planned,
          amountMinor: 649,
        ),
      ],
      updatedAt: _at,
      duplicate: false,
    );
    return plan!;
  }

  @override
  Future<PosTenderPlanView> find(String planRef) async => plan!;

  @override
  Future<PosTenderPlanView> collect({
    required String planRef,
    required String allocationRef,
    int? tenderedMinor,
  }) async {
    collectCount++;
    throw const PosTenderFailure('PAYMENT_EXTERNAL_BLOCKED', '电子支付资料尚未解阻。');
  }
}
