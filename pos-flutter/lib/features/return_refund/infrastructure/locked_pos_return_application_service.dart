import '../application/pos_return_application_service.dart';
import '../domain/pos_return_models.dart';

/// 未配置 Return/Refund 正式组合根时的生产默认实现，始终失败关闭。
final class LockedPosReturnApplicationService
    implements PosReturnApplicationService {
  const LockedPosReturnApplicationService();

  Never _unavailable() => throw const PosReturnFailure(
    'RETURN_WORKSPACE_UNAVAILABLE',
    '退货退款应用服务尚未完成安全配置，请联系管理员。',
  );

  @override
  Future<PosReturnWorkspace> changeRequestedQuantity(
    String orderLineRef,
    String quantity,
  ) async => _unavailable();

  @override
  Future<PosReturnWorkspace> findOriginalOrder(String orderQuery) async =>
      _unavailable();

  @override
  Future<PosReturnSubmissionView> refreshReturnStatus(String returnRef) async =>
      _unavailable();

  @override
  Future<PosReturnSubmissionView> submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  }) async => _unavailable();
}
