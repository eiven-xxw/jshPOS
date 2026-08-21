import '../../session/domain/pos_session_models.dart';
import '../application/pos_shift_application_service.dart';
import '../domain/shift_models.dart';

/// 未配置正式 SQLite/Checkout 组合根时的安全默认实现。
final class LockedPosShiftApplicationService
    implements PosShiftApplicationService {
  const LockedPosShiftApplicationService();

  Never _unavailable() => throw const PosSessionFailure(
    'SHIFT_RUNTIME_UNAVAILABLE',
    '班次应用服务尚未完成安全配置，请联系管理员。',
  );

  @override
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  }) async => _unavailable();

  @override
  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  }) async => _unavailable();

  @override
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => _unavailable();

  @override
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => _unavailable();
}
