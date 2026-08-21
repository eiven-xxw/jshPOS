import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/catalog/infrastructure/catalog_package_installer.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);
final fixedNow = DateTime.parse('2026-08-21T02:00:00Z');

void main() {
  test(
    'signed JSHCAT package switches atomically and resolves store price',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final keyPair = await Ed25519().newKeyPair();
      final installer = CatalogPackageInstaller(
        database,
        trustedSigningKeys: {
          'SYNTHETIC_CATALOG_KEY': await keyPair.extractPublicKey(),
        },
        utcNow: () => fixedNow,
      );
      final signed = await envelope(keyPair);

      final installed = await installer.install(signed);
      final replay = await installer.install(signed);
      final price = installer.resolveBarcode('6900000000001', at: fixedNow);

      expect(installed.packageVersion, 1);
      expect(installed.recordCount, 3);
      expect(replay.duplicate, isTrue);
      expect(price.product.name, '合成柠檬水');
      expect(price.amountMinor, 299);
      expect(price.priceSource, 'STORE_OVERRIDE');
      expect(price.catalogVersion, 1);
      expect(price.priceVersion, 2);
      expect(installer.search('柠檬', at: fixedNow), hasLength(1));
      expect(
        database.database
            .select(
              "SELECT state FROM local_catalog_package_slot WHERE package_version=1",
            )
            .single['state'],
        'ACTIVE',
      );
    },
  );

  test(
    'digest mismatch and cross-tenant package fail without partial rows',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final keyPair = await Ed25519().newKeyPair();
      final installer = CatalogPackageInstaller(
        database,
        trustedSigningKeys: {
          'SYNTHETIC_CATALOG_KEY': await keyPair.extractPublicKey(),
        },
        utcNow: () => fixedNow,
      );
      final signed = await envelope(keyPair, tenantId: 'TENANT_B');

      await expectLater(installer.install(signed), throwsStateError);
      expect(
        database.database
            .select('SELECT COUNT(*) value FROM local_catalog_package_slot')
            .single['value'],
        0,
      );
    },
  );
}

Future<CatalogPackageEnvelope> envelope(
  KeyPair keyPair, {
  String tenantId = 'TENANT_A',
}) async {
  final priceBase = jsonEncode({
    'priceBookId': '201',
    'bookCode': 'BASE',
    'versionNo': 1,
    'scopeType': 'TENANT_BASE',
    'storeId': null,
    'skuId': '101',
    'unitId': '301',
    'amountMinor': 399,
    'currency': 'CNY',
    'effectiveFrom': '2026-08-01T00:00:00.000000Z',
    'effectiveTo': null,
  });
  final priceStore = jsonEncode({
    'priceBookId': '202',
    'bookCode': 'STORE',
    'versionNo': 2,
    'scopeType': 'STORE',
    'storeId': '1101',
    'skuId': '101',
    'unitId': '301',
    'amountMinor': 299,
    'currency': 'CNY',
    'effectiveFrom': '2026-08-01T00:00:00.000000Z',
    'effectiveTo': null,
  });
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
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHCAT|1.0|$tenantId|1101|1|0|2026-08-21T01:00:00Z\n'
      'PRICE|000000000|${escape(priceBase)}\n'
      'PRICE|000000001|${escape(priceStore)}\n'
      'PRODUCT|000000000|${escape(product)}\n',
    ),
  );
  final signature = await Ed25519().sign(payload, keyPair: keyPair);
  return CatalogPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: 'SYNTHETIC_CATALOG_KEY',
  );
}

String escape(String value) => value
    .replaceAll(r'\', r'\\')
    .replaceAll('|', r'\p')
    .replaceAll('\r', r'\r')
    .replaceAll('\n', r'\n');
