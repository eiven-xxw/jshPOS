import '../domain/pos_tender_models.dart';

/// PAY-004 应用端口；页面不得直接访问 SQLite、HTTP 或拼装资金事实。
abstract interface class PosTenderApplicationService {
  Future<PosTenderPlanView> freeze({
    required PosTenderSource source,
    required List<PosTenderAllocationDraft> allocations,
  });

  Future<PosTenderPlanView> find(String planRef);

  /// 只处理原 allocation；UNKNOWN 或外部未解阻时不得生成替代命令。
  Future<PosTenderPlanView> collect({
    required String planRef,
    required String allocationRef,
    int? tenderedMinor,
  });
}
