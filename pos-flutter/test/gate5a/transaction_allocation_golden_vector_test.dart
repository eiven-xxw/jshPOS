import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/promotion/domain/transaction_allocation_engine.dart';

void main() {
  test('Flutter matches every shared PRM-003 allocation vector exactly', () {
    final root = jsonDecode(
      File(
        '../contracts/t2/gate5a/test-vectors/transaction-allocation-vectors-v1.json',
      ).readAsStringSync(),
    ) as Map<String, dynamic>;
    final rawSnapshot = root['snapshot'] as Map<String, dynamic>;
    final engine = TransactionAllocationEngine();
    final snapshot = engine.freeze(
      (rawSnapshot['lines'] as List<dynamic>).map((raw) {
        final value = raw as Map<String, dynamic>;
        return TransactionSnapshotLine(
          lineId: value['lineId'] as String,
          lineNo: value['lineNo'] as int,
          skuId: int.parse(value['skuId'] as String),
          quantity: value['quantity'] as String,
          grossAmountMinor: value['grossAmountMinor'] as int,
          discountAmountMinor: value['discountAmountMinor'] as int,
          payableAmountMinor: value['payableAmountMinor'] as int,
        );
      }).toList(),
    );
    expect(snapshot.grossAmountMinor, rawSnapshot['grossAmountMinor']);
    final history = <String, PriorRefundAllocation>{};
    for (final raw in root['refunds'] as List<dynamic>) {
      final scenario = raw as Map<String, dynamic>;
      final expected = scenario['expected'] as Map<String, dynamic>;
      final result = engine.refund(
        snapshot: snapshot,
        history: history.values.toList(),
        requests: (scenario['lines'] as List<dynamic>).map((rawLine) {
          final line = rawLine as Map<String, dynamic>;
          return RefundAllocationRequestLine(
            lineId: line['lineId'] as String,
            quantity: line['quantity'] as String,
          );
        }).toList(),
      );
      expect(
        result.grossAmountMinor,
        expected['grossAmountMinor'],
        reason: scenario['id'] as String,
      );
      expect(result.recoveredDiscountMinor, expected['recoveredDiscountMinor']);
      expect(result.refundableAmountMinor, expected['refundableAmountMinor']);
      for (final line in result.lines) {
        history[line.lineId] = PriorRefundAllocation(
          lineId: line.lineId,
          quantity: line.cumulativeQuantity,
          grossAmountMinor: line.cumulativeGrossAmountMinor,
          discountAmountMinor: line.cumulativeDiscountAmountMinor,
          payableAmountMinor: line.cumulativePayableAmountMinor,
        );
      }
    }
  });

  test('Flutter rejects over-refund duplicate lines and corrupted history', () {
    const lineId = '01K5R000000000000000000001';
    final engine = TransactionAllocationEngine();
    final snapshot = engine.freeze([
      TransactionSnapshotLine(
        lineId: lineId,
        lineNo: 1,
        skuId: 101,
        quantity: '3',
        grossAmountMinor: 1000,
        discountAmountMinor: 101,
        payableAmountMinor: 899,
      ),
    ]);
    expect(
      () => engine.refund(
        snapshot: snapshot,
        history: const [],
        requests: [RefundAllocationRequestLine(lineId: lineId, quantity: '4')],
      ),
      throwsFormatException,
    );
    expect(
      () => engine.refund(
        snapshot: snapshot,
        history: const [],
        requests: [
          RefundAllocationRequestLine(lineId: lineId, quantity: '1'),
          RefundAllocationRequestLine(lineId: lineId, quantity: '1'),
        ],
      ),
      throwsFormatException,
    );
    expect(
      () => engine.refund(
        snapshot: snapshot,
        history: [
          PriorRefundAllocation(
            lineId: lineId,
            quantity: '1',
            grossAmountMinor: 333,
            discountAmountMinor: 33,
            payableAmountMinor: 300,
          ),
        ],
        requests: [RefundAllocationRequestLine(lineId: lineId, quantity: '1')],
      ),
      throwsFormatException,
    );
  });
}
