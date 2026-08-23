import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/catalog/domain/lot_expiry.dart';
import 'package:jshpos_pos/features/catalog/infrastructure/lot_package_installer.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';
import 'package:sqlite3/sqlite3.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test('Dart FEFO 与 Java 规则一致且到期日当天可售', () {
    final allocations = LocalLotRules.allocateFefo(
      candidates: [
        candidate(
          '01K2A000000000000000000073',
          '2026-08-20',
          '2026-08-30',
          '1',
        ),
        candidate(
          '01K2A000000000000000000072',
          '2026-08-22',
          '2026-08-24',
          '0.6',
        ),
      ],
      requested: ExactLotQuantity.parse('1'),
      businessDate: DateTime.utc(2026, 8, 23),
    );
    expect(allocations.map((item) => item.lotId), [
      '01K2A000000000000000000072',
      '01K2A000000000000000000073',
    ]);
    expect(allocations.map((item) => item.quantity.canonical), ['0.6', '0.4']);
    expect(
      LocalLotRules.classify(
        DateTime.utc(2026, 8, 24),
        DateTime.utc(2026, 8, 24),
        0,
      ),
      'NEAR_EXPIRY',
    );
  });

  test('已验签包原子安装且旧包、跨租户和摘要漂移失败关闭', () async {
    final fixture = await LotFixture.create();
    addTearDown(fixture.close);
    expect(fixture.count('local_lot_policy'), 1);
    expect(fixture.count('local_lot_balance'), 2);
    expect(fixture.scalar('PRAGMA user_version'), 16);
    expect(
      () => fixture.database.database.execute(
        "UPDATE local_lot_balance SET expiry_date='2099-12-31' WHERE lot_id='01K2A000000000000000000072'",
      ),
      throwsA(isA<SqliteException>()),
    );

    final duplicate = await fixture.installer.install(fixture.envelope);
    expect(duplicate.duplicate, isTrue);
    final next = await fixture.envelopeFor(
      packageVersion: 2,
      previousVersion: 1,
    );
    expect((await fixture.installer.install(next)).packageVersion, 2);
    await expectLater(
      fixture.installer.install(fixture.envelope),
      throwsStateError,
    );
    final bad = LotPackageEnvelope(
      payload: fixture.envelope.payload,
      payloadSha256: repeat('0'),
      signature: fixture.envelope.signature,
      signingKeyId: fixture.envelope.signingKeyId,
    );
    await expectLater(fixture.installer.install(bad), throwsStateError);

    expect(
      () => fixture.database.database.execute(
        "UPDATE local_lot_package_binding SET tenant_id='TENANT_B' WHERE singleton_id=1",
      ),
      throwsA(isA<SqliteException>()),
    );

    final crossTenant = await LotFixture.create(
      installPackage: false,
      payloadTenantId: 'TENANT_B',
    );
    addTearDown(crossTenant.close);
    await expectLater(
      crossTenant.installer.install(crossTenant.envelope),
      throwsStateError,
    );

    final wrongTimezone = await crossTenant.envelopeFor(
      packageVersion: 1,
      previousVersion: 0,
      overrides: const {'tenantId': 'TENANT_A', 'businessZoneId': 'UTC'},
    );
    await expectLater(
      crossTenant.installer.install(wrongTimezone),
      throwsStateError,
    );
  });

  test('社区超市现金成交在一个事务冻结 FEFO、订单、现金和 lot Outbox', () async {
    final fixture = await LotFixture.create();
    addTearDown(fixture.close);
    final shift = fixture.openShift();
    final command = fixture.sale(shift.shiftId);

    final first = fixture.checkout.completeCashSale(command);
    final second = fixture.checkout.completeCashSale(command);

    expect(second.duplicate, isTrue);
    expect(second.orderId, first.orderId);
    expect(fixture.count('local_order_lot_allocation'), 2);
    expect(fixture.count('local_lot_ledger'), 2);
    expect(fixture.count('local_order_lot_snapshot'), 1);
    expect(
      fixture.countWhere(
        'local_outbox',
        "event_type='inventory.lot-sale.requested.v1'",
      ),
      1,
    );
    expect(
      fixture.values(
        'SELECT quantity_decimal FROM local_lot_balance ORDER BY expiry_date',
      ),
      ['0', '0.6'],
    );
  });

  test('批次快照写入失败时订单、现金、分配、流水和余额整体回滚', () async {
    var armed = false;
    final fixture = await LotFixture.create(
      failureInjector: (checkpoint) {
        if (armed && checkpoint == 'lot.sale.snapshot') {
          throw StateError('synthetic lot snapshot failure');
        }
      },
    );
    addTearDown(fixture.close);
    final shift = fixture.openShift();
    armed = true;

    expect(
      () => fixture.checkout.completeCashSale(fixture.sale(shift.shiftId)),
      throwsStateError,
    );
    expect(fixture.count('local_order'), 0);
    expect(fixture.count('local_cash_payment'), 0);
    expect(fixture.count('local_order_lot_allocation'), 0);
    expect(fixture.count('local_lot_ledger'), 0);
    expect(
      fixture.values(
        'SELECT quantity_decimal FROM local_lot_balance ORDER BY expiry_date',
      ),
      ['0.6', '1'],
    );
  });

  test('非社区超市模板绝不进入批次路径', () async {
    final fixture = await LotFixture.create(installPackage: false);
    addTearDown(fixture.close);
    final shift = fixture.openShift();
    final result = fixture.checkout.completeCashSale(
      fixture.sale(shift.shiftId, industry: 'CONVENIENCE_V1'),
    );
    expect(result.receivableAmountMinor, 100);
    expect(fixture.count('local_order_lot_snapshot'), 0);
  });
}

LocalLotCandidate candidate(
  String id,
  String received,
  String expiry,
  String quantity,
) => LocalLotCandidate(
  lotId: id,
  receivedDate: DateTime.parse('${received}T00:00:00Z'),
  expiryDate: DateTime.parse('${expiry}T00:00:00Z'),
  available: ExactLotQuantity.parse(quantity, allowZero: true),
  policyVersionId: '01K2A000000000000000000061',
);

final class LotFixture {
  LotFixture._(
    this.database,
    this.checkout,
    this.installer,
    this.envelope,
    this._algorithm,
    this._pair,
    this._payloadDocument,
  );

  static Future<LotFixture> create({
    FailureInjector? failureInjector,
    bool installPackage = true,
    String payloadTenantId = 'TENANT_A',
  }) async {
    final database = PosLocalDatabase.inMemory(
      binding,
      failureInjector: failureInjector,
    );
    final algorithm = Ed25519();
    final pair = await algorithm.newKeyPair();
    final publicKey = await pair.extractPublicKey();
    final payloadDocument = <String, Object?>{
      'schemaVersion': '1.0',
      'tenantId': payloadTenantId,
      'storeId': '1101',
      'warehouseId': '01K2A000000000000000000071',
      'industry': 'COMMUNITY_SUPERMARKET',
      'industryTemplateVersionId': '30',
      'industryTemplateSha256': repeat('0'),
      'businessZoneId': 'Asia/Shanghai',
      'businessDayStart': '04:00',
      'packageVersion': 1,
      'previousVersion': 0,
      'generatedAt': '2026-08-23T00:00:00.000Z',
      'policies': [
        {
          'policyVersionId': '01K2A000000000000000000061',
          'skuId': '701',
          'enabled': true,
          'expiryBasis': 'EXPLICIT_EXPIRY_DATE',
          'shelfLifeDays': null,
          'nearExpiryDays': 3,
          'effectiveFrom': '2026-08-01T00:00:00.000Z',
          'contentSha256': repeat('1'),
        },
      ],
      'lots': [
        lot('01K2A000000000000000000072', '2026-08-24', '0.6', repeat('2')),
        lot('01K2A000000000000000000073', '2026-08-30', '1', repeat('3')),
      ],
    };
    final payload = Uint8List.fromList(
      utf8.encode(jsonEncode(payloadDocument)),
    );
    final signature = await algorithm.sign(payload, keyPair: pair);
    final envelope = LotPackageEnvelope(
      payload: payload,
      payloadSha256: sha256.convert(payload).toString(),
      signature: Uint8List.fromList(signature.bytes),
      signingKeyId: 'synthetic-lot-key-v1',
    );
    final installer = LotPackageInstaller(
      database,
      trustedSigningKeys: {'synthetic-lot-key-v1': publicKey},
      utcNow: () => DateTime.utc(2026, 8, 23, 8),
    );
    if (installPackage) await installer.install(envelope);
    final now = DateTime.utc(2026, 8, 23, 9);
    final ulids = UlidGenerator(random: Random(20260823), now: () => now);
    return LotFixture._(
      database,
      CheckoutLocalService(
        localDatabase: database,
        ulids: ulids,
        shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 0),
      ),
      installer,
      envelope,
      algorithm,
      pair,
      payloadDocument,
    );
  }

  final PosLocalDatabase database;
  final CheckoutLocalService checkout;
  final LotPackageInstaller installer;
  final LotPackageEnvelope envelope;
  final Ed25519 _algorithm;
  final KeyPair _pair;
  final Map<String, Object?> _payloadDocument;

  Future<LotPackageEnvelope> envelopeFor({
    required int packageVersion,
    required int previousVersion,
    Map<String, Object?> overrides = const {},
  }) async {
    final document = Map<String, Object?>.from(_payloadDocument)
      ..['packageVersion'] = packageVersion
      ..['previousVersion'] = previousVersion
      ..addAll(overrides);
    final payload = Uint8List.fromList(utf8.encode(jsonEncode(document)));
    final signature = await _algorithm.sign(payload, keyPair: _pair);
    return LotPackageEnvelope(
      payload: payload,
      payloadSha256: sha256.convert(payload).toString(),
      signature: Uint8List.fromList(signature.bytes),
      signingKeyId: envelope.signingKeyId,
    );
  }

  ShiftResult openShift() => checkout.openShift(
    commandId: '01K2A000000000000000000021',
    idempotencyKey: 'lot-open-shift-key-0001',
    businessDate: '2026-08-23',
    openingCashMinor: 0,
    configVersion: 1,
    occurredAt: DateTime.utc(2026, 8, 23, 9),
  );

  CashSaleCommand sale(
    String shiftId, {
    String industry = 'COMMUNITY_SUPERMARKET_V1',
  }) => CashSaleCommand(
    commandId: '01K2A000000000000000000051',
    idempotencyKey: 'lot-cash-order-key-0001',
    basket: Basket(
      orderId: '01K2A000000000000000000031',
      localOrderNo: 'LOT-A-000001',
      lines: [
        BasketLine(
          lineId: '01K2A000000000000000000041',
          lineNo: 1,
          quote: PriceQuote.fromVerifiedPackage(
            skuId: '701',
            skuCode: 'LOT-SKU-001',
            productName: 'Synthetic Yogurt',
            unitId: '301',
            unitCode: 'PCS',
            unitPriceMinor: 100,
            priceSource: 'TENANT_BASE',
            barcode: '000701',
          ),
          quantity: '1',
        ),
      ],
    ),
    shiftId: shiftId,
    businessDate: '2026-08-23',
    catalogVersion: 1,
    priceVersion: 1,
    industryTemplateVersion: industry,
    tenderedAmountMinor: 100,
    occurredAt: DateTime.utc(2026, 8, 23, 9, 5),
  );

  int count(String table) =>
      database.database.select('SELECT COUNT(*) AS c FROM $table').single['c']!
          as int;
  int countWhere(String table, String where) =>
      database.database
              .select('SELECT COUNT(*) AS c FROM $table WHERE $where')
              .single['c']!
          as int;
  Object? scalar(String sql) =>
      database.database.select(sql).single.values.first;
  List<Object?> values(String sql) =>
      database.database.select(sql).map((row) => row.values.first).toList();
  void close() => database.close();
}

Map<String, Object?> lot(
  String id,
  String expiry,
  String quantity,
  String hash,
) => {
  'lotId': id,
  'skuId': '701',
  'baseUnitId': '301',
  'supplierLotCode': 'SUP-${id.substring(22)}',
  'internalLotCode': 'INT-${id.substring(22)}',
  'productionDate': '2026-08-20',
  'receivedDate': '2026-08-22',
  'expiryDate': expiry,
  'policyVersionId': '01K2A000000000000000000060',
  'nearExpiryDays': 3,
  'quantity': quantity,
  'lastLedgerSequence': 0,
  'sourceSha256': hash,
};

String repeat(String value) => List.filled(64, value).join();
