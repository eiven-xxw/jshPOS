import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/promotion/domain/manual_adjustment_engine.dart';
import 'package:jshpos_pos/features/promotion/domain/promotion_engine.dart';

void main() {
  test('Flutter matches every shared PRM-002 manual vector exactly', () {
    final root = jsonDecode(
      File(
        '../contracts/t2/gate5a/test-vectors/manual-adjustment-vectors-v1.json',
      ).readAsStringSync(),
    ) as Map<String, Object?>;
    final rawPolicy = root['policy']! as Map<String, Object?>;
    final policy = ManualPolicy(
      policyVersionId: rawPolicy['policyVersionId']! as int,
      policySha256: rawPolicy['policySha256']! as String,
      withoutApprovalMinor: rawPolicy['withoutApprovalMinor']! as int,
      withApprovalMinor: rawPolicy['withApprovalMinor']! as int,
      minimumLinePayableMinor: rawPolicy['minimumLinePayableMinor']! as int,
      maximumRoundingMinor: rawPolicy['maximumRoundingMinor']! as int,
      roundingMultiplesMinor: (rawPolicy['roundingMultiplesMinor']! as List)
          .cast<int>(),
    );
    final lines = (root['lines']! as List<Object?>).map((raw) {
      final value = raw! as Map<String, Object?>;
      return PromotionLine(
        lineId: value['lineId']! as String,
        lineNo: value['lineNo']! as int,
        skuId: value['skuId']! as String,
        categoryId: null,
        brandId: null,
        quantity: ExactDecimal.parse(
          value['quantity']! as String,
          maximumScale: 6,
        ),
        unitPriceMinor: value['unitPriceMinor']! as int,
      );
    }).toList();
    final rawBase = root['base']! as Map<String, Object?>;
    final base = PromotionQuote(
      grossAmountMinor: rawBase['grossAmountMinor']! as int,
      discountAmountMinor: rawBase['discountAmountMinor']! as int,
      payableAmountMinor: rawBase['payableAmountMinor']! as int,
      lineDiscounts: (rawBase['lineDiscounts']! as Map<String, Object?>).map(
        (key, value) => MapEntry(key, value! as int),
      ),
      appliedRuleIds: const [],
      explanations: const [],
      adjustments: const [],
    );
    final engine = ManualAdjustmentEngine();
    for (final raw in root['scenarios']! as List<Object?>) {
      final scenario = raw! as Map<String, Object?>;
      final expected = scenario['expected']! as Map<String, Object?>;
      final result = engine.preview(
        current: base,
        lines: lines,
        command: ManualCommand(
          authorizationId: '01K5R000000000000000000050',
          actionType: _action(scenario['actionType']! as String),
          lineId: scenario['lineId'] as String?,
          amountOrRate: scenario['amountOrRate']! as String,
          paymentMethod: scenario['paymentMethod'] == 'CASH'
              ? ManualPaymentMethod.cash
              : ManualPaymentMethod.nonCash,
        ),
        policy: policy,
      );
      expect(
        result.incrementalDiscountMinor,
        expected['incrementalDiscountMinor'],
        reason: scenario['id']! as String,
      );
      expect(result.requiresApproval, expected['requiresApproval']);
      expect(result.result.payableAmountMinor, expected['payableAmountMinor']);
      expect(
        result.result.lineDiscounts,
        (expected['lineDiscounts']! as Map<String, Object?>).map(
          (key, value) => MapEntry(key, value! as int),
        ),
      );
    }
  });
}

ManualActionType _action(String value) => switch (value) {
  'LINE_FIXED_PRICE' => ManualActionType.lineFixedPrice,
  'ORDER_AMOUNT_OFF' => ManualActionType.orderAmountOff,
  'ORDER_PERCENT_OFF' => ManualActionType.orderPercentOff,
  'ROUNDING' => ManualActionType.rounding,
  _ => throw FormatException('unknown action $value'),
};
