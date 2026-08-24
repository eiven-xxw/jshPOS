import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/catalog/infrastructure/lot_package_installer.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const _binding = TrustedDeviceBinding(
  tenantId: 'PERF_TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Perf',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test(
    'PERF002 installs 100000 signed lot package records atomically',
    () async {
      final database = PosLocalDatabase.inMemory(_binding);
      addTearDown(database.close);
      final algorithm = Ed25519();
      final keyPair = await algorithm.newKeyPair();
      final publicKey = await keyPair.extractPublicKey();
      final lots = List<Map<String, Object?>>.generate(99999, (index) {
        final suffix = index.toString().padLeft(21, '0');
        final lotId = '01K2A$suffix';
        return {
          'lotId': lotId,
          'skuId': '701',
          'baseUnitId': '301',
          'supplierLotCode': 'SYN-${index.toString().padLeft(6, '0')}',
          'internalLotCode': 'PERF-${index.toString().padLeft(6, '0')}',
          'productionDate': '2026-08-20',
          'receivedDate': '2026-08-22',
          'expiryDate': '2027-08-22',
          'policyVersionId': '01K2A000000000000000000061',
          'nearExpiryDays': 3,
          'quantity': '1',
          'lastLedgerSequence': 0,
          'sourceSha256': List.filled(64, '2').join(),
        };
      }, growable: false);
      final document = <String, Object?>{
        'schemaVersion': '1.0',
        'tenantId': _binding.tenantId,
        'storeId': _binding.storeId,
        'warehouseId': '01K2A000000000000000000071',
        'industry': 'COMMUNITY_SUPERMARKET',
        'industryTemplateVersionId': '30',
        'industryTemplateSha256': List.filled(64, '0').join(),
        'businessZoneId': _binding.storeTimezone,
        'businessDayStart': '04:00',
        'packageVersion': 1,
        'previousVersion': 0,
        'generatedAt': '2026-08-24T00:00:00.000Z',
        'policies': [
          {
            'policyVersionId': '01K2A000000000000000000061',
            'skuId': '701',
            'enabled': true,
            'expiryBasis': 'EXPLICIT_EXPIRY_DATE',
            'shelfLifeDays': null,
            'nearExpiryDays': 3,
            'effectiveFrom': '2026-08-01T00:00:00.000Z',
            'contentSha256': List.filled(64, '1').join(),
          },
        ],
        'lots': lots,
      };
      final payload = Uint8List.fromList(utf8.encode(jsonEncode(document)));
      final signature = await algorithm.sign(payload, keyPair: keyPair);
      final envelope = LotPackageEnvelope(
        payload: payload,
        payloadSha256: sha256.convert(payload).toString(),
        signature: Uint8List.fromList(signature.bytes),
        signingKeyId: 'synthetic-perf-key-v1',
      );
      final installer = LotPackageInstaller(
        database,
        trustedSigningKeys: {'synthetic-perf-key-v1': publicKey},
        utcNow: () => DateTime.utc(2026, 8, 24, 8),
      );
      final stopwatch = Stopwatch()..start();
      final installed = await installer.install(envelope);
      stopwatch.stop();
      expect(installed.recordCount, 100000);
      expect(
        database.database
            .select('SELECT COUNT(*) AS c FROM local_lot_balance')
            .single['c'],
        99999,
      );
      expect(
        database.database
            .select(
              'SELECT record_count,state FROM local_lot_package_slot WHERE package_version=1',
            )
            .single
            .values
            .toList(),
        [100000, 'ACTIVE'],
      );
      debugPrint(
        'PERF002_METRIC data_package_100000_ms=${stopwatch.elapsedMilliseconds}',
      );
    },
    skip: Platform.environment['T2_PERF002_HEAVY'] == '1'
        ? false
        : 'dedicated Gate 8C performance job only',
    timeout: const Timeout(Duration(minutes: 5)),
  );
}
