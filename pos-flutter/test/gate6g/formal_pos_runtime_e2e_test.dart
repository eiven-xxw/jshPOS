import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/session/infrastructure/http_pos_session_repository.dart';
import 'package:jshpos_pos/features/session/infrastructure/ruoyi_api_request_encryptor.dart';
import 'package:jshpos_pos/infrastructure/runtime/session_bound_pos_runtime.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';
import 'package:sqlite3/sqlite3.dart';

const deviceId = '01K2A000000000000000000181';
const terminalId = '01K2A000000000000000000182';
const warehouseId = '01K2A000000000000000000183';

void main() {
  test('正式组合根经 HTTP 签名包和文件 SQLite 完成现金交易并安全注销', () async {
    final signingPair = await Ed25519().newKeyPair();
    final publicKey = await signingPair.extractPublicKey();
    final now = DateTime.now().toUtc();
    final catalog = await _catalogArtifact(signingPair, now);
    final promotion = await _promotionArtifact(signingPair, now);
    final observed = <_ObservedRequest>[];
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final subscription = server.listen((request) async {
      final body = await request.fold<List<int>>(
        <int>[],
        (all, part) => all..addAll(part),
      );
      observed.add(
        _ObservedRequest(
          method: request.method,
          path: request.uri.path,
          tenantHeader: request.headers.value('X-Tenant-Id'),
          authorization: request.headers.value(HttpHeaders.authorizationHeader),
          body: body,
        ),
      );
      await _respond(request, catalog, promotion);
    });
    final directory = await Directory.systemTemp.createTemp(
      'jshpos-gate6g-runtime-',
    );
    final databasePath = '${directory.path}/formal-pos.sqlite3';
    final baseUri = Uri.parse('http://127.0.0.1:${server.port}/');
    final session = HttpPosSessionRepository(
      baseUri: baseUri,
      clientId: 'synthetic-pos-client',
      materialProvider: const _MaterialProvider(),
      loginEncryptor: const _SyntheticEncryptor(),
    );
    final runtime = SessionBoundPosRuntime(
      sessions: session,
      assembler: FilePosBusinessRuntimeAssembler(
        databasePathProvider: (_) async => databasePath,
        baseUri: baseUri,
        accessTokenProvider: session.accessToken,
        catalogPackageVersion: 1,
        promotionPackageVersion: 1,
        catalogSigningKeys: {'SYNTHETIC_KEY': publicKey},
        promotionSigningKeys: {'SYNTHETIC_KEY': publicKey},
        industryTemplateVersion: 'CONVENIENCE_V1',
        returnWarehouseId: warehouseId,
        configVersion: 1,
        cashDifferenceApprovalMinor: 500,
      ),
    );
    TrustedTerminalContext? authenticatedTerminal;
    EmployeeSession? authenticatedEmployee;
    addTearDown(() async {
      if (authenticatedTerminal != null && authenticatedEmployee != null) {
        try {
          await runtime.logout(
            authenticatedTerminal,
            authenticatedEmployee,
            '01K2A000000000000000000186',
          );
        } on Object {
          // 清理失败不能覆盖原始测试结果；服务端与文件句柄仍在下方强制释放。
        }
      }
      session.close();
      await server.close(force: true);
      await subscription.cancel();
      if (directory.existsSync()) directory.deleteSync(recursive: true);
    });

    final terminal = await runtime.verifyTerminal(_device());
    final login = await runtime.authenticate(
      terminal,
      EmployeeLoginCommand(
        loginName: 'cashier01',
        secret: 'synthetic-password',
        correlationId: '01K2A000000000000000000184',
        occurredAt: now,
      ),
    );
    authenticatedTerminal = terminal;
    authenticatedEmployee = login.employee;
    final shift = await runtime.open(
      businessDate: terminal.businessDate,
      openingCash: '100.00',
      idempotencyKey: 'gate6g:open:01K2A000000000000000000184',
    );
    final quoted = await runtime.scanBarcode('6900000000001');
    final adjusted = await runtime.applyManualAdjustment(
      actionCode: 'ORDER_AMOUNT_OFF',
      value: '50',
    );
    final settled = await runtime.settleCash(
      tenderedAmount: '3.00',
      idempotencyKey:
          'gate6g:cash:${adjusted.saleRef}:${adjusted.quoteFingerprint}',
    );
    final preview = await runtime.previewPrintTask(settled.orderRef);
    await runtime.close(
      shiftId: shift.shiftId,
      actualCash: '101.49',
      idempotencyKey: 'gate6g:close:${shift.shiftId}',
    );
    await runtime.logout(
      terminal,
      login.employee,
      '01K2A000000000000000000185',
    );
    authenticatedTerminal = null;
    authenticatedEmployee = null;

    expect(quoted.totals.receivableAmountMinor, 199);
    expect(adjusted.totals.receivableAmountMinor, 149);
    expect(settled.receivableAmountMinor, 149);
    expect(settled.changeAmountMinor, 151);
    expect(preview.adapterEvidence, contains('BLOCKED_REAL_PRINTER'));
    expect(observed.every((item) => item.tenantHeader == null), isTrue);
    expect(
      observed
          .where(
            (item) =>
                item.path.contains('/packages/') ||
                item.path == '/system/user/getInfo',
          )
          .every((item) => item.authorization == 'Bearer synthetic-token'),
      isTrue,
    );
    expect(
      observed.map((item) => '${item.method} ${item.path}'),
      containsAll(<String>[
        'POST /api/pos/v1/terminals/authenticate',
        'POST /auth/login',
        'GET /system/user/getInfo',
        'GET /api/v1/catalog/packages/1/content',
        'GET /api/v1/promotions/packages/1/content',
        'POST /auth/logout',
      ]),
    );

    final database = sqlite3.open(databasePath);
    try {
      expect(_count(database, 'local_order'), 1);
      expect(_count(database, 'local_cash_payment'), 1);
      expect(_count(database, 'local_promotion_transaction_snapshot'), 1);
      expect(_count(database, 'local_outbox'), greaterThanOrEqualTo(4));
      expect(
        database.select('SELECT status FROM local_shift').single['status'],
        'CLOSED',
      );
    } finally {
      database.close();
    }
  });
}

Future<void> _respond(
  HttpRequest request,
  _SignedArtifact catalog,
  _SignedArtifact promotion,
) async {
  switch (request.uri.path) {
    case '/api/pos/v1/terminals/authenticate':
      return _json(request, {
        'tenantId': 'TENANT_A',
        'tenantName': '虚构租户甲',
        'deviceId': deviceId,
        'orgUnitId': 1001,
        'storeId': 1101,
        'terminalId': terminalId,
        'boundUserId': 201,
        'storeName': '虚构便利店一店',
        'storeTimezone': 'Asia/Shanghai',
        'businessDate': '2026-08-21',
        'terminalName': '一号收银台',
        'status': 'ACTIVE',
        'protocolVersion': '1.0',
        'validUntil': '2099-08-21T00:00:00Z',
        'approvedCapabilities': <String>[],
      });
    case '/auth/login':
      return _json(request, {
        'access_token': 'synthetic-token',
        'expire_in': 3600,
      });
    case '/system/user/getInfo':
      return _json(request, {
        'user': {
          'userId': 201,
          'tenantId': 'TENANT_A',
          'userName': 'cashier01',
          'nickName': '虚构收银员',
        },
        'permissions': [
          'pos:session:login',
          'pos:shift:open',
          'pos:shift:close',
          'pos:sale:operate',
          'pos:discount:manual',
          'pos:cash:settle',
          'pos:print:preview',
        ],
        'roles': ['cashier'],
      });
    case '/api/v1/catalog/packages/1/content':
      return _binary(request, catalog);
    case '/api/v1/promotions/packages/1/content':
      return _binary(request, promotion);
    case '/auth/logout':
      return _json(request, null);
    default:
      request.response.statusCode = HttpStatus.notFound;
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode({'code': 404, 'msg': 'not found'}));
      await request.response.close();
  }
}

Future<void> _json(HttpRequest request, Object? data) async {
  request.response.headers.contentType = ContentType.json;
  request.response.write(jsonEncode({'code': 200, 'data': data}));
  await request.response.close();
}

Future<void> _binary(HttpRequest request, _SignedArtifact artifact) async {
  request.response.headers
    ..contentType = ContentType.binary
    ..set('X-JSH-Payload-Sha256', artifact.sha256)
    ..set('X-JSH-Signing-Key-Id', 'SYNTHETIC_KEY')
    ..set('X-JSH-Signature', base64Encode(artifact.signature));
  request.response.add(artifact.payload);
  await request.response.close();
}

Future<_SignedArtifact> _catalogArtifact(KeyPair keyPair, DateTime now) async {
  final product = jsonEncode({
    'skuId': '101',
    'skuCode': 'LEMON-001',
    'name': '合成柠檬水',
    'productType': 'STANDARD',
    'status': 'ACTIVE',
    'categoryId': '401',
    'brandId': null,
    'unitId': '301',
    'unitCode': 'BTL',
    'unitName': '瓶',
    'decimalScale': 0,
    'ratioNumerator': 1,
    'ratioDenominator': 1,
    'barcode': '6900000000001',
  });
  final price = jsonEncode({
    'priceBookId': '201',
    'bookCode': 'BASE',
    'versionNo': 1,
    'scopeType': 'TENANT_BASE',
    'storeId': null,
    'skuId': '101',
    'unitId': '301',
    'amountMinor': 299,
    'currency': 'CNY',
    'effectiveFrom': now.subtract(const Duration(days: 1)).toIso8601String(),
    'effectiveTo': null,
  });
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHCAT|1.0|TENANT_A|1101|1|0|${now.toIso8601String()}\n'
      'PRICE|000000000|${_escape(price)}\n'
      'PRODUCT|000000000|${_escape(product)}\n',
    ),
  );
  return _sign(keyPair, payload);
}

Future<_SignedArtifact> _promotionArtifact(
  KeyPair keyPair,
  DateTime now,
) async {
  final expires = now.add(const Duration(days: 1));
  final policy = jsonEncode({
    'maximumRoundingMinor': 9,
    'minimumLinePayableMinor': 20,
    'policyType': 'PROMOTION_MANUAL_AUTHORITY',
    'roundingMultiplesMinor': [1, 10],
    'withApprovalMinor': 1000,
    'withoutApprovalMinor': 100,
  });
  final policySha = sha256.convert(utf8.encode(policy)).toString();
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHPRM|1.0|promotion-engine-1.0.0|TENANT_A|1101|1|0|${now.toIso8601String()}|${expires.toIso8601String()}\n'
      '01K5R000000000000000000001|'
      '{"benefit":{"amountMinor":100},"effectiveFrom":"${now.toIso8601String()}",'
      '"effectiveTo":"${expires.toIso8601String()}","priority":1,"ruleType":"AMOUNT_OFF",'
      '"ruleVersionId":"01K5R000000000000000000001","scope":{"skuIds":["101"]},'
      '"stackMode":"STACKABLE"}\n'
      '@MANUAL_POLICY|31|$policySha|$policy\n',
    ),
  );
  return _sign(keyPair, payload);
}

Future<_SignedArtifact> _sign(KeyPair keyPair, Uint8List payload) async {
  final signature = await Ed25519().sign(payload, keyPair: keyPair);
  return _SignedArtifact(
    payload,
    sha256.convert(payload).toString(),
    Uint8List.fromList(signature.bytes),
  );
}

String _escape(String value) => value
    .replaceAll(r'\', r'\\')
    .replaceAll('|', r'\p')
    .replaceAll('\r', r'\r')
    .replaceAll('\n', r'\n');

int _count(Database database, String table) =>
    database.select('SELECT COUNT(*) c FROM $table').single['c']! as int;

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
    deviceId: deviceId,
    deviceCredential: 'synthetic-device-credential-123456789012',
    deviceFingerprintSha256:
        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
    publicKeySha256:
        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
    appVersion: '1.0.0',
    protocolVersion: '1.0',
    schemaVersion: '11',
  );
}

final class _SyntheticEncryptor implements ApiRequestEncryptor {
  const _SyntheticEncryptor();

  @override
  EncryptedApiRequest encryptJson(Map<String, Object?> payload) =>
      const EncryptedApiRequest(
        body: 'synthetic-encrypted-login',
        encryptKey: 'synthetic-encrypted-key',
      );
}

final class _SignedArtifact {
  const _SignedArtifact(this.payload, this.sha256, this.signature);

  final Uint8List payload;
  final String sha256;
  final Uint8List signature;
}

final class _ObservedRequest {
  const _ObservedRequest({
    required this.method,
    required this.path,
    required this.tenantHeader,
    required this.authorization,
    required this.body,
  });

  final String method;
  final String path;
  final String? tenantHeader;
  final String? authorization;
  final List<int> body;
}
