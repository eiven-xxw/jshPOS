import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/app/jshpos_app.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/shift/application/pos_shift_application_service.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/features/tender/application/pos_tender_application_service.dart';
import 'package:jshpos_pos/features/tender/application/pos_tender_controller.dart';
import 'package:jshpos_pos/features/tender/domain/pos_tender_models.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  testWidgets('生产默认会在没有可信会话适配器时保持安全锁定', (tester) async {
    await tester.pumpWidget(
      const JshposApp(deviceGateway: _FakeDeviceGateway()),
    );
    await tester.pumpAndSettle();

    expect(find.text('鲸熵汇收银系统'), findsOneWidget);
    expect(find.text('终端安全锁定'), findsOneWidget);
    expect(find.textContaining('TERMINAL_UNTRUSTED'), findsOneWidget);
    expect(find.textContaining('ACME POS-01'), findsNothing);
  });

  testWidgets('可信虚构终端进入员工登录并可安全退出', (tester) async {
    final repository = _FakeSessionRepository();
    await tester.pumpWidget(
      JshposApp(
        deviceGateway: const _FakeDeviceGateway(),
        sessionRepository: repository,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('虚构便利一店 · 虚构收银机 01'), findsOneWidget);
    expect(find.byKey(const Key('employeeLoginSubmit')), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const Key('employeeLoginSubmit'))).height,
      greaterThanOrEqualTo(48),
    );

    await tester.enterText(find.byKey(const Key('employeeLogin')), 'cashier01');
    await tester.enterText(
      find.byKey(const Key('employeeSecret')),
      'synthetic-pin',
    );
    await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
    await tester.pumpAndSettle();

    expect(find.text('虚构收银员甲'), findsOneWidget);
    expect(find.text('班次进行中'), findsOneWidget);
    expect(find.text('收银工作台'), findsOneWidget);
    expect(find.text('synthetic-pin'), findsNothing);

    await tester.tap(find.byKey(const Key('secureLogout')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('employeeLoginSubmit')), findsOneWidget);
    expect(repository.logoutCount, 1);
  });

  testWidgets('无效凭据只显示稳定安全错误且不会泄露账号状态', (tester) async {
    final repository = _FakeSessionRepository(failAuthentication: true);
    await tester.pumpWidget(
      JshposApp(
        deviceGateway: const _FakeDeviceGateway(),
        sessionRepository: repository,
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('employeeLogin')), 'unknown');
    await tester.enterText(
      find.byKey(const Key('employeeSecret')),
      'wrong-secret',
    );
    await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
    await tester.pumpAndSettle();

    expect(find.textContaining('AUTH_FAILED'), findsOneWidget);
    expect(find.textContaining('账号不存在'), findsNothing);
    expect(find.textContaining('wrong-secret'), findsNothing);
  });

  testWidgets('开关班页面只在正式班次应用服务成功后更新会话', (tester) async {
    final repository = _FakeSessionRepository(startWithoutShift: true);
    final shiftService = _FakeShiftApplicationService();
    await tester.pumpWidget(
      JshposApp(
        deviceGateway: const _FakeDeviceGateway(),
        sessionRepository: repository,
        shiftService: shiftService,
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('employeeLogin')), 'cashier01');
    await tester.enterText(
      find.byKey(const Key('employeeSecret')),
      'synthetic-pin',
    );
    await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('未开班'), findsOneWidget);

    await tester.tap(find.text('开启班次'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('开启班次-cash')), '100.00');
    await tester.tap(find.byKey(const Key('开启班次-submit')));
    await tester.pumpAndSettle();

    expect(find.text('班次进行中'), findsOneWidget);
    expect(shiftService.openingCash, '100.00');

    await tester.tap(find.text('关闭班次'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('关闭班次-cash')), '100.00');
    await tester.tap(find.byKey(const Key('关闭班次-submit')));
    await tester.pumpAndSettle();

    expect(find.text('未开班'), findsOneWidget);
    expect(shiftService.closedShiftId, _shift.shiftId);
  });

  testWidgets('班次应用失败仅展示稳定错误且不伪造开班成功', (tester) async {
    final repository = _FakeSessionRepository(startWithoutShift: true);
    await tester.pumpWidget(
      JshposApp(
        deviceGateway: const _FakeDeviceGateway(),
        sessionRepository: repository,
        shiftService: _FakeShiftApplicationService(failOpen: true),
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('employeeLogin')), 'cashier01');
    await tester.enterText(
      find.byKey(const Key('employeeSecret')),
      'synthetic-pin',
    );
    await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开启班次'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('开启班次-cash')), '100.00');
    await tester.tap(find.byKey(const Key('开启班次-submit')));
    await tester.pumpAndSettle();

    expect(find.textContaining('SHIFT_OPEN_BLOCKED'), findsOneWidget);
    expect(find.text('未开班'), findsOneWidget);
  });

  testWidgets('可信会话只使用冻结订单班次业务日进入组合支付且电子份额失败关闭', (tester) async {
    final tenderService = _BlockedTenderApplicationService();
    final tenderController = PosTenderController(
      service: tenderService,
      source: const PosTenderSource(
        orderRef: '01K2A000000000000000000031',
        orderSnapshotSha256:
            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        storeRef: '1101',
        terminalRef: '01K2A000000000000000000011',
        shiftRef: '01K2A000000000000000000021',
        businessDate: '2026-08-20',
        receivableAmountMinor: 1200,
      ),
    );
    await tester.pumpWidget(
      JshposApp(
        deviceGateway: const _FakeDeviceGateway(),
        sessionRepository: _FakeSessionRepository(),
        tenderController: tenderController,
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('employeeLogin')), 'cashier01');
    await tester.enterText(
      find.byKey(const Key('employeeSecret')),
      'synthetic-pin',
    );
    await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('openTenderPlan')), findsOneWidget);
    await tester.ensureVisible(find.byKey(const Key('openTenderPlan')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('openTenderPlan')));
    await tester.pumpAndSettle();
    expect(find.text('组合支付'), findsOneWidget);
    expect(find.textContaining('业务日 2026-08-20'), findsOneWidget);
    expect(find.textContaining('离线计划仅冻结本地事实'), findsOneWidget);

    await tester.tap(find.byKey(const Key('freezeTenderPlan')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('collectTender-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('collectTender-1')));
    await tester.pumpAndSettle();
    expect(find.textContaining('PAYMENT_EXTERNAL_BLOCKED'), findsOneWidget);
    expect(tenderService.collectCount, 1);
  });

  testWidgets('组合支付冻结来源与可信会话不一致时失败关闭且不得进入支付页', (tester) async {
    final tenderController = PosTenderController(
      service: _BlockedTenderApplicationService(),
      source: const PosTenderSource(
        orderRef: '01K2A000000000000000000032',
        orderSnapshotSha256:
            'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
        storeRef: '9999',
        terminalRef: '01K2A000000000000000000011',
        shiftRef: '01K2A000000000000000000021',
        businessDate: '2026-08-20',
        receivableAmountMinor: 1200,
      ),
    );
    await tester.pumpWidget(
      JshposApp(
        deviceGateway: const _FakeDeviceGateway(),
        sessionRepository: _FakeSessionRepository(),
        tenderController: tenderController,
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('employeeLogin')), 'cashier01');
    await tester.enterText(
      find.byKey(const Key('employeeSecret')),
      'synthetic-pin',
    );
    await tester.tap(find.byKey(const Key('employeeLoginSubmit')));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('openTenderPlan')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('openTenderPlan')));
    await tester.pumpAndSettle();

    expect(find.textContaining('TENDER_CONTEXT_MISMATCH'), findsOneWidget);
    expect(find.byKey(const Key('tenderTrustedContext')), findsNothing);
  });
}

class _FakeDeviceGateway implements PosDeviceGateway {
  const _FakeDeviceGateway();

  @override
  Future<DeviceSnapshot> snapshot() async => DeviceSnapshot(
    metadata: const DeviceMetadata(
      manufacturer: 'ACME',
      model: 'POS-01',
      androidRelease: '14',
      androidSdk: 34,
      adapterVersion: '0.1.0',
    ),
    capabilities: {DeviceCapability.receiptPrinter},
  );
}

class _FakeSessionRepository implements PosSessionRepository {
  _FakeSessionRepository({
    this.failAuthentication = false,
    bool startWithoutShift = false,
  }) : initialShift = startWithoutShift ? null : _shift;

  final bool failAuthentication;
  final PosShiftContext? initialShift;
  int logoutCount = 0;

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) async =>
      _terminal;

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) async {
    if (failAuthentication) throw StateError('synthetic invalid credential');
    return PosLoginResult(employee: _employee, shift: initialShift);
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async => PosSessionRefresh(
    terminal: terminal,
    employee: employee,
    shift: initialShift,
  );

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {
    logoutCount++;
  }
}

final class _FakeShiftApplicationService implements PosShiftApplicationService {
  _FakeShiftApplicationService({this.failOpen = false});

  final bool failOpen;
  String? openingCash;
  String? closedShiftId;

  @override
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  }) async {
    if (failOpen) {
      throw const PosSessionFailure('SHIFT_OPEN_BLOCKED', '班次状态不允许开班。');
    }
    this.openingCash = openingCash;
    return _shift;
  }

  @override
  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  }) async {
    closedShiftId = shiftId;
  }

  @override
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => throw UnimplementedError();

  @override
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => throw UnimplementedError();
}

final class _BlockedTenderApplicationService
    implements PosTenderApplicationService {
  int collectCount = 0;
  PosTenderPlanView? _plan;

  @override
  Future<PosTenderPlanView> freeze({
    required PosTenderSource source,
    required List<PosTenderAllocationDraft> allocations,
  }) async {
    _plan ??= PosTenderPlanView(
      planRef: '01K2A000000000000000000032',
      orderRef: source.orderRef,
      status: PosTenderPlanStatus.frozen,
      receivableAmountMinor: source.receivableAmountMinor,
      succeededAmountMinor: 0,
      occupiedAmountMinor: 0,
      currency: source.currency,
      allocations: const [
        PosTenderAllocationView(
          allocationRef: '01K2A000000000000000000033',
          sequenceNo: 1,
          tenderType: PosTenderType.electronic,
          status: PosTenderAllocationStatus.planned,
          amountMinor: 600,
        ),
        PosTenderAllocationView(
          allocationRef: '01K2A000000000000000000034',
          sequenceNo: 2,
          tenderType: PosTenderType.cash,
          status: PosTenderAllocationStatus.planned,
          amountMinor: 600,
        ),
      ],
      updatedAt: DateTime.utc(2026, 8, 20, 8),
      duplicate: false,
    );
    return _plan!;
  }

  @override
  Future<PosTenderPlanView> find(String planRef) async => _plan!;

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

final _terminal = TrustedTerminalContext(
  tenantId: 'TENANT_A',
  tenantName: '虚构便利租户',
  orgUnitId: '101',
  storeId: '1101',
  storeName: '虚构便利一店',
  terminalId: '01K2A000000000000000000011',
  terminalName: '虚构收银机 01',
  storeTimezone: 'Asia/Shanghai',
  businessDate: '2026-08-20',
  status: 'ACTIVE',
  protocolVersion: '1.0',
  validUntil: DateTime.utc(2099),
  approvedCapabilities: {DeviceCapability.receiptPrinter},
);

final _employee = EmployeeSession(
  employeeId: '101',
  employeeName: '虚构收银员甲',
  sessionRef: 'session:synthetic:0001',
  authenticatedAt: DateTime.utc(2026, 8, 20, 8),
  expiresAt: DateTime.utc(2099),
  roles: {'CASHIER'},
  permissions: {
    PosPermission.sessionLogin,
    PosPermission.shiftOpen,
    PosPermission.shiftClose,
    PosPermission.saleOperate,
    PosPermission.cashSettle,
    PosPermission.syncView,
    PosPermission.printPreview,
  },
);

final _shift = PosShiftContext(
  shiftId: '01K2A000000000000000000021',
  businessDate: '2026-08-20',
  status: 'OPEN',
  openedAt: DateTime.utc(2026, 8, 20, 8),
);
