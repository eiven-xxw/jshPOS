import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/session/infrastructure/http_pos_session_repository.dart';
import 'package:jshpos_pos/features/session/infrastructure/ruoyi_api_request_encryptor.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  const deviceId = '01K2A000000000000000000151';
  const terminalId = '01K2A000000000000000000152';

  test('可信终端、加密登录、权限刷新和注销使用正式自有 API', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requests = <_ObservedRequest>[];
    final loop = server.listen((request) async {
      final body = await utf8.decoder.bind(request).join();
      requests.add(
        _ObservedRequest(
          path: request.uri.path,
          body: body,
          authorization: request.headers.value(HttpHeaders.authorizationHeader),
          encryptKey: request.headers.value('encrypt-key'),
        ),
      );
      request.response.headers.contentType = ContentType.json;
      switch (request.uri.path) {
        case '/api/pos/v1/terminals/authenticate':
          request.response.write(
            jsonEncode({
              'code': 200,
              'data': {
                'tenantId': 'TENANT_A',
                'deviceId': deviceId,
                'orgUnitId': 1001,
                'storeId': 1101,
                'terminalId': terminalId,
                'boundUserId': 201,
                'storeName': '合成便利店一店',
                'storeTimezone': 'Asia/Shanghai',
                'businessDate': '2026-08-21',
                'terminalName': '一号收银台',
                'status': 'ACTIVE',
                'protocolVersion': '1.0',
                'validUntil': '2099-08-21T00:00:00Z',
                'approvedCapabilities': <String>[],
              },
            }),
          );
        case '/auth/login':
          request.response.write(
            jsonEncode({
              'code': 200,
              'data': {
                'access_token': 'synthetic-access-token-not-a-secret',
                'expire_in': 3600,
              },
            }),
          );
        case '/system/user/getInfo':
          request.response.write(
            jsonEncode({
              'code': 200,
              'data': {
                'user': {
                  'userId': 201,
                  'tenantId': 'TENANT_A',
                  'userName': 'cashier01',
                  'nickName': '合成收银员',
                },
                'permissions': [
                  'pos:shift:open',
                  'pos:basket:operate',
                  'pos:cash:collect',
                  'promotion:manual:authorize',
                  'pos:sync:operate',
                ],
                'roles': ['cashier'],
              },
            }),
          );
        case '/auth/logout':
          request.response.write(jsonEncode({'code': 200, 'data': null}));
        default:
          request.response.statusCode = 404;
          request.response.write(jsonEncode({'code': 404, 'msg': 'not found'}));
      }
      await request.response.close();
    });
    final repository = HttpPosSessionRepository(
      baseUri: Uri.parse('http://127.0.0.1:${server.port}/'),
      clientId: 'synthetic-client',
      materialProvider: const _MaterialProvider(),
      loginEncryptor: const _FakeEncryptor(),
    );
    addTearDown(() async {
      repository.close();
      await server.close(force: true);
      await loop.cancel();
    });

    final terminal = await repository.verifyTerminal(_device());
    final login = await repository.authenticate(
      terminal,
      EmployeeLoginCommand(
        loginName: 'cashier01',
        secret: 'synthetic-password',
        correlationId: '01K2A000000000000000000153',
        occurredAt: DateTime.utc(2026, 8, 21),
      ),
    );
    final refreshed = await repository.refresh(terminal, login.employee);
    await repository.logout(
      refreshed.terminal,
      refreshed.employee,
      '01K2A000000000000000000154',
    );

    expect(terminal.tenantId, 'TENANT_A');
    expect(terminal.storeName, '合成便利店一店');
    expect(login.employee.permissions, contains(PosPermission.shiftOpen));
    expect(login.employee.permissions, contains(PosPermission.sessionLogin));
    expect(login.employee.permissions, contains(PosPermission.saleOperate));
    expect(login.employee.permissions, contains(PosPermission.cashSettle));
    expect(login.employee.permissions, contains(PosPermission.manualDiscount));
    expect(login.employee.permissions, contains(PosPermission.syncView));
    expect(refreshed.employee.employeeId, '201');
    expect(
      requests.where((item) => item.path.endsWith('/authenticate')),
      hasLength(2),
    );
    final loginRequest = requests.singleWhere(
      (item) => item.path == '/auth/login',
    );
    expect(loginRequest.body, 'encrypted-login-body');
    expect(loginRequest.encryptKey, 'encrypted-aes-key');
    expect(loginRequest.body, isNot(contains('synthetic-password')));
    expect(
      requests
          .where((item) => item.path == '/system/user/getInfo')
          .every(
            (item) =>
                item.authorization ==
                'Bearer synthetic-access-token-not-a-secret',
          ),
      isTrue,
    );
  });

  test('RuoYi 请求加密器产生 RSA 密钥头和 AES 分组载荷', () {
    const publicKey =
        'MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKoR8mX0rGKLqzcWmOzbfj64K8ZIgOdHnzkXSOVOZbFu/TJhZ7rFAN+eaGkl3C4buccQd/EjEsj9ir7ijT7h96MCAwEAAQ==';
    final encrypted = RuoYiApiRequestEncryptor(publicKey).encryptJson({
      'tenantId': 'TENANT_A',
      'username': 'cashier01',
      'password': 'synthetic-password',
    });

    expect(base64Decode(encrypted.encryptKey), hasLength(64));
    expect(base64Decode(encrypted.body).length % 16, 0);
    expect(encrypted.body, isNot(contains('synthetic-password')));
  });
}

DeviceSnapshot _device() => const DeviceSnapshot(
  metadata: DeviceMetadata(
    manufacturer: 'SYNTHETIC',
    model: 'VIRTUAL_POS',
    androidRelease: '15',
    androidSdk: 35,
    adapterVersion: '1.0',
  ),
  capabilities: <DeviceCapability>{},
);

final class _MaterialProvider implements PosTerminalMaterialProvider {
  const _MaterialProvider();

  @override
  Future<PosTerminalMaterial> load() async => const PosTerminalMaterial(
    deviceId: '01K2A000000000000000000151',
    deviceCredential: 'synthetic-device-credential-123456789012',
    deviceFingerprintSha256:
        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
    publicKeySha256:
        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
    appVersion: '1.0.0',
    protocolVersion: '1.0',
    schemaVersion: '8',
  );
}

final class _FakeEncryptor implements ApiRequestEncryptor {
  const _FakeEncryptor();

  @override
  EncryptedApiRequest encryptJson(Map<String, Object?> value) =>
      const EncryptedApiRequest(
        encryptKey: 'encrypted-aes-key',
        body: 'encrypted-login-body',
      );
}

final class _ObservedRequest {
  const _ObservedRequest({
    required this.path,
    required this.body,
    required this.authorization,
    required this.encryptKey,
  });

  final String path;
  final String body;
  final String? authorization;
  final String? encryptKey;
}
