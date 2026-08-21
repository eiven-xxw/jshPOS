import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/promoted_order_snapshot_codec.dart';

void main() {
  test(
    'Dart and Java freeze the identical Promotion Owner snapshot digest',
    () {
      const sources = {'RULE:01K2A000000000000000000061': 200};
      final document = <String, Object?>{
        'snapshotId': '01K2A000000000000000000051',
        'orderId': '01K2A000000000000000000031',
        'quoteId': '01K2A000000000000000000052',
        'storeId': 1101,
        'terminalId': '01K2A000000000000000000011',
        'currency': 'CNY',
        'quoteFingerprint': List.filled(64, '2').join(),
        'grossAmountMinor': 1299,
        'discountAmountMinor': 200,
        'payableAmountMinor': 1099,
        'lines': [
          {
            'lineId': '01K2A000000000000000000041',
            'lineNo': 1,
            'skuId': 701,
            'quantity': '1.000000',
            'grossAmountMinor': 1299,
            'discountAmountMinor': 200,
            'payableAmountMinor': 1099,
            'sourceAllocationsSha256': PromotedOrderSnapshotCodec.sha256Hex(
              sources,
            ),
          },
        ],
      };

      expect(
        PromotedOrderSnapshotCodec.sha256Hex(document),
        'b747993a40606c06fcd0799286ffae1f50249fc7ccdd0c07c46a9d3e2f04a1d8',
      );
    },
  );
}
