import 'dart:convert';

import 'package:crypto/crypto.dart';

import 'exact_quantity.dart';
import 'ulid_generator.dart';

final class TrustedDeviceBinding {
  const TrustedDeviceBinding({
    required this.tenantId,
    required this.storeId,
    required this.terminalId,
    String? deviceId,
    required this.cashierId,
    required this.cashierName,
    required this.storeTimezone,
  }) : deviceId = deviceId ?? terminalId;

  final String tenantId;
  final String storeId;

  /// 服务端终端注册表分配的设备 ULID；旧本地绑定缺失时兼容沿用 terminalId。
  final String deviceId;

  /// 门店内稳定的终端业务标识，与设备注册 ID 不要求相同。
  final String terminalId;
  final String cashierId;
  final String cashierName;
  final String storeTimezone;

  void validate() {
    if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$').hasMatch(tenantId) ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(storeId) ||
        !UlidGenerator.isCanonical(deviceId) ||
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

/// POS-006 成交行：保留商品快照，并冻结逐行优惠、附加费与优惠来源分摊。
final class PromotedSettlementLine {
  PromotedSettlementLine({
    required this.basketLine,
    required this.discountAmountMinor,
    this.surchargeAmountMinor = 0,
    Map<String, int> sourceAllocations = const {},
  }) : sourceAllocations = Map.unmodifiable(
         Map.fromEntries(
           sourceAllocations.entries.toList()
             ..sort((left, right) => left.key.compareTo(right.key)),
         ),
       ) {
    MoneyRules.requireMinor(discountAmountMinor, 'discountAmountMinor');
    MoneyRules.requireMinor(surchargeAmountMinor, 'surchargeAmountMinor');
    if (discountAmountMinor > basketLine.grossAmountMinor ||
        sourceAllocations.values.any((amount) => amount <= 0) ||
        sourceAllocations.keys.any(
          (source) =>
              !RegExp(r'^[A-Z][A-Z0-9_]{1,31}:[A-Za-z0-9_-]{1,64}$')
                  .hasMatch(source),
        ) ||
        sourceAllocations.values.fold<int>(0, (sum, amount) => sum + amount) !=
            discountAmountMinor) {
      throw const FormatException(
        'POS-PROMOTION-001: invalid promoted line allocation',
      );
    }
  }

  final BasketLine basketLine;
  final int discountAmountMinor;
  final int surchargeAmountMinor;
  final Map<String, int> sourceAllocations;

  int get receivableAmountMinor =>
      basketLine.grossAmountMinor - discountAmountMinor + surchargeAmountMinor;

  Map<String, Object?> toSnapshot() => {
    ...basketLine.toSnapshot(),
    'discountAmountMinor': discountAmountMinor,
    'surchargeAmountMinor': surchargeAmountMinor,
    'payableAmountMinor': receivableAmountMinor,
    'sourceAllocations': sourceAllocations,
  };
}

/// POS-006 促销现金成交命令；可信租户/门店/终端仍由设备绑定注入。
final class PromotedCashSaleCommand {
  PromotedCashSaleCommand({
    required this.commandId,
    required this.idempotencyKey,
    required this.basket,
    required this.shiftId,
    required this.businessDate,
    required this.catalogVersion,
    required this.priceVersion,
    required this.industryTemplateVersion,
    required this.quoteId,
    required this.quoteFingerprint,
    required this.settlementFingerprint,
    required this.packageVersion,
    required this.promotionSnapshotId,
    required Iterable<PromotedSettlementLine> lines,
    Iterable<String> manualEventRefs = const [],
    required this.tenderedAmountMinor,
    required this.occurredAt,
  }) : lines = List.unmodifiable(
         [...lines]..sort(
           (left, right) =>
               left.basketLine.lineNo.compareTo(right.basketLine.lineNo),
         ),
       ),
       manualEventRefs = List.unmodifiable(manualEventRefs) {
    if (!UlidGenerator.isCanonical(quoteId) ||
        !UlidGenerator.isCanonical(promotionSnapshotId) ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(quoteFingerprint) ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(settlementFingerprint) ||
        packageVersion <= 0 ||
        this.lines.isEmpty ||
        this.lines.length > 500 ||
        this.manualEventRefs.length > 50 ||
        this.manualEventRefs.any((id) => !UlidGenerator.isCanonical(id))) {
      throw const FormatException(
        'POS-PROMOTION-002: invalid promoted settlement context',
      );
    }
    final basketIds = basket.lines.map((line) => line.lineId).toSet();
    final settlementIds = this.lines
        .map((line) => line.basketLine.lineId)
        .toSet();
    if (basketIds.length != basket.lines.length ||
        settlementIds.length != this.lines.length ||
        basketIds.length != settlementIds.length ||
        !basketIds.containsAll(settlementIds)) {
      throw const FormatException(
        'POS-PROMOTION-003: settlement lines differ from basket',
      );
    }
  }

  final String commandId;
  final String idempotencyKey;
  final Basket basket;
  final String shiftId;
  final String businessDate;
  final int catalogVersion;
  final int priceVersion;
  final String industryTemplateVersion;
  final String quoteId;
  final String quoteFingerprint;
  final String settlementFingerprint;
  final int packageVersion;
  final String promotionSnapshotId;
  final List<PromotedSettlementLine> lines;
  final List<String> manualEventRefs;
  final int tenderedAmountMinor;
  final DateTime occurredAt;

  int get grossAmountMinor => lines.fold(
    0,
    (sum, line) => MoneyRules.requireMinor(
      (BigInt.from(sum) + BigInt.from(line.basketLine.grossAmountMinor))
          .toInt(),
      'grossAmountMinor',
    ),
  );
  int get discountAmountMinor => lines.fold(
    0,
    (sum, line) => MoneyRules.requireMinor(
      (BigInt.from(sum) + BigInt.from(line.discountAmountMinor)).toInt(),
      'discountAmountMinor',
    ),
  );
  int get surchargeAmountMinor => lines.fold(
    0,
    (sum, line) => MoneyRules.requireMinor(
      (BigInt.from(sum) + BigInt.from(line.surchargeAmountMinor)).toInt(),
      'surchargeAmountMinor',
    ),
  );
  int get receivableAmountMinor =>
      grossAmountMinor - discountAmountMinor + surchargeAmountMinor;

  String basketInputHash(TrustedDeviceBinding binding) => sha256
      .convert(
        utf8.encode(
          _canonicalValues([
            basket.orderId,
            basket.localOrderNo,
            binding.tenantId,
            binding.storeId,
            binding.terminalId,
            shiftId,
            businessDate,
            catalogVersion,
            priceVersion,
            ...basket.lines.expand(
              (line) => [
                line.lineId,
                line.lineNo,
                line.quote.skuId,
                line.quote.unitId,
                line.quantity.canonical,
                line.quote.unitPriceMinor,
                line.grossAmountMinor,
              ],
            ),
          ]),
        ),
      )
      .toString();

  String requestHash(TrustedDeviceBinding binding) => sha256
      .convert(
        utf8.encode(
          _canonicalValues([
            basketInputHash(binding),
            quoteId,
            quoteFingerprint,
            settlementFingerprint,
            packageVersion,
            promotionSnapshotId,
            ...manualEventRefs,
            grossAmountMinor,
            discountAmountMinor,
            surchargeAmountMinor,
            receivableAmountMinor,
            tenderedAmountMinor,
            ...lines.expand(
              (line) => [
                line.basketLine.lineId,
                line.discountAmountMinor,
                line.surchargeAmountMinor,
                jsonEncode(line.sourceAllocations),
              ],
            ),
          ]),
        ),
      )
      .toString();
}

/// POS-006 幂等成交结果，分别暴露订单和促销快照摘要。
final class PromotedCashSaleResult {
  const PromotedCashSaleResult({
    required this.orderId,
    required this.paymentId,
    required this.promotionSnapshotId,
    required this.receivableAmountMinor,
    required this.tenderedAmountMinor,
    required this.changeAmountMinor,
    required this.orderSnapshotHash,
    required this.promotionSnapshotHash,
    required this.outboxEventId,
    this.duplicate = false,
  });

  factory PromotedCashSaleResult.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => PromotedCashSaleResult(
    orderId: json['orderId']! as String,
    paymentId: json['paymentId']! as String,
    promotionSnapshotId: json['promotionSnapshotId']! as String,
    receivableAmountMinor: json['receivableAmountMinor']! as int,
    tenderedAmountMinor: json['tenderedAmountMinor']! as int,
    changeAmountMinor: json['changeAmountMinor']! as int,
    orderSnapshotHash: json['orderSnapshotHash']! as String,
    promotionSnapshotHash: json['promotionSnapshotHash']! as String,
    outboxEventId: json['outboxEventId']! as String,
    duplicate: duplicate,
  );

  final String orderId;
  final String paymentId;
  final String promotionSnapshotId;
  final int receivableAmountMinor;
  final int tenderedAmountMinor;
  final int changeAmountMinor;
  final String orderSnapshotHash;
  final String promotionSnapshotHash;
  final String outboxEventId;
  final bool duplicate;

  Map<String, Object?> toJson() => {
    'orderId': orderId,
    'paymentId': paymentId,
    'promotionSnapshotId': promotionSnapshotId,
    'receivableAmountMinor': receivableAmountMinor,
    'tenderedAmountMinor': tenderedAmountMinor,
    'changeAmountMinor': changeAmountMinor,
    'orderSnapshotHash': orderSnapshotHash,
    'promotionSnapshotHash': promotionSnapshotHash,
    'outboxEventId': outboxEventId,
  };
}

/// POS-011 补打请求结果；真实打印未解阻时 executionStatus 必须失败关闭。
final class ReceiptReprintResult {
  const ReceiptReprintResult({
    required this.printRequestId,
    required this.orderId,
    required this.documentId,
    required this.reprintNo,
    required this.documentSha256,
    required this.executionStatus,
    required this.outboxEventId,
    this.duplicate = false,
  });

  factory ReceiptReprintResult.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => ReceiptReprintResult(
    printRequestId: json['printRequestId']! as String,
    orderId: json['orderId']! as String,
    documentId: json['documentId']! as String,
    reprintNo: json['reprintNo']! as int,
    documentSha256: json['documentSha256']! as String,
    executionStatus: json['executionStatus']! as String,
    outboxEventId: json['outboxEventId']! as String,
    duplicate: duplicate,
  );

  final String printRequestId;
  final String orderId;
  final String documentId;
  final int reprintNo;
  final String documentSha256;
  final String executionStatus;
  final String outboxEventId;
  final bool duplicate;

  Map<String, Object?> toJson() => {
    'printRequestId': printRequestId,
    'orderId': orderId,
    'documentId': documentId,
    'reprintNo': reprintNo,
    'documentSha256': documentSha256,
    'executionStatus': executionStatus,
    'outboxEventId': outboxEventId,
  };
}

/// ORD-004 取消或反向处置路由结果；成交后 effectiveStatus 必须保持原状态。
final class OrderDispositionResult {
  const OrderDispositionResult({
    required this.dispositionId,
    required this.orderId,
    required this.dispositionType,
    required this.fromStatus,
    required this.effectiveStatus,
    required this.requestSha256,
    required this.outboxEventId,
    this.duplicate = false,
  });

  factory OrderDispositionResult.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => OrderDispositionResult(
    dispositionId: json['dispositionId']! as String,
    orderId: json['orderId']! as String,
    dispositionType: json['dispositionType']! as String,
    fromStatus: json['fromStatus']! as String,
    effectiveStatus: json['effectiveStatus']! as String,
    requestSha256: json['requestSha256']! as String,
    outboxEventId: json['outboxEventId']! as String,
    duplicate: duplicate,
  );

  final String dispositionId;
  final String orderId;
  final String dispositionType;
  final String fromStatus;
  final String effectiveStatus;
  final String requestSha256;
  final String outboxEventId;
  final bool duplicate;

  Map<String, Object?> toJson() => {
    'dispositionId': dispositionId,
    'orderId': orderId,
    'dispositionType': dispositionType,
    'fromStatus': fromStatus,
    'effectiveStatus': effectiveStatus,
    'requestSha256': requestSha256,
    'outboxEventId': outboxEventId,
  };
}

String _canonicalValues(Iterable<Object?> values) => values.map((value) {
  final text = '$value';
  return '${text.length}:$text;';
}).join();

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
