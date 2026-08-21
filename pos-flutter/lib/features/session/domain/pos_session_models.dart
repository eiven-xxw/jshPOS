import 'package:pos_device_adapter/pos_device_adapter.dart';

/// POS 会话阶段；危险状态只能由应用服务迁移，页面不得自行解锁。
enum PosSessionPhase {
  bootstrapping,
  locked,
  signedOut,
  authenticating,
  readyNoShift,
  readyWithShift,
  signingOut,
}

/// POS 权限值对象。wireCode 与服务端权限字符串保持稳定一致。
enum PosPermission {
  sessionLogin('pos:session:login'),
  shiftOpen('pos:shift:open'),
  shiftClose('pos:shift:close'),
  saleOperate('pos:sale:operate'),
  manualDiscount('pos:discount:manual'),
  approveDiscount('pos:discount:approve'),
  cashSettle('pos:cash:settle'),
  cashManage('pos:shift:cash-manage'),
  drawerNoSale('pos:drawer:no-sale'),
  returnRead('return:request:read'),
  returnCreate('return:request:create'),
  returnApprove('return:request:approve'),
  printPreview('pos:print:preview'),
  printReprint('pos:print:reprint'),
  syncView('pos:sync:view');

  const PosPermission(this.wireCode);

  final String wireCode;
}

/// 应用层可安全展示的失败；message 不得包含凭据、PII 或服务端原始响应。
final class PosSessionFailure implements Exception {
  const PosSessionFailure(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => '$code: $message';
}

/// 服务端验证后的终端上下文，是 POS 租户、门店和终端身份的唯一来源。
final class TrustedTerminalContext {
  TrustedTerminalContext({
    required this.tenantId,
    required this.tenantName,
    required this.orgUnitId,
    required this.storeId,
    required this.storeName,
    String? deviceId,
    required this.terminalId,
    required this.terminalName,
    required this.storeTimezone,
    required this.businessDate,
    required this.status,
    required this.protocolVersion,
    required this.validUntil,
    required Set<DeviceCapability> approvedCapabilities,
  }) : deviceId = deviceId ?? terminalId,
       approvedCapabilities = Set.unmodifiable(approvedCapabilities) {
    _validate();
  }

  final String tenantId;
  final String tenantName;
  final String orgUnitId;
  final String storeId;
  final String storeName;

  /// 设备注册表 ULID；与门店内终端业务标识相互独立。
  final String deviceId;
  final String terminalId;
  final String terminalName;
  final String storeTimezone;
  final String businessDate;
  final String status;
  final String protocolVersion;
  final DateTime validUntil;
  final Set<DeviceCapability> approvedCapabilities;

  bool get isActive => status == 'ACTIVE';

  void _validate() {
    if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$').hasMatch(tenantId) ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(orgUnitId) ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(storeId) ||
        !RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(deviceId) ||
        !RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(terminalId) ||
        tenantName.trim().isEmpty ||
        storeName.trim().isEmpty ||
        terminalName.trim().isEmpty ||
        storeTimezone.trim().isEmpty ||
        !RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(businessDate) ||
        protocolVersion.trim().isEmpty) {
      throw const PosSessionFailure(
        'TERMINAL_CONTEXT_INVALID',
        '终端上下文无效，请联系管理员。',
      );
    }
  }
}

/// 员工认证命令。secret 只在调用栈内短暂存在，服务不得保存或写入日志。
final class EmployeeLoginCommand {
  EmployeeLoginCommand({
    required this.loginName,
    required this.secret,
    required this.correlationId,
    required this.occurredAt,
  }) {
    if (!RegExp(r'^[A-Za-z0-9._@-]{2,64}$').hasMatch(loginName) ||
        secret.length < 4 ||
        secret.length > 128 ||
        !RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(correlationId)) {
      throw const PosSessionFailure('AUTH_INPUT_INVALID', '工号或口令格式不正确。');
    }
  }

  final String loginName;
  final String secret;
  final String correlationId;
  final DateTime occurredAt;
}

/// 认证成功后的员工会话；只保存显示名、角色、权限和服务端会话引用。
final class EmployeeSession {
  EmployeeSession({
    required this.employeeId,
    required this.employeeName,
    required this.sessionRef,
    required this.authenticatedAt,
    required this.expiresAt,
    required Set<String> roles,
    required Set<PosPermission> permissions,
  }) : roles = Set.unmodifiable(roles),
       permissions = Set.unmodifiable(permissions) {
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(employeeId) ||
        employeeName.trim().isEmpty ||
        !RegExp(r'^[A-Za-z0-9._:-]{16,128}$').hasMatch(sessionRef) ||
        !expiresAt.isAfter(authenticatedAt) ||
        !this.permissions.contains(PosPermission.sessionLogin)) {
      throw const PosSessionFailure('SESSION_CONTEXT_INVALID', '员工会话上下文无效。');
    }
  }

  final String employeeId;
  final String employeeName;
  final String sessionRef;
  final DateTime authenticatedAt;
  final DateTime expiresAt;
  final Set<String> roles;
  final Set<PosPermission> permissions;
}

/// 已存在班次的只读上下文；开关班仍由 Checkout 应用服务拥有。
final class PosShiftContext {
  const PosShiftContext({
    required this.shiftId,
    required this.businessDate,
    required this.status,
    required this.openedAt,
  });

  final String shiftId;
  final String businessDate;
  final String status;
  final DateTime openedAt;

  bool get isOpen => status == 'OPEN';
}

/// 一次认证的完整结果，确保员工、终端与可见班次来自同一可信边界。
final class PosLoginResult {
  const PosLoginResult({required this.employee, this.shift});

  final EmployeeSession employee;
  final PosShiftContext? shift;
}

/// 刷新后的会话事实，用于发现吊销、会话过期和业务日切换。
final class PosSessionRefresh {
  const PosSessionRefresh({
    required this.terminal,
    required this.employee,
    this.shift,
  });

  final TrustedTerminalContext terminal;
  final EmployeeSession employee;
  final PosShiftContext? shift;
}

/// UI 只读会话快照，不包含登录口令或设备凭据。
final class PosSessionState {
  const PosSessionState({
    required this.phase,
    this.device,
    this.terminal,
    this.employee,
    this.shift,
    this.errorCode,
    this.safeMessage,
    this.businessDateChanged = false,
  });

  const PosSessionState.bootstrapping()
    : this(phase: PosSessionPhase.bootstrapping);

  final PosSessionPhase phase;
  final DeviceSnapshot? device;
  final TrustedTerminalContext? terminal;
  final EmployeeSession? employee;
  final PosShiftContext? shift;
  final String? errorCode;
  final String? safeMessage;
  final bool businessDateChanged;

  bool hasPermission(PosPermission permission) =>
      employee?.permissions.contains(permission) ?? false;
}
