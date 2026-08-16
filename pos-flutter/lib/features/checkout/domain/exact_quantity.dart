import 'dart:math' show max;

final class ExactQuantity {
  ExactQuantity._(this.unscaled, this.scale);

  factory ExactQuantity.parse(String source) {
    if (!RegExp(r'^(0|[1-9][0-9]{0,12})(\.[0-9]{1,6})?$').hasMatch(source)) {
      throw const FormatException(
        'ORD-QTY-001: quantity must be canonical with at most six decimals',
      );
    }
    final parts = source.split('.');
    final scale = parts.length == 1 ? 0 : parts[1].length;
    final digits = parts.length == 1 ? parts[0] : '${parts[0]}${parts[1]}';
    final value = int.parse(digits);
    if (value <= 0) {
      throw const FormatException('ORD-QTY-001: quantity must be positive');
    }
    return ExactQuantity._(value, scale);
  }

  final int unscaled;
  final int scale;

  int multiplyMinor(int unitPriceMinor) {
    MoneyRules.requireMinor(unitPriceMinor, 'unitPriceMinor');
    final divisor = _pow10(scale);
    final numerator = unitPriceMinor * unscaled;
    final quotient = numerator ~/ divisor;
    final remainder = numerator.remainder(divisor);
    final rounded = quotient + (remainder * 2 >= divisor ? 1 : 0);
    return MoneyRules.requireMinor(rounded, 'lineGrossMinor');
  }

  String get canonical {
    if (scale == 0) return unscaled.toString();
    final padded = unscaled.toString().padLeft(scale + 1, '0');
    final integer = padded.substring(0, padded.length - scale);
    var fraction = padded.substring(padded.length - scale);
    fraction = fraction.replaceFirst(RegExp(r'0+$'), '');
    return fraction.isEmpty ? integer : '$integer.$fraction';
  }

  static int _pow10(int scale) {
    var value = 1;
    for (var index = 0; index < max(0, scale); index++) {
      value *= 10;
    }
    return value;
  }
}

abstract final class MoneyRules {
  static const int maxSafeJsonInteger = 9007199254740991;

  static int requireMinor(int value, String field) {
    if (value < 0 || value > maxSafeJsonInteger) {
      throw RangeError('$field outside supported integer range');
    }
    return value;
  }
}
