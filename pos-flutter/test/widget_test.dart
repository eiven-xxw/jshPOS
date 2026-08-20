import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/app/jshpos_app.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
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

    await tester.enterText(
      find.byKey(const Key('employeeLogin')),
      'cashier01',
    );
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
  _FakeSessionRepository({this.failAuthentication = false});

  final bool failAuthentication;
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
    return PosLoginResult(employee: _employee, shift: _shift);
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async => PosSessionRefresh(
    terminal: terminal,
    employee: employee,
    shift: _shift,
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
