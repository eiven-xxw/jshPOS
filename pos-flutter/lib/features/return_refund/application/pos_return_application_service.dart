import '../domain/pos_return_models.dart';

/// POS-009 正式应用编排端口；实现负责 Owner 协作、稳定命令和结果恢复。
abstract interface class PosReturnApplicationService {
  /// 解析订单 ULID 或本地小票号，并返回可信门店内的原单和累计可退投影。
  Future<PosReturnWorkspace> findOriginalOrder(String orderQuery);

  /// 更新某原成交行的本次退货数量；所有金额仍由 Owner 重新预检后返回。
  Future<PosReturnWorkspace> changeRequestedQuantity(
    String orderLineRef,
    String quantity,
  );

  /// 提交现金退货退款；稳定幂等键、命令标识与审批编排由实现持有。
  Future<PosReturnSubmissionView> submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  });

  /// 按原 returnRef 查询已持久化检查点；禁止生成新的退款命令。
  Future<PosReturnSubmissionView> refreshReturnStatus(String returnRef);
}
