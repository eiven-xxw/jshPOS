import '../../session/domain/pos_session_models.dart';
import '../domain/shift_models.dart';

/// POS 班次页面应用端口；金额输入由实现转成最小货币单位并交给 Checkout Owner。
abstract interface class PosShiftApplicationService {
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  });

  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  });

  /// 记录班次内非销售现金动作；金额输入为正数，方向由 movementType 决定。
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  });

  /// 只生成受审计的钱箱开启请求；外设未解阻时必须返回 BLOCKED_EXTERNAL。
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  });
}
