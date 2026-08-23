import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';

final class MemberBenefitPackageEnvelope {
  const MemberBenefitPackageEnvelope({
    required this.payload,
    required this.payloadSha256,
    required this.signature,
    required this.signingKeyId,
  });
  final Uint8List payload;
  final String payloadSha256;
  final Uint8List signature;
  final String signingKeyId;
}

final class InstalledMemberBenefitPackage {
  const InstalledMemberBenefitPackage({
    required this.packageVersion,
    required this.payloadSha256,
    required this.expiresAt,
    required this.benefitCount,
    required this.memberPriceCount,
  });
  final int packageVersion;
  final String payloadSha256;
  final DateTime expiresAt;
  final int benefitCount;
  final int memberPriceCount;
}

/// 验证 Ed25519、摘要、租户/门店、连续版本和有效期后原子切换 SQLite A/B 槽。
final class MemberBenefitPackageInstaller {
  MemberBenefitPackageInstaller(
    this.database, {
    required Map<String, SimplePublicKey> trustedSigningKeys,
    Ed25519? algorithm,
    DateTime Function()? utcNow,
  }) : _trustedSigningKeys = Map.unmodifiable(trustedSigningKeys),
       _algorithm = algorithm ?? Ed25519(),
       _utcNow = utcNow ?? (() => DateTime.now().toUtc());
  final PosLocalDatabase database;
  final Map<String, SimplePublicKey> _trustedSigningKeys;
  final Ed25519 _algorithm;
  final DateTime Function() _utcNow;

  Future<InstalledMemberBenefitPackage> install(
    MemberBenefitPackageEnvelope envelope,
  ) async {
    final digest = sha256.convert(envelope.payload).toString();
    if (!_equal(digest, envelope.payloadSha256)) {
      throw StateError('MBP-PKG-101: digest mismatch');
    }
    final key = _trustedSigningKeys[envelope.signingKeyId];
    if (key == null ||
        !await _algorithm.verify(
          envelope.payload,
          signature: Signature(envelope.signature, publicKey: key),
        )) {
      throw StateError('MBP-PKG-102: signature invalid');
    }
    final parsed = _parse(envelope.payload);
    final binding = database.binding..validate();
    if (parsed.tenantId != binding.tenantId ||
        parsed.storeId != binding.storeId) {
      throw StateError('MBP-PKG-103: trusted binding mismatch');
    }
    final current = database.database.select(
      'SELECT active_slot,active_package_version FROM local_member_benefit_package_binding WHERE singleton_id=1 AND tenant_id=? AND store_id=?',
      [binding.tenantId, binding.storeId],
    );
    final currentVersion = current.isEmpty
        ? 0
        : current.single['active_package_version']! as int;
    if (parsed.previousVersion != currentVersion ||
        parsed.packageVersion != currentVersion + 1) {
      throw StateError('MBP-PKG-104: package version is not contiguous');
    }
    final oldSlot = current.isEmpty
        ? null
        : current.single['active_slot']! as String;
    final slot = oldSlot == 'A' ? 'B' : 'A';
    final now = _utcNow().toUtc().toIso8601String();
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_member_benefit_package_slot(slot_code,tenant_id,store_id,package_version,previous_version,
          schema_version,engine_version,payload_blob,payload_sha256,signature_blob,signing_key_id,generated_at,
          expires_at,installed_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          ON CONFLICT(tenant_id,store_id,slot_code) DO UPDATE SET package_version=excluded.package_version,
          previous_version=excluded.previous_version,payload_blob=excluded.payload_blob,
          payload_sha256=excluded.payload_sha256,signature_blob=excluded.signature_blob,
          signing_key_id=excluded.signing_key_id,generated_at=excluded.generated_at,
          expires_at=excluded.expires_at,installed_at=excluded.installed_at,state='STAGED' ''',
        [
          slot,
          binding.tenantId,
          binding.storeId,
          parsed.packageVersion,
          parsed.previousVersion,
          '1.0',
          'member-benefit-engine-1.0.0',
          envelope.payload,
          digest,
          envelope.signature,
          envelope.signingKeyId,
          parsed.generatedAt.toIso8601String(),
          parsed.expiresAt.toIso8601String(),
          now,
          'STAGED',
        ],
      );
      for (final row in parsed.benefits) {
        database.database.execute(
          '''INSERT INTO local_member_benefit_level(tenant_id,store_id,package_version,benefit_version_id,
            level_code,member_price_eligible,stacking_allowed,default_combination_policy,
            policy_allow_stacking,revocation_epoch,effective_at,expires_at,content_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)''',
          [
            binding.tenantId,
            binding.storeId,
            parsed.packageVersion,
            row[1],
            row[2],
            int.parse(row[3]),
            int.parse(row[4]),
            row[5],
            int.parse(row[6]),
            int.parse(row[7]),
            row[8],
            row[9] == '-' ? null : row[9],
            row[10],
          ],
        );
      }
      for (final row in parsed.prices) {
        database.database.execute(
          '''INSERT INTO local_member_price_item(tenant_id,store_id,package_version,version_id,version_no,
            level_code,sku_id,unit_id,scope_store_id,amount_minor,effective_at,expires_at,content_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)''',
          [
            binding.tenantId,
            binding.storeId,
            parsed.packageVersion,
            row[1],
            int.parse(row[2]),
            row[3],
            row[4],
            row[5],
            row[6] == '*' ? null : row[6],
            int.parse(row[7]),
            row[8],
            row[9] == '-' ? null : row[9],
            row[10],
          ],
        );
      }
      if (oldSlot != null) {
        database.database.execute(
          "UPDATE local_member_benefit_package_slot SET state='RETIRED' WHERE tenant_id=? AND store_id=? AND slot_code=? AND state='ACTIVE'",
          [binding.tenantId, binding.storeId, oldSlot],
        );
      }
      database.database.execute(
        '''INSERT INTO local_member_benefit_package_binding(singleton_id,tenant_id,store_id,active_slot,
          active_package_version,active_payload_sha256,switched_at,record_version) VALUES(1,?,?,?,?,?,?,1)
          ON CONFLICT(singleton_id) DO UPDATE SET active_slot=excluded.active_slot,
          active_package_version=excluded.active_package_version,active_payload_sha256=excluded.active_payload_sha256,
          switched_at=excluded.switched_at,record_version=local_member_benefit_package_binding.record_version+1''',
        [
          binding.tenantId,
          binding.storeId,
          slot,
          parsed.packageVersion,
          digest,
          now,
        ],
      );
      database.database.execute(
        "UPDATE local_member_benefit_package_slot SET state='ACTIVE' WHERE tenant_id=? AND store_id=? AND slot_code=? AND payload_sha256=? AND state='STAGED'",
        [binding.tenantId, binding.storeId, slot, digest],
      );
      database.checkpoint('member_benefit_package_after_atomic_switch');
    });
    return InstalledMemberBenefitPackage(
      packageVersion: parsed.packageVersion,
      payloadSha256: digest,
      expiresAt: parsed.expiresAt,
      benefitCount: parsed.benefits.length,
      memberPriceCount: parsed.prices.length,
    );
  }

  Future<InstalledMemberBenefitPackage> requireActive() async {
    final binding = database.binding;
    final rows = database.database.select(
      '''SELECT s.package_version,s.payload_blob,s.payload_sha256,s.signature_blob,s.signing_key_id,
        b.active_payload_sha256 FROM local_member_benefit_package_binding b
        JOIN local_member_benefit_package_slot s ON s.tenant_id=b.tenant_id AND s.store_id=b.store_id
          AND s.slot_code=b.active_slot AND s.state='ACTIVE'
        WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=?''',
      [binding.tenantId, binding.storeId],
    );
    if (rows.length != 1) {
      throw StateError('MBP-PKG-110: active package missing');
    }
    final row = rows.single;
    final payload = Uint8List.fromList(row['payload_blob']! as List<int>);
    final digest = sha256.convert(payload).toString();
    if (!_equal(digest, row['payload_sha256']! as String) ||
        !_equal(digest, row['active_payload_sha256']! as String)) {
      throw StateError('MBP-PKG-111: active digest mismatch');
    }
    final key = _trustedSigningKeys[row['signing_key_id']! as String];
    final signature = Uint8List.fromList(row['signature_blob']! as List<int>);
    if (key == null ||
        !await _algorithm.verify(
          payload,
          signature: Signature(signature, publicKey: key),
        )) {
      throw StateError('MBP-PKG-112: active signature invalid');
    }
    final parsed = _parse(payload);
    if (parsed.tenantId != binding.tenantId ||
        parsed.storeId != binding.storeId ||
        parsed.packageVersion != row['package_version']) {
      throw StateError('MBP-PKG-113: active binding mismatch');
    }
    return InstalledMemberBenefitPackage(
      packageVersion: parsed.packageVersion,
      payloadSha256: digest,
      expiresAt: parsed.expiresAt,
      benefitCount: parsed.benefits.length,
      memberPriceCount: parsed.prices.length,
    );
  }

  _ParsedPackage _parse(Uint8List payload) {
    final text = utf8.decode(payload, allowMalformed: false);
    final lines = text.split('\n');
    final header = lines.first.split('|');
    if (header.length != 9 ||
        header[0] != 'JSHMBP' ||
        header[1] != '1.0' ||
        header[2] != 'member-benefit-engine-1.0.0') {
      throw StateError('MBP-PKG-105: unsupported header');
    }
    final generated = DateTime.parse(header[7]).toUtc();
    final expires = DateTime.parse(header[8]).toUtc();
    if (!expires.isAfter(_utcNow().toUtc()) || !expires.isAfter(generated)) {
      throw StateError('MBP-PKG-106: package expired');
    }
    final benefits = <List<String>>[];
    final prices = <List<String>>[];
    for (final line in lines.skip(1).where((line) => line.isNotEmpty)) {
      final row = line.split('|');
      if (row.length != 11 ||
          !const {'B', 'P'}.contains(row[0]) ||
          !RegExp(r'^[a-f0-9]{64}$').hasMatch(row[10])) {
        throw StateError('MBP-PKG-107: malformed record');
      }
      if (row[0] == 'B') {
        benefits.add(row);
      } else {
        prices.add(row);
      }
    }
    if (benefits.length > 2000 || prices.length > 500000) {
      throw StateError('MBP-PKG-108: record limit exceeded');
    }
    return _ParsedPackage(
      tenantId: header[3],
      storeId: header[4],
      packageVersion: int.parse(header[5]),
      previousVersion: int.parse(header[6]),
      generatedAt: generated,
      expiresAt: expires,
      benefits: benefits,
      prices: prices,
    );
  }

  bool _equal(String a, String b) {
    if (a.length != b.length) return false;
    var d = 0;
    for (var i = 0; i < a.length; i++) {
      d |= a.codeUnitAt(i) ^ b.codeUnitAt(i);
    }
    return d == 0;
  }
}

final class _ParsedPackage {
  const _ParsedPackage({
    required this.tenantId,
    required this.storeId,
    required this.packageVersion,
    required this.previousVersion,
    required this.generatedAt,
    required this.expiresAt,
    required this.benefits,
    required this.prices,
  });
  final String tenantId;
  final String storeId;
  final int packageVersion;
  final int previousVersion;
  final DateTime generatedAt;
  final DateTime expiresAt;
  final List<List<String>> benefits;
  final List<List<String>> prices;
}
