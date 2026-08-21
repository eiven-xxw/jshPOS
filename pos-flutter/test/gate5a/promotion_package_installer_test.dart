import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
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
    'SQLite V8 preserves signed packages and transaction allocation schema',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      expect(
        database.database.select('PRAGMA user_version').single.values.first,
        8,
      );
      final keyPair = await Ed25519().newKeyPair();
      final trustedKey = await keyPair.extractPublicKey();
      final installer = PromotionPackageInstaller(
        database,
        trustedSigningKeys: {'SYNTHETIC_TEST_KEY': trustedKey},
        utcNow: () => fixedNow,
      );
      final first = await installer.install(
        await _envelope(keyPair, version: 1, previous: 0),
      );
      final second = await installer.install(
        await _envelope(keyPair, version: 2, previous: 1),
      );
      expect(first.slot, 'A');
      expect(second.slot, 'B');
      expect(second.rules.single.ruleVersionId, '01K5R000000000000000000001');
      expect(second.manualPolicy.policyVersionId, 31);
      expect(second.manualPolicy.roundingMultiplesMinor, [1, 10]);
      expect((await installer.requireActive()).packageVersion, 2);
      final bindingRow = database.database
          .select('SELECT * FROM local_promotion_package_binding')
          .single;
      expect(bindingRow['active_package_version'], 2);
      expect(bindingRow['active_slot'], 'B');
      expect(
        database.database.select('SELECT * FROM local_promotion_package_slot'),
        hasLength(2),
      );
      expect(
        database.database
            .select(
              "SELECT state FROM local_promotion_package_slot WHERE slot_code='A'",
            )
            .single['state'],
        'RETIRED',
      );
    },
  );

  test(
    'digest signature tenant and version failures do not change active slot',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final keyPair = await Ed25519().newKeyPair();
      final trustedKey = await keyPair.extractPublicKey();
      final installer = PromotionPackageInstaller(
        database,
        trustedSigningKeys: {'SYNTHETIC_TEST_KEY': trustedKey},
        utcNow: () => fixedNow,
      );
      final first = await _envelope(keyPair, version: 1, previous: 0);
      await installer.install(first);
      final tampered = PromotionPackageEnvelope(
        payload: Uint8List.fromList([...first.payload, 1]),
        payloadSha256: first.payloadSha256,
        signature: first.signature,
        signingKeyId: first.signingKeyId,
      );
      await expectLater(installer.install(tampered), throwsStateError);
      await expectLater(
        installer.install(await _envelope(keyPair, version: 3, previous: 1)),
        throwsStateError,
      );
      await expectLater(
        installer.install(
          await _envelope(
            keyPair,
            version: 2,
            previous: 1,
            tenantId: 'TENANT_B',
          ),
        ),
        throwsStateError,
      );
      expect(
        database.database
            .select(
              'SELECT active_package_version FROM local_promotion_package_binding',
            )
            .single['active_package_version'],
        1,
      );
    },
  );

  test(
    'injected switch failure rolls back slot and binding together',
    () async {
      final database = PosLocalDatabase.inMemory(
        binding,
        failureInjector: (checkpoint) {
          if (checkpoint == 'promotion_package_after_atomic_switch') {
            throw StateError('synthetic kill');
          }
        },
      );
      addTearDown(database.close);
      final keyPair = await Ed25519().newKeyPair();
      final trustedKey = await keyPair.extractPublicKey();
      await expectLater(
        PromotionPackageInstaller(
          database,
          trustedSigningKeys: {'SYNTHETIC_TEST_KEY': trustedKey},
          utcNow: () => fixedNow,
        ).install(await _envelope(keyPair, version: 1, previous: 0)),
        throwsStateError,
      );
      expect(
        database.database.select(
          'SELECT * FROM local_promotion_package_binding',
        ),
        isEmpty,
      );
      expect(
        database.database.select('SELECT * FROM local_promotion_package_slot'),
        isEmpty,
      );
    },
  );

  test(
    'untrusted key substitution and active SQLite tampering fail closed',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final trustedPair = await Ed25519().newKeyPair();
      final attackerPair = await Ed25519().newKeyPair();
      final trustedKey = await trustedPair.extractPublicKey();
      final installer = PromotionPackageInstaller(
        database,
        trustedSigningKeys: {'SYNTHETIC_TEST_KEY': trustedKey},
        utcNow: () => fixedNow,
      );
      await expectLater(
        installer.install(
          await _envelope(
            attackerPair,
            version: 1,
            previous: 0,
            signingKeyId: 'ATTACKER_KEY',
          ),
        ),
        throwsStateError,
      );
      await installer.install(
        await _envelope(trustedPair, version: 1, previous: 0),
      );
      database.database.execute(
        "UPDATE local_promotion_package_slot SET payload_blob=? WHERE state='ACTIVE'",
        [Uint8List.fromList(utf8.encode('tampered'))],
      );
      await expectLater(installer.requireActive(), throwsStateError);
    },
  );

  test(
    'active package is revalidated against expiry on every transaction',
    () async {
      final database = PosLocalDatabase.inMemory(binding);
      addTearDown(database.close);
      final keyPair = await Ed25519().newKeyPair();
      final trustedKey = await keyPair.extractPublicKey();
      var now = fixedNow;
      final installer = PromotionPackageInstaller(
        database,
        trustedSigningKeys: {'SYNTHETIC_TEST_KEY': trustedKey},
        utcNow: () => now,
      );
      await installer.install(
        await _envelope(keyPair, version: 1, previous: 0),
      );
      now = fixedNow.add(const Duration(days: 2));
      await expectLater(installer.requireActive(), throwsStateError);
    },
  );
}

Future<PromotionPackageEnvelope> _envelope(
  KeyPair keyPair, {
  required int version,
  required int previous,
  String tenantId = 'TENANT_A',
  String signingKeyId = 'SYNTHETIC_TEST_KEY',
}) async {
  final generated = fixedNow;
  final expires = generated.add(const Duration(days: 1));
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
      'JSHPRM|1.0|promotion-engine-1.0.0|$tenantId|1101|$version|$previous|${generated.toIso8601String()}|${expires.toIso8601String()}\n'
      '01K5R000000000000000000001|'
      '{"benefit":{"amountMinor":100},"effectiveFrom":"${generated.toIso8601String()}",'
      '"effectiveTo":"${expires.toIso8601String()}","priority":1,"ruleType":"AMOUNT_OFF",'
      '"ruleVersionId":"01K5R000000000000000000001","scope":{"skuIds":["101"]},'
      '"stackMode":"STACKABLE"}\n'
      '@MANUAL_POLICY|31|$policySha|$policyJson\n',
    ),
  );
  final algorithm = Ed25519();
  final signature = await algorithm.sign(payload, keyPair: keyPair);
  return PromotionPackageEnvelope(
    payload: payload,
    payloadSha256: sha256.convert(payload).toString(),
    signature: Uint8List.fromList(signature.bytes),
    signingKeyId: signingKeyId,
  );
}
