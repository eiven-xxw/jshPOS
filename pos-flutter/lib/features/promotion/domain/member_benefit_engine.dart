import 'promotion_engine.dart';

/// 离线包中与当前会员等级绑定的最小权益快照，不包含 PII。
final class MemberBenefitEntitlement {
  const MemberBenefitEntitlement({
    required this.entitlementSnapshotId,
    required this.benefitVersionId,
    required this.levelCode,
    required this.memberPriceEligible,
    required this.stackingAllowed,
    required this.policyAllowStacking,
    required this.defaultCombinationPolicy,
    required this.revocationEpoch,
    required this.rightsDigest,
  });

  final String entitlementSnapshotId;
  final String benefitVersionId;
  final String levelCode;
  final bool memberPriceEligible;
  final bool stackingAllowed;
  final bool policyAllowStacking;
  final String defaultCombinationPolicy;
  final int revocationEpoch;
  final String rightsDigest;
}

/// 一条基础/门店价行及其可选会员价候选。
final class MemberBenefitLineInput {
  const MemberBenefitLineInput({
    required this.line,
    required this.normalDiscountMinor,
    this.memberPriceMinor,
    this.memberPriceVersionId,
  });
  final PromotionLine line;
  final int normalDiscountMinor;
  final int? memberPriceMinor;
  final String? memberPriceVersionId;
}

/// Java/Dart 共用规则下的确定性结果和解释链。
final class MemberBenefitQuote {
  const MemberBenefitQuote({
    required this.selectedPath,
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.payableAmountMinor,
    required this.lineDiscounts,
    required this.sourceAllocationsByLine,
    required this.memberPriceVersions,
    required this.explanations,
  });
  final String selectedPath;
  final int grossAmountMinor;
  final int discountAmountMinor;
  final int payableAmountMinor;
  final Map<String, int> lineDiscounts;
  final Map<String, Map<String, int>> sourceAllocationsByLine;
  final List<String> memberPriceVersions;
  final List<String> explanations;
}

/// BEST_PRICE 默认、平局选普通路径；只有双重显式同意才允许叠加。
final class MemberBenefitEngine {
  static const engineVersion = 'member-benefit-engine-1.0.0';

  MemberBenefitQuote combine({
    required bool capabilityEnabled,
    required MemberBenefitEntitlement? entitlement,
    required List<MemberBenefitLineInput> lines,
  }) {
    if (lines.isEmpty || lines.length > 500) {
      throw const FormatException('MBP-ENGINE-001: invalid line count');
    }
    final gross = lines.fold<int>(
      0,
      (sum, item) =>
          sum +
          item.line.quantity.multiplyMinorHalfUp(item.line.unitPriceMinor),
    );
    final normalDiscount = lines.fold<int>(
      0,
      (sum, item) => sum + item.normalDiscountMinor,
    );
    if (!capabilityEnabled || entitlement == null) {
      return _normal(
        lines,
        gross,
        normalDiscount,
        'CAPABILITY_OR_ENTITLEMENT_UNAVAILABLE',
      );
    }
    if (entitlement.defaultCombinationPolicy == 'NORMAL_ONLY') {
      return _normal(lines, gross, normalDiscount, 'POLICY_NORMAL_ONLY');
    }
    final memberDiscounts = <String, int>{};
    final versions = <String>{};
    for (final item in lines) {
      final lineGross = item.line.quantity.multiplyMinorHalfUp(
        item.line.unitPriceMinor,
      );
      final candidate = entitlement.memberPriceEligible
          ? item.memberPriceMinor
          : null;
      final memberGross = candidate == null
          ? lineGross
          : _multiplyMinorHalfEven(item.line.quantity, candidate);
      memberDiscounts[item.line.lineId] = lineGross - memberGross;
      if (candidate != null && item.memberPriceVersionId != null) {
        versions.add(item.memberPriceVersionId!);
      }
    }
    final memberDiscount = memberDiscounts.values.fold<int>(0, (a, b) => a + b);
    final stacking =
        entitlement.stackingAllowed && entitlement.policyAllowStacking;
    final path = stacking && memberDiscount > 0 && normalDiscount > 0
        ? 'STACKED_MEMBER_PATH'
        : entitlement.defaultCombinationPolicy == 'MEMBER_ONLY' ||
              memberDiscount > normalDiscount
        ? 'MEMBER_PATH'
        : 'NORMAL_PATH';
    final lineDiscounts = <String, int>{};
    final sources = <String, Map<String, int>>{};
    for (final item in lines) {
      final normal = item.normalDiscountMinor;
      final member = memberDiscounts[item.line.lineId]!;
      final lineGross = item.line.quantity.multiplyMinorHalfUp(
        item.line.unitPriceMinor,
      );
      final selected = switch (path) {
        'MEMBER_PATH' => member,
        'STACKED_MEMBER_PATH' => (normal + member).clamp(0, lineGross),
        _ => normal,
      };
      lineDiscounts[item.line.lineId] = selected;
      final allocation = <String, int>{};
      if (path != 'MEMBER_PATH' && normal > 0) {
        allocation['NORMAL_PROMOTION'] = normal.clamp(0, selected);
      }
      final remaining =
          selected - allocation.values.fold<int>(0, (a, b) => a + b);
      if (remaining > 0) allocation['MEMBER_PRICE'] = remaining;
      sources[item.line.lineId] = Map.unmodifiable(allocation);
    }
    final discount = lineDiscounts.values.fold<int>(0, (a, b) => a + b);
    if (discount < 0 || discount > gross) {
      throw StateError('MBP-ENGINE-002: amount conservation failed');
    }
    return MemberBenefitQuote(
      selectedPath: path,
      grossAmountMinor: gross,
      discountAmountMinor: discount,
      payableAmountMinor: gross - discount,
      lineDiscounts: Map.unmodifiable(lineDiscounts),
      sourceAllocationsByLine: Map.unmodifiable(sources),
      memberPriceVersions: (versions.toList()..sort()),
      explanations: List.unmodifiable([
        'DEFAULT_${entitlement.defaultCombinationPolicy}',
        stacking ? 'DOUBLE_OPT_IN_STACKING' : 'BEST_PRICE_TIE_NORMAL',
        path,
      ]),
    );
  }

  MemberBenefitQuote _normal(
    List<MemberBenefitLineInput> lines,
    int gross,
    int discount,
    String explanation,
  ) => MemberBenefitQuote(
    selectedPath: 'NORMAL_PATH',
    grossAmountMinor: gross,
    discountAmountMinor: discount,
    payableAmountMinor: gross - discount,
    lineDiscounts: Map.unmodifiable({
      for (final item in lines) item.line.lineId: item.normalDiscountMinor,
    }),
    sourceAllocationsByLine: Map.unmodifiable({
      for (final item in lines)
        item.line.lineId: item.normalDiscountMinor == 0
            ? const <String, int>{}
            : {'NORMAL_PROMOTION': item.normalDiscountMinor},
    }),
    memberPriceVersions: const [],
    explanations: [explanation, 'NORMAL_PATH'],
  );

  int _multiplyMinorHalfEven(ExactDecimal quantity, int minor) {
    final divisor = BigInt.from(10).pow(quantity.scale);
    final numerator = quantity.unscaled * BigInt.from(minor);
    final quotient = numerator ~/ divisor;
    final remainder = numerator.remainder(divisor);
    final doubled = remainder * BigInt.two;
    final rounded = doubled > divisor || (doubled == divisor && quotient.isOdd)
        ? quotient + BigInt.one
        : quotient;
    if (rounded < BigInt.zero || rounded > BigInt.from(9000000000000000)) {
      throw const FormatException('MBP-ENGINE-003: amount overflow');
    }
    return rounded.toInt();
  }
}
