import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/promoted_order_snapshot_codec.dart';

void main() {
  test('Dart promoted order snapshot matches every shared vector', () {
    final root = jsonDecode(
      File(
        '../contracts/t2/gate5b/test-vectors/settlement-order-vectors-v1.json',
      ).readAsStringSync(),
    ) as Map<String, Object?>;
    for (final raw in root['scenarios']! as List<Object?>) {
      final scenario = raw! as Map<String, Object?>;
      final bindingJson = scenario['binding']! as Map<String, Object?>;
      final commandJson = scenario['command']! as Map<String, Object?>;
      final binding = TrustedDeviceBinding(
        tenantId: bindingJson['tenantId']! as String,
        storeId: bindingJson['storeId']! as String,
        terminalId: bindingJson['terminalId']! as String,
        cashierId: bindingJson['cashierId']! as String,
        cashierName: bindingJson['cashierName']! as String,
        storeTimezone: bindingJson['storeTimezone']! as String,
      );
      final promotedLines = (scenario['lines']! as List<Object?>).map((raw) {
        final line = raw! as Map<String, Object?>;
        final basketLine = BasketLine(
          lineId: line['lineId']! as String,
          lineNo: line['lineNo']! as int,
          quote: PriceQuote.fromVerifiedPackage(
            skuId: line['skuId']! as String,
            skuCode: line['skuCode']! as String,
            productName: line['productName']! as String,
            unitId: line['unitId']! as String,
            unitCode: line['unitCode']! as String,
            unitPriceMinor: line['unitPriceMinor']! as int,
            priceSource: line['priceSource']! as String,
          ),
          quantity: line['quantity']! as String,
        );
        return PromotedSettlementLine(
          basketLine: basketLine,
          discountAmountMinor: line['discountAmountMinor']! as int,
          surchargeAmountMinor: line['surchargeAmountMinor']! as int,
          sourceAllocations:
              (line['sourceAllocations']! as Map<String, Object?>).map(
                (key, value) => MapEntry(key, value! as int),
              ),
        );
      }).toList();
      final command = PromotedCashSaleCommand(
        commandId: commandJson['commandId']! as String,
        idempotencyKey: commandJson['idempotencyKey']! as String,
        basket: Basket(
          orderId: commandJson['orderId']! as String,
          localOrderNo: commandJson['localOrderNo']! as String,
          lines: promotedLines.map((line) => line.basketLine),
        ),
        shiftId: commandJson['shiftId']! as String,
        businessDate: commandJson['businessDate']! as String,
        catalogVersion: commandJson['catalogVersion']! as int,
        priceVersion: commandJson['priceVersion']! as int,
        industryTemplateVersion:
            commandJson['industryTemplateVersion']! as String,
        quoteId: commandJson['quoteId']! as String,
        quoteFingerprint: commandJson['quoteFingerprint']! as String,
        settlementFingerprint: commandJson['settlementFingerprint']! as String,
        packageVersion: commandJson['packageVersion']! as int,
        promotionSnapshotId: commandJson['promotionSnapshotId']! as String,
        lines: promotedLines,
        manualEventRefs: (commandJson['manualEventRefs']! as List<Object?>)
            .cast<String>(),
        tenderedAmountMinor: commandJson['tenderedAmountMinor']! as int,
        occurredAt: DateTime.parse(commandJson['occurredAt']! as String),
      );
      final expected = scenario['expected']! as Map<String, Object?>;
      final document = PromotedOrderSnapshotCodec.document(
        command: command,
        binding: binding,
        promotionSnapshotSha256:
            commandJson['promotionSnapshotSha256']! as String,
      );
      expect(command.grossAmountMinor, expected['grossAmountMinor']);
      expect(command.discountAmountMinor, expected['discountAmountMinor']);
      expect(command.surchargeAmountMinor, expected['surchargeAmountMinor']);
      expect(command.receivableAmountMinor, expected['receivableAmountMinor']);
      expect(
        PromotedOrderSnapshotCodec.sha256Hex(document),
        expected['orderSnapshotSha256'],
        reason: scenario['id']! as String,
      );
    }
  });
}
