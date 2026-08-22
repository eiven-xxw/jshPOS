import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/catalog/domain/weighted_barcode.dart';
import 'package:jshpos_pos/features/catalog/infrastructure/catalog_package_installer.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';
import 'package:sqlite3/sqlite3.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: '虚构收银员甲',
  storeTimezone: 'Asia/Shanghai',
);
final fixedNow = DateTime.parse('2026-08-22T01:00:00Z');
const templateHash =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

void main() {
  test('Java 与 Dart 共同消费同一份秤码金额金标', () {
    final root = jsonDecode(
      File(
        '../contracts/t2/gate7c-prd005/weighted-barcode-golden-vectors-v1.json',
      ).readAsStringSync(),
    ) as Map<String, Object?>;
    final cases = root['cases']! as List<Object?>;
    for (final entry in cases.cast<Map<String, Object?>>()) {
      final expected = entry['expected']! as Map<String, Object?>;
      final actual = WeightedBarcodeParser.parse(
        template: template(
          kind: entry['kind']! as String,
          prefix: entry['prefix']! as String,
          scale: entry['valueScale']! as int,
          templateId: '${entry['templateId']}',
          templateCode: entry['templateCode']! as String,
        ),
        rawBarcode: entry['rawBarcode']! as String,
        unitPriceMinor: entry['unitPriceMinor']! as int,
        unitDecimalScale: entry['unitDecimalScale']! as int,
        occurredAt: DateTime.parse(entry['occurredAt']! as String),
      );
      expect(actual.skuCode, expected['skuCode']);
      expect(actual.quantity, expected['quantity']);
      expect(actual.amountMinor, expected['amountMinor']);
      expect(actual.roundingApplied, expected['roundingApplied']);
      expect(actual.parseSha256, expected['parseSha256']);
    }
  });

  test('纯领域解析器与 Java 固定向量一致并按 HALF_EVEN 计算', () {
    final weight = WeightedBarcodeParser.parse(
      template: template(kind: 'WEIGHT', prefix: '22', scale: 3),
      rawBarcode: ean13('220012300250'),
      unitPriceMinor: 1990,
      unitDecimalScale: 3,
      occurredAt: fixedNow,
    );
    expect(weight.skuCode, '00123');
    expect(weight.quantity, '0.25');
    expect(weight.amountMinor, 498);
    expect(weight.roundingApplied, isTrue);
    expect(weight.parseSha256, hasLength(64));

    final amount = WeightedBarcodeParser.parse(
      template: template(kind: 'AMOUNT', prefix: '23', scale: 2),
      rawBarcode: ean13('230012301234'),
      unitPriceMinor: 1990,
      unitDecimalScale: 3,
      occurredAt: fixedNow,
    );
    expect(amount.quantity, '0.62');
    expect(amount.amountMinor, 1234);
    expect(amount.roundingApplied, isTrue);
  });

  test('签名数据包原子安装模板并冻结成交计量快照', () async {
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

    final installed = await installer.install(await envelope(keyPair));
    final barcode = ean13('220012300250');
    final resolved = installer.resolveBarcode(barcode, at: fixedNow);
    final line = BasketLine(
      lineId: '01K2A000000000000000000099',
      lineNo: 1,
      quote: resolved.toCheckoutQuote(),
      quantity: resolved.measuredSnapshot!.quantity,
    );

    expect(installed.recordCount, 3);
    expect(resolved.product.skuCode, '00123');
    expect(resolved.measuredSnapshot!.templateVersion, 1);
    expect(line.quantity.canonical, '0.25');
    expect(line.grossAmountMinor, 498);
    expect(
      line.toSnapshot()['measuredBarcodeSnapshot'],
      isA<Map<String, Object?>>(),
    );
    expect(
      database.database
          .select('SELECT COUNT(*) c FROM local_weighted_barcode_template')
          .single['c'],
      1,
    );
    expect(
      () => BasketLine(
        lineId: '01K2A000000000000000000098',
        lineNo: 1,
        quote: resolved.toCheckoutQuote(),
        quantity: '1',
      ),
      throwsFormatException,
    );

    final checkout = CheckoutLocalService(
      localDatabase: database,
      ulids: UlidGenerator(random: Random(21), now: () => fixedNow),
      shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 0),
    );
    final shift = checkout.openShift(
      commandId: '01K2A000000000000000000071',
      idempotencyKey: 'prd005-open-shift-0001',
      businessDate: '2026-08-22',
      openingCashMinor: 0,
      configVersion: 1,
      occurredAt: fixedNow,
    );
    checkout.completeCashSale(
      CashSaleCommand(
        commandId: '01K2A000000000000000000072',
        idempotencyKey: 'prd005-cash-order-0001',
        basket: Basket(
          orderId: '01K2A000000000000000000073',
          localOrderNo: 'SYN-PRD005-001',
          lines: [line],
        ),
        shiftId: shift.shiftId,
        businessDate: '2026-08-22',
        catalogVersion: 1,
        priceVersion: 1,
        industryTemplateVersion: 'CONVENIENCE.1',
        tenderedAmountMinor: 500,
        occurredAt: fixedNow,
      ),
    );
    final persisted = database.database
        .select(
          'SELECT measurement_snapshot_json,measurement_parse_sha256 FROM local_order_line',
        )
        .single;
    expect(
      (jsonDecode(persisted['measurement_snapshot_json']! as String)
          as Map<String, Object?>)['rawBarcode'],
      barcode,
    );
    expect(persisted['measurement_parse_sha256'], hasLength(64));
  });

  test('校验位错误与模板歧义失败关闭且不回退普通条码', () async {
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
    await installer.install(await envelope(keyPair));
    final valid = ean13('220012300250');
    final bad = '${valid.substring(0, 12)}${(int.parse(valid[12]) + 1) % 10}';
    expect(() => installer.resolveBarcode(bad), throwsStateError);
    database.database.execute('''INSERT INTO local_weighted_barcode_template
         SELECT tenant_id,store_id,package_version,'502','SCALE-WEIGHT-B',version_no,scope_type,
           scope_store_id,barcode_kind,symbology,prefix_value,total_length,sku_start_pos,sku_length,
           value_start_pos,value_length,value_scale,priority_no,effective_from,effective_to,content_sha256
         FROM local_weighted_barcode_template WHERE template_id='501' ''');
    expect(() => installer.resolveBarcode(valid), throwsStateError);
  });

  test('过期模板、精度越界和金额码舍入均按确定性规则失败或收敛', () {
    final expired = template(kind: 'WEIGHT', prefix: '22', scale: 3);
    final bounded = WeightedBarcodeTemplate(
      templateId: expired.templateId,
      templateCode: expired.templateCode,
      versionNo: expired.versionNo,
      scopeType: expired.scopeType,
      storeId: expired.storeId,
      barcodeKind: expired.barcodeKind,
      symbology: expired.symbology,
      prefixValue: expired.prefixValue,
      totalLength: expired.totalLength,
      skuStartPos: expired.skuStartPos,
      skuLength: expired.skuLength,
      valueStartPos: expired.valueStartPos,
      valueLength: expired.valueLength,
      valueScale: expired.valueScale,
      priorityNo: expired.priorityNo,
      effectiveFrom: expired.effectiveFrom,
      effectiveTo: DateTime.parse('2026-08-21T00:00:00Z'),
      contentSha256: expired.contentSha256,
    );
    expect(
      () => WeightedBarcodeParser.parse(
        template: bounded,
        rawBarcode: ean13('220012300250'),
        unitPriceMinor: 1990,
        unitDecimalScale: 3,
        occurredAt: fixedNow,
      ),
      throwsStateError,
    );
    expect(
      () => WeightedBarcodeParser.parse(
        template: template(kind: 'WEIGHT', prefix: '22', scale: 4),
        rawBarcode: ean13('220012300251'),
        unitPriceMinor: 1990,
        unitDecimalScale: 3,
        occurredAt: fixedNow,
      ),
      throwsStateError,
    );
    expect(
      () => WeightedBarcodeParser.parse(
        template: template(kind: 'AMOUNT', prefix: '23', scale: 3),
        rawBarcode: ean13('230012301234'),
        unitPriceMinor: 100,
        unitDecimalScale: 3,
        occurredAt: fixedNow,
      ),
      throwsStateError,
    );
  });

  test('SQLite v14 迁移中断不留下半张模板表且可安全前向重试', () async {
    final directory = Directory.systemTemp.createTempSync('jshpos-prd005-');
    final path = '${directory.path}${Platform.pathSeparator}pos.db';
    try {
      expect(
        () => PosLocalDatabase.openPath(
          path,
          binding,
          failureInjector: (checkpoint) {
            if (checkpoint == 'migration.v14.before-version') {
              throw StateError('synthetic v14 interruption');
            }
          },
        ),
        throwsStateError,
      );
      final verify = sqlite3.open(path);
      expect(verify.select('PRAGMA user_version').single.values.first, 13);
      expect(
        verify
            .select(
              "SELECT COUNT(*) c FROM sqlite_master WHERE type='table' AND name='local_weighted_barcode_template'",
            )
            .single['c'],
        0,
      );
      verify.close();

      final recovered = PosLocalDatabase.openPath(path, binding);
      expect(
        recovered.database.select('PRAGMA user_version').single.values.first,
        14,
      );
      recovered.close();
    } finally {
      await deleteTemporaryDirectory(directory);
    }
  });
}

WeightedBarcodeTemplate template({
  required String kind,
  required String prefix,
  required int scale,
  String templateId = '501',
  String? templateCode,
}) => WeightedBarcodeTemplate(
  templateId: templateId,
  templateCode: templateCode ?? 'SCALE-$kind',
  versionNo: 1,
  scopeType: 'TENANT',
  storeId: null,
  barcodeKind: kind,
  symbology: 'EAN13',
  prefixValue: prefix,
  totalLength: 13,
  skuStartPos: 3,
  skuLength: 5,
  valueStartPos: 8,
  valueLength: 5,
  valueScale: scale,
  priorityNo: 10,
  effectiveFrom: DateTime.parse('2026-01-01T00:00:00Z'),
  effectiveTo: null,
  contentSha256: templateHash,
);

Future<CatalogPackageEnvelope> envelope(KeyPair keyPair) async {
  final product = jsonEncode({
    'skuId': '101',
    'skuCode': '00123',
    'name': '合成称重苹果',
    'productType': 'WEIGHT',
    'status': 'ACTIVE',
    'categoryId': '401',
    'brandId': null,
    'unitId': '301',
    'unitCode': 'KG',
    'unitName': '千克',
    'decimalScale': 3,
    'ratioNumerator': 1,
    'ratioDenominator': 1,
    'barcode': null,
  });
  final price = jsonEncode({
    'priceBookId': '201',
    'bookCode': 'BASE',
    'versionNo': 1,
    'scopeType': 'TENANT_BASE',
    'storeId': null,
    'skuId': '101',
    'unitId': '301',
    'amountMinor': 1990,
    'currency': 'CNY',
    'effectiveFrom': '2026-01-01T00:00:00.000000Z',
    'effectiveTo': null,
  });
  final weighted = jsonEncode({
    'templateId': '501',
    'templateCode': 'SCALE-WEIGHT',
    'versionNo': 1,
    'scopeType': 'TENANT',
    'storeId': null,
    'barcodeKind': 'WEIGHT',
    'symbology': 'EAN13',
    'prefixValue': '22',
    'totalLength': 13,
    'skuStartPos': 3,
    'skuLength': 5,
    'valueStartPos': 8,
    'valueLength': 5,
    'valueScale': 3,
    'priorityNo': 10,
    'effectiveFrom': '2026-01-01T00:00:00.000000Z',
    'effectiveTo': null,
    'contentSha256': templateHash,
  });
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHCAT|1.0|TENANT_A|1101|1|0|2026-08-22T00:00:00Z\n'
      'PRICE|000000000|${escape(price)}\n'
      'PRODUCT|000000000|${escape(product)}\n'
      'WEIGHT_BARCODE_TEMPLATE|000000000|${escape(weighted)}\n',
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

String ean13(String firstTwelve) =>
    '$firstTwelve${WeightedBarcodeParser.checkDigit(firstTwelve)}';

String escape(String value) => value
    .replaceAll(r'\', r'\\')
    .replaceAll('|', r'\p')
    .replaceAll('\r', r'\r')
    .replaceAll('\n', r'\n');

Future<void> deleteTemporaryDirectory(Directory directory) async {
  for (var attempt = 0; attempt < 20; attempt += 1) {
    try {
      await directory.delete(recursive: true);
      return;
    } on FileSystemException {
      if (attempt == 19) rethrow;
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
  }
}
