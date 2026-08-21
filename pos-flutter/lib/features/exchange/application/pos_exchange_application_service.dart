import '../domain/pos_exchange_models.dart';

/// EXG-001 应用端口；页面不得访问 SQLite 或拼装 Owner 事实。
abstract interface class PosExchangeApplicationService {
  /// 冻结并提交已完成的原退货与新销售关联。
  Future<PosExchangeView> create({
    required PosExchangeSource source,
    required String reasonCode,
  });

  /// 按原 exchangeRef 观察；UNKNOWN 时禁止创建新换货或 Owner 命令。
  Future<PosExchangeView> refreshExchange(String exchangeRef);

  /// 独立审批换货关联；审批身份由服务端会话确定，客户端不得提交审批人。
  Future<PosExchangeView> approve({
    required String exchangeRef,
    required String correlationRef,
    required String reasonCode,
  });

  /// 只恢复原 RETURN/SALE 检查点，不生成替代 Owner 命令。
  Future<PosExchangeView> recover({
    required String exchangeRef,
    required String correlationRef,
    required String targetLeg,
    required String reasonCode,
  });
}
