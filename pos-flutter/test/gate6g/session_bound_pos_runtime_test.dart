import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/infrastructure/runtime/session_bound_pos_runtime.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  test('服务端登录成功但本地运行时装配失败时撤销会话并保持失败关闭', () async {
    final sessions = _RecordingSessionRepository();
    final runtime = SessionBoundPosRuntime(
      sessions: sessions,
      assembler: const _RejectingAssembler(),
    );
    final terminal = await sessions.verifyTerminal(_device());
    final command = EmployeeLoginCommand(
      loginName: 'cashier01',
      secret: 'synthetic-password',
      correlationId: '01K2A000000000000000000174',
      occurredAt: DateTime.utc(2026, 8, 21),
    );

    await expectLater(
      runtime.authenticate(terminal, command),
      throwsA(
        isA<PosSessionFailure>().having(
          (error) => error.code,
          'code',
          'RUNTIME_ASSEMBLY_FAILED',
        ),
      ),
    );

    expect(sessions.logoutCorrelations, [command.correlationId]);
    expect(
      () => runtime.loadWorkspace(),
      throwsA(
        isA<PosSessionFailure>().having(
          (error) => error.code,
          'code',
          'SESSION_REQUIRED',
        ),
      ),
    );
  });
}

DeviceSnapshot _device() => const DeviceSnapshot(
  metadata: DeviceMetadata(
    manufacturer: 'SYNTHETIC',
    model: 'VIRTUAL_POS',
    androidRelease: '15',
    androidSdk: 35,
    adapterVersion: '1.0',
  ),
  capabilities: <DeviceCapability>{},
);

final class _RecordingSessionRepository implements PosSessionRepository {
  final List<String> logoutCorrelations = [];

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) async =>
      TrustedTerminalContext(
        tenantId: 'TENANT_A',
        tenantName: '虚构租户甲',
        orgUnitId: '1001',
        storeId: '1101',
        storeName: '虚构便利店一店',
        deviceId: '01K2A000000000000000000171',
        terminalId: '01K2A000000000000000000172',
        terminalName: '一号收银台',
        storeTimezone: 'Asia/Shanghai',
        businessDate: '2026-08-21',
        status: 'ACTIVE',
        protocolVersion: '1.0',
        validUntil: DateTime.utc(2099),
        approvedCapabilities: const <DeviceCapability>{},
      );

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) async => PosLoginResult(
    employee: EmployeeSession(
      employeeId: '201',
      employeeName: '虚构收银员',
      sessionRef: 'synthetic-session-0001',
      authenticatedAt: DateTime.utc(2026, 8, 21),
      expiresAt: DateTime.utc(2026, 8, 21, 1),
      roles: const {'cashier'},
      permissions: const {PosPermission.sessionLogin},
    ),
  );

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async => PosSessionRefresh(terminal: terminal, employee: employee);

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {
    logoutCorrelations.add(correlationId);
  }
}

final class _RejectingAssembler implements PosBusinessRuntimeAssembler {
  const _RejectingAssembler();

  @override
  Future<PosBusinessRuntime> assemble(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async {
    throw const PosSessionFailure('RUNTIME_ASSEMBLY_FAILED', '合成装配失败。');
  }
}
