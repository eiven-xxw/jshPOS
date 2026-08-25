import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/infrastructure/runtime/http_signed_package_source.dart';

void main() {
  test('签名包下载携带 RuoYi 客户端身份且不传递客户端租户值', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    final handled = server.first.then((request) async {
      expect(request.uri.path, '/api/v1/catalog/packages/1/content');
      expect(request.uri.queryParameters['storeId'], '1101');
      expect(request.headers.value('clientid'), 'synthetic-pos-client');
      expect(
        request.headers.value(HttpHeaders.authorizationHeader),
        'Bearer synthetic-session',
      );
      expect(request.headers.value('X-Tenant-Id'), isNull);
      request.response.headers
        ..set('X-JSH-Payload-Sha256', List.filled(64, 'a').join())
        ..set('X-JSH-Signing-Key-Id', 'SYNTHETIC_KEY')
        ..set('X-JSH-Signature', base64Encode(List<int>.filled(64, 1)));
      request.response.add(utf8.encode('{"schemaVersion":1}'));
      await request.response.close();
    });
    final source = HttpSignedPackageSource(
      baseUri: Uri.parse('http://127.0.0.1:${server.port}/'),
      clientId: 'synthetic-pos-client',
      accessTokenProvider: () async => 'synthetic-session',
    );
    addTearDown(source.close);

    final envelope = await source.catalog(storeId: '1101', packageVersion: 1);
    await handled;
    expect(utf8.decode(envelope.payload), '{"schemaVersion":1}');
    expect(envelope.signingKeyId, 'SYNTHETIC_KEY');
  });
}
