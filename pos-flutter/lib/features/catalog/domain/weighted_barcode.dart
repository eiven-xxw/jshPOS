import 'dart:convert';

import 'package:crypto/crypto.dart';

/// 来自已验签商品数据包的版本化秤码模板；客户端不得自行创建或改写。
final class WeightedBarcodeTemplate {
  const WeightedBarcodeTemplate({
    required this.templateId,
    required this.templateCode,
    required this.versionNo,
    required this.scopeType,
    required this.storeId,
    required this.barcodeKind,
    required this.symbology,
    required this.prefixValue,
    required this.totalLength,
    required this.skuStartPos,
    required this.skuLength,
    required this.valueStartPos,
    required this.valueLength,
    required this.valueScale,
    required this.priorityNo,
    required this.effectiveFrom,
    required this.effectiveTo,
    required this.contentSha256,
  });

  final String templateId;
  final String templateCode;
  final int versionNo;
  final String scopeType;
  final String? storeId;
  final String barcodeKind;
  final String symbology;
  final String prefixValue;
  final int totalLength;
  final int skuStartPos;
  final int skuLength;
  final int valueStartPos;
  final int valueLength;
  final int valueScale;
  final int priorityNo;
  final DateTime effectiveFrom;
  final DateTime? effectiveTo;
  final String contentSha256;

  bool activeAt(DateTime at) =>
      !at.isBefore(effectiveFrom) &&
      (effectiveTo == null || at.isBefore(effectiveTo!));
}

/// 成交行必须原样持久化的计量快照；不允许按后续模板或售价重算。
final class MeasuredBarcodeSnapshot {
  const MeasuredBarcodeSnapshot({
    required this.rawBarcode,
    required this.skuCode,
    required this.encodedValue,
    required this.quantity,
    required this.amountMinor,
    required this.unitPriceMinor,
    required this.currency,
    required this.templateId,
    required this.templateVersion,
    required this.templateSha256,
    required this.parseSha256,
    required this.roundingApplied,
    required this.occurredAt,
  });

  final String rawBarcode;
  final String skuCode;
  final String encodedValue;
  final String quantity;
  final int amountMinor;
  final int unitPriceMinor;
  final String currency;
  final String templateId;
  final int templateVersion;
  final String templateSha256;
  final String parseSha256;
  final bool roundingApplied;
  final DateTime occurredAt;

  Map<String, Object?> toJson() => {
    'rawBarcode': rawBarcode,
    'skuCode': skuCode,
    'encodedValue': encodedValue,
    'quantity': quantity,
    'amountMinor': amountMinor,
    'unitPriceMinor': unitPriceMinor,
    'currency': currency,
    'templateId': templateId,
    'templateVersion': templateVersion,
    'templateSha256': templateSha256,
    'parseSha256': parseSha256,
    'roundingApplied': roundingApplied,
    'occurredAt': _canonicalInstant(occurredAt),
  };
}

/// EAN-13 秤码/金额码确定性解析器；仅使用整数运算和 HALF_EVEN 舍入。
abstract final class WeightedBarcodeParser {
  static MeasuredBarcodeSnapshot parse({
    required WeightedBarcodeTemplate template,
    required String rawBarcode,
    required int unitPriceMinor,
    required int unitDecimalScale,
    required DateTime occurredAt,
  }) {
    _validateTemplate(template);
    if (!RegExp(r'^[0-9]{13}$').hasMatch(rawBarcode)) {
      throw StateError('CAT-WBC-108: raw barcode must be 13 digits');
    }
    if (!rawBarcode.startsWith(template.prefixValue)) {
      throw StateError('CAT-WBC-109: barcode prefix does not match');
    }
    if (checkDigit(rawBarcode.substring(0, 12)) !=
        int.parse(rawBarcode.substring(12))) {
      throw StateError('CAT-WBC-110: EAN-13 checksum failed');
    }
    if (unitPriceMinor <= 0 ||
        unitPriceMinor > 9007199254740991 ||
        unitDecimalScale < 0 ||
        unitDecimalScale > 6) {
      throw StateError('CAT-WBC-111: frozen price or unit scale is invalid');
    }
    if (!template.activeAt(occurredAt.toUtc())) {
      throw StateError('CAT-WBC-116: template is not active at scan time');
    }
    final skuCode = _segment(
      rawBarcode,
      template.skuStartPos,
      template.skuLength,
    );
    final encodedValue = _segment(
      rawBarcode,
      template.valueStartPos,
      template.valueLength,
    );
    final rawValue = int.parse(encodedValue);
    late final String quantity;
    late final int amountMinor;
    var roundingApplied = false;
    if (template.barcodeKind == 'WEIGHT') {
      if (template.valueScale > unitDecimalScale) {
        final removable = _pow10(template.valueScale - unitDecimalScale);
        if (rawValue.remainder(removable) != 0) {
          throw StateError('CAT-WBC-112: weight precision exceeds unit scale');
        }
      }
      quantity = _decimalCanonical(rawValue, template.valueScale);
      final rounded = _divideHalfEven(
        BigInt.from(rawValue) * BigInt.from(unitPriceMinor),
        BigInt.from(_pow10(template.valueScale)),
      );
      amountMinor = _safeMinor(rounded.value);
      roundingApplied = rounded.hadRemainder;
    } else {
      final minor = template.valueScale <= 2
          ? _RoundedInteger(
              BigInt.from(rawValue) *
                  BigInt.from(_pow10(2 - template.valueScale)),
              false,
            )
          : _divideHalfEven(
              BigInt.from(rawValue),
              BigInt.from(_pow10(template.valueScale - 2)),
            );
      amountMinor = _safeMinor(minor.value);
      final quantityRounded = _divideHalfEven(
        BigInt.from(amountMinor) * BigInt.from(_pow10(unitDecimalScale)),
        BigInt.from(unitPriceMinor),
      );
      quantity = _decimalCanonical(
        quantityRounded.value.toInt(),
        unitDecimalScale,
      );
      roundingApplied = minor.hadRemainder || quantityRounded.hadRemainder;
    }
    if (amountMinor <= 0 || quantity == '0') {
      throw StateError(
        'CAT-WBC-113: measured quantity and amount must be positive',
      );
    }
    final at = occurredAt.toUtc();
    final canonical = [
      rawBarcode,
      template.templateId,
      template.versionNo,
      template.contentSha256,
      skuCode,
      quantity,
      amountMinor,
      unitPriceMinor,
      'CNY',
      _canonicalInstant(at),
    ].join('|');
    return MeasuredBarcodeSnapshot(
      rawBarcode: rawBarcode,
      skuCode: skuCode,
      encodedValue: encodedValue,
      quantity: quantity,
      amountMinor: amountMinor,
      unitPriceMinor: unitPriceMinor,
      currency: 'CNY',
      templateId: template.templateId,
      templateVersion: template.versionNo,
      templateSha256: template.contentSha256,
      parseSha256: sha256.convert(utf8.encode(canonical)).toString(),
      roundingApplied: roundingApplied,
      occurredAt: at,
    );
  }

  static int checkDigit(String firstTwelveDigits) {
    if (!RegExp(r'^[0-9]{12}$').hasMatch(firstTwelveDigits)) {
      throw StateError('CAT-WBC-107: checksum input must be 12 digits');
    }
    var sum = 0;
    for (var index = 0; index < 12; index++) {
      final digit = firstTwelveDigits.codeUnitAt(index) - 48;
      sum += index.isEven ? digit : digit * 3;
    }
    return (10 - sum.remainder(10)).remainder(10);
  }

  static void _validateTemplate(WeightedBarcodeTemplate value) {
    final validScope =
        value.scopeType == 'TENANT' && value.storeId == null ||
        value.scopeType == 'STORE' && value.storeId != null;
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(value.templateId) ||
        value.versionNo <= 0 ||
        value.symbology != 'EAN13' ||
        value.totalLength != 13 ||
        !const {'WEIGHT', 'AMOUNT'}.contains(value.barcodeKind) ||
        !RegExp(r'^[0-9]{2,5}$').hasMatch(value.prefixValue) ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(value.contentSha256) ||
        !validScope ||
        value.effectiveTo != null &&
            !value.effectiveTo!.isAfter(value.effectiveFrom) ||
        value.valueScale < 0 ||
        value.valueScale > 6 ||
        value.barcodeKind == 'AMOUNT' && value.valueScale != 2) {
      throw StateError('CAT-WBC-101: signed template identity is invalid');
    }
    _validateSegment(value.skuStartPos, value.skuLength);
    _validateSegment(value.valueStartPos, value.valueLength);
    final skuEnd = value.skuStartPos + value.skuLength - 1;
    final valueEnd = value.valueStartPos + value.valueLength - 1;
    if (value.skuStartPos <= value.prefixValue.length ||
        value.valueStartPos <= value.prefixValue.length ||
        !(skuEnd < value.valueStartPos || valueEnd < value.skuStartPos)) {
      throw StateError('CAT-WBC-105: template segments overlap');
    }
  }

  static void _validateSegment(int start, int length) {
    if (start < 1 || length < 1 || length > 8 || start + length - 1 > 12) {
      throw StateError('CAT-WBC-114: template segment is out of range');
    }
  }

  static String _segment(String value, int start, int length) =>
      value.substring(start - 1, start - 1 + length);

  static _RoundedInteger _divideHalfEven(BigInt numerator, BigInt divisor) {
    final quotient = numerator ~/ divisor;
    final remainder = numerator.remainder(divisor);
    if (remainder == BigInt.zero) return _RoundedInteger(quotient, false);
    final doubled = remainder * BigInt.two;
    final increment = doubled > divisor || doubled == divisor && quotient.isOdd;
    return _RoundedInteger(increment ? quotient + BigInt.one : quotient, true);
  }

  static int _safeMinor(BigInt value) {
    final maximum = BigInt.from(9007199254740991);
    if (value < BigInt.zero || value > maximum) {
      throw StateError('CAT-WBC-115: amount overflows supported integer range');
    }
    return value.toInt();
  }

  static int _pow10(int scale) {
    var value = 1;
    for (var index = 0; index < scale; index++) {
      value *= 10;
    }
    return value;
  }

  static String _decimalCanonical(int unscaled, int scale) {
    if (scale == 0) return unscaled.toString();
    final padded = unscaled.toString().padLeft(scale + 1, '0');
    final integer = padded.substring(0, padded.length - scale);
    var fraction = padded.substring(padded.length - scale);
    fraction = fraction.replaceFirst(RegExp(r'0+$'), '');
    return fraction.isEmpty ? integer : '$integer.$fraction';
  }
}

final class _RoundedInteger {
  const _RoundedInteger(this.value, this.hadRemainder);
  final BigInt value;
  final bool hadRemainder;
}

String _canonicalInstant(DateTime value) =>
    value.toUtc().toIso8601String().replaceFirst(RegExp(r'\.000Z$'), 'Z');
