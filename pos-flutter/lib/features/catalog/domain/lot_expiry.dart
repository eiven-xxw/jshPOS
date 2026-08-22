/// 不依赖浮点数的六位小数批次数量。
final class ExactLotQuantity implements Comparable<ExactLotQuantity> {
  const ExactLotQuantity._(this.micros);

  factory ExactLotQuantity.parse(String source, {bool allowZero = false}) {
    if (!RegExp(r'^(0|[1-9][0-9]{0,12})(\.[0-9]{1,6})?$').hasMatch(source)) {
      throw const FormatException('LOT-QTY-101: invalid exact lot quantity');
    }
    final parts = source.split('.');
    final fraction = parts.length == 1 ? '' : parts[1];
    final micros = BigInt.parse('${parts[0]}${fraction.padRight(6, '0')}');
    if (micros.isNegative || !allowZero && micros == BigInt.zero) {
      throw const FormatException('LOT-QTY-101: invalid exact lot quantity');
    }
    return ExactLotQuantity._(micros);
  }

  final BigInt micros;

  ExactLotQuantity operator +(ExactLotQuantity other) =>
      ExactLotQuantity._(micros + other.micros);

  ExactLotQuantity operator -(ExactLotQuantity other) {
    final value = micros - other.micros;
    if (value.isNegative) {
      throw StateError('LOT-BALANCE-101: lot quantity is insufficient');
    }
    return ExactLotQuantity._(value);
  }

  ExactLotQuantity min(ExactLotQuantity other) =>
      micros <= other.micros ? this : other;

  bool get isZero => micros == BigInt.zero;

  String get canonical {
    final padded = micros.toString().padLeft(7, '0');
    final integer = padded.substring(0, padded.length - 6);
    var fraction = padded.substring(padded.length - 6);
    fraction = fraction.replaceFirst(RegExp(r'0+$'), '');
    return fraction.isEmpty ? integer : '$integer.$fraction';
  }

  @override
  int compareTo(ExactLotQuantity other) => micros.compareTo(other.micros);
}

/// 已验签包中的 FEFO 候选。
final class LocalLotCandidate {
  const LocalLotCandidate({
    required this.lotId,
    required this.receivedDate,
    required this.expiryDate,
    required this.available,
    required this.policyVersionId,
  });

  final String lotId;
  final DateTime receivedDate;
  final DateTime expiryDate;
  final ExactLotQuantity available;
  final String policyVersionId;
}

/// POS 成交冻结的批次分配。
final class LocalLotAllocation {
  const LocalLotAllocation({
    required this.lotId,
    required this.quantity,
    required this.policyVersionId,
    required this.expiryDate,
  });

  final String lotId;
  final ExactLotQuantity quantity;
  final String policyVersionId;
  final DateTime expiryDate;

  Map<String, Object?> toJson() => {
    'lotId': lotId,
    'quantity': quantity.canonical,
    'policyVersionId': policyVersionId,
    'expiryDate': _date(expiryDate),
  };
}

/// Java 与 Dart 共用：到期日当天可售，FEFO 以到期日、入库日和批次 ULID 排序。
abstract final class LocalLotRules {
  static List<LocalLotAllocation> allocateFefo({
    required List<LocalLotCandidate> candidates,
    required ExactLotQuantity requested,
    required DateTime businessDate,
  }) {
    var remaining = requested;
    final day = _day(businessDate);
    final ordered = candidates.where((item) => !item.available.isZero).toList()
      ..sort((left, right) {
        final expiry = left.expiryDate.compareTo(right.expiryDate);
        if (expiry != 0) return expiry;
        final received = left.receivedDate.compareTo(right.receivedDate);
        return received != 0 ? received : left.lotId.compareTo(right.lotId);
      });
    final result = <LocalLotAllocation>[];
    for (final candidate in ordered) {
      if (day.isAfter(_day(candidate.expiryDate))) {
        throw StateError('LOT-EXPIRED-101: expired lot cannot be sold');
      }
      final amount = candidate.available.min(remaining);
      result.add(
        LocalLotAllocation(
          lotId: candidate.lotId,
          quantity: amount,
          policyVersionId: candidate.policyVersionId,
          expiryDate: candidate.expiryDate,
        ),
      );
      remaining = remaining - amount;
      if (remaining.isZero) return List.unmodifiable(result);
    }
    throw StateError('LOT-BALANCE-101: saleable lot quantity is insufficient');
  }

  static String classify(
    DateTime businessDate,
    DateTime expiryDate,
    int nearExpiryDays,
  ) {
    if (nearExpiryDays < 0 || nearExpiryDays > 3650) {
      throw StateError('LOT-DATE-101: invalid near-expiry threshold');
    }
    final day = _day(businessDate);
    final expiry = _day(expiryDate);
    if (day.isAfter(expiry)) return 'EXPIRED';
    return day.add(Duration(days: nearExpiryDays)).isBefore(expiry)
        ? 'AVAILABLE'
        : 'NEAR_EXPIRY';
  }
}

DateTime _day(DateTime value) =>
    DateTime.utc(value.year, value.month, value.day);
String _date(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
