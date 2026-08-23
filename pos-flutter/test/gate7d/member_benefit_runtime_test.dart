import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/promotion/application/local_promotion_quote_service.dart';
import 'package:jshpos_pos/features/promotion/domain/member_benefit_engine.dart';
import 'package:jshpos_pos/features/promotion/domain/promotion_engine.dart';
import 'package:jshpos_pos/features/promotion/infrastructure/member_benefit_package_installer.dart';
import 'package:jshpos_pos/features/promotion/infrastructure/promotion_package_installer.dart';
import 'package:jshpos_pos/infrastructure/local_database/member_cache_store.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);
final now = DateTime.parse('2026-08-23T02:00:00Z');

void main() {
  test('BEST_PRICE ties choose normal and double opt-in stacking conserves every line', () {
    final line = PromotionLine(
      lineId: '01K7M000000000000000000001',
      lineNo: 1,
      skuId: '101',
      categoryId: null,
      brandId: null,
      quantity: ExactDecimal.parse('1.000000'),
      unitPriceMinor: 1000,
    );
    final entitlement = MemberBenefitEntitlement(
      entitlementSnapshotId: '01K7E000000000000000000001',
      benefitVersionId: '01K7B000000000000000000001',
      levelCode: 'GOLD',
      memberPriceEligible: true,
      stackingAllowed: false,
      policyAllowStacking: false,
      defaultCombinationPolicy: 'BEST_PRICE',
      revocationEpoch: 0,
      rightsDigest: _hash('d'),
    );
    final engine = MemberBenefitEngine();
    final tie = engine.combine(
      capabilityEnabled: true,
      entitlement: entitlement,
      lines: [
        MemberBenefitLineInput(
          line: line,
          normalDiscountMinor: 100,
          memberPriceMinor: 900,
          memberPriceVersionId: '01K7P000000000000000000001',
        ),
      ],
    );
    expect(tie.selectedPath, 'NORMAL_PATH');
    final stacked = engine.combine(
      capabilityEnabled: true,
      entitlement: MemberBenefitEntitlement(
        entitlementSnapshotId: entitlement.entitlementSnapshotId,
        benefitVersionId: entitlement.benefitVersionId,
        levelCode: 'GOLD',
        memberPriceEligible: true,
        stackingAllowed: true,
        policyAllowStacking: true,
        defaultCombinationPolicy: 'BEST_PRICE',
        revocationEpoch: 0,
        rightsDigest: _hash('d'),
      ),
      lines: [
        MemberBenefitLineInput(
          line: line,
          normalDiscountMinor: 100,
          memberPriceMinor: 800,
          memberPriceVersionId: '01K7P000000000000000000001',
        ),
      ],
    );
    expect(stacked.selectedPath, 'STACKED_MEMBER_PATH');
    expect(stacked.discountAmountMinor, 300);
    expect(
      stacked.grossAmountMinor - stacked.discountAmountMinor,
      stacked.payableAmountMinor,
    );
  });

  test('signed member package atomically installs and quote freezes minimal snapshot', () async {
    final db = PosLocalDatabase.inMemory(binding);
    addTearDown(db.close);
    final pair = await Ed25519().newKeyPair();
    final key = await pair.extractPublicKey();
    final memberInstaller = MemberBenefitPackageInstaller(
      db,
      trustedSigningKeys: {'TEST_KEY': key},
      utcNow: () => now,
    );
    final installed = await memberInstaller.install(
      await _memberEnvelope(pair),
    );
    expect(installed.benefitCount, 1);
    expect(installed.memberPriceCount, 1);
    final promotionInstaller = PromotionPackageInstaller(
      db,
      trustedSigningKeys: {'TEST_KEY': key},
      utcNow: () => now,
    );
    await promotionInstaller.install(await _promotionEnvelope(pair));
    final cache = MemberCacheStore(db);
    cache.upsert(
      MemberCacheEntry(
        tenantId: 'TENANT_A',
        storeId: '1101',
        memberRef: '01K7R000000000000000000001',
        memberToken: 'opaque-token',
        maskedLabel: '会员***01',
        levelCode: 'GOLD',
        rightsDigest: _hash('d'),
        snapshotVersion: 1,
        expiresAt: now.add(const Duration(hours: 6)),
        receivedAt: now,
        entitlementSnapshotId: '01K7E000000000000000000001',
      ),
    );
    final member = cache.resolve('opaque-token', now)!;
    final service = LocalPromotionQuoteService(
      database: db,
      packageInstaller: promotionInstaller,
      engine: PromotionEngine(),
      ulids: UlidGenerator(random: Random(73), now: () => now),
      memberBenefitPackageInstaller: memberInstaller,
      memberBenefitEngine: MemberBenefitEngine(),
    );
    final line = PromotionLine(
      lineId: '01K7A000000000000000000001',
      lineNo: 1,
      skuId: '101',
      categoryId: null,
      brandId: null,
      quantity: ExactDecimal.parse('1.000000'),
      unitPriceMinor: 1000,
    );
    final result = await service.quote(
      pricingRequestId: '01K7Q000000000000000000001',
      businessTime: now,
      channel: 'POS',
      lines: [line],
      memberBenefitEnabled: true,
      member: member,
      capabilityConfigVersion: 31,
      capabilitySha256: _hash('c'),
      unitIdsByLine: {line.lineId: '201'},
    );
    expect(result.quote.payableAmountMinor, 800);
    expect(result.memberBenefitSnapshot!.selectedPath, 'MEMBER_PATH');
    expect(
      db.database.select('SELECT * FROM local_promotion_quote_member_benefit'),
      hasLength(1),
    );
    db.database.execute(
      "UPDATE local_member_benefit_package_slot SET payload_blob=? WHERE state='ACTIVE'",
      [
        [9],
      ],
    );
    await expectLater(memberInstaller.requireActive(), throwsStateError);
  });

  test('cross-tenant package and injected atomic switch fail closed', () async {
    var armed = true;
    final db = PosLocalDatabase.inMemory(
      binding,
      failureInjector: (point) {
        if (armed && point == 'member_benefit_package_after_atomic_switch') {
          throw StateError('kill');
        }
      },
    );
    addTearDown(db.close);
    final pair = await Ed25519().newKeyPair();
    final key = await pair.extractPublicKey();
    final installer = MemberBenefitPackageInstaller(
      db,
      trustedSigningKeys: {'TEST_KEY': key},
      utcNow: () => now,
    );
    await expectLater(
      installer.install(await _memberEnvelope(pair)),
      throwsStateError,
    );
    expect(
      db.database.select('SELECT * FROM local_member_benefit_package_binding'),
      isEmpty,
    );
    armed = false;
    await expectLater(
      installer.install(await _memberEnvelope(pair, tenantId: 'TENANT_B')),
      throwsStateError,
    );
  });
}

Future<MemberBenefitPackageEnvelope> _memberEnvelope(
  KeyPair pair, {
  String tenantId = 'TENANT_A',
}) async {
  final expires = now.add(const Duration(days: 1));
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHMBP|1.0|member-benefit-engine-1.0.0|$tenantId|1101|1|0|${now.toIso8601String()}|${expires.toIso8601String()}\n'
      'B|01K7B000000000000000000001|GOLD|1|0|BEST_PRICE|0|0|${now.toIso8601String()}|${expires.toIso8601String()}|${_hash('b')}\n'
      'P|01K7P000000000000000000001|1|GOLD|101|201|1101|800|${now.toIso8601String()}|${expires.toIso8601String()}|${_hash('a')}\n',
    ),
  );
  final signature = await Ed25519().sign(payload, keyPair: pair);
  return MemberBenefitPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: 'TEST_KEY',
  );
}

Future<PromotionPackageEnvelope> _promotionEnvelope(KeyPair pair) async {
  final expires = now.add(const Duration(days: 1));
  final policy = jsonEncode({
    'maximumRoundingMinor': 9,
    'minimumLinePayableMinor': 20,
    'policyType': 'PROMOTION_MANUAL_AUTHORITY',
    'roundingMultiplesMinor': [1, 10],
    'withApprovalMinor': 1000,
    'withoutApprovalMinor': 100,
  });
  final policyHash = sha256.convert(utf8.encode(policy)).toString();
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHPRM|1.0|promotion-engine-1.0.0|TENANT_A|1101|1|0|${now.toIso8601String()}|${expires.toIso8601String()}\n'
      '@MANUAL_POLICY|31|$policyHash|$policy\n',
    ),
  );
  final signature = await Ed25519().sign(payload, keyPair: pair);
  return PromotionPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: 'TEST_KEY',
  );
}

String _hash(String value) => List.filled(64, value).join();
