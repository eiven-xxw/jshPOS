import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../domain/pos_session_models.dart';

/// POS 会话可信端口。实现方必须在服务端或安全存储边界校验终端与员工。
abstract interface class PosSessionRepository {
  /// 使用设备适配快照和安全存储中的终端凭据换取可信上下文。
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device);

  /// 认证员工并返回绑定同一终端、门店和业务日的会话事实。
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  );

  /// 刷新吊销、会话有效期、权限、业务日和当前班次。
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  );

  /// 安全退出并审计；correlationId 用于服务端关联，不包含客户端租户声明。
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  );
}
