import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/exact_quantity.dart';

void main() {
  group('exact quantity and minor money', () {
    test('normalizes six-decimal quantities without floating point', () {
      expect(ExactQuantity.parse('1.000000').canonical, '1');
      expect(ExactQuantity.parse('0.125').canonical, '0.125');
      expect(ExactQuantity.parse('999999.999999').canonical, '999999.999999');
    });

    test('uses explicit half-up rounding', () {
      expect(ExactQuantity.parse('0.125').multiplyMinor(100), 13);
      expect(ExactQuantity.parse('1.5').multiplyMinor(101), 152);
    });

    test('rejects zero, excess scale, exponent and float-like inputs', () {
      for (final value in ['0', '-1', '0.0000001', '1e2', '01.0', 'NaN']) {
        expect(
          () => ExactQuantity.parse(value),
          throwsFormatException,
          reason: value,
        );
      }
    });

    test('rejects money outside the cross-runtime safe integer range', () {
      expect(() => MoneyRules.requireMinor(-1, 'amount'), throwsRangeError);
      expect(
        () => MoneyRules.requireMinor(
          MoneyRules.maxSafeJsonInteger + 1,
          'amount',
        ),
        throwsRangeError,
      );
    });
  });
}
