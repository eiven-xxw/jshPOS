import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/return_refund/domain/pos_return_models.dart';
import 'package:jshpos_pos/features/return_refund/infrastructure/http_pos_return_application_service.dart';

const orderId = '01K2A000000000000000000031';
const lineId = '01K2A000000000000000000032';
const snapshotId = '01K2A000000000000000000033';
const terminalId = '01K2A000000000000000000011';
const shiftId = '01K2A000000000000000000021';
const warehouseId = '01K2A000000000000000000041';

void main() {
  test(
    'formal HTTP return adapter previews submits and observes original return',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      final requests = <Map<String, Object?>>[];
      final handled = Completer<void>();
      unawaited(() async {
        await for (final request in server) {
          expect(request.headers.value('X-Tenant-Id'), isNull);
          expect(request.headers.value('clientid'), 'synthetic-pos-client');
          expect(request.headers.value('X-Device-Id'), terminalId);
          final body = request.method == 'POST'
              ? jsonDecode(await utf8.decoder.bind(request).join())
                    as Map<String, Object?>
              : <String, Object?>{};
          requests.add(<String, Object?>{
            'method': request.method,
            'path': request.uri.path,
            'body': body,
          });
          request.response.headers.contentType = ContentType.json;
          if (request.uri.path.endsWith('/returns/preview')) {
            final selected = (body['lines']! as List).isNotEmpty;
            request.response.write(jsonEncode(_preview(selected)));
          } else if (request.method == 'POST') {
            expect(jsonEncode(body), isNot(contains('supervisor')));
            request.response.write(
              jsonEncode(_returnView(body, 'PENDING_APPROVAL')),
            );
          } else {
            request.response.write(jsonEncode(_returnView(null, 'COMPLETED')));
            handled.complete();
          }
          await request.response.close();
          if (handled.isCompleted) break;
        }
      }());

      final service = HttpPosReturnApplicationService(
        baseUri: Uri.parse(
          'http://${server.address.address}:${server.port}/api/v1/',
        ),
        clientId: 'synthetic-pos-client',
        binding: const TrustedDeviceBinding(
          tenantId: 'TENANT_A',
          storeId: '1101',
          terminalId: terminalId,
          cashierId: '101',
          cashierName: '虚构收银员甲',
          storeTimezone: 'Asia/Shanghai',
        ),
        accessTokenProvider: () async => 'synthetic-session',
        currentShiftIdProvider: () => shiftId,
        returnWarehouseIdProvider: () => warehouseId,
        ulids: UlidGenerator(
          random: Random(17),
          now: () => DateTime.parse('2026-08-21T02:00:00Z'),
        ),
      );
      addTearDown(service.close);

      final original = await service.findOriginalOrder('SYN-ORDER-1');
      final selected = await service.changeRequestedQuantity(lineId, '1.000');
      final submitted = await service.submitCashReturn(
        reasonCode: 'CUSTOMER_REQUEST',
      );
      final completed = await service.refreshReturnStatus(submitted.returnRef);
      await handled.future;

      expect(original.refundableAmountMinor, 0);
      expect(selected.refundableAmountMinor, 900);
      expect(submitted.status, PosReturnSagaStatus.pendingApproval);
      expect(submitted.refundableAmountMinor, 900);
      expect(completed.status, PosReturnSagaStatus.completed);
      expect(requests.map((value) => value['path']), [
        '/api/v1/returns/preview',
        '/api/v1/returns/preview',
        '/api/v1/returns',
        startsWith('/api/v1/returns/'),
      ]);
      final create = requests[2]['body']! as Map<String, Object?>;
      expect(create['storeId'], '1101');
      expect(create['terminalId'], terminalId);
      expect(create['refundShiftId'], shiftId);
      expect(create['warehouseId'], warehouseId);
      expect(create.containsKey('tenantId'), isFalse);
    },
  );

  test(
    'inline supervisor credential fails closed before any network request',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      var requests = 0;
      unawaited(() async {
        await for (final request in server) {
          requests++;
          await utf8.decoder.bind(request).join();
          request.response.headers.contentType = ContentType.json;
          request.response.write(jsonEncode(_preview(true)));
          await request.response.close();
        }
      }());
      final service = HttpPosReturnApplicationService(
        baseUri: Uri.parse(
          'http://${server.address.address}:${server.port}/api/v1/',
        ),
        clientId: 'synthetic-pos-client',
        binding: const TrustedDeviceBinding(
          tenantId: 'TENANT_A',
          storeId: '1101',
          terminalId: terminalId,
          cashierId: '101',
          cashierName: '虚构收银员甲',
          storeTimezone: 'Asia/Shanghai',
        ),
        accessTokenProvider: () async => 'synthetic-session',
        currentShiftIdProvider: () => shiftId,
        returnWarehouseIdProvider: () => warehouseId,
      );
      addTearDown(service.close);
      await service.findOriginalOrder(orderId);
      await service.changeRequestedQuantity(lineId, '1');
      final beforeSubmit = requests;

      await expectLater(
        service.submitCashReturn(
          reasonCode: 'CUSTOMER_REQUEST',
          supervisorCredential: 'must-not-leave-memory',
        ),
        throwsA(
          isA<PosReturnFailure>().having(
            (error) => error.code,
            'code',
            'RETURN_SUPERVISOR_INLINE_UNSUPPORTED',
          ),
        ),
      );
      expect(requests, beforeSubmit);
    },
  );
}

Map<String, Object?> _preview(bool selected) => <String, Object?>{
  'code': 200,
  'data': <String, Object?>{
    'orderId': orderId,
    'localOrderNo': 'SYN-ORDER-1',
    'storeId': '1101',
    'businessDate': '2026-08-21',
    'currency': 'CNY',
    'settlementKind': 'CASH',
    'promotionSnapshotId': snapshotId,
    'promotionSnapshotSha256': 'a'.padRight(64, 'a'),
    'originalReceivableAmountMinor': 1800,
    'cumulativeRefundedAmountMinor': 0,
    'maximumRefundableAmountMinor': 1800,
    'requestedGrossAmountMinor': selected ? 1000 : 0,
    'recoveredDiscountAmountMinor': selected ? 100 : 0,
    'refundableAmountMinor': selected ? 900 : 0,
    'lines': [
      <String, Object?>{
        'orderLineId': lineId,
        'skuCode': 'SKU-701',
        'productName': '合成商品',
        'unitCode': 'PCS',
        'originalQuantity': 2,
        'cumulativeReturnedQuantity': 0,
        'maximumReturnableQuantity': 2,
        'requestedQuantity': selected ? 1 : 0,
        'requestedGrossMinor': selected ? 1000 : 0,
        'recoveredDiscountMinor': selected ? 100 : 0,
        'refundableAmountMinor': selected ? 900 : 0,
      },
    ],
  },
};

Map<String, Object?> _returnView(Map<String, Object?>? request, String status) {
  final returnId =
      request?['returnId']?.toString() ?? '01K2A000000000000000000051';
  final correlationId =
      request?['correlationId']?.toString() ?? '01K2A000000000000000000052';
  return <String, Object?>{
    'code': 200,
    'data': <String, Object?>{
      'returnId': returnId,
      'requestCommandId': '01K5A000000000000000000099',
      'orderId': orderId,
      'status': status,
      'refundableAmountMinor': status == 'PENDING_APPROVAL' ? null : 900,
      'promotionSnapshotId': snapshotId,
      'promotionSnapshotSha256': 'a'.padRight(64, 'a'),
      'correlationId': correlationId,
      'recordVersion': status == 'PENDING_APPROVAL' ? 1 : 5,
      'updatedAt': '2026-08-21T02:00:00',
      'duplicate': false,
    },
  };
}
