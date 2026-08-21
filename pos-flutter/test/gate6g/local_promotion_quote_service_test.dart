import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/promotion/application/local_promotion_quote_service.dart';
import 'package:jshpos_pos/features/promotion/domain/promotion_engine.dart';
import 'package:jshpos_pos/features/promotion/infrastructure/promotion_package_installer.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);
final fixedNow = DateTime.parse('2026-08-17T02:00:00Z');

void main() {
  test(
    'formal local quote persists a signed-package result and exact replay',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final keyPair = await Ed25519().newKeyPair();
      final publicKey = await keyPair.extractPublicKey();
      final installer = PromotionPackageInstaller(
        database,
        trustedSigningKeys: {'SYNTHETIC_TEST_KEY': publicKey},
        utcNow: () => fixedNow,
      );
      await installer.install(await envelope(keyPair));
      final service = LocalPromotionQuoteService(
        database: database,
        packageInstaller: installer,
        engine: PromotionEngine(),
        ulids: UlidGenerator(random: Random(7), now: () => fixedNow),
      );
      final line = PromotionLine(
        lineId: '01K5K000000000000000000001',
        lineNo: 1,
        skuId: '101',
        categoryId: null,
        brandId: null,
        quantity: ExactDecimal.parse('1.000000'),
        unitPriceMinor: 1000,
      );

      final first = await service.quote(
        pricingRequestId: '01K5Q000000000000000000001',
        businessTime: fixedNow,
        channel: 'POS',
        lines: [line],
      );
      final replay = await service.quote(
        pricingRequestId: '01K5Q000000000000000000001',
        businessTime: fixedNow,
        channel: 'POS',
        lines: [line],
      );

      expect(first.quote.discountAmountMinor, 100);
      expect(first.sourceAllocationsByLine[line.lineId]!.values.single, 100);
      expect(replay.duplicate, isTrue);
      expect(replay.quoteId, first.quoteId);
      expect(
        database.database.select('SELECT * FROM local_promotion_quote'),
        hasLength(1),
      );
      expect(
        database.database.select('SELECT * FROM local_audit_event'),
        hasLength(1),
      );
    },
  );
}

Future<PromotionPackageEnvelope> envelope(KeyPair keyPair) async {
  final expires = fixedNow.add(const Duration(days: 1));
  final policyJson = jsonEncode({
    'maximumRoundingMinor': 9,
    'minimumLinePayableMinor': 20,
    'policyType': 'PROMOTION_MANUAL_AUTHORITY',
    'roundingMultiplesMinor': [1, 10],
    'withApprovalMinor': 1000,
    'withoutApprovalMinor': 100,
  });
  final policySha = sha256.convert(utf8.encode(policyJson)).toString();
  final payload = Uint8List.fromList(
    utf8.encode(
      'JSHPRM|1.0|promotion-engine-1.0.0|TENANT_A|1101|1|0|${fixedNow.toIso8601String()}|${expires.toIso8601String()}\n'
      '01K5R000000000000000000001|'
      '{"benefit":{"amountMinor":100},"effectiveFrom":"${fixedNow.toIso8601String()}",'
      '"effectiveTo":"${expires.toIso8601String()}","priority":1,"ruleType":"AMOUNT_OFF",'
      '"ruleVersionId":"01K5R000000000000000000001","scope":{"skuIds":["101"]},'
      '"stackMode":"STACKABLE"}\n'
      '@MANUAL_POLICY|31|$policySha|$policyJson\n',
    ),
  );
  final signature = await Ed25519().sign(payload, keyPair: keyPair);
  return PromotionPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: 'SYNTHETIC_TEST_KEY',
  );
}
