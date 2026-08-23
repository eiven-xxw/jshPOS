import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/promotion/domain/member_benefit_engine.dart';
import 'package:jshpos_pos/features/promotion/domain/promotion_engine.dart';

/// 与 Java 读取同一份 40 场景金标，金额场景逐字段执行并比较。
void main() {
  test('Java/Dart common member benefit vectors remain identical', () {
    final root = jsonDecode(
      File('../contracts/t2/gate7d-mem003/member-benefit-price-vectors.json')
          .readAsStringSync(),
    ) as Map<String, dynamic>;
    final vectors = root['vectors']! as List<dynamic>;
    final ids = <String>{};
    var calculationCount = 0;
    for (final raw in vectors) {
      final vector = raw! as Map<String, dynamic>;
      expect(ids.add(vector['id']! as String), isTrue);
      expect(vector['case']! as String, isNotEmpty);
      if (vector['mode'] != 'CALCULATION') {
        expect(vector['expectedOutcome']! as String, isNotEmpty);
        continue;
      }
      calculationCount++;
      final expected = vector['expected']! as Map<String, dynamic>;
      final line = PromotionLine(
        lineId: '01K7V000000000000000000001',
        lineNo: 1,
        skuId: '101',
        categoryId: null,
        brandId: null,
        quantity: ExactDecimal.parse(vector['quantity']! as String),
        unitPriceMinor: vector['unitPriceMinor']! as int,
      );
      final actual = MemberBenefitEngine().combine(
        capabilityEnabled: vector['capabilityEnabled']! as bool,
        entitlement: const MemberBenefitEntitlement(
          entitlementSnapshotId: '01K7V000000000000000000002',
          benefitVersionId: '01K7V000000000000000000003',
          levelCode: 'GOLD',
          memberPriceEligible: true,
          stackingAllowed: false,
          policyAllowStacking: false,
          defaultCombinationPolicy: 'BEST_PRICE',
          revocationEpoch: 0,
          rightsDigest: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        ),
        lines: [
          MemberBenefitLineInput(
            line: line,
            normalDiscountMinor: vector['normalDiscountMinor']! as int,
            memberPriceMinor: vector['memberPriceMinor']! as int,
            memberPriceVersionId: '01K7V000000000000000000004',
          ),
        ],
      );
      final allowsStacking =
          vector['entitlementAllowsStacking']! as bool &&
          vector['promotionAllowsStacking']! as bool;
      final rerun = allowsStacking
          ? MemberBenefitEngine().combine(
              capabilityEnabled: vector['capabilityEnabled']! as bool,
              entitlement: const MemberBenefitEntitlement(
                entitlementSnapshotId: '01K7V000000000000000000002',
                benefitVersionId: '01K7V000000000000000000003',
                levelCode: 'GOLD',
                memberPriceEligible: true,
                stackingAllowed: true,
                policyAllowStacking: true,
                defaultCombinationPolicy: 'BEST_PRICE',
                revocationEpoch: 0,
                rightsDigest: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
              ),
              lines: [
                MemberBenefitLineInput(
                  line: line,
                  normalDiscountMinor: vector['normalDiscountMinor']! as int,
                  memberPriceMinor: vector['memberPriceMinor']! as int,
                  memberPriceVersionId: '01K7V000000000000000000004',
                ),
              ],
            )
          : actual;
      expect(rerun.selectedPath, expected['selectedPath']);
      expect(rerun.grossAmountMinor, expected['grossAmountMinor']);
      expect(rerun.discountAmountMinor, expected['discountAmountMinor']);
      expect(rerun.payableAmountMinor, expected['payableAmountMinor']);
    }
    expect(vectors, hasLength(40));
    expect(calculationCount, greaterThanOrEqualTo(10));
    for (var index = 1; index <= 40; index++) {
      expect(ids, contains('MBP-${index.toString().padLeft(3, '0')}'));
    }
  });
}
