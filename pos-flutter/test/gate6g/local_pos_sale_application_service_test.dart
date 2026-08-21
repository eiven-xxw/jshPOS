import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/catalog/infrastructure/catalog_package_installer.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/promotion/application/local_manual_adjustment_service.dart';
import 'package:jshpos_pos/features/promotion/application/local_promotion_quote_service.dart';
import 'package:jshpos_pos/features/promotion/domain/manual_adjustment_engine.dart';
import 'package:jshpos_pos/features/promotion/domain/promotion_engine.dart';
import 'package:jshpos_pos/features/promotion/infrastructure/promotion_package_installer.dart';
import 'package:jshpos_pos/features/sale/infrastructure/local_pos_sale_application_service.dart';
import 'package:jshpos_pos/features/sale/domain/pos_sale_models.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
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
    'formal POS service closes scan promotion manual cash and print path',
    () async {
      final fixture = await _Fixture.create();
      addTearDown(fixture.close);

      final initial = await fixture.service.loadWorkspace();
      final quoted = await fixture.service.scanBarcode('6900000000001');
      final adjusted = await fixture.service.applyManualAdjustment(
        actionCode: 'ORDER_AMOUNT_OFF',
        value: '50',
      );
      final settled = await fixture.service.settleCash(
        tenderedAmount: '3.00',
        idempotencyKey: 'cash:${adjusted.saleRef}:${adjusted.quoteFingerprint}',
      );
      final preview = await fixture.service.previewPrintTask(settled.orderRef);
      final reprint = await fixture.service.requestReceiptReprint(
        orderRef: settled.orderRef,
        reasonCode: 'CUSTOMER_COPY',
        reasonText: '顾客要求补打',
        idempotencyKey: 'reprint:${settled.orderRef}:0001',
      );
      final reprintPreview = await fixture.service.previewPrintTask(
        settled.orderRef,
      );

      expect(initial.lines, isEmpty);
      expect(quoted.totals.grossAmountMinor, 299);
      expect(quoted.totals.discountAmountMinor, 100);
      expect(adjusted.totals.discountAmountMinor, 150);
      expect(adjusted.manualAuthorizationRef, isNotNull);
      expect(settled.receivableAmountMinor, 149);
      expect(settled.changeAmountMinor, 151);
      expect(preview.lines.single, contains('合成柠檬水'));
      expect(preview.adapterEvidence, contains('BLOCKED_REAL_PRINTER'));
      expect(preview.adapterEvidence, startsWith('SOFTWARE_PREVIEW'));
      expect(reprint.executionStatus, 'BLOCKED_EXTERNAL');
      expect(reprintPreview.reprintNo, 1);
      expect(reprintPreview.title, contains('补打 #1'));
      expect(fixture.count('local_order'), 1);
      expect(fixture.count('local_promotion_manual_event'), 1);
      expect(fixture.count('local_promotion_transaction_snapshot'), 1);
      expect(fixture.count('local_cash_payment'), 1);
      expect(fixture.count('local_outbox'), greaterThanOrEqualTo(3));
    },
  );

  test(
    'formal POS hold and resume use Checkout facts and revalidate price',
    () async {
      final fixture = await _Fixture.create();
      addTearDown(fixture.close);

      await fixture.service.loadWorkspace();
      final sale = await fixture.service.scanBarcode('6900000000001');
      final afterHold = await fixture.service.holdCurrentSale();
      final resumed = await fixture.service.resumeHeldSale(sale.saleRef);

      expect(afterHold.lines, isEmpty);
      expect(afterHold.heldSales.single.saleRef, sale.saleRef);
      expect(resumed.saleRef, sale.saleRef);
      expect(resumed.lines.single.receivableAmountMinor, 199);
      expect(resumed.heldSales, isEmpty);
    },
  );

  test('formal product search quantity and sync branches stay inside application service', () async {
    final fixture = await _Fixture.create();
    addTearDown(fixture.close);

    await fixture.service.loadWorkspace();
    final products = await fixture.service.searchProducts('柠檬');
    var workspace = await fixture.service.addProduct(
      products.single.productRef,
    );
    workspace = await fixture.service.addProduct(products.single.productRef);
    expect(workspace.lines.single.quantity, '2');

    workspace = await fixture.service.changeQuantity(
      workspace.lines.single.lineRef,
      '-1',
    );
    expect(workspace.lines.single.quantity, '1');
    workspace = await fixture.service.changeQuantity(
      workspace.lines.single.lineRef,
      '0',
    );
    expect(workspace.lines, isEmpty);
    expect((await fixture.service.refreshPromotionQuote()).lines, isEmpty);

    final sync = await fixture.service.refreshSyncStatus();
    expect(sync.syncStatus.online, isFalse);
    expect(sync.syncStatus.safeMessage, contains('Outbox'));
    await expectLater(
      fixture.service.changeQuantity('missing-line', '1'),
      throwsA(
        isA<PosSaleFailure>().having(
          (error) => error.code,
          'code',
          'SALE_LINE_NOT_FOUND',
        ),
      ),
    );
    await expectLater(
      fixture.service.previewPrintTask('01K2A000000000000000009999'),
      throwsA(
        isA<PosSaleFailure>().having(
          (error) => error.code,
          'code',
          'RECEIPT_NOT_FOUND',
        ),
      ),
    );
  });

  test(
    'Gate 6H synthetic POS scan baseline executes 1000 formal scans',
    () async {
      final fixture = await _Fixture.create();
      addTearDown(fixture.close);
      await fixture.service.loadWorkspace();

      final stopwatch = Stopwatch()..start();
      PosSaleWorkspace? workspace;
      for (var index = 0; index < 1000; index++) {
        workspace = await fixture.service.scanBarcode('6900000000001');
      }
      stopwatch.stop();

      expect(workspace?.lines.single.quantity, '1000');
      // 只输出合成执行器趋势，禁止解释为真实扫码枪或 Android 性能。
      debugPrint(
        'GATE6H_METRIC pos_scan_1000_ms=${stopwatch.elapsedMilliseconds}',
      );
    },
  );

  test(
    'Gate 6H synthetic POS settlement baseline commits 200 cash orders',
    () async {
      final fixture = await _Fixture.create();
      addTearDown(fixture.close);
      await fixture.service.loadWorkspace();

      final stopwatch = Stopwatch()..start();
      for (var index = 0; index < 200; index++) {
        final workspace = await fixture.service.scanBarcode('6900000000001');
        await fixture.service.settleCash(
          tenderedAmount: '3.00',
          idempotencyKey: 'gate6h-cash:${workspace.saleRef}:$index',
        );
      }
      stopwatch.stop();

      expect(fixture.count('local_order'), 200);
      expect(fixture.count('local_cash_payment'), 200);
      debugPrint(
        'GATE6H_METRIC pos_settlement_200_ms=${stopwatch.elapsedMilliseconds}',
      );
    },
  );
}

final class _Fixture {
  _Fixture(this.database, this.service);

  final PosLocalDatabase database;
  final LocalPosSaleApplicationService service;

  static Future<_Fixture> create() async {
    final database = PosLocalDatabase.inMemory(binding);
    final ulids = UlidGenerator(random: Random(19), now: () => fixedNow);
    final checkout = CheckoutLocalService(
      localDatabase: database,
      ulids: ulids,
      shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 500),
    );
    checkout.openShift(
      commandId: ulids.next(),
      idempotencyKey: 'open-shift:gate6g-001',
      businessDate: '2026-08-21',
      openingCashMinor: 10000,
      configVersion: 1,
      occurredAt: fixedNow,
    );
    final keyPair = await Ed25519().newKeyPair();
    final publicKey = await keyPair.extractPublicKey();
    final catalog = CatalogPackageInstaller(
      database,
      trustedSigningKeys: {'SYNTHETIC_KEY': publicKey},
      utcNow: () => fixedNow,
    );
    await catalog.install(await _catalogEnvelope(keyPair));
    final promotionInstaller = PromotionPackageInstaller(
      database,
      trustedSigningKeys: {'SYNTHETIC_KEY': publicKey},
      utcNow: () => fixedNow,
    );
    await promotionInstaller.install(await _promotionEnvelope(keyPair));
    final quotes = LocalPromotionQuoteService(
      database: database,
      packageInstaller: promotionInstaller,
      engine: PromotionEngine(),
      ulids: ulids,
    );
    final manuals = LocalManualAdjustmentService(
      database: database,
      packageInstaller: promotionInstaller,
      engine: ManualAdjustmentEngine(),
      approvalPort: const RejectingManualApprovalPort(),
      ulids: ulids,
      now: () => fixedNow,
    );
    return _Fixture(
      database,
      LocalPosSaleApplicationService(
        database: database,
        catalog: catalog,
        promotions: quotes,
        manualAdjustments: manuals,
        checkout: checkout,
        ulids: ulids,
        industryTemplateVersion: 'CONVENIENCE_V1',
        permissions: const {
          PosPermission.printPreview,
          PosPermission.printReprint,
        },
        authorizationRef: 'SYNTHETIC_SESSION_REF_0001',
        now: () => fixedNow,
      ),
    );
  }

  int count(String table) =>
      database.database.select('SELECT COUNT(*) c FROM $table').single['c']!
          as int;

  void close() => database.close();
}

Future<CatalogPackageEnvelope> _catalogEnvelope(KeyPair keyPair) async {
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
    'effectiveFrom': '2026-08-01T00:00:00.000000Z',
    'effectiveTo': null,
  });
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHCAT|1.0|TENANT_A|1101|1|0|2026-08-21T01:00:00Z\n'
      'PRICE|000000000|${_escape(price)}\n'
      'PRODUCT|000000000|${_escape(product)}\n',
    ),
  );
  final signature = await Ed25519().sign(payload, keyPair: keyPair);
  return CatalogPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: 'SYNTHETIC_KEY',
  );
}

Future<PromotionPackageEnvelope> _promotionEnvelope(KeyPair keyPair) async {
  final expires = fixedNow.add(const Duration(days: 1));
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
      'JSHPRM|1.0|promotion-engine-1.0.0|TENANT_A|1101|1|0|${fixedNow.toIso8601String()}|${expires.toIso8601String()}\n'
      '01K5R000000000000000000001|'
      '{"benefit":{"amountMinor":100},"effectiveFrom":"${fixedNow.toIso8601String()}",'
      '"effectiveTo":"${expires.toIso8601String()}","priority":1,"ruleType":"AMOUNT_OFF",'
      '"ruleVersionId":"01K5R000000000000000000001","scope":{"skuIds":["101"]},'
      '"stackMode":"STACKABLE"}\n'
      '@MANUAL_POLICY|31|$policySha|$policy\n',
    ),
  );
  final signature = await Ed25519().sign(payload, keyPair: keyPair);
  return PromotionPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: 'SYNTHETIC_KEY',
  );
}

String _escape(String value) => value
    .replaceAll(r'\', r'\\')
    .replaceAll('|', r'\p')
    .replaceAll('\r', r'\r')
    .replaceAll('\n', r'\n');
