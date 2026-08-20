/// POS-009 页面可安全展示的失败；不得包含凭据、原始响应或支付敏感数据。
final class PosReturnFailure implements Exception {
  const PosReturnFailure(
    this.code,
    this.message, {
    this.resultUnknown = false,
    this.returnRef,
  });

  final String code;
  final String message;
  final bool resultUnknown;
  final String? returnRef;

  @override
  String toString() => '$code: $message';
}

/// Return Owner 的稳定 Saga 状态；未知 wire 值必须失败关闭。
enum PosReturnSagaStatus {
  pendingApproval('PENDING_APPROVAL', '待独立审批'),
  promotionPending('PROMOTION_PENDING', '正在恢复原成交优惠'),
  cashRefundPending('CASH_REFUND_PENDING', '现金退款处理中'),
  paymentPending('PAYMENT_PENDING', 'Provider 无关退款处理中'),
  paymentUnknown('PAYMENT_UNKNOWN', '退款结果未知，必须查询'),
  inventoryPending('INVENTORY_PENDING', '退货入库处理中'),
  completed('COMPLETED', '退货退款完成'),
  failed('FAILED', '退货退款失败');

  const PosReturnSagaStatus(this.wireCode, this.safeLabel);

  final String wireCode;
  final String safeLabel;

  static PosReturnSagaStatus fromWire(String value) {
    for (final status in values) {
      if (status.wireCode == value) return status;
    }
    throw const PosReturnFailure(
      'RETURN_STATUS_UNSUPPORTED',
      '退货状态版本不兼容，请升级应用后重试。',
    );
  }

  bool get terminal => this == completed || this == failed;
}

/// 原成交行与本次申请投影；数量和金额均由 Owner 返回，页面不得计算。
final class PosReturnLineView {
  PosReturnLineView({
    required this.lineRef,
    required this.skuCode,
    required this.name,
    required this.unitName,
    required this.originalQuantity,
    required this.cumulativeReturnedQuantity,
    required this.maximumReturnableQuantity,
    required this.requestedQuantity,
    required this.requestedGrossMinor,
    required this.recoveredDiscountMinor,
    required this.refundableAmountMinor,
  }) {
    _requireUlid(lineRef, 'lineRef');
    if (skuCode.trim().isEmpty ||
        name.trim().isEmpty ||
        unitName.trim().isEmpty) {
      throw const PosReturnFailure('RETURN_LINE_INVALID', '原单行资料不完整。');
    }
    final original = _ExactDecimal.parse(originalQuantity, allowZero: false);
    final returned = _ExactDecimal.parse(cumulativeReturnedQuantity);
    final maximum = _ExactDecimal.parse(maximumReturnableQuantity);
    final requested = _ExactDecimal.parse(requestedQuantity);
    if (returned.compareTo(original) > 0 ||
        maximum.compareTo(original) > 0 ||
        requested.compareTo(maximum) > 0) {
      throw const PosReturnFailure('RETURN_QUANTITY_INVARIANT', '原单可退数量校验失败。');
    }
    _requireMoney(requestedGrossMinor, 'requestedGrossMinor');
    _requireMoney(recoveredDiscountMinor, 'recoveredDiscountMinor');
    _requireMoney(refundableAmountMinor, 'refundableAmountMinor');
    if (requestedGrossMinor - recoveredDiscountMinor != refundableAmountMinor ||
        (requested.isZero &&
            (requestedGrossMinor != 0 ||
                recoveredDiscountMinor != 0 ||
                refundableAmountMinor != 0))) {
      throw const PosReturnFailure('RETURN_AMOUNT_INVARIANT', '原成交优惠恢复金额不守恒。');
    }
  }

  final String lineRef;
  final String skuCode;
  final String name;
  final String unitName;
  final String originalQuantity;
  final String cumulativeReturnedQuantity;
  final String maximumReturnableQuantity;
  final String requestedQuantity;
  final int requestedGrossMinor;
  final int recoveredDiscountMinor;
  final int refundableAmountMinor;

  bool get selected => !_ExactDecimal.parse(requestedQuantity).isZero;
}

/// 原单及退货草稿的组合只读投影；每次应用命令后整体替换。
final class PosReturnWorkspace {
  PosReturnWorkspace({
    required this.orderRef,
    required this.localOrderNo,
    required this.storeName,
    required this.businessDate,
    required this.currency,
    required this.settlementKind,
    required this.promotionSnapshotRef,
    required this.promotionSnapshotSha256,
    required this.originalReceivableAmountMinor,
    required this.cumulativeRefundedAmountMinor,
    required this.maximumRefundableAmountMinor,
    required this.requestedGrossAmountMinor,
    required this.recoveredDiscountAmountMinor,
    required this.refundableAmountMinor,
    required Iterable<PosReturnLineView> lines,
    this.existingReturnRef,
  }) : lines = List.unmodifiable(lines) {
    _requireUlid(orderRef, 'orderRef');
    if (existingReturnRef != null) {
      _requireUlid(existingReturnRef!, 'existingReturnRef');
    }
    _requireUlid(promotionSnapshotRef, 'promotionSnapshotRef');
    _requireSha256(promotionSnapshotSha256, 'promotionSnapshotSha256');
    if (localOrderNo.trim().isEmpty ||
        storeName.trim().isEmpty ||
        !RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(businessDate) ||
        currency != 'CNY' ||
        !const {'CASH', 'PROVIDER_NEUTRAL'}.contains(settlementKind) ||
        this.lines.isEmpty ||
        this.lines.length > 500) {
      throw const PosReturnFailure('RETURN_ORDER_INVALID', '原单退货快照不完整或不受支持。');
    }
    for (final amount in [
      originalReceivableAmountMinor,
      cumulativeRefundedAmountMinor,
      maximumRefundableAmountMinor,
      requestedGrossAmountMinor,
      recoveredDiscountAmountMinor,
      refundableAmountMinor,
    ]) {
      _requireMoney(amount, 'returnAmount');
    }
    if (cumulativeRefundedAmountMinor + maximumRefundableAmountMinor >
            originalReceivableAmountMinor ||
        requestedGrossAmountMinor - recoveredDiscountAmountMinor !=
            refundableAmountMinor ||
        refundableAmountMinor > maximumRefundableAmountMinor ||
        this.lines.fold<int>(
              0,
              (sum, line) => sum + line.requestedGrossMinor,
            ) !=
            requestedGrossAmountMinor ||
        this.lines.fold<int>(
              0,
              (sum, line) => sum + line.recoveredDiscountMinor,
            ) !=
            recoveredDiscountAmountMinor ||
        this.lines.fold<int>(
              0,
              (sum, line) => sum + line.refundableAmountMinor,
            ) !=
            refundableAmountMinor) {
      throw const PosReturnFailure('RETURN_TOTAL_INVARIANT', '退货头行金额或累计上限不守恒。');
    }
  }

  final String orderRef;
  final String localOrderNo;
  final String storeName;
  final String businessDate;
  final String currency;
  final String settlementKind;
  final String promotionSnapshotRef;
  final String promotionSnapshotSha256;
  final int originalReceivableAmountMinor;
  final int cumulativeRefundedAmountMinor;
  final int maximumRefundableAmountMinor;
  final int requestedGrossAmountMinor;
  final int recoveredDiscountAmountMinor;
  final int refundableAmountMinor;
  final List<PosReturnLineView> lines;
  final String? existingReturnRef;

  bool get canSubmit =>
      refundableAmountMinor > 0 && lines.any((line) => line.selected);
}

/// 已持久化退货退款检查点；UNKNOWN 只允许通过原 returnRef 查询。
final class PosReturnSubmissionView {
  PosReturnSubmissionView({
    required this.returnRef,
    required this.orderRef,
    required this.status,
    required this.refundableAmountMinor,
    required this.promotionSnapshotRef,
    required this.promotionSnapshotSha256,
    required this.auditRef,
    required this.correlationRef,
    required this.updatedAt,
    required this.duplicate,
  }) {
    for (final value in [
      returnRef,
      orderRef,
      promotionSnapshotRef,
      correlationRef,
    ]) {
      _requireUlid(value, 'returnIdentity');
    }
    _requireSha256(promotionSnapshotSha256, 'promotionSnapshotSha256');
    _requireMoney(refundableAmountMinor, 'refundableAmountMinor');
    if (auditRef.trim().isEmpty) {
      throw const PosReturnFailure('RETURN_AUDIT_INVALID', '退货审计引用缺失。');
    }
  }

  final String returnRef;
  final String orderRef;
  final PosReturnSagaStatus status;
  final int refundableAmountMinor;
  final String promotionSnapshotRef;
  final String promotionSnapshotSha256;
  final String auditRef;
  final String correlationRef;
  final DateTime updatedAt;
  final bool duplicate;
}

void _requireUlid(String value, String field) {
  if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(value)) {
    throw PosReturnFailure('RETURN_ID_INVALID', '$field 不是规范业务标识。');
  }
}

void _requireSha256(String value, String field) {
  if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(value)) {
    throw PosReturnFailure('RETURN_DIGEST_INVALID', '$field 摘要无效。');
  }
}

void _requireMoney(int value, String field) {
  if (value < 0 || value > 9007199254740991) {
    throw PosReturnFailure('RETURN_MONEY_INVALID', '$field 金额无效。');
  }
}

final class _ExactDecimal {
  const _ExactDecimal(this.unscaled, this.scale);

  factory _ExactDecimal.parse(String value, {bool allowZero = true}) {
    final match = RegExp(r'^(0|[1-9]\d{0,12})(?:\.(\d{1,6}))?$')
        .firstMatch(value);
    if (match == null) {
      throw const PosReturnFailure('RETURN_QUANTITY_INVALID', '退货数量格式无效。');
    }
    final fraction = match.group(2) ?? '';
    final parsed = _ExactDecimal(
      BigInt.parse('${match.group(1)}$fraction'),
      fraction.length,
    );
    if (!allowZero && parsed.isZero) {
      throw const PosReturnFailure('RETURN_QUANTITY_INVALID', '退货数量必须大于零。');
    }
    return parsed;
  }

  final BigInt unscaled;
  final int scale;

  bool get isZero => unscaled == BigInt.zero;

  int compareTo(_ExactDecimal other) {
    final common = scale > other.scale ? scale : other.scale;
    return (unscaled * _pow10(common - scale)).compareTo(
      other.unscaled * _pow10(common - other.scale),
    );
  }
}

BigInt _pow10(int exponent) {
  var result = BigInt.one;
  for (var index = 0; index < exponent; index++) {
    result *= BigInt.from(10);
  }
  return result;
}
