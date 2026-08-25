import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/app/jshpos_app.dart';
import 'package:jshpos_pos/features/exchange/application/pos_exchange_application_service.dart';
import 'package:jshpos_pos/features/exchange/application/pos_exchange_controller.dart';
import 'package:jshpos_pos/features/exchange/domain/pos_exchange_models.dart';
import 'package:jshpos_pos/features/exchange/presentation/pos_exchange_page.dart';
import 'package:jshpos_pos/features/return_refund/domain/pos_return_models.dart';
import 'package:jshpos_pos/features/sale/domain/pos_sale_models.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/shift/application/pos_shift_application_service.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/features/tender/application/pos_tender_application_service.dart';
import 'package:jshpos_pos/features/tender/application/pos_tender_controller.dart';
import 'package:jshpos_pos/features/tender/domain/pos_tender_models.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  group('G9A-R3D R3 同一可信 POS 会话联合旅程', () {
    testWidgets('六个正式页面沿用同一租户门店班次与业务日', (tester) async {
      tester.view.physicalSize = const Size(1440, 1100);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_app(_JourneyShiftService()));
      await tester.pumpAndSettle();
      await _login(tester);

      expect(find.text('虚构便利租户 · Asia/Shanghai'), findsOneWidget);
      expect(find.text('业务日 2026-08-25'), findsOneWidget);

      await _openAndReturn(
        tester,
        tileLabel: '收银工作台',
        pageMarker: const Key('retrySaleWorkspace'),
      );
      await _openAndReturn(
        tester,
        tileLabel: '组合支付',
        pageMarker: const Key('tenderTrustedContext'),
      );
      await _openAndReturn(
        tester,
        tileLabel: '班次现金与钱箱',
        pageMarker: const Key('cashFrozenContext'),
      );
      await _openAndReturn(
        tester,
        tileLabel: '原单退货退款',
        pageMarker: const Key('returnOrderQuery'),
      );

      final homeContext = tester.element(find.text('可用工作区'));
      unawaited(
        Navigator.of(homeContext).push(
          MaterialPageRoute<void>(
            builder: (_) => PosExchangePage(
              controller: PosExchangeController(
                service: _JourneyExchangeService(),
                source: _exchangeSource(),
              ),
              allowApprove: true,
              allowRecover: true,
            ),
          ),
        ),
      );
      await _pumpRoute(tester);
      expect(find.byKey(const Key('exchangeOwnerBoundary')), findsOneWidget);
      await tester.pageBack();
      await _pumpRoute(tester);

      expect(find.text('虚构便利租户 · Asia/Shanghai'), findsOneWidget);
      expect(find.text('业务日 2026-08-25'), findsOneWidget);
      expect(find.text('班次进行中'), findsOneWidget);
    });

    testWidgets('现金失败跨页返回后只恢复原幂等命令', (tester) async {
      tester.view.physicalSize = const Size(1440, 1100);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final shiftService = _JourneyShiftService(failCashCall: 2);

      await tester.pumpWidget(_app(shiftService));
      await tester.pumpAndSettle();
      await _login(tester);
      await _openCashPage(tester);

      await _submitCash(tester, amount: '1.00', reason: '虚构成功现金动作');
      await _hideSnackBar(tester);
      await _submitCash(tester, amount: '2.00', reason: '虚构失败现金动作');
      expect(find.textContaining('原操作键已保留'), findsOneWidget);
      await _hideSnackBar(tester);

      await tester.pageBack();
      await tester.pumpAndSettle();
      await _openCashPage(tester);
      await _submitCash(tester, amount: '2.00', reason: '虚构失败现金动作');

      expect(shiftService.cashKeys, hasLength(3));
      expect(shiftService.cashKeys[2], shiftService.cashKeys[1]);
      expect(find.textContaining('当前理论现金'), findsOneWidget);
    });
  });
}

JshposApp _app(PosShiftApplicationService shiftService) => JshposApp(
  deviceGateway: const _JourneyDeviceGateway(),
  sessionRepository: _JourneySessionRepository(),
  shiftService: shiftService,
  tenderController: PosTenderController(
    service: _JourneyTenderService(),
    source: const PosTenderSource(
      orderRef: '01K2A000000000000000000031',
      orderSnapshotSha256:
          'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      storeRef: '1101',
      terminalRef: '01K2A000000000000000000011',
      shiftRef: '01K2A000000000000000000021',
      businessDate: '2026-08-25',
      receivableAmountMinor: 1200,
    ),
  ),
);

Future<void> _login(WidgetTester tester) async {
  await tester.enterText(find.byKey(const Key('employeeLogin')), 'cashier01');
  await tester.enterText(
    find.byKey(const Key('employeeSecret')),
    'synthetic-pin',
  );
  await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
  await tester.pumpAndSettle();
}

Future<void> _openAndReturn(
  WidgetTester tester, {
  required String tileLabel,
  required Key pageMarker,
}) async {
  await tester.ensureVisible(find.text(tileLabel));
  await tester.tap(find.text(tileLabel));
  await _pumpRoute(tester);
  expect(find.byKey(pageMarker), findsOneWidget);
  await tester.pageBack();
  await _pumpRoute(tester);
  expect(find.text('班次进行中'), findsOneWidget);
}

Future<void> _openCashPage(WidgetTester tester) async {
  await tester.ensureVisible(find.text('班次现金与钱箱'));
  await tester.tap(find.text('班次现金与钱箱'));
  await _pumpRoute(tester);
  expect(find.byKey(const Key('cashFrozenContext')), findsOneWidget);
}

Future<void> _pumpRoute(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
  await tester.pump(const Duration(milliseconds: 400));
}

Future<void> _submitCash(
  WidgetTester tester, {
  required String amount,
  required String reason,
}) async {
  await tester.enterText(find.byType(TextField).at(0), amount);
  await tester.enterText(find.byType(TextField).at(1), reason);
  await tester.tap(find.byKey(const Key('recordShiftCashMovement')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('confirmCashOperation')));
  await tester.pumpAndSettle();
}

Future<void> _hideSnackBar(WidgetTester tester) async {
  ScaffoldMessenger.of(tester.element(find.byType(Scaffold).first))
      .hideCurrentSnackBar();
  await tester.pumpAndSettle();
}

final class _JourneyDeviceGateway implements PosDeviceGateway {
  const _JourneyDeviceGateway();

  @override
  Future<DeviceSnapshot> snapshot() async => DeviceSnapshot(
    metadata: const DeviceMetadata(
      manufacturer: 'ACME',
      model: 'POS-R3D',
      androidRelease: '14',
      androidSdk: 34,
      adapterVersion: '0.1.0',
    ),
    capabilities: {DeviceCapability.receiptPrinter},
  );
}

final class _JourneySessionRepository implements PosSessionRepository {
  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) async =>
      _terminal;

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) async => PosLoginResult(employee: _employee, shift: _shift);

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async =>
      PosSessionRefresh(terminal: terminal, employee: employee, shift: _shift);

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {}
}

final class _JourneyShiftService implements PosShiftApplicationService {
  _JourneyShiftService({this.failCashCall});

  final int? failCashCall;
  final List<String> cashKeys = <String>[];

  @override
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async {
    cashKeys.add(idempotencyKey);
    if (cashKeys.length == failCashCall) {
      throw const PosSessionFailure('SHIFT_WRITE_FAILED', '本地事务未完成');
    }
    return ShiftOperationResult(
      operationId: 'OP-R3D-${cashKeys.length}',
      shiftId: shiftId,
      operationType: movementType.wireCode,
      theoreticalCashMinor: 10100,
      recordVersion: cashKeys.length,
      deviceExecutionStatus: 'NOT_REQUIRED',
    );
  }

  @override
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => ShiftOperationResult(
    operationId: 'DRAWER-R3D-001',
    shiftId: shiftId,
    operationType: 'NO_SALE_DRAWER',
    theoreticalCashMinor: 10000,
    recordVersion: 1,
    deviceExecutionStatus: 'BLOCKED_EXTERNAL',
  );

  @override
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  }) async => _shift;

  @override
  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  }) async {}
}

final class _JourneyTenderService implements PosTenderApplicationService {
  @override
  Future<PosTenderPlanView> freeze({
    required PosTenderSource source,
    required List<PosTenderAllocationDraft> allocations,
  }) async =>
      throw const PosTenderFailure('PAYMENT_EXTERNAL_BLOCKED', '电子支付资料尚未解阻。');

  @override
  Future<PosTenderPlanView> find(String planRef) async =>
      throw const PosTenderFailure('PAYMENT_EXTERNAL_BLOCKED', '电子支付资料尚未解阻。');

  @override
  Future<PosTenderPlanView> collect({
    required String planRef,
    required String allocationRef,
    int? tenderedMinor,
  }) async =>
      throw const PosTenderFailure('PAYMENT_EXTERNAL_BLOCKED', '电子支付资料尚未解阻。');
}

final class _JourneyExchangeService implements PosExchangeApplicationService {
  @override
  Future<PosExchangeView> create({
    required PosExchangeSource source,
    required String reasonCode,
  }) async =>
      throw const PosExchangeFailure('EXCHANGE_LOCKED', '联合验收不创建新换货命令。');

  @override
  Future<PosExchangeView> refreshExchange(String exchangeRef) async =>
      throw const PosExchangeFailure('EXCHANGE_LOCKED', '联合验收不查询外部状态。');

  @override
  Future<PosExchangeView> approve({
    required String exchangeRef,
    required String correlationRef,
    required String reasonCode,
  }) async => throw const PosExchangeFailure('EXCHANGE_LOCKED', '联合验收不执行审批。');

  @override
  Future<PosExchangeView> recover({
    required String exchangeRef,
    required String correlationRef,
    required String targetLeg,
    required String reasonCode,
  }) async => throw const PosExchangeFailure('EXCHANGE_LOCKED', '联合验收不执行恢复。');
}

PosExchangeSource _exchangeSource() => PosExchangeSource(
  originalReturn: PosReturnSubmissionView(
    returnRef: '01K7A000000000000000000002',
    requestCommandRef: '01K7A000000000000000000003',
    orderRef: '01K7A000000000000000000004',
    status: PosReturnSagaStatus.completed,
    refundableAmountMinor: 900,
    promotionSnapshotRef: '01K7A000000000000000000005',
    promotionSnapshotSha256: 'a'.padRight(64, 'a'),
    auditRef: 'RETURN_HISTORY:SYNTHETIC',
    correlationRef: '01K7A000000000000000000006',
    updatedAt: DateTime.utc(2026, 8, 25),
    duplicate: false,
  ),
  newSale: PosCashSettlementView(
    commandRef: '01K7A000000000000000000007',
    orderRef: '01K7A000000000000000000008',
    localOrderNo: 'SYN-ORDER-R3D',
    receivableAmountMinor: 1200,
    tenderedAmountMinor: 1200,
    changeAmountMinor: 0,
    snapshotDigest: 'b'.padRight(64, 'b'),
    quoteFingerprint: 'c'.padRight(64, 'c'),
    settlementFingerprint: 'c'.padRight(64, 'c'),
    outboxEventRef: '01K7A000000000000000000009',
    completedAt: DateTime.utc(2026, 8, 25),
    duplicate: false,
  ),
);

final _terminal = TrustedTerminalContext(
  tenantId: 'TENANT_A',
  tenantName: '虚构便利租户',
  orgUnitId: '101',
  storeId: '1101',
  storeName: '虚构便利一店',
  terminalId: '01K2A000000000000000000011',
  terminalName: '虚构收银机 R3D',
  storeTimezone: 'Asia/Shanghai',
  businessDate: '2026-08-25',
  status: 'ACTIVE',
  protocolVersion: '1.0',
  validUntil: DateTime.utc(2099),
  approvedCapabilities: {DeviceCapability.receiptPrinter},
);

final _employee = EmployeeSession(
  employeeId: '101',
  employeeName: '虚构收银员甲',
  sessionRef: 'session:synthetic:r3d:0001',
  authenticatedAt: DateTime.utc(2026, 8, 25, 8),
  expiresAt: DateTime.utc(2099),
  roles: {'CASHIER', 'SUPERVISOR'},
  permissions: PosPermission.values.toSet(),
);

final _shift = PosShiftContext(
  shiftId: '01K2A000000000000000000021',
  businessDate: '2026-08-25',
  status: 'OPEN',
  openedAt: DateTime.utc(2026, 8, 25, 8),
);
