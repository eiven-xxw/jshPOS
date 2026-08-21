import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/application/pos_session_service.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/session/infrastructure/locked_pos_session_repository.dart';
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
        verifyFailure: const PosSessionFailure('TERMINAL_REVOKED', '终端已停用。'),
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

    test('已停用或过期的服务端终端事实必须失败关闭', () async {
      final inactive = _Fixture(
        verifiedTerminal: terminalFor(status: 'REVOKED'),
      );
      final expired = _Fixture(
        verifiedTerminal: terminalFor(validUntil: fixtureNow),
      );

      expect(
        (await inactive.service.bootstrap()).errorCode,
        'TERMINAL_REVOKED',
      );
      expect((await expired.service.bootstrap()).errorCode, 'TERMINAL_REVOKED');
    });

    test('未知终端验证异常不得把底层信息暴露给页面', () async {
      final fixture = _Fixture(verifyFailure: StateError('synthetic secret'));

      final state = await fixture.service.bootstrap();

      expect(state.phase, PosSessionPhase.locked);
      expect(state.errorCode, 'TERMINAL_UNTRUSTED');
      expect(state.safeMessage, isNot(contains('synthetic secret')));
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
      expect(
        states.every((state) => state.employee?.employeeId == '101'),
        isTrue,
      );
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

    test('授权权限可以通过本地最终防线', () async {
      final fixture = _Fixture();
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      expect(
        () => fixture.service.requirePermission(PosPermission.cashSettle),
        returnsNormally,
      );
    });

    test('非法登录输入和认证异常保持未登录且不泄漏口令', () async {
      final invalid = _Fixture();
      await invalid.service.bootstrap();
      final invalidState = await invalid.service.login(
        loginName: 'x',
        secret: '123',
      );
      final denied = _Fixture(
        authenticateFailure: const PosSessionFailure(
          'AUTH_DENIED',
          '员工无权使用当前终端。',
        ),
      );
      await denied.service.bootstrap();
      final deniedState = await denied.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final unavailable = _Fixture(
        authenticateFailure: StateError('synthetic upstream detail'),
      );
      await unavailable.service.bootstrap();
      final unavailableState = await unavailable.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      expect(invalidState.errorCode, 'AUTH_INPUT_INVALID');
      expect(deniedState.errorCode, 'AUTH_DENIED');
      expect(unavailableState.errorCode, 'AUTH_FAILED');
      expect(
        '$invalidState$deniedState$unavailableState',
        isNot(contains('synthetic-pin')),
      );
      expect('$unavailableState', isNot(contains('upstream detail')));
    });

    test('过期员工会话和非法班次上下文不得进入营业状态', () async {
      final expiredEmployee = employeeFor(
        authenticatedAt: fixtureNow.subtract(const Duration(hours: 1)),
        expiresAt: fixtureNow,
      );
      final expired = _Fixture(
        loginResult: PosLoginResult(employee: expiredEmployee),
      );
      await expired.service.bootstrap();
      final expiredState = await expired.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final invalidShift = _Fixture(
        loginResult: PosLoginResult(
          employee: employee,
          shift: shiftFor(status: 'CLOSED'),
        ),
      );
      await invalidShift.service.bootstrap();
      final invalidShiftState = await invalidShift.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      expect(expiredState.errorCode, 'SESSION_EXPIRED');
      expect(invalidShiftState.errorCode, 'SHIFT_CONTEXT_INVALID');
      expect(expiredState.employee, isNull);
      expect(invalidShiftState.employee, isNull);
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

    test('刷新发现会话过期时安全回到未登录状态', () async {
      final fixture = _Fixture(
        refreshResult: PosSessionRefresh(
          terminal: terminalFor(),
          employee: employeeFor(
            authenticatedAt: fixtureNow.subtract(const Duration(hours: 1)),
            expiresAt: fixtureNow,
          ),
          shift: shift,
        ),
      );
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      final state = await fixture.service.refresh();

      expect(state.phase, PosSessionPhase.signedOut);
      expect(state.errorCode, 'SESSION_EXPIRED');
      expect(state.employee, isNull);
    });

    test('刷新吊销终端时锁屏，网络异常时保留上下文但暂停危险操作', () async {
      final revoked = _Fixture(
        refreshResult: PosSessionRefresh(
          terminal: terminalFor(status: 'REVOKED'),
          employee: employee,
          shift: shift,
        ),
      );
      await revoked.service.bootstrap();
      await revoked.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final revokedState = await revoked.service.refresh();
      final unavailable = _Fixture(
        refreshFailure: StateError('synthetic network detail'),
      );
      await unavailable.service.bootstrap();
      await unavailable.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final unavailableState = await unavailable.service.refresh();

      expect(revokedState.phase, PosSessionPhase.locked);
      expect(revokedState.errorCode, 'TERMINAL_REVOKED');
      expect(unavailableState.phase, PosSessionPhase.readyWithShift);
      expect(unavailableState.errorCode, 'SESSION_REFRESH_FAILED');
      expect(unavailableState.businessDateChanged, isTrue);
      expect('$unavailableState', isNot(contains('network detail')));
    });

    test('未登录刷新和退出均保持当前状态且不调用远端', () async {
      final fixture = _Fixture();
      final before = await fixture.service.bootstrap();

      final refreshed = await fixture.service.refresh();
      final loggedOut = await fixture.service.logout();

      expect(identical(refreshed, before), isTrue);
      expect(identical(loggedOut, before), isTrue);
      expect(fixture.repository.refreshCount, 0);
      expect(fixture.repository.logoutCount, 0);
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

    test('安全退出失败时保留会话并使用安全错误信息', () async {
      final denied = _Fixture(
        logoutFailure: const PosSessionFailure('SIGN_OUT_DENIED', '当前班次不允许退出。'),
      );
      await denied.service.bootstrap();
      await denied.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final deniedState = await denied.service.logout();
      final unavailable = _Fixture(
        logoutFailure: StateError('synthetic server detail'),
      );
      await unavailable.service.bootstrap();
      await unavailable.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );
      final unavailableState = await unavailable.service.logout();

      expect(deniedState.phase, PosSessionPhase.readyWithShift);
      expect(deniedState.errorCode, 'SIGN_OUT_DENIED');
      expect(deniedState.employee, isNotNull);
      expect(unavailableState.errorCode, 'SIGN_OUT_FAILED');
      expect('$unavailableState', isNot(contains('server detail')));
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

    test('只有 Checkout Owner 成功结果才能改变会话内开关班状态', () async {
      final fixture = _Fixture(loginResult: PosLoginResult(employee: employee));
      await fixture.service.bootstrap();
      await fixture.service.login(
        loginName: 'cashier01',
        secret: 'synthetic-pin',
      );

      final opened = fixture.service.acceptOpenedShift(shift);
      final closed = fixture.service.acceptClosedShift(shift.shiftId);

      expect(opened.phase, PosSessionPhase.readyWithShift);
      expect(opened.shift?.shiftId, shift.shiftId);
      expect(closed.phase, PosSessionPhase.readyNoShift);
      expect(closed.shift, isNull);
      expect(
        () => fixture.service.acceptClosedShift(shift.shiftId),
        throwsA(isA<PosSessionFailure>()),
      );
    });
  });

  group('T2-POS-007 会话值对象与默认失败关闭仓储', () {
    test('终端、登录和员工非法值拒绝构造并返回安全错误码', () {
      expect(
        () => terminalFor(tenantId: 'INVALID TENANT'),
        throwsA(
          isA<PosSessionFailure>().having(
            (error) => error.code,
            'code',
            'TERMINAL_CONTEXT_INVALID',
          ),
        ),
      );
      expect(
        () => EmployeeLoginCommand(
          loginName: 'x',
          secret: '123',
          correlationId: 'invalid',
          occurredAt: fixtureNow,
        ),
        throwsA(isA<PosSessionFailure>()),
      );
      expect(
        () => EmployeeSession(
          employeeId: '0',
          employeeName: '',
          sessionRef: 'short',
          authenticatedAt: fixtureNow,
          expiresAt: fixtureNow,
          roles: const {},
          permissions: const {},
        ),
        throwsA(isA<PosSessionFailure>()),
      );
      expect(
        const PosSessionFailure('SAFE_CODE', '安全信息').toString(),
        'SAFE_CODE: 安全信息',
      );
      expect(shiftFor(status: 'CLOSED').isOpen, isFalse);
      expect(
        const PosSessionState.bootstrapping().hasPermission(
          PosPermission.syncView,
        ),
        isFalse,
      );
    });

    test('默认生产仓储在未接入可信通信时除退出外全部失败关闭', () async {
      const repository = LockedPosSessionRepository();

      await expectLater(
        () => repository.verifyTerminal(deviceSnapshot),
        throwsA(isA<PosSessionFailure>()),
      );
      await expectLater(
        () => repository.authenticate(
          terminalFor(),
          EmployeeLoginCommand(
            loginName: 'cashier01',
            secret: 'synthetic-pin',
            correlationId: '01K2A000000000000000000099',
            occurredAt: fixtureNow,
          ),
        ),
        throwsA(isA<PosSessionFailure>()),
      );
      await expectLater(
        () => repository.refresh(terminalFor(), employee),
        throwsA(isA<PosSessionFailure>()),
      );
      await repository.logout(
        terminalFor(),
        employee,
        '01K2A000000000000000000099',
      );
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
  String status = 'ACTIVE',
  DateTime? validUntil,
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
  status: status,
  protocolVersion: '1.0',
  validUntil: validUntil ?? DateTime.utc(2099),
  approvedCapabilities: {DeviceCapability.receiptPrinter},
);

EmployeeSession employeeFor({DateTime? authenticatedAt, DateTime? expiresAt}) =>
    EmployeeSession(
      employeeId: '101',
      employeeName: '虚构收银员甲',
      sessionRef: 'session:synthetic:0001',
      authenticatedAt: authenticatedAt ?? fixtureNow,
      expiresAt: expiresAt ?? fixtureNow.add(const Duration(hours: 8)),
      roles: {'CASHIER'},
      permissions: {
        PosPermission.sessionLogin,
        PosPermission.shiftOpen,
        PosPermission.shiftClose,
        PosPermission.saleOperate,
        PosPermission.cashSettle,
      },
    );

final employee = employeeFor();

PosShiftContext shiftFor({String status = 'OPEN'}) => PosShiftContext(
  shiftId: '01K2A000000000000000000021',
  businessDate: '2026-08-20',
  status: status,
  openedAt: fixtureNow,
);

final shift = shiftFor();

class _Fixture {
  _Fixture({
    Object? verifyFailure,
    Object? authenticateFailure,
    Object? refreshFailure,
    Object? logoutFailure,
    Completer<PosLoginResult>? loginCompleter,
    PosLoginResult? loginResult,
    PosSessionRefresh? refreshResult,
    DeviceSnapshot? deviceSnapshot,
    TrustedTerminalContext? verifiedTerminal,
  }) : gateway = _FakeDeviceGateway(deviceSnapshot ?? posSessionTestDevice),
       repository = _FakeRepository(
         verifyFailure: verifyFailure,
         authenticateFailure: authenticateFailure,
         refreshFailure: refreshFailure,
         logoutFailure: logoutFailure,
         loginCompleter: loginCompleter,
         loginResult:
             loginResult ?? PosLoginResult(employee: employee, shift: shift),
         refreshResult: refreshResult,
         verifiedTerminal: verifiedTerminal,
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
    required this.authenticateFailure,
    required this.refreshFailure,
    required this.logoutFailure,
    required this.loginCompleter,
    required this.loginResult,
    required this.refreshResult,
    required this.verifiedTerminal,
  });

  final Object? verifyFailure;
  final Object? authenticateFailure;
  final Object? refreshFailure;
  final Object? logoutFailure;
  final Completer<PosLoginResult>? loginCompleter;
  final PosLoginResult loginResult;
  final PosSessionRefresh? refreshResult;
  final TrustedTerminalContext? verifiedTerminal;
  int verifyCount = 0;
  int authenticateCount = 0;
  int refreshCount = 0;
  int logoutCount = 0;

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) async {
    verifyCount++;
    if (verifyFailure != null) throw verifyFailure!;
    return verifiedTerminal ?? terminalFor();
  }

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) {
    authenticateCount++;
    if (authenticateFailure != null) throw authenticateFailure!;
    return loginCompleter?.future ?? Future.value(loginResult);
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async {
    refreshCount++;
    if (refreshFailure != null) throw refreshFailure!;
    return refreshResult ??
        PosSessionRefresh(terminal: terminal, employee: employee, shift: shift);
  }

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {
    logoutCount++;
    if (logoutFailure != null) throw logoutFailure!;
  }
}
