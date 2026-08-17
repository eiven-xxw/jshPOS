import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/promotion/domain/promotion_engine.dart';

void main() {
  test('Flutter matches every shared PRM-001 golden vector exactly', () {
    final file = File(
      '../contracts/t2/gate5a/test-vectors/promotion-golden-vectors-v1.json',
    );
    expect(file.existsSync(), isTrue);
    final root = jsonDecode(file.readAsStringSync()) as Map<String, Object?>;
    expect(root['engineVersion'], PromotionEngine.engineVersion);
    final scenarios = root['scenarios']! as List<Object?>;
    expect(scenarios, hasLength(17));
    final engine = PromotionEngine();
    for (final raw in scenarios) {
      final scenario = raw! as Map<String, Object?>;
      final result = engine.quote(
        businessTime: DateTime.parse(scenario['businessTime']! as String),
        storeId: scenario['storeId']! as String,
        channel: scenario['channel']! as String,
        lines: (scenario['lines']! as List<Object?>).map(_line).toList(),
        rules: (scenario['rules']! as List<Object?>).map(_rule).toList(),
      );
      final expected = scenario['expected']! as Map<String, Object?>;
      expect(
        result.grossAmountMinor,
        expected['grossAmountMinor'],
        reason: scenario['id']! as String,
      );
      expect(
        result.discountAmountMinor,
        expected['discountAmountMinor'],
        reason: scenario['id']! as String,
      );
      expect(
        result.payableAmountMinor,
        expected['payableAmountMinor'],
        reason: scenario['id']! as String,
      );
      expect(
        result.lineDiscounts,
        (expected['lineDiscounts']! as Map<String, Object?>).map(
          (key, value) => MapEntry(key, value! as int),
        ),
      );
      expect(
        result.appliedRuleIds,
        (expected['appliedRuleIds']! as List<Object?>).cast<String>(),
      );
      expect(
        result.explanations,
        (expected['explanations']! as List<Object?>).map((item) {
          final value = item! as Map<String, Object?>;
          return PromotionExplanation(
            value['sourceId']! as String,
            value['code']! as String,
          );
        }).toList(),
      );
      expect(
        result.adjustments.fold(0, (sum, item) => sum + item.amountMinor),
        result.discountAmountMinor,
      );
    }
  });
}

PromotionLine _line(Object? raw) {
  final value = raw! as Map<String, Object?>;
  return PromotionLine(
    lineId: value['lineId']! as String,
    lineNo: value['lineNo']! as int,
    skuId: value['skuId']! as String,
    categoryId: value['categoryId'] as String?,
    brandId: value['brandId'] as String?,
    quantity: ExactDecimal.parse(value['quantity']! as String, maximumScale: 6),
    unitPriceMinor: value['unitPriceMinor']! as int,
  );
}

PromotionRule _rule(Object? raw) {
  final value = raw! as Map<String, Object?>;
  final scope = value['scope']! as Map<String, Object?>;
  final benefit = value['benefit']! as Map<String, Object?>;
  return PromotionRule(
    ruleVersionId: value['ruleVersionId']! as String,
    ruleType: _ruleType(value['ruleType']! as String),
    priority: value['priority']! as int,
    stackMode: _stackMode(value['stackMode']! as String),
    exclusiveGroup: value['exclusiveGroup'] as String?,
    effectiveFrom: DateTime.parse(value['effectiveFrom']! as String),
    effectiveTo: DateTime.parse(value['effectiveTo']! as String),
    scope: PromotionScope(
      skuIds: _strings(scope['skuIds']),
      categoryIds: _strings(scope['categoryIds']),
      brandIds: _strings(scope['brandIds']),
      storeIds: _strings(scope['storeIds']),
      channels: _strings(scope['channels']),
      businessDays: _integers(scope['businessDays']),
    ),
    benefit: PromotionBenefit(
      amountMinor: benefit['amountMinor'] as int?,
      discountRate: _decimal(benefit['discountRate'], 8),
      nth: benefit['nth'] as int?,
      thresholdMinor: benefit['thresholdMinor'] as int?,
      thresholdQuantity: _decimal(benefit['thresholdQuantity'], 6),
      bundlePriceMinor: benefit['bundlePriceMinor'] as int?,
      bundleComponents:
          ((benefit['bundleComponents'] as List<Object?>?) ?? const []).map((
            item,
          ) {
            final component = item! as Map<String, Object?>;
            return BundleComponent(
              component['skuId']! as String,
              ExactDecimal.parse(
                component['quantity']! as String,
                maximumScale: 6,
              ),
            );
          }).toList(),
    ),
  );
}

Set<String> _strings(Object? value) =>
    ((value as List<Object?>?) ?? const []).cast<String>().toSet();

Set<int> _integers(Object? value) =>
    ((value as List<Object?>?) ?? const []).cast<int>().toSet();
ExactDecimal? _decimal(Object? value, int scale) => value == null
    ? null
    : ExactDecimal.parse(value as String, maximumScale: scale);

PromotionRuleType _ruleType(String value) => switch (value) {
  'SPECIAL_PRICE' => PromotionRuleType.specialPrice,
  'PERCENT_OFF' => PromotionRuleType.percentOff,
  'AMOUNT_OFF' => PromotionRuleType.amountOff,
  'NTH_ITEM_DISCOUNT' => PromotionRuleType.nthItemDiscount,
  'BUNDLE_PRICE' => PromotionRuleType.bundlePrice,
  'THRESHOLD_AMOUNT_OFF' => PromotionRuleType.thresholdAmountOff,
  'THRESHOLD_QUANTITY_OFF' => PromotionRuleType.thresholdQuantityOff,
  _ => throw FormatException('unknown rule $value'),
};

PromotionStackMode _stackMode(String value) => switch (value) {
  'EXCLUSIVE' => PromotionStackMode.exclusive,
  'STACKABLE' => PromotionStackMode.stackable,
  'BEST_OF_GROUP' => PromotionStackMode.bestOfGroup,
  _ => throw FormatException('unknown stack mode $value'),
};
