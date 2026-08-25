import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/synchronization/domain/sync_models.dart';
import 'package:jshpos_pos/features/synchronization/infrastructure/pos_sync_http_transport.dart';

void main() {
  test('real loopback HTTP uses device auth headers and never sends tenant authority', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    final handled = server.first.then((request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/api/pos/v1/sync/bootstrap');
      expect(
        request.headers.value('X-Device-Id'),
        '01K2A000000000000000000011',
      );
      expect(
        request.headers.value(HttpHeaders.authorizationHeader),
        'Bearer synthetic-session',
      );
      expect(request.headers.value('clientid'), 'synthetic-pos-client');
      expect(request.headers.value('X-Tenant-Id'), isNull);
      request.response.headers.contentType = ContentType.json;
      request.response.write(
        jsonEncode({
          'code': 200,
          'data': {
            'deviceId': '01K2A000000000000000000011',
            'storeId': '1101',
            'terminalId': '01K2A000000000000000000011',
            'protocolVersion': '1.0',
            'limits': {
              'maxBatchEvents': 100,
              'maxBatchBytes': 2097152,
              'maxEventBytes': 262144,
            },
          },
        }),
      );
      await request.response.close();
    });
    final transport = PosSyncHttpTransport(
      baseUri: Uri.parse(
        'http://${server.address.address}:${server.port}/api/pos/v1/',
      ),
      clientId: 'synthetic-pos-client',
      deviceId: '01K2A000000000000000000011',
      accessTokenProvider: () async => 'synthetic-session',
    );
    addTearDown(transport.close);

    final result = await transport.bootstrap('01K2A000000000000000000071');
    await handled;
    expect(result.storeId, '1101');
    expect(result.protocolVersion, '1.0');
  });

  test('push result pull and ACK preserve the formal wire contract', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    final observed = <String>[];
    final subscription = server.listen((request) async {
      observed.add('${request.method} ${request.uri.path}');
      expect(request.headers.value('clientid'), 'synthetic-pos-client');
      expect(request.headers.value('X-Tenant-Id'), isNull);
      request.response.headers.contentType = ContentType.json;
      if (request.uri.path.endsWith('/sync/push')) {
        final body = jsonDecode(
          await utf8.decoder.bind(request).join(),
        ) as Map<String, Object?>;
        final events = body['events']! as List<Object?>;
        final event = events.single! as Map<String, Object?>;
        request.response.write(
          jsonEncode({
            'code': 200,
            'data': {
              'batchId': body['batchId'],
              'acks': [
                {
                  'eventId': event['eventId'],
                  'payloadHash': event['payloadHash'],
                  'status': 'ACCEPTED_PENDING',
                  'resultCode': 'PROCESSING',
                  'retryAfterMs': 1250,
                },
              ],
              'serverTime': '2026-08-16T08:00:01Z',
            },
          }),
        );
      } else if (request.uri.path.contains('/sync/results/')) {
        request.response.write(
          jsonEncode({
            'eventId': '01K2A000000000000000000081',
            'payloadHash': List.filled(64, 'a').join(),
            'status': 'ACCEPTED',
          }),
        );
      } else if (request.uri.path.endsWith('/sync/pull')) {
        expect(request.uri.queryParameters['stream'], 'sync.control');
        expect(
          request.uri.queryParameters['cursor'],
          '01K2A000000000000000000091',
        );
        request.response.write(
          jsonEncode({
            'stream': 'sync.control',
            'changes': [
              {
                'changeId': '01K2A000000000000000000092',
                'eventType': 'sync.device-policy.changed.v1',
                'aggregateId': '01K2A000000000000000000011',
                'aggregateVersion': 2,
                'payloadHash': List.filled(64, 'b').join(),
                'payload': {'deviceStatus': 'ACTIVE'},
                'publishedAt': '2026-08-16T08:00:02Z',
              },
            ],
            'nextCursor': '01K2A000000000000000000092',
            'pageDigest': List.filled(64, 'c').join(),
            'hasMore': false,
          }),
        );
      } else if (request.uri.path.endsWith('/sync/ack')) {
        final body = jsonDecode(
          await utf8.decoder.bind(request).join(),
        ) as Map<String, Object?>;
        expect(body['stream'], 'sync.control');
        expect(body['appliedChangeIds'], hasLength(1));
        request.response.statusCode = HttpStatus.noContent;
      } else {
        request.response.statusCode = HttpStatus.notFound;
      }
      await request.response.close();
    });
    addTearDown(subscription.cancel);
    final transport = PosSyncHttpTransport(
      baseUri: Uri.parse(
        'http://${server.address.address}:${server.port}/api/pos/v1',
      ),
      clientId: 'synthetic-pos-client',
      deviceId: '01K2A000000000000000000011',
      accessTokenProvider: () async => 'synthetic-session',
    );
    addTearDown(transport.close);
    final event = SyncEventEnvelope(
      eventId: '01K2A000000000000000000081',
      stream: 'order.command',
      eventType: 'order.completed.v1',
      eventVersion: 1,
      aggregateId: '01K2A000000000000000000031',
      aggregateVersion: 4,
      deviceId: '01K2A000000000000000000011',
      storeId: '1101',
      terminalId: '01K2A000000000000000000011',
      sequenceNo: 12,
      occurredAt: DateTime.parse('2026-08-16T08:00:00Z'),
      idempotencyKey: '01K2A000000000000000000081',
      correlationId: '01K2A000000000000000000071',
      payloadHash: List.filled(64, 'a').join(),
      payload: const {'receivableAmountMinor': 1299},
      attemptCount: 1,
    );

    final pushed = await transport.push(
      SyncPushBatch(batchId: '01K2A000000000000000000093', events: [event]),
    );
    expect(pushed.acks.single.status, 'ACCEPTED_PENDING');
    expect(pushed.acks.single.retryAfterMs, 1250);
    final result = await transport.result(event.eventId, event.correlationId);
    expect(result.status, 'ACCEPTED');
    final page = await transport.pull(
      stream: 'sync.control',
      cursor: '01K2A000000000000000000091',
      correlationId: event.correlationId,
    );
    expect(page.changes.single.aggregateVersion, 2);
    expect(page.hasMore, isFalse);
    await transport.acknowledge(
      SyncAckCommand(
        stream: page.stream,
        cursor: page.nextCursor,
        appliedChangeIds: [page.changes.single.changeId],
        pageDigest: page.pageDigest,
      ),
      event.correlationId,
    );

    expect(observed, [
      'POST /api/pos/v1/sync/push',
      'GET /api/pos/v1/sync/results/${event.eventId}',
      'GET /api/pos/v1/sync/pull',
      'POST /api/pos/v1/sync/ack',
    ]);
    expect(
      const SyncTransportException(
        'SYNTHETIC',
        'failure',
        retryable: false,
      ).toString(),
      'SYNTHETIC: failure',
    );
  });

  test(
    'HTTP retryable failure is explicit and preserves status evidence',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      final handled = server.first.then((request) async {
        request.response.statusCode = HttpStatus.serviceUnavailable;
        request.response.write('synthetic outage');
        await request.response.close();
      });
      final transport = PosSyncHttpTransport(
        baseUri: Uri.parse(
          'http://${server.address.address}:${server.port}/api/pos/v1/',
        ),
        clientId: 'synthetic-pos-client',
        deviceId: '01K2A000000000000000000011',
        accessTokenProvider: () async => 'synthetic-session',
      );
      addTearDown(transport.close);

      await expectLater(
        transport.bootstrap('01K2A000000000000000000071'),
        throwsA(
          isA<SyncTransportException>()
              .having((error) => error.code, 'code', 'SYNC_HTTP_503')
              .having((error) => error.retryable, 'retryable', isTrue)
              .having((error) => error.statusCode, 'statusCode', 503),
        ),
      );
      await handled;
    },
  );
}
