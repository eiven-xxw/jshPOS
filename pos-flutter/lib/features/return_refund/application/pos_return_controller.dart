import '../../session/application/pos_session_service.dart';
import '../../session/domain/pos_session_models.dart';
import '../domain/pos_return_models.dart';
import 'pos_return_application_service.dart';

/// POS-009 页面阶段；提交结果未知时必须保留原 returnRef 并只允许查询。
enum PosReturnPagePhase {
  idle,
  searching,
  ready,
  submitting,
  pending,
  unknown,
  completed,
  failed,
}

/// 页面只读状态；不保存审批凭据、原始响应或客户端拼装的业务事实。
final class PosReturnPageState {
  const PosReturnPageState({
    required this.phase,
    this.workspace,
    this.submission,
    this.errorCode,
    this.safeMessage,
    this.recoverableReturnRef,
  });

  const PosReturnPageState.idle() : this(phase: PosReturnPagePhase.idle);

  final PosReturnPagePhase phase;
  final PosReturnWorkspace? workspace;
  final PosReturnSubmissionView? submission;
  final String? errorCode;
  final String? safeMessage;
  final String? recoverableReturnRef;

  bool get busy =>
      phase == PosReturnPagePhase.searching ||
      phase == PosReturnPagePhase.submitting;
}

/// 退货退款页面编排器，统一权限、单航班、状态映射和 UNKNOWN 恢复。
final class PosReturnController {
  PosReturnController({
    required this.sessionService,
    required this.returnService,
  });

  final PosSessionService sessionService;
  final PosReturnApplicationService returnService;
  PosReturnPageState _state = const PosReturnPageState.idle();
  Future<PosReturnPageState>? _flight;

  PosReturnPageState get state => _state;

  Future<PosReturnPageState> searchOriginalOrder(String query) {
    if (query.trim().isEmpty || query.trim().length > 64) {
      return Future.value(_failure('RETURN_QUERY_INVALID', '请输入有效的原单号。'));
    }
    return _flight ??= _search(query.trim()).whenComplete(() => _flight = null);
  }

  Future<PosReturnPageState> _search(String query) async {
    try {
      sessionService.requirePermission(PosPermission.returnRead);
      _state = const PosReturnPageState(phase: PosReturnPagePhase.searching);
      final workspace = await returnService.findOriginalOrder(query);
      return _state = PosReturnPageState(
        phase: PosReturnPagePhase.ready,
        workspace: workspace,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosReturnFailure catch (error) {
      return _returnFailure(error);
    } catch (_) {
      return _failure('RETURN_SEARCH_FAILED', '原单查询失败，请稍后重试。');
    }
  }

  Future<PosReturnPageState> changeQuantity(
    String orderLineRef,
    String quantity,
  ) => _flight ??= _changeQuantity(
    orderLineRef,
    quantity.trim(),
  ).whenComplete(() => _flight = null);

  Future<PosReturnPageState> _changeQuantity(
    String orderLineRef,
    String quantity,
  ) async {
    try {
      sessionService.requirePermission(PosPermission.returnCreate);
      if (_state.workspace == null) {
        throw const PosReturnFailure('RETURN_ORDER_REQUIRED', '请先查询原单。');
      }
      _state = _copy(phase: PosReturnPagePhase.submitting, clearError: true);
      final workspace = await returnService.changeRequestedQuantity(
        orderLineRef,
        quantity,
      );
      return _state = PosReturnPageState(
        phase: PosReturnPagePhase.ready,
        workspace: workspace,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosReturnFailure catch (error) {
      return _returnFailure(error);
    } catch (_) {
      return _failure('RETURN_QUANTITY_FAILED', '退货数量更新失败，请检查可退上限。');
    }
  }

  Future<PosReturnPageState> submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  }) => _flight ??= _submitCashReturn(
    reasonCode: reasonCode,
    supervisorCredential: supervisorCredential,
  ).whenComplete(() => _flight = null);

  Future<PosReturnPageState> _submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  }) async {
    try {
      sessionService.requirePermission(PosPermission.returnCreate);
      final workspace = _state.workspace;
      if (workspace == null || !workspace.canSubmit) {
        throw const PosReturnFailure('RETURN_SELECTION_REQUIRED', '请选择合法退货数量。');
      }
      if (!RegExp(r'^[A-Z0-9_]{2,32}$').hasMatch(reasonCode)) {
        throw const PosReturnFailure('RETURN_REASON_INVALID', '请选择有效的退货原因。');
      }
      _state = _copy(phase: PosReturnPagePhase.submitting, clearError: true);
      final submission = await returnService.submitCashReturn(
        reasonCode: reasonCode,
        supervisorCredential: supervisorCredential,
      );
      return _state = _fromSubmission(workspace, submission);
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosReturnFailure catch (error) {
      return _returnFailure(error);
    } catch (_) {
      return _failure('RETURN_SUBMIT_FAILED', '退货提交失败；请查询原申请状态，不要重新生成退款命令。');
    }
  }

  Future<PosReturnPageState> refreshStatus([String? returnRef]) {
    final target =
        returnRef ??
        _state.submission?.returnRef ??
        _state.recoverableReturnRef ??
        _state.workspace?.existingReturnRef;
    if (target == null) {
      return Future.value(_failure('RETURN_REFERENCE_REQUIRED', '没有可查询的退货申请。'));
    }
    return _flight ??= _refreshStatus(target)
        .whenComplete(() => _flight = null);
  }

  Future<PosReturnPageState> _refreshStatus(String returnRef) async {
    try {
      sessionService.requirePermission(PosPermission.returnRead);
      _state = _copy(phase: PosReturnPagePhase.searching, clearError: true);
      final submission = await returnService.refreshReturnStatus(returnRef);
      return _state = _fromSubmission(_state.workspace, submission);
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosReturnFailure catch (error) {
      return _returnFailure(error);
    } catch (_) {
      return _state = _copy(
        phase: PosReturnPagePhase.unknown,
        errorCode: 'RETURN_STATUS_QUERY_FAILED',
        safeMessage: '状态查询失败，请稍后继续查询原申请。',
        recoverableReturnRef: returnRef,
      );
    }
  }

  void reset() => _state = const PosReturnPageState.idle();

  PosReturnPageState _fromSubmission(
    PosReturnWorkspace? workspace,
    PosReturnSubmissionView submission,
  ) {
    final phase = switch (submission.status) {
      PosReturnSagaStatus.completed => PosReturnPagePhase.completed,
      PosReturnSagaStatus.failed => PosReturnPagePhase.failed,
      PosReturnSagaStatus.paymentUnknown => PosReturnPagePhase.unknown,
      _ => PosReturnPagePhase.pending,
    };
    return PosReturnPageState(
      phase: phase,
      workspace: workspace,
      submission: submission,
      recoverableReturnRef: submission.returnRef,
      errorCode: submission.status == PosReturnSagaStatus.failed
          ? 'RETURN_SAGA_FAILED'
          : null,
      safeMessage: submission.status == PosReturnSagaStatus.failed
          ? '退货退款未完成，请由受权人员核查审计链。'
          : null,
    );
  }

  PosReturnPageState _returnFailure(PosReturnFailure error) {
    if (error.resultUnknown) {
      return _state = _copy(
        phase: PosReturnPagePhase.unknown,
        errorCode: error.code,
        safeMessage: error.message,
        recoverableReturnRef: error.returnRef,
      );
    }
    return _failure(error.code, error.message);
  }

  PosReturnPageState _failure(String code, String message) => _state = _copy(
    phase: PosReturnPagePhase.failed,
    errorCode: code,
    safeMessage: message,
  );

  PosReturnPageState _copy({
    required PosReturnPagePhase phase,
    String? errorCode,
    String? safeMessage,
    String? recoverableReturnRef,
    bool clearError = false,
  }) => PosReturnPageState(
    phase: phase,
    workspace: _state.workspace,
    submission: _state.submission,
    errorCode: clearError ? null : errorCode ?? _state.errorCode,
    safeMessage: clearError ? null : safeMessage ?? _state.safeMessage,
    recoverableReturnRef: recoverableReturnRef ?? _state.recoverableReturnRef,
  );
}
