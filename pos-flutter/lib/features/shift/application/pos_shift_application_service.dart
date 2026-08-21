import '../../session/domain/pos_session_models.dart';

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
}
