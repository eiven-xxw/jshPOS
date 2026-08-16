import 'dart:convert';

import 'package:crypto/crypto.dart';

import 'exact_quantity.dart';
import 'ulid_generator.dart';

final class TrustedDeviceBinding {
  const TrustedDeviceBinding({
    required this.tenantId,
    required this.storeId,
    required this.terminalId,
    required this.cashierId,
    required this.cashierName,
    required this.storeTimezone,
  });

  final String tenantId;
  final String storeId;
  final String terminalId;
  final String cashierId;
  final String cashierName;
  final String storeTimezone;

  void validate() {
    if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$').hasMatch(tenantId) ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(storeId) ||
        !UlidGenerator.isCanonical(terminalId) ||
        cashierId.isEmpty ||
        cashierName.isEmpty ||
        storeTimezone.isEmpty) {
      throw StateError(
        'TENANT_CONTEXT_REQUIRED: invalid trusted device binding',
      );
    }
  }
}

final class PriceQuote {
  const PriceQuote._({
    required this.skuId,
    required this.skuCode,
    required this.productName,
    required this.unitId,
    required this.unitCode,
    required this.unitPriceMinor,
    required this.priceSource,
    this.barcode,
  });

  factory PriceQuote.fromVerifiedPackage({
    required String skuId,
    required String skuCode,
    required String productName,
    required String unitId,
    required String unitCode,
    required int unitPriceMinor,
    required String priceSource,
    String? barcode,
  }) {
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(skuId) ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(unitId) ||
        !const {'TENANT_BASE', 'STORE_OVERRIDE'}.contains(priceSource)) {
      throw const FormatException('ORD-PRICE-001: invalid verified quote');
    }
    MoneyRules.requireMinor(unitPriceMinor, 'unitPriceMinor');
    return PriceQuote._(
      skuId: skuId,
      skuCode: skuCode,
      productName: productName,
      unitId: unitId,
      unitCode: unitCode,
      unitPriceMinor: unitPriceMinor,
      priceSource: priceSource,
      barcode: barcode,
    );
  }

  final String skuId;
  final String skuCode;
  final String productName;
  final String unitId;
  final String unitCode;
  final int unitPriceMinor;
  final String priceSource;
  final String? barcode;
}

final class BasketLine {
  BasketLine({
    required this.lineId,
    required this.lineNo,
    required this.quote,
    required String quantity,
  }) : quantity = ExactQuantity.parse(quantity) {
    if (!UlidGenerator.isCanonical(lineId) || lineNo < 1 || lineNo > 500) {
      throw const FormatException('ORD-LINE-002: invalid line identity');
    }
  }

  final String lineId;
  final int lineNo;
  final PriceQuote quote;
  final ExactQuantity quantity;
  int get grossAmountMinor => quantity.multiplyMinor(quote.unitPriceMinor);

  Map<String, Object?> toSnapshot() => {
    'lineId': lineId,
    'lineNo': lineNo,
    'skuId': quote.skuId,
    'skuCode': quote.skuCode,
    if (quote.barcode != null) 'barcode': quote.barcode,
    'productName': quote.productName,
    'unitId': quote.unitId,
    'unitCode': quote.unitCode,
    'quantity': quantity.canonical,
    'unitPriceMinor': quote.unitPriceMinor,
    'grossAmountMinor': grossAmountMinor,
    'discountAmountMinor': 0,
    'surchargeAmountMinor': 0,
    'payableAmountMinor': grossAmountMinor,
    'priceSource': quote.priceSource,
  };
}

final class Basket {
  Basket({
    required this.orderId,
    required this.localOrderNo,
    Iterable<BasketLine> lines = const [],
  }) : _lines = [...lines] {
    if (!UlidGenerator.isCanonical(orderId) ||
        localOrderNo.isEmpty ||
        localOrderNo.length > 40) {
      throw const FormatException(
        'ORDER_INPUT_INVALID: invalid basket identity',
      );
    }
    _validateLines();
  }

  final String orderId;
  final String localOrderNo;
  final List<BasketLine> _lines;
  bool _suspended = false;

  List<BasketLine> get lines => List.unmodifiable(_lines);
  bool get suspended => _suspended;
  int get grossAmountMinor =>
      _lines.fold(0, (sum, line) => sum + line.grossAmountMinor);

  void add(BasketLine line) {
    if (_suspended) {
      throw StateError('ORDER_STATE_CONFLICT: basket is suspended');
    }
    if (_lines.length >= 500 ||
        _lines.any((existing) => existing.lineNo == line.lineNo)) {
      throw StateError('ORD-LINE-002: duplicate or excessive line numbers');
    }
    _lines.add(line);
  }

  void suspend() => _suspended = true;
  void resume() => _suspended = false;

  void _validateLines() {
    if (_lines.length > 500 ||
        _lines.map((line) => line.lineNo).toSet().length != _lines.length) {
      throw StateError('ORD-LINE-002: duplicate or excessive line numbers');
    }
  }
}

final class CashSaleCommand {
  const CashSaleCommand({
    required this.commandId,
    required this.idempotencyKey,
    required this.basket,
    required this.shiftId,
    required this.businessDate,
    required this.catalogVersion,
    required this.priceVersion,
    required this.industryTemplateVersion,
    required this.tenderedAmountMinor,
    required this.occurredAt,
  });

  final String commandId;
  final String idempotencyKey;
  final Basket basket;
  final String shiftId;
  final String businessDate;
  final int catalogVersion;
  final int priceVersion;
  final String industryTemplateVersion;
  final int tenderedAmountMinor;
  final DateTime occurredAt;

  String canonical(TrustedDeviceBinding binding) {
    final values = <Object?>[
      basket.orderId,
      basket.localOrderNo,
      binding.storeId,
      binding.terminalId,
      shiftId,
      binding.cashierId,
      businessDate,
      binding.storeTimezone,
      catalogVersion,
      priceVersion,
      industryTemplateVersion,
      basket.grossAmountMinor,
      basket.grossAmountMinor,
      tenderedAmountMinor,
    ];
    for (final line in [
      ...basket.lines,
    ]..sort((a, b) => a.lineNo.compareTo(b.lineNo))) {
      values.addAll([
        line.lineId,
        line.lineNo,
        line.quote.skuId,
        line.quote.skuCode,
        line.quote.barcode,
        line.quote.productName,
        line.quote.unitId,
        line.quote.unitCode,
        line.quantity.canonical,
        line.quote.unitPriceMinor,
        line.grossAmountMinor,
        line.grossAmountMinor,
        line.quote.priceSource,
      ]);
    }
    return values.map((value) {
      final text = '$value';
      return '${text.length}:$text;';
    }).join();
  }

  String requestHash(TrustedDeviceBinding binding) =>
      sha256.convert(utf8.encode(canonical(binding))).toString();
}

final class CashSaleResult {
  const CashSaleResult({
    required this.orderId,
    required this.paymentId,
    required this.receivableAmountMinor,
    required this.tenderedAmountMinor,
    required this.changeAmountMinor,
    required this.snapshotHash,
    required this.outboxEventId,
    this.duplicate = false,
  });

  factory CashSaleResult.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => CashSaleResult(
    orderId: json['orderId']! as String,
    paymentId: json['paymentId']! as String,
    receivableAmountMinor: json['receivableAmountMinor']! as int,
    tenderedAmountMinor: json['tenderedAmountMinor']! as int,
    changeAmountMinor: json['changeAmountMinor']! as int,
    snapshotHash: json['snapshotHash']! as String,
    outboxEventId: json['outboxEventId']! as String,
    duplicate: duplicate,
  );

  final String orderId;
  final String paymentId;
  final int receivableAmountMinor;
  final int tenderedAmountMinor;
  final int changeAmountMinor;
  final String snapshotHash;
  final String outboxEventId;
  final bool duplicate;

  Map<String, Object?> toJson() => {
    'orderId': orderId,
    'paymentId': paymentId,
    'receivableAmountMinor': receivableAmountMinor,
    'tenderedAmountMinor': tenderedAmountMinor,
    'changeAmountMinor': changeAmountMinor,
    'snapshotHash': snapshotHash,
    'outboxEventId': outboxEventId,
  };
}
