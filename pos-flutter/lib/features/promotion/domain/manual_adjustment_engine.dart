import '../../checkout/domain/exact_quantity.dart';
import 'promotion_engine.dart';

/// PRM-002 获准的人工优惠动作。
enum ManualActionType {
  lineFixedPrice,
  orderAmountOff,
  orderPercentOff,
  rounding,
}

/// 只用于限制现金抹零，不代表支付成功事实。
enum ManualPaymentMethod { cash, nonCash }

/// 服务端签名配置包中冻结的人工优惠阈值。
final class ManualPolicy {
  ManualPolicy({
    required this.policyVersionId,
    required this.policySha256,
    required this.withoutApprovalMinor,
    required this.withApprovalMinor,
    required this.minimumLinePayableMinor,
    required this.maximumRoundingMinor,
    required this.roundingMultiplesMinor,
  }) {
    if (policyVersionId <= 0 ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(policySha256) ||
        withoutApprovalMinor < 0 ||
        withApprovalMinor < withoutApprovalMinor ||
        withApprovalMinor > MoneyRules.maxSafeJsonInteger ||
        minimumLinePayableMinor < 0 ||
        maximumRoundingMinor < 0 ||
        maximumRoundingMinor > withApprovalMinor ||
        roundingMultiplesMinor.isEmpty ||
        roundingMultiplesMinor.any((value) => value <= 0 || value > 10000)) {
      throw const FormatException('PRM-AUTH-010: invalid manual policy');
    }
  }

  final int policyVersionId;
  final String policySha256;
  final int withoutApprovalMinor;
  final int withApprovalMinor;
  final int minimumLinePayableMinor;
  final int maximumRoundingMinor;
  final List<int> roundingMultiplesMinor;
}

/// 人工优惠命令；授权 ID 同时作为解释来源。
final class ManualCommand {
  ManualCommand({
    required this.authorizationId,
    required this.actionType,
    required this.amountOrRate,
    required this.paymentMethod,
    this.lineId,
  }) {
    if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(authorizationId) ||
        amountOrRate.isEmpty ||
        amountOrRate.length > 32) {
      throw const FormatException('PRM-AUTH-011: invalid manual command');
    }
  }

  final String authorizationId;
  final ManualActionType actionType;
  final String? lineId;
  final String amountOrRate;
  final ManualPaymentMethod paymentMethod;
}

/// 人工优惠预检结果；需要复核时不得把 result 当作已成交事实。
final class ManualPreview {
  const ManualPreview({
    required this.result,
    required this.incrementalDiscountMinor,
    required this.requiresApproval,
  });
  final PromotionQuote result;
  final int incrementalDiscountMinor;
  final bool requiresApproval;
}

/// 与 Java 使用同一金额、顺序和最大余数语义的离线人工优惠引擎。
final class ManualAdjustmentEngine {
  ManualPreview preview({
    required PromotionQuote current,
    required List<PromotionLine> lines,
    required ManualCommand command,
    required ManualPolicy policy,
  }) {
    _requireCurrent(current, lines);
    final allocations = switch (command.actionType) {
      ManualActionType.lineFixedPrice => _lineFixedPrice(
        current,
        lines,
        command,
        policy,
      ),
      ManualActionType.orderAmountOff => _allocate(
        current,
        lines,
        _minor(command.amountOrRate),
      ),
      ManualActionType.orderPercentOff => _percent(
        current,
        lines,
        command.amountOrRate,
      ),
      ManualActionType.rounding => _rounding(current, lines, command, policy),
    };
    final incremental = _sum(allocations.values);
    if (incremental <= 0 || incremental > policy.withApprovalMinor) {
      throw const FormatException('PRM-AUTH-012: manual limit exceeded');
    }
    final discounts = <String, int>{};
    for (final line in lines) {
      discounts[line.lineId] = _add(
        current.lineDiscounts[line.lineId]!,
        allocations[line.lineId] ?? 0,
      );
    }
    final adjustments = [
      ...current.adjustments,
      PromotionAdjustment(command.authorizationId, incremental, allocations),
    ];
    final result = PromotionQuote(
      grossAmountMinor: current.grossAmountMinor,
      discountAmountMinor: _add(current.discountAmountMinor, incremental),
      payableAmountMinor: current.payableAmountMinor - incremental,
      lineDiscounts: discounts,
      appliedRuleIds: current.appliedRuleIds,
      explanations: [
        ...current.explanations,
        PromotionExplanation(
          command.authorizationId,
          'APPLIED_MANUAL_${command.actionType.name.toUpperCase()}',
        ),
      ],
      adjustments: adjustments,
    );
    return ManualPreview(
      result: result,
      incrementalDiscountMinor: incremental,
      requiresApproval: incremental > policy.withoutApprovalMinor,
    );
  }

  Map<String, int> _lineFixedPrice(
    PromotionQuote current,
    List<PromotionLine> lines,
    ManualCommand command,
    ManualPolicy policy,
  ) {
    if (command.lineId == null) {
      throw const FormatException('PRM-AMOUNT-011: line required');
    }
    final line = lines.singleWhere(
      (value) => value.lineId == command.lineId,
      orElse: () => throw const FormatException('PRM-AMOUNT-012: line missing'),
    );
    final gross = line.quantity.multiplyMinorHalfUp(line.unitPriceMinor);
    final payable = gross - current.lineDiscounts[line.lineId]!;
    final target = line.quantity.multiplyMinorHalfUp(
      _minor(command.amountOrRate),
    );
    if (target < policy.minimumLinePayableMinor || target >= payable) {
      throw const FormatException('PRM-AMOUNT-013: fixed price outside policy');
    }
    return {line.lineId: payable - target};
  }

  Map<String, int> _percent(
    PromotionQuote current,
    List<PromotionLine> lines,
    String source,
  ) {
    final rate = ExactDecimal.parse(source, maximumScale: 8);
    final one = BigInt.from(10).pow(rate.scale);
    if (!rate.isPositive || rate.unscaled > one) {
      throw const FormatException('PRM-AMOUNT-019: invalid rate');
    }
    final amount = rate.multiplyMinorHalfUp(current.payableAmountMinor);
    return _allocate(current, lines, amount);
  }

  Map<String, int> _rounding(
    PromotionQuote current,
    List<PromotionLine> lines,
    ManualCommand command,
    ManualPolicy policy,
  ) {
    if (command.paymentMethod != ManualPaymentMethod.cash ||
        command.lineId != null) {
      throw const FormatException('PRM-AMOUNT-015: cash only');
    }
    final multiple = _minor(command.amountOrRate);
    if (!policy.roundingMultiplesMinor.contains(multiple)) {
      throw const FormatException('PRM-AMOUNT-016: rounding not allowed');
    }
    final amount = current.payableAmountMinor % multiple;
    if (amount <= 0 || amount > policy.maximumRoundingMinor) {
      throw const FormatException('PRM-AMOUNT-017: rounding limit');
    }
    return _allocate(current, lines, amount);
  }

  Map<String, int> _allocate(
    PromotionQuote current,
    List<PromotionLine> lines,
    int amount,
  ) {
    if (amount <= 0 || amount > current.payableAmountMinor) {
      throw const FormatException('PRM-AMOUNT-014: order discount exceeded');
    }
    final sorted = [...lines]..sort((a, b) => a.lineNo.compareTo(b.lineNo));
    final weights = <_Weight>[];
    for (final line in sorted) {
      final gross = line.quantity.multiplyMinorHalfUp(line.unitPriceMinor);
      weights.add(_Weight(line, gross - current.lineDiscounts[line.lineId]!));
    }
    final total = _sum(weights.map((value) => value.amount));
    final shares = weights.map((weight) {
      final numerator = BigInt.from(amount) * BigInt.from(weight.amount);
      return _Share(
        weight,
        (numerator ~/ BigInt.from(total)).toInt(),
        numerator.remainder(BigInt.from(total)),
      );
    }).toList();
    var assigned = _sum(shares.map((value) => value.amount));
    shares.sort((left, right) {
      final remainder = right.remainder.compareTo(left.remainder);
      if (remainder != 0) return remainder;
      final lineNo = left.weight.line.lineNo.compareTo(
        right.weight.line.lineNo,
      );
      if (lineNo != 0) return lineNo;
      final sku = left.weight.line.skuId.compareTo(right.weight.line.skuId);
      return sku != 0
          ? sku
          : left.weight.line.lineId.compareTo(right.weight.line.lineId);
    });
    for (var index = 0; index < amount - assigned; index++) {
      shares[index % shares.length].amount++;
    }
    shares.sort((a, b) => a.weight.line.lineNo.compareTo(b.weight.line.lineNo));
    final result = {
      for (final value in shares) value.weight.line.lineId: value.amount,
    };
    if (_sum(result.values) != amount) throw StateError('PRM-ALLOC-004');
    return result;
  }

  void _requireCurrent(PromotionQuote current, List<PromotionLine> lines) {
    if (lines.isEmpty ||
        lines.length != current.lineDiscounts.length ||
        current.grossAmountMinor !=
            current.discountAmountMinor + current.payableAmountMinor ||
        lines.any((line) => !current.lineDiscounts.containsKey(line.lineId))) {
      throw const FormatException('PRM-AMOUNT-018: invalid base quote');
    }
  }
}

final class _Weight {
  const _Weight(this.line, this.amount);
  final PromotionLine line;
  final int amount;
}

final class _Share {
  _Share(this.weight, this.amount, this.remainder);
  final _Weight weight;
  int amount;
  final BigInt remainder;
}

int _minor(String value) {
  if (!RegExp(r'^(0|[1-9][0-9]{0,15})$').hasMatch(value)) {
    throw const FormatException('PRM-AMOUNT-020: invalid minor');
  }
  return MoneyRules.requireMinor(int.parse(value), 'manualMoney');
}

int _add(int left, int right) => MoneyRules.requireMinor(
  (BigInt.from(left) + BigInt.from(right)).toInt(),
  'manualMoney',
);

int _sum(Iterable<int> values) {
  final result = values.fold<BigInt>(
    BigInt.zero,
    (sum, value) => sum + BigInt.from(value),
  );
  if (result > BigInt.from(MoneyRules.maxSafeJsonInteger)) {
    throw const FormatException('PRM-AMOUNT-021: amount overflow');
  }
  return result.toInt();
}
