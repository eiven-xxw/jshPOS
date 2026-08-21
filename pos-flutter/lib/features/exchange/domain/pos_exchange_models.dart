import '../../return_refund/domain/pos_return_models.dart';
import '../../sale/domain/pos_sale_models.dart';

/// 换货页面安全错误；resultUnknown 时只能按原 exchangeRef 观察。
final class PosExchangeFailure implements Exception {
  const PosExchangeFailure(
    this.code,
    this.safeMessage, {
    this.resultUnknown = false,
    this.exchangeRef,
  });
  final String code;
  final String safeMessage;
  final bool resultUnknown;
  final String? exchangeRef;
}

enum PosExchangeStatus {
  prepared,
  submitting,
  unknown,
  draft,
  approved,
  returnPending,
  returnUnknown,
  returnCompleted,
  salePending,
  saleUnknown,
  completed,
  failed,
  manualRecoveryRequired,
  closed;

  factory PosExchangeStatus.fromWire(String value) => switch (value) {
    'PREPARED' => prepared,
    'SUBMITTING' => submitting,
    'UNKNOWN' => unknown,
    'DRAFT' => draft,
    'APPROVED' => approved,
    'RETURN_PENDING' => returnPending,
    'RETURN_UNKNOWN' => returnUnknown,
    'RETURN_COMPLETED' => returnCompleted,
    'SALE_PENDING' => salePending,
    'SALE_UNKNOWN' => saleUnknown,
    'COMPLETED' => completed,
    'FAILED' => failed,
    'MANUAL_RECOVERY_REQUIRED' => manualRecoveryRequired,
    'CLOSED' => closed,
    _ => throw const PosExchangeFailure(
      'EXCHANGE_RESPONSE_INVALID',
      '服务端返回了未知换货状态。',
    ),
  };

  String get wire => switch (this) {
    prepared => 'PREPARED',
    submitting => 'SUBMITTING',
    unknown => 'UNKNOWN',
    draft => 'DRAFT',
    approved => 'APPROVED',
    returnPending => 'RETURN_PENDING',
    returnUnknown => 'RETURN_UNKNOWN',
    returnCompleted => 'RETURN_COMPLETED',
    salePending => 'SALE_PENDING',
    saleUnknown => 'SALE_UNKNOWN',
    completed => 'COMPLETED',
    failed => 'FAILED',
    manualRecoveryRequired => 'MANUAL_RECOVERY_REQUIRED',
    closed => 'CLOSED',
  };

  bool get terminal => this == completed || this == failed || this == closed;
  String get safeLabel => switch (this) {
    prepared || submitting => '换货关联正在提交',
    unknown || returnUnknown || saleUnknown => '结果未知，仅允许查询原命令',
    draft => '等待另一名受权员工审批',
    approved || returnPending => '等待原退货完成',
    returnCompleted || salePending => '等待新销售完成',
    completed => '换货关联已完成',
    failed => '换货关联失败',
    manualRecoveryRequired => '需要受权人工恢复',
    closed => '换货关联已关闭',
  };
}

/// 已完成的原退货和新销售；本地仅据此冻结关联，不做净额结算。
final class PosExchangeSource {
  const PosExchangeSource({required this.originalReturn, required this.newSale});
  final PosReturnSubmissionView originalReturn;
  final PosCashSettlementView newSale;
}

/// 服务端换货只读检查点。
final class PosExchangeView {
  const PosExchangeView({
    required this.exchangeRef,
    required this.returnRef,
    required this.newOrderRef,
    required this.status,
    required this.expectedRefundAmountMinor,
    required this.expectedSaleReceivableMinor,
    required this.displayDifferenceMinor,
    required this.correlationRef,
    required this.recordVersion,
    required this.updatedAt,
    required this.duplicate,
  });
  final String exchangeRef;
  final String returnRef;
  final String newOrderRef;
  final PosExchangeStatus status;
  final int expectedRefundAmountMinor;
  final int expectedSaleReceivableMinor;
  final int displayDifferenceMinor;
  final String correlationRef;
  final int recordVersion;
  final DateTime updatedAt;
  final bool duplicate;
}
