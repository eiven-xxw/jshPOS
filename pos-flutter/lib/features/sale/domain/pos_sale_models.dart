/// POS-008 页面可展示的安全失败；不得携带凭据、底层响应或个人敏感信息。
final class PosSaleFailure implements Exception {
  const PosSaleFailure(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => '$code: $message';
}

/// 商品搜索结果只包含已发布数据包中的销售快照字段。
final class PosProductView {
  const PosProductView({
    required this.productRef,
    required this.skuCode,
    required this.name,
    required this.unitName,
    required this.unitPriceMinor,
    this.barcode,
    this.stockHint,
  });

  final String productRef;
  final String skuCode;
  final String name;
  final String unitName;
  final int unitPriceMinor;
  final String? barcode;
  final String? stockHint;
}

/// 购物篮行是 Checkout Owner 返回的只读投影；页面不得自行计算行金额。
final class PosBasketLineView {
  const PosBasketLineView({
    required this.lineRef,
    required this.productRef,
    required this.name,
    required this.unitName,
    required this.quantity,
    required this.unitPriceMinor,
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.surchargeAmountMinor,
    required this.receivableAmountMinor,
    this.barcode,
  });

  final String lineRef;
  final String productRef;
  final String name;
  final String unitName;
  final String quantity;
  final int unitPriceMinor;
  final int grossAmountMinor;
  final int discountAmountMinor;
  final int surchargeAmountMinor;
  final int receivableAmountMinor;
  final String? barcode;
}

/// Owner 冻结后的金额汇总；构造时再次校验金额守恒，避免错误投影进入结算页。
final class PosSaleTotals {
  PosSaleTotals({
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.surchargeAmountMinor,
    required this.receivableAmountMinor,
  }) {
    if (grossAmountMinor < 0 ||
        discountAmountMinor < 0 ||
        surchargeAmountMinor < 0 ||
        receivableAmountMinor < 0 ||
        grossAmountMinor - discountAmountMinor + surchargeAmountMinor !=
            receivableAmountMinor) {
      throw const PosSaleFailure('SALE_AMOUNT_INVARIANT', '购物篮金额校验失败，请重新报价。');
    }
  }

  final int grossAmountMinor;
  final int discountAmountMinor;
  final int surchargeAmountMinor;
  final int receivableAmountMinor;
}

/// 挂单摘要只用于选择恢复目标，恢复时仍由 Checkout Owner 重新校验班次与版本。
final class PosHeldSaleView {
  const PosHeldSaleView({
    required this.saleRef,
    required this.localSaleNo,
    required this.lineCount,
    required this.receivableAmountMinor,
    required this.heldAt,
  });

  final String saleRef;
  final String localSaleNo;
  final int lineCount;
  final int receivableAmountMinor;
  final DateTime heldAt;
}

/// 同步状态来自正式同步应用服务，不以页面内计数替代 Outbox 权威投影。
final class PosSyncStatusView {
  const PosSyncStatusView({
    required this.online,
    required this.pendingCount,
    required this.retryCount,
    required this.deadLetterCount,
    required this.lastSuccessfulAt,
    required this.safeMessage,
  });

  final bool online;
  final int pendingCount;
  final int retryCount;
  final int deadLetterCount;
  final DateTime? lastSuccessfulAt;
  final String safeMessage;

  int get backlogCount => pendingCount + retryCount + deadLetterCount;
}

/// 当前收银工作区完整快照；每次命令后整体替换，页面不直接修改集合。
final class PosSaleWorkspace {
  PosSaleWorkspace({
    required this.saleRef,
    required this.localSaleNo,
    required Iterable<PosBasketLineView> lines,
    required this.totals,
    required this.quoteVersion,
    required this.quoteFingerprint,
    required this.businessDate,
    required Iterable<PosHeldSaleView> heldSales,
    required this.syncStatus,
    this.manualAuthorizationRef,
  }) : lines = List.unmodifiable(lines),
       heldSales = List.unmodifiable(heldSales);

  final String saleRef;
  final String localSaleNo;
  final List<PosBasketLineView> lines;
  final PosSaleTotals totals;
  final int quoteVersion;
  final String quoteFingerprint;
  final String businessDate;
  final List<PosHeldSaleView> heldSales;
  final PosSyncStatusView syncStatus;
  final String? manualAuthorizationRef;

  bool get canSettle => lines.isNotEmpty && totals.receivableAmountMinor > 0;
}

/// 现金成交结果只展示已持久化的订单、找零、快照和 Outbox 标识。
final class PosCashSettlementView {
  const PosCashSettlementView({
    required this.orderRef,
    required this.localOrderNo,
    required this.receivableAmountMinor,
    required this.tenderedAmountMinor,
    required this.changeAmountMinor,
    required this.snapshotDigest,
    required this.outboxEventRef,
    required this.completedAt,
    required this.duplicate,
  });

  final String orderRef;
  final String localOrderNo;
  final int receivableAmountMinor;
  final int tenderedAmountMinor;
  final int changeAmountMinor;
  final String snapshotDigest;
  final String outboxEventRef;
  final DateTime completedAt;
  final bool duplicate;
}

/// 打印只读预览；Gate 6D 不下发真实打印命令。
final class PosPrintPreviewView {
  const PosPrintPreviewView({
    required this.taskRef,
    required this.orderRef,
    required this.title,
    required this.lines,
    required this.totalText,
    required this.adapterEvidence,
    this.templateVersion = 'LEGACY',
    this.contentSha256 = '',
    this.reprintNo = 0,
    this.reprintAuditText,
  });

  final String taskRef;
  final String orderRef;
  final String title;
  final List<String> lines;
  final String totalText;
  final String adapterEvidence;
  final String templateVersion;
  final String contentSha256;
  final int reprintNo;
  final String? reprintAuditText;
}

/// 补打只形成受审计请求；真实打印未解阻时不得返回成功设备状态。
final class PosReprintRequestView {
  const PosReprintRequestView({
    required this.printRequestRef,
    required this.orderRef,
    required this.reprintNo,
    required this.documentDigest,
    required this.executionStatus,
    required this.outboxEventRef,
    required this.duplicate,
  });

  final String printRequestRef;
  final String orderRef;
  final int reprintNo;
  final String documentDigest;
  final String executionStatus;
  final String outboxEventRef;
  final bool duplicate;
}

/// ORD-004 页面可展示的取消或反向处置结果；不暴露底层表或跨 Owner 细节。
final class PosOrderDispositionView {
  const PosOrderDispositionView({
    required this.dispositionRef,
    required this.orderRef,
    required this.dispositionType,
    required this.fromStatus,
    required this.effectiveStatus,
    required this.requestDigest,
    required this.outboxEventRef,
    required this.duplicate,
  });

  final String dispositionRef;
  final String orderRef;
  final String dispositionType;
  final String fromStatus;
  final String effectiveStatus;
  final String requestDigest;
  final String outboxEventRef;
  final bool duplicate;
}
