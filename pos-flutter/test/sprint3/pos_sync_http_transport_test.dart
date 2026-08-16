import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
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
      deviceId: '01K2A000000000000000000011',
      accessTokenProvider: () async => 'synthetic-session',
    );
    addTearDown(transport.close);

    final result = await transport.bootstrap(
      '01K2A000000000000000000071',
    );
    await handled;
    expect(result.storeId, '1101');
    expect(result.protocolVersion, '1.0');
  });
}
