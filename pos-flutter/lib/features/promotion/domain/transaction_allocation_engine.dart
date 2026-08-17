import '../../checkout/domain/exact_quantity.dart';

/// PRM-003 不可变成交行；金额使用分整数，数量使用规范十进制字符串。
final class TransactionSnapshotLine {
  TransactionSnapshotLine({
    required this.lineId,
    required this.lineNo,
    required this.skuId,
    required this.quantity,
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.payableAmountMinor,
  }) {
    _requireUlid(lineId);
    if (lineNo <= 0 || skuId <= 0) {
      throw const FormatException('PRM-SNAPSHOT-001: invalid line identity');
    }
    _Scaled.parse(quantity, positive: true);
    _conserved(grossAmountMinor, discountAmountMinor, payableAmountMinor);
  }

  final String lineId;
  final int lineNo;
  final int skuId;
  final String quantity;
  final int grossAmountMinor;
  final int discountAmountMinor;
  final int payableAmountMinor;
}

/// 守恒且按稳定行号排序的成交优惠快照。
final class TransactionSnapshot {
  const TransactionSnapshot({
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.payableAmountMinor,
    required this.lines,
  });
  final int grossAmountMinor;
  final int discountAmountMinor;
  final int payableAmountMinor;
  final List<TransactionSnapshotLine> lines;
}

/// 本次退款前某成交行的累计恢复事实。
final class PriorRefundAllocation {
  PriorRefundAllocation({
    required this.lineId,
    required this.quantity,
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.payableAmountMinor,
  }) {
    _requireUlid(lineId);
    _Scaled.parse(quantity, positive: false);
    _conserved(grossAmountMinor, discountAmountMinor, payableAmountMinor);
  }
  final String lineId;
  final String quantity;
  final int grossAmountMinor;
  final int discountAmountMinor;
  final int payableAmountMinor;
}

/// 本次请求退回的成交行数量。
final class RefundAllocationRequestLine {
  RefundAllocationRequestLine({required this.lineId, required this.quantity}) {
    _requireUlid(lineId);
    _Scaled.parse(quantity, positive: true);
  }
  final String lineId;
  final String quantity;
}

/// 本次行退款以及执行后的累计上限。
final class RefundAllocationLine {
  const RefundAllocationLine({
    required this.lineId,
    required this.quantity,
    required this.grossAmountMinor,
    required this.recoveredDiscountMinor,
    required this.refundableAmountMinor,
    required this.cumulativeQuantity,
    required this.cumulativeGrossAmountMinor,
    required this.cumulativeDiscountAmountMinor,
    required this.cumulativePayableAmountMinor,
  });
  final String lineId;
  final String quantity;
  final int grossAmountMinor;
  final int recoveredDiscountMinor;
  final int refundableAmountMinor;
  final String cumulativeQuantity;
  final int cumulativeGrossAmountMinor;
  final int cumulativeDiscountAmountMinor;
  final int cumulativePayableAmountMinor;
}

/// 一次退款优惠恢复的守恒结果。
final class RefundAllocationResult {
  const RefundAllocationResult({
    required this.grossAmountMinor,
    required this.recoveredDiscountMinor,
    required this.refundableAmountMinor,
    required this.lines,
  });
  final int grossAmountMinor;
  final int recoveredDiscountMinor;
  final int refundableAmountMinor;
  final List<RefundAllocationLine> lines;
}

/// 与 Java 使用同一累计比例、HALF_UP 和最终余数吸收语义的离线引擎。
final class TransactionAllocationEngine {
  TransactionSnapshot freeze(List<TransactionSnapshotLine> source) {
    if (source.isEmpty || source.length > 500) {
      throw const FormatException('PRM-SNAPSHOT-002: invalid line count');
    }
    final lines = [...source]
      ..sort((left, right) {
        var order = left.lineNo.compareTo(right.lineNo);
        if (order != 0) return order;
        order = left.skuId.compareTo(right.skuId);
        return order != 0 ? order : left.lineId.compareTo(right.lineId);
      });
    final ids = <String>{};
    final numbers = <int>{};
    var gross = 0;
    var discount = 0;
    var payable = 0;
    for (final line in lines) {
      if (!ids.add(line.lineId) || !numbers.add(line.lineNo)) {
        throw const FormatException('PRM-SNAPSHOT-003: duplicate line');
      }
      gross = _addMoney(gross, line.grossAmountMinor);
      discount = _addMoney(discount, line.discountAmountMinor);
      payable = _addMoney(payable, line.payableAmountMinor);
    }
    _conserved(gross, discount, payable);
    return TransactionSnapshot(
      grossAmountMinor: gross,
      discountAmountMinor: discount,
      payableAmountMinor: payable,
      lines: List.unmodifiable(lines),
    );
  }

  RefundAllocationResult refund({
    required TransactionSnapshot snapshot,
    required List<PriorRefundAllocation> history,
    required List<RefundAllocationRequestLine> requests,
  }) {
    if (requests.isEmpty || requests.length > 500) {
      throw const FormatException('PRM-REFUND-001: invalid refund lines');
    }
    final verified = freeze(snapshot.lines);
    if (verified.grossAmountMinor != snapshot.grossAmountMinor ||
        verified.discountAmountMinor != snapshot.discountAmountMinor ||
        verified.payableAmountMinor != snapshot.payableAmountMinor) {
      throw const FormatException('PRM-SNAPSHOT-004: header mismatch');
    }
    final originals = {for (final line in verified.lines) line.lineId: line};
    final priors = <String, PriorRefundAllocation>{};
    for (final prior in history) {
      if (priors.containsKey(prior.lineId)) {
        throw const FormatException('PRM-REFUND-005: duplicate history');
      }
      final original = originals[prior.lineId];
      if (original == null) {
        throw const FormatException('PRM-REFUND-006: history outside snapshot');
      }
      final quantity = _Scaled.parse(prior.quantity, positive: false);
      final originalQuantity = _Scaled.parse(original.quantity, positive: true);
      if (quantity.compareTo(originalQuantity) > 0) {
        throw const FormatException('PRM-REFUND-006: history exceeded');
      }
      final expected = _target(original, quantity);
      if (expected.gross != prior.grossAmountMinor ||
          expected.discount != prior.discountAmountMinor ||
          expected.payable != prior.payableAmountMinor) {
        throw const FormatException('PRM-REFUND-007: corrupted history');
      }
      priors[prior.lineId] = prior;
    }
    final seen = <String>{};
    final lines = <RefundAllocationLine>[];
    var gross = 0;
    var discount = 0;
    var payable = 0;
    for (final request in requests) {
      if (!seen.add(request.lineId)) {
        throw const FormatException('PRM-REFUND-002: duplicate request line');
      }
      final original = originals[request.lineId];
      if (original == null) {
        throw const FormatException('PRM-REFUND-003: line outside snapshot');
      }
      final prior = priors[request.lineId];
      final priorQuantity = _Scaled.parse(
        prior?.quantity ?? '0',
        positive: false,
      );
      final requestQuantity = _Scaled.parse(request.quantity, positive: true);
      final cumulative = priorQuantity.add(requestQuantity);
      if (cumulative.compareTo(
            _Scaled.parse(original.quantity, positive: true),
          ) >
          0) {
        throw const FormatException('PRM-REFUND-004: quantity exceeded');
      }
      final target = _target(original, cumulative);
      final lineGross = _subtract(target.gross, prior?.grossAmountMinor ?? 0);
      final lineDiscount = _subtract(
        target.discount,
        prior?.discountAmountMinor ?? 0,
      );
      final linePayable = _subtract(
        target.payable,
        prior?.payableAmountMinor ?? 0,
      );
      _conserved(lineGross, lineDiscount, linePayable);
      lines.add(
        RefundAllocationLine(
          lineId: request.lineId,
          quantity: requestQuantity.canonical,
          grossAmountMinor: lineGross,
          recoveredDiscountMinor: lineDiscount,
          refundableAmountMinor: linePayable,
          cumulativeQuantity: cumulative.canonical,
          cumulativeGrossAmountMinor: target.gross,
          cumulativeDiscountAmountMinor: target.discount,
          cumulativePayableAmountMinor: target.payable,
        ),
      );
      gross = _addMoney(gross, lineGross);
      discount = _addMoney(discount, lineDiscount);
      payable = _addMoney(payable, linePayable);
    }
    _conserved(gross, discount, payable);
    return RefundAllocationResult(
      grossAmountMinor: gross,
      recoveredDiscountMinor: discount,
      refundableAmountMinor: payable,
      lines: List.unmodifiable(lines),
    );
  }

  _Amounts _target(TransactionSnapshotLine original, _Scaled cumulative) {
    if (cumulative.isZero) return const _Amounts(0, 0, 0);
    final total = _Scaled.parse(original.quantity, positive: true);
    if (cumulative.compareTo(total) == 0) {
      return _Amounts(
        original.grossAmountMinor,
        original.discountAmountMinor,
        original.payableAmountMinor,
      );
    }
    final gross = _proportional(original.grossAmountMinor, cumulative, total);
    final discount = _proportional(
      original.discountAmountMinor,
      cumulative,
      total,
    );
    if (discount > gross) throw StateError('PRM-REFUND-008');
    return _Amounts(gross, discount, gross - discount);
  }
}

final class _Amounts {
  const _Amounts(this.gross, this.discount, this.payable);
  final int gross;
  final int discount;
  final int payable;
}

final class _Scaled {
  const _Scaled(this.unscaled, this.scale);

  factory _Scaled.parse(String source, {required bool positive}) {
    if (!RegExp(r'^(0|[1-9][0-9]{0,12})(\.[0-9]{1,6})?$').hasMatch(source)) {
      throw const FormatException('PRM-QUANTITY-001: invalid quantity');
    }
    final parts = source.split('.');
    final scale = parts.length == 1 ? 0 : parts[1].length;
    final unscaled = BigInt.parse(parts.join());
    if ((positive && unscaled <= BigInt.zero) ||
        (!positive && unscaled < BigInt.zero)) {
      throw const FormatException('PRM-QUANTITY-001: invalid quantity');
    }
    return _Scaled(unscaled, scale);
  }

  final BigInt unscaled;
  final int scale;
  bool get isZero => unscaled == BigInt.zero;

  _Scaled add(_Scaled other) {
    final target = scale > other.scale ? scale : other.scale;
    return _Scaled(
      unscaled * _pow10(target - scale) +
          other.unscaled * _pow10(target - other.scale),
      target,
    ).normalized;
  }

  int compareTo(_Scaled other) {
    final target = scale > other.scale ? scale : other.scale;
    return (unscaled * _pow10(target - scale)).compareTo(
      other.unscaled * _pow10(target - other.scale),
    );
  }

  _Scaled get normalized {
    var value = unscaled;
    var digits = scale;
    while (digits > 0 && value.remainder(BigInt.from(10)) == BigInt.zero) {
      value ~/= BigInt.from(10);
      digits--;
    }
    return _Scaled(value, digits);
  }

  String get canonical {
    final value = normalized;
    if (value.scale == 0) return value.unscaled.toString();
    final digits = value.unscaled.toString().padLeft(value.scale + 1, '0');
    return '${digits.substring(0, digits.length - value.scale)}.${digits.substring(digits.length - value.scale)}';
  }
}

int _proportional(int amount, _Scaled cumulative, _Scaled original) {
  final numerator =
      BigInt.from(amount) * cumulative.unscaled * _pow10(original.scale);
  final denominator = original.unscaled * _pow10(cumulative.scale);
  final quotient = numerator ~/ denominator;
  final remainder = numerator.remainder(denominator);
  final rounded =
      quotient +
      (remainder * BigInt.two >= denominator ? BigInt.one : BigInt.zero);
  return MoneyRules.requireMinor(rounded.toInt(), 'refundAmount');
}

BigInt _pow10(int scale) => BigInt.from(10).pow(scale);

void _requireUlid(String value) {
  if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(value)) {
    throw const FormatException('PRM-INPUT-001: invalid ULID');
  }
}

void _conserved(int gross, int discount, int payable) {
  MoneyRules.requireMinor(gross, 'grossAmountMinor');
  MoneyRules.requireMinor(discount, 'discountAmountMinor');
  MoneyRules.requireMinor(payable, 'payableAmountMinor');
  if (gross != discount + payable) {
    throw const FormatException('PRM-AMOUNT-030: amount not conserved');
  }
}

int _addMoney(int left, int right) => MoneyRules.requireMinor(
  (BigInt.from(left) + BigInt.from(right)).toInt(),
  'transactionAmount',
);

int _subtract(int left, int right) {
  final result = BigInt.from(left) - BigInt.from(right);
  if (result < BigInt.zero) throw StateError('PRM-REFUND-010');
  return MoneyRules.requireMinor(result.toInt(), 'refundDelta');
}
