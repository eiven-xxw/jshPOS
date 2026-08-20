import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/application/pos_session_service.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  group('T2-POS-007 可信会话应用服务', () {
    test('可信终端启动后只进入未登录状态且租户来自仓储端口', () async {
      final fixture = _Fixture();

      final state = await fixture.service.bootstrap();

      expect(state.phase, PosSessionPhase.signedOut);
      expect(state.terminal?.tenantId, 'TENANT_A');
      expect(state.terminal?.storeId, '1101');
      expect(state.employee, isNull);
      expect(fixture.repository.verifyCount, 1);
    });

    test('终端吊销或验证失败时锁定且员工登录不可达', () async {
      final fixture = _Fixture(
        verifyFailure: const PosSessionFailure(
          'TERMINAL_REVOKED',
          '终端已停用。',
        ),
      );

      final state = await fixture.service.bootstrap();
      final login = await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      expect(state.phase, PosSessionPhase.locked);
      expect(state.errorCode, 'TERMINAL_REVOKED');
      expect(login.phase, PosSessionPhase.locked);
      expect(fixture.repository.authenticateCount, 0);
    });

    test('重复登录共享同一航班且不保留口令', () async {
      final completer = Completer<PosLoginResult>();
      final fixture = _Fixture(loginCompleter: completer);
      await fixture.service.bootstrap();

      final first = fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final second = fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      completer.complete(PosLoginResult(employee: employee, shift: shift));
      final states = await Future.wait([first, second]);

      expect(fixture.repository.authenticateCount, 1);
      expect(states.every((state) => state.employee?.employeeId == '101'), isTrue);
      expect('$states', isNot(contains('synthetic-pin')));
    });

    test('权限拒绝在本地失败关闭且没有隐式放行', () async {
      final fixture = _Fixture(
        loginResult: PosLoginResult(
          employee: EmployeeSession(
            employeeId: '101',
            employeeName: '虚构只读员工',
            sessionRef: 'session:synthetic:readonly',
            authenticatedAt: fixtureNow,
            expiresAt: fixtureNow.add(const Duration(hours: 8)),
            roles: {'VIEWER'},
            permissions: {PosPermission.sessionLogin},
          ),
        ),
      );
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'viewer01',
        secret: 'synthetic-pin',
      );

      expect(
        () => fixture.service.requirePermission(PosPermission.cashSettle),
        throwsA(
          isA<PosSessionFailure>().having(
            (error) => error.code,
            'code',
            'PERMISSION_DENIED',
          ),
        ),
      );
    });

    test('业务日切换保留原班次但阻断危险操作', () async {
      final nextTerminal = terminalFor(businessDate: '2026-08-21');
      final fixture = _Fixture(
        refreshResult: PosSessionRefresh(
          terminal: nextTerminal,
          employee: employee,
          shift: shift,
        ),
      );
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      final state = await fixture.service.refresh();

      expect(state.businessDateChanged, isTrue);
      expect(state.errorCode, 'BUSINESS_DATE_CHANGED');
      expect(state.shift?.shiftId, shift.shiftId);
      expect(
        () => fixture.service.requirePermission(PosPermission.saleOperate),
        throwsA(isA<PosSessionFailure>()),
      );
    });

    test('刷新出现跨租户替换时清理员工并锁屏', () async {
      final fixture = _Fixture(
        refreshResult: PosSessionRefresh(
          terminal: terminalFor(tenantId: 'TENANT_B'),
          employee: employee,
          shift: shift,
        ),
      );
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      final state = await fixture.service.refresh();

      expect(state.phase, PosSessionPhase.locked);
      expect(state.errorCode, 'SESSION_CONTEXT_MISMATCH');
      expect(state.employee, isNull);
      expect(state.terminal, isNull);
    });

    test('安全退出只调用一次服务端撤销并清空员工和班次', () async {
      final fixture = _Fixture();
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      final states = await Future.wait([
        fixture.service.logout(),
        fixture.service.logout(),
      ]);

      expect(fixture.repository.logoutCount, 1);
      expect(states.last.phase, PosSessionPhase.signedOut);
      expect(states.last.employee, isNull);
      expect(states.last.shift, isNull);
    });

    test('终端上报未获批准能力时失败关闭', () async {
      final fixture = _Fixture(
        deviceSnapshot: DeviceSnapshot(
          metadata: deviceSnapshot.metadata,
          capabilities: {
            DeviceCapability.receiptPrinter,
            DeviceCapability.cashDrawer,
          },
        ),
      );

      final state = await fixture.service.bootstrap();

      expect(state.phase, PosSessionPhase.locked);
      expect(state.errorCode, 'TERMINAL_CAPABILITY_MISMATCH');
    });
  });
}

final fixtureNow = DateTime.utc(2026, 8, 20, 8);

final deviceSnapshot = DeviceSnapshot(
  metadata: const DeviceMetadata(
    manufacturer: 'ACME',
    model: 'POS-01',
    androidRelease: '14',
    androidSdk: 34,
    adapterVersion: '0.1.0',
  ),
  capabilities: {DeviceCapability.receiptPrinter},
);

TrustedTerminalContext terminalFor({
  String tenantId = 'TENANT_A',
  String businessDate = '2026-08-20',
}) => TrustedTerminalContext(
  tenantId: tenantId,
  tenantName: '虚构便利租户',
  orgUnitId: '101',
  storeId: '1101',
  storeName: '虚构便利一店',
  terminalId: '01K2A000000000000000000011',
  terminalName: '虚构收银机 01',
  storeTimezone: 'Asia/Shanghai',
  businessDate: businessDate,
  status: 'ACTIVE',
  protocolVersion: '1.0',
  validUntil: DateTime.utc(2099),
  approvedCapabilities: {DeviceCapability.receiptPrinter},
);

final employee = EmployeeSession(
  employeeId: '101',
  employeeName: '虚构收银员甲',
  sessionRef: 'session:synthetic:0001',
  authenticatedAt: fixtureNow,
  expiresAt: fixtureNow.add(const Duration(hours: 8)),
  roles: {'CASHIER'},
  permissions: {
    PosPermission.sessionLogin,
    PosPermission.shiftOpen,
    PosPermission.shiftClose,
    PosPermission.saleOperate,
    PosPermission.cashSettle,
  },
);

final shift = PosShiftContext(
  shiftId: '01K2A000000000000000000021',
  businessDate: '2026-08-20',
  status: 'OPEN',
  openedAt: fixtureNow,
);

class _Fixture {
  _Fixture({
    PosSessionFailure? verifyFailure,
    Completer<PosLoginResult>? loginCompleter,
    PosLoginResult? loginResult,
    PosSessionRefresh? refreshResult,
    DeviceSnapshot? deviceSnapshot,
  }) : gateway = _FakeDeviceGateway(deviceSnapshot ?? posSessionTestDevice),
       repository = _FakeRepository(
         verifyFailure: verifyFailure,
         loginCompleter: loginCompleter,
         loginResult:
             loginResult ?? PosLoginResult(employee: employee, shift: shift),
         refreshResult: refreshResult,
       ) {
    service = PosSessionService(
      deviceGateway: gateway,
      repository: repository,
      correlationId: () => '01K2A000000000000000000099',
      now: () => fixtureNow,
    );
  }

  static final posSessionTestDevice = deviceSnapshot;
  final _FakeDeviceGateway gateway;
  final _FakeRepository repository;
  late final PosSessionService service;
}

class _FakeDeviceGateway implements PosDeviceGateway {
  const _FakeDeviceGateway(this.device);
  final DeviceSnapshot device;
  @override
  Future<DeviceSnapshot> snapshot() async => device;
}

class _FakeRepository implements PosSessionRepository {
  _FakeRepository({
    required this.verifyFailure,
    required this.loginCompleter,
    required this.loginResult,
    required this.refreshResult,
  });

  final PosSessionFailure? verifyFailure;
  final Completer<PosLoginResult>? loginCompleter;
  final PosLoginResult loginResult;
  final PosSessionRefresh? refreshResult;
  int verifyCount = 0;
  int authenticateCount = 0;
  int logoutCount = 0;

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) async {
    verifyCount++;
    if (verifyFailure != null) throw verifyFailure!;
    return terminalFor();
  }

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) {
    authenticateCount++;
    return loginCompleter?.future ?? Future.value(loginResult);
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async =>
      refreshResult ??
      PosSessionRefresh(
        terminal: terminal,
        employee: employee,
        shift: shift,
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
