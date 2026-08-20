import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../application/pos_session_repository.dart';
import '../domain/pos_session_models.dart';

/// 未配置可信终端通信与安全凭据存储时的生产默认实现，始终失败关闭。
final class LockedPosSessionRepository implements PosSessionRepository {
  const LockedPosSessionRepository();

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) {
    throw const PosSessionFailure(
      'TERMINAL_UNTRUSTED',
      '终端尚未完成可信激活，请联系管理员。',
    );
  }

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) {
    throw const PosSessionFailure(
      'TERMINAL_UNTRUSTED',
      '终端尚未完成可信激活，请联系管理员。',
    );
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) {
    throw const PosSessionFailure(
      'TERMINAL_UNTRUSTED',
      '终端尚未完成可信激活，请联系管理员。',
    );
  }

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {}
}
