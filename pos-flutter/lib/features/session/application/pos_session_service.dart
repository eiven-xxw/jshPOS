import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../domain/pos_session_models.dart';
import 'pos_session_repository.dart';

/// POS-007 会话应用服务，独占页面会话状态迁移与重复操作抑制。
final class PosSessionService {
  PosSessionService({
    required this.deviceGateway,
    required this.repository,
    required this.correlationId,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  /// 设备能力只读契约；应用层不得绕过它调用平台通道。
  final PosDeviceGateway deviceGateway;

  /// 会话可信仓储端口；具体实现负责服务端与安全存储边界。
  final PosSessionRepository repository;

  /// 关联标识生成器；用于重复请求与审计关联，不承载业务身份。
  final String Function() correlationId;
  final DateTime Function() _now;
  PosSessionState _state = const PosSessionState.bootstrapping();
  Future<PosSessionState>? _bootstrapFlight;
  Future<PosSessionState>? _loginFlight;
  Future<PosSessionState>? _logoutFlight;

  PosSessionState get state => _state;

  /// 启动时串行校验设备适配快照和服务端终端注册事实；失败保持锁屏。
  Future<PosSessionState> bootstrap() {
    return _bootstrapFlight ??= _bootstrap().whenComplete(() {
      _bootstrapFlight = null;
    });
  }

  Future<PosSessionState> _bootstrap() async {
    _state = const PosSessionState.bootstrapping();
    DeviceSnapshot? device;
    try {
      device = await deviceGateway.snapshot();
      final terminal = await repository.verifyTerminal(device);
      if (!terminal.isActive || !terminal.validUntil.isAfter(_now().toUtc())) {
        throw const PosSessionFailure(
          'TERMINAL_REVOKED',
          '终端已停用或授权已过期，请联系管理员。',
        );
      }
      final reported = device.capabilities.map((item) => item.id).toSet();
      final approved = terminal.approvedCapabilities
          .map((item) => item.id)
          .toSet();
      if (!approved.containsAll(reported)) {
        throw const PosSessionFailure(
          'TERMINAL_CAPABILITY_MISMATCH',
          '终端能力与登记信息不一致，请重新核验。',
        );
      }
      return _state = PosSessionState(
        phase: PosSessionPhase.signedOut,
        device: device,
        terminal: terminal,
      );
    } on PosSessionFailure catch (error) {
      return _lock(device, error);
    } catch (_) {
      return _lock(
        device,
        const PosSessionFailure('TERMINAL_UNTRUSTED', '无法验证终端身份，请检查网络或联系管理员。'),
      );
    }
  }

  /// 登录操作使用单航班；重复点击只等待原请求，不产生第二次认证。
  Future<PosSessionState> login({
    required String loginName,
    required String secret,
  }) {
    return _loginFlight ??= _login(loginName: loginName, secret: secret)
        .whenComplete(() {
          _loginFlight = null;
        });
  }

  Future<PosSessionState> _login({
    required String loginName,
    required String secret,
  }) async {
    final before = _state;
    final terminal = before.terminal;
    if (before.phase != PosSessionPhase.signedOut || terminal == null) {
      return _failWithoutUnlock(
        const PosSessionFailure('SESSION_STATE_CONFLICT', '当前状态不允许登录。'),
      );
    }
    _state = PosSessionState(
      phase: PosSessionPhase.authenticating,
      device: before.device,
      terminal: terminal,
    );
    try {
      final command = EmployeeLoginCommand(
        loginName: loginName.trim(),
        secret: secret,
        correlationId: correlationId(),
        occurredAt: _now().toUtc(),
      );
      final result = await repository.authenticate(terminal, command);
      if (!result.employee.expiresAt.isAfter(_now().toUtc())) {
        throw const PosSessionFailure('SESSION_EXPIRED', '会话已过期，请重新登录。');
      }
      final shift = result.shift;
      if (shift != null &&
          (!shift.isOpen || shift.businessDate != terminal.businessDate)) {
        throw const PosSessionFailure(
          'SHIFT_CONTEXT_INVALID',
          '班次与当前业务日不一致，请联系值班负责人。',
        );
      }
      return _state = PosSessionState(
        phase: shift == null
            ? PosSessionPhase.readyNoShift
            : PosSessionPhase.readyWithShift,
        device: before.device,
        terminal: terminal,
        employee: result.employee,
        shift: shift,
      );
    } on PosSessionFailure catch (error) {
      return _state = PosSessionState(
        phase: PosSessionPhase.signedOut,
        device: before.device,
        terminal: terminal,
        errorCode: error.code,
        safeMessage: error.message,
      );
    } catch (_) {
      return _state = PosSessionState(
        phase: PosSessionPhase.signedOut,
        device: before.device,
        terminal: terminal,
        errorCode: 'AUTH_FAILED',
        safeMessage: '工号或口令不正确，或当前无权使用此终端。',
      );
    }
  }

  /// 刷新服务端可信事实；吊销和不一致会立即清理员工并锁屏。
  Future<PosSessionState> refresh() async {
    final before = _state;
    final terminal = before.terminal;
    final employee = before.employee;
    if (terminal == null || employee == null) return before;
    try {
      final refreshed = await repository.refresh(terminal, employee);
      if (!refreshed.terminal.isActive ||
          !refreshed.terminal.validUntil.isAfter(_now().toUtc())) {
        throw const PosSessionFailure('TERMINAL_REVOKED', '终端已被停用，请联系管理员。');
      }
      if (refreshed.terminal.tenantId != terminal.tenantId ||
          refreshed.terminal.storeId != terminal.storeId ||
          refreshed.terminal.deviceId != terminal.deviceId ||
          refreshed.terminal.terminalId != terminal.terminalId ||
          refreshed.employee.employeeId != employee.employeeId) {
        throw const PosSessionFailure(
          'SESSION_CONTEXT_MISMATCH',
          '会话上下文发生异常变化，终端已锁定。',
        );
      }
      if (!refreshed.employee.expiresAt.isAfter(_now().toUtc())) {
        return _state = PosSessionState(
          phase: PosSessionPhase.signedOut,
          device: before.device,
          terminal: refreshed.terminal,
          errorCode: 'SESSION_EXPIRED',
          safeMessage: '会话已过期，请重新登录。',
        );
      }
      final changed =
          refreshed.shift != null &&
          refreshed.shift!.businessDate != refreshed.terminal.businessDate;
      return _state = PosSessionState(
        phase: refreshed.shift == null
            ? PosSessionPhase.readyNoShift
            : PosSessionPhase.readyWithShift,
        device: before.device,
        terminal: refreshed.terminal,
        employee: refreshed.employee,
        shift: refreshed.shift,
        businessDateChanged: changed,
        errorCode: changed ? 'BUSINESS_DATE_CHANGED' : null,
        safeMessage: changed ? '业务日已经切换，请完成当前班次处置后继续。' : null,
      );
    } on PosSessionFailure catch (error) {
      return _lock(before.device, error);
    } catch (_) {
      return _state = PosSessionState(
        phase: before.phase,
        device: before.device,
        terminal: terminal,
        employee: employee,
        shift: before.shift,
        errorCode: 'SESSION_REFRESH_FAILED',
        safeMessage: '暂时无法刷新会话，危险操作已暂停。',
        businessDateChanged: true,
      );
    }
  }

  /// 安全退出使用单航班；服务端确认审计后才清除员工上下文。
  Future<PosSessionState> logout() {
    return _logoutFlight ??= _logout().whenComplete(() {
      _logoutFlight = null;
    });
  }

  Future<PosSessionState> _logout() async {
    final before = _state;
    final terminal = before.terminal;
    final employee = before.employee;
    if (terminal == null || employee == null) return before;
    _state = PosSessionState(
      phase: PosSessionPhase.signingOut,
      device: before.device,
      terminal: terminal,
      employee: employee,
      shift: before.shift,
    );
    try {
      await repository.logout(terminal, employee, correlationId());
      return _state = PosSessionState(
        phase: PosSessionPhase.signedOut,
        device: before.device,
        terminal: terminal,
      );
    } on PosSessionFailure catch (error) {
      return _state = PosSessionState(
        phase: before.phase,
        device: before.device,
        terminal: terminal,
        employee: employee,
        shift: before.shift,
        errorCode: error.code,
        safeMessage: error.message,
      );
    } catch (_) {
      return _state = PosSessionState(
        phase: before.phase,
        device: before.device,
        terminal: terminal,
        employee: employee,
        shift: before.shift,
        errorCode: 'SIGN_OUT_FAILED',
        safeMessage: '安全退出失败，请检查网络后重试。',
      );
    }
  }

  /// 应用能力入口的最终本地防线；服务端仍必须再次授权。
  void requirePermission(PosPermission permission) {
    if (!_state.hasPermission(permission) || _state.businessDateChanged) {
      throw const PosSessionFailure('PERMISSION_DENIED', '当前员工无权执行该操作。');
    }
  }

  /// 本地 Checkout Owner 成功开班后，才将页面会话切换为可交易状态。
  PosSessionState acceptOpenedShift(PosShiftContext shift) {
    final before = _state;
    if (before.phase != PosSessionPhase.readyNoShift ||
        before.terminal == null ||
        before.employee == null ||
        !shift.isOpen ||
        shift.businessDate != before.terminal!.businessDate) {
      throw const PosSessionFailure('SHIFT_CONTEXT_INVALID', '开班结果与当前可信会话不一致。');
    }
    return _state = PosSessionState(
      phase: PosSessionPhase.readyWithShift,
      device: before.device,
      terminal: before.terminal,
      employee: before.employee,
      shift: shift,
    );
  }

  /// Checkout Owner 确认关班后清除会话内班次；失败时不得由页面自行清除。
  PosSessionState acceptClosedShift(String shiftId) {
    final before = _state;
    if (before.phase != PosSessionPhase.readyWithShift ||
        before.shift?.shiftId != shiftId) {
      throw const PosSessionFailure('SHIFT_CONTEXT_INVALID', '关班结果与当前可信会话不一致。');
    }
    return _state = PosSessionState(
      phase: PosSessionPhase.readyNoShift,
      device: before.device,
      terminal: before.terminal,
      employee: before.employee,
    );
  }

  PosSessionState _lock(DeviceSnapshot? device, PosSessionFailure error) {
    return _state = PosSessionState(
      phase: PosSessionPhase.locked,
      device: device,
      errorCode: error.code,
      safeMessage: error.message,
    );
  }

  PosSessionState _failWithoutUnlock(PosSessionFailure error) {
    return _state = PosSessionState(
      phase: _state.phase,
      device: _state.device,
      terminal: _state.terminal,
      employee: _state.employee,
      shift: _state.shift,
      errorCode: error.code,
      safeMessage: error.message,
      businessDateChanged: _state.businessDateChanged,
    );
  }
}
