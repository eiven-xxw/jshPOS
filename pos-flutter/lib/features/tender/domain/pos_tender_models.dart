import 'dart:convert';

import 'package:crypto/crypto.dart';

/// PAY-004 页面和应用层使用的安全错误；resultUnknown 时只能查询原计划。
final class PosTenderFailure implements Exception {
  const PosTenderFailure(
    this.code,
    this.safeMessage, {
    this.resultUnknown = false,
    this.planRef,
  });
  final String code;
  final String safeMessage;
  final bool resultUnknown;
  final String? planRef;
}

enum PosTenderType { electronic, cash }

enum PosTenderAllocationStatus {
  planned,
  processing,
  unknown,
  succeeded,
  failed,
  cancelled,
}

enum PosTenderPlanStatus {
  frozen,
  collecting,
  unknown,
  paid,
  failed,
  cancelled,
  manualRecoveryRequired,
}

extension PosTenderWire on PosTenderType {
  String get wire => this == PosTenderType.cash ? 'CASH' : 'ELECTRONIC';
}

/// 订单 Owner 已冻结的最小支付来源；客户端不能在此声明租户或支付成功。
final class PosTenderSource {
  const PosTenderSource({
    required this.orderRef,
    required this.orderSnapshotSha256,
    required this.storeRef,
    required this.terminalRef,
    required this.shiftRef,
    required this.businessDate,
    required this.receivableAmountMinor,
    this.currency = 'CNY',
  });
  final String orderRef;
  final String orderSnapshotSha256;
  final String storeRef;
  final String terminalRef;
  final String shiftRef;
  final String businessDate;
  final int receivableAmountMinor;
  final String currency;
}

final class PosTenderAllocationDraft {
  const PosTenderAllocationDraft({
    required this.sequenceNo,
    required this.tenderType,
    required this.amountMinor,
  });
  final int sequenceNo;
  final PosTenderType tenderType;
  final int amountMinor;
}

/// 已分配稳定 ULID 的冻结份额，仅用于生成跨端一致摘要。
final class PosTenderAllocationIdentity {
  const PosTenderAllocationIdentity({
    required this.allocationRef,
    required this.sequenceNo,
    required this.tenderType,
    required this.amountMinor,
  });

  final String allocationRef;
  final int sequenceNo;
  final PosTenderType tenderType;
  final int amountMinor;
}

final class PosTenderAllocationView {
  const PosTenderAllocationView({
    required this.allocationRef,
    required this.sequenceNo,
    required this.tenderType,
    required this.status,
    required this.amountMinor,
  });
  final String allocationRef;
  final int sequenceNo;
  final PosTenderType tenderType;
  final PosTenderAllocationStatus status;
  final int amountMinor;
}

final class PosTenderPlanView {
  const PosTenderPlanView({
    required this.planRef,
    required this.orderRef,
    required this.status,
    required this.receivableAmountMinor,
    required this.succeededAmountMinor,
    required this.occupiedAmountMinor,
    required this.currency,
    required this.allocations,
    required this.updatedAt,
    required this.duplicate,
  });
  final String planRef;
  final String orderRef;
  final PosTenderPlanStatus status;
  final int receivableAmountMinor;
  final int succeededAmountMinor;
  final int occupiedAmountMinor;
  final String currency;
  final List<PosTenderAllocationView> allocations;
  final DateTime updatedAt;
  final bool duplicate;
}

/// 与 Java 端一致的 2—8 份额、现金最后、金额守恒规则。
abstract final class PosTenderRules {
  static List<PosTenderAllocationDraft> validate(
    PosTenderSource source,
    List<PosTenderAllocationDraft> input,
  ) {
    if (source.receivableAmountMinor <= 0 ||
        source.receivableAmountMinor > 9007199254740991) {
      throw const PosTenderFailure('PAY-AMOUNT-001', '订单应收超出支持范围。');
    }
    if (source.currency != 'CNY') {
      throw const PosTenderFailure('TENDER-CURRENCY-001', '商业 V1 组合支付只支持 CNY。');
    }
    if (input.length < 2 || input.length > 8) {
      throw const PosTenderFailure('TENDER-PLAN-001', '组合支付必须包含 2 至 8 个份额。');
    }
    final sorted = [...input]
      ..sort((a, b) => a.sequenceNo.compareTo(b.sequenceNo));
    var sum = 0;
    var cashCount = 0;
    for (var index = 0; index < sorted.length; index++) {
      final item = sorted[index];
      if (item.sequenceNo != index + 1) {
        throw const PosTenderFailure('TENDER-SEQUENCE-001', '份额顺序必须从 1 连续递增。');
      }
      if (item.amountMinor <= 0 || item.amountMinor > 9007199254740991) {
        throw const PosTenderFailure('PAY-AMOUNT-001', '支付份额金额超出支持范围。');
      }
      if (item.tenderType == PosTenderType.cash) cashCount++;
      sum += item.amountMinor;
    }
    if (cashCount > 1) {
      throw const PosTenderFailure('TENDER-CASH-001', '现金份额至多一个。');
    }
    if (cashCount == 1 && sorted.last.tenderType != PosTenderType.cash) {
      throw const PosTenderFailure('TENDER-CASH-002', '现金份额必须最后收取。');
    }
    if (sum != source.receivableAmountMinor) {
      throw const PosTenderFailure('TENDER-AMOUNT-002', '支付份额合计必须等于订单应收。');
    }
    return List.unmodifiable(sorted);
  }
}

/// Java `PaymentHash.canonical` 的 Dart 等价实现；只接受正式冻结字段顺序。
abstract final class PosTenderDigest {
  static String planContentSha256({
    required String planRef,
    required PosTenderSource source,
    required List<PosTenderAllocationIdentity> allocations,
  }) {
    final values = <Object>[
      planRef,
      source.orderRef,
      source.orderSnapshotSha256,
      source.storeRef,
      source.terminalRef,
      source.shiftRef,
      source.businessDate,
      source.receivableAmountMinor,
      source.currency,
      for (final item in allocations) ...[
        item.allocationRef,
        item.sequenceNo,
        item.tenderType.wire,
        item.amountMinor,
      ],
    ];
    final canonical = values.map((value) {
      final text = value.toString();
      return '${text.length}:$text;';
    }).join();
    return sha256.convert(utf8.encode(canonical)).toString();
  }

  static String allocationSha256({
    required String planRef,
    required PosTenderAllocationIdentity allocation,
    required String currency,
  }) {
    final values = <Object>[
      planRef,
      allocation.allocationRef,
      allocation.sequenceNo,
      allocation.tenderType.wire,
      allocation.amountMinor,
      currency,
    ];
    final canonical = values.map((value) {
      final text = value.toString();
      return '${text.length}:$text;';
    }).join();
    return sha256.convert(utf8.encode(canonical)).toString();
  }
}
