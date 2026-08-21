import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/exchange/application/pos_exchange_application_service.dart';
import 'package:jshpos_pos/features/exchange/application/pos_exchange_controller.dart';
import 'package:jshpos_pos/features/exchange/domain/pos_exchange_models.dart';
import 'package:jshpos_pos/features/exchange/presentation/pos_exchange_page.dart';
import 'package:jshpos_pos/features/return_refund/domain/pos_return_models.dart';
import 'package:jshpos_pos/features/sale/domain/pos_sale_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const _binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K7A000000000000000000001',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test('SQLite V12 freezes exchange command journal and append-only event policy', () {
    final database = PosLocalDatabase.inMemory(_binding);
    addTearDown(database.close);
    expect(database.database.select('PRAGMA user_version').single.values.first, 12);
    expect(
      database.database.select(
        "SELECT COUNT(*) value FROM sqlite_master WHERE type='table' AND name IN ('local_exchange_command','local_exchange_event')",
      ).single['value'],
      2,
    );
    expect(
      database.database.select(
        "SELECT COUNT(*) value FROM sqlite_master WHERE type='trigger' AND name LIKE 'local_exchange_%'",
      ).single['value'],
      4,
    );
  });

  testWidgets('page renders two separate owner legs and creates append-only link', (tester) async {
    final service = _FakeExchangeService();
    final controller = PosExchangeController(service: service, source: _source());
    await tester.pumpWidget(MaterialApp(home: PosExchangePage(
      controller: controller,
      allowApprove: true,
      allowRecover: true,
    )));
    expect(find.byKey(const Key('exchangeOwnerBoundary')), findsOneWidget);
    expect(find.text('原单退货退款'), findsOneWidget);
    expect(find.text('新销售'), findsOneWidget);
    expect(find.byKey(const Key('exchangeDisplayDifference')), findsOneWidget);
    await tester.tap(find.byKey(const Key('createExchangeLink')));
    await tester.pumpAndSettle();
    expect(service.createCount, 1);
    expect(find.byKey(const Key('exchangeSagaStatus')), findsOneWidget);
    expect(find.text('等待另一名受权员工审批'), findsOneWidget);
    await tester.tap(find.byKey(const Key('approveExchange')));
    await tester.pumpAndSettle();
    expect(service.approveCount, 1);
  });

  test('UNKNOWN preserves original exchange id and refresh never creates replacement', () async {
    final service = _FakeExchangeService(unknownOnCreate: true);
    final controller = PosExchangeController(service: service, source: _source());
    await controller.create('CUSTOMER_EXCHANGE');
    expect(controller.state.phase, PosExchangePagePhase.unknown);
    expect(controller.state.recoverableExchangeRef, _exchangeId);
    await controller.create('CUSTOMER_EXCHANGE');
    expect(service.createCount, 1);
    await controller.refresh();
    expect(service.refreshCount, 1);
    expect(controller.state.view?.exchangeRef, _exchangeId);
  });
}

PosExchangeSource _source() => PosExchangeSource(
  originalReturn: PosReturnSubmissionView(
    returnRef: _returnId,
    requestCommandRef: '01K7A000000000000000000003',
    orderRef: '01K7A000000000000000000004',
    status: PosReturnSagaStatus.completed,
    refundableAmountMinor: 900,
    promotionSnapshotRef: '01K7A000000000000000000005',
    promotionSnapshotSha256: 'a'.padRight(64, 'a'),
    auditRef: 'RETURN_HISTORY:SYNTHETIC',
    correlationRef: '01K7A000000000000000000006',
    updatedAt: DateTime.utc(2026, 8, 22),
    duplicate: false,
  ),
  newSale: PosCashSettlementView(
    commandRef: '01K7A000000000000000000007',
    orderRef: '01K7A000000000000000000008',
    localOrderNo: 'SYN-ORDER-002',
    receivableAmountMinor: 1200,
    tenderedAmountMinor: 1200,
    changeAmountMinor: 0,
    snapshotDigest: 'b'.padRight(64, 'b'),
    quoteFingerprint: 'c'.padRight(64, 'c'),
    settlementFingerprint: 'c'.padRight(64, 'c'),
    outboxEventRef: '01K7A000000000000000000009',
    completedAt: DateTime.utc(2026, 8, 22),
    duplicate: false,
  ),
);

final class _FakeExchangeService implements PosExchangeApplicationService {
  _FakeExchangeService({this.unknownOnCreate = false});
  final bool unknownOnCreate;
  int createCount = 0;
  int refreshCount = 0;
  int approveCount = 0;
  int recoverCount = 0;

  @override
  Future<PosExchangeView> create({required PosExchangeSource source, required String reasonCode}) async {
    createCount++;
    if (unknownOnCreate) {
      throw const PosExchangeFailure(
        'EXCHANGE_RESULT_UNKNOWN',
        '结果未知',
        resultUnknown: true,
        exchangeRef: _exchangeId,
      );
    }
    return _view(PosExchangeStatus.draft);
  }

  @override
  Future<PosExchangeView> refresh(String exchangeRef) async {
    refreshCount++;
    return _view(PosExchangeStatus.draft);
  }

  @override
  Future<PosExchangeView> approve({
    required String exchangeRef,
    required String correlationRef,
    required String reasonCode,
  }) async {
    approveCount++;
    return _view(PosExchangeStatus.returnPending);
  }

  @override
  Future<PosExchangeView> recover({
    required String exchangeRef,
    required String correlationRef,
    required String targetLeg,
    required String reasonCode,
  }) async {
    recoverCount++;
    return _view(PosExchangeStatus.salePending);
  }
}

PosExchangeView _view(PosExchangeStatus status) => PosExchangeView(
  exchangeRef: _exchangeId,
  returnRef: _returnId,
  newOrderRef: '01K7A000000000000000000008',
  status: status,
  expectedRefundAmountMinor: 900,
  expectedSaleReceivableMinor: 1200,
  displayDifferenceMinor: 300,
  correlationRef: '01K7A000000000000000000010',
  recordVersion: 1,
  updatedAt: DateTime.utc(2026, 8, 22),
  duplicate: false,
);

const _exchangeId = '01K7A000000000000000000011';
const _returnId = '01K7A000000000000000000002';
