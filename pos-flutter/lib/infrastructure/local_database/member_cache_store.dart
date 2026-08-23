import 'dart:convert';

import 'package:crypto/crypto.dart';

import 'pos_local_database.dart';

/// POS 仅使用短期会员令牌查询脱敏缓存，不支持离线积分消费。
final class MemberCacheStore {
  MemberCacheStore(this._local);

  final PosLocalDatabase _local;

  void upsert(MemberCacheEntry entry) {
    entry.validate(_local.binding.tenantId, _local.binding.storeId);
    _local.database.execute(
      '''INSERT INTO local_member_cache(
        tenant_id,store_id,member_ref,member_token_hash,masked_label,level_code,
        rights_digest,snapshot_version,expires_at,revoked_at,received_at,entitlement_snapshot_id)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(tenant_id,store_id,member_ref) DO UPDATE SET
        member_token_hash=excluded.member_token_hash,
        masked_label=excluded.masked_label,
        level_code=excluded.level_code,
        rights_digest=excluded.rights_digest,
        snapshot_version=excluded.snapshot_version,
        expires_at=excluded.expires_at,
        revoked_at=excluded.revoked_at,
        received_at=excluded.received_at
        ,entitlement_snapshot_id=excluded.entitlement_snapshot_id
      WHERE excluded.snapshot_version>local_member_cache.snapshot_version''',
      [
        entry.tenantId,
        entry.storeId,
        entry.memberRef,
        _tokenHash(entry.memberToken),
        entry.maskedLabel,
        entry.levelCode,
        entry.rightsDigest,
        entry.snapshotVersion,
        entry.expiresAt.toUtc().toIso8601String(),
        entry.revokedAt?.toUtc().toIso8601String(),
        entry.receivedAt.toUtc().toIso8601String(),
        entry.entitlementSnapshotId,
      ],
    );
  }

  MemberCacheView? resolve(String memberToken, DateTime now) {
    if (memberToken.isEmpty || memberToken.length > 256) {
      throw ArgumentError.value(memberToken.length, 'memberToken');
    }
    final rows = _local.database.select(
      '''SELECT member_ref,masked_label,level_code,rights_digest,snapshot_version,expires_at,entitlement_snapshot_id
      FROM local_member_cache
      WHERE tenant_id=? AND store_id=? AND member_token_hash=? AND revoked_at IS NULL AND expires_at>?''',
      [
        _local.binding.tenantId,
        _local.binding.storeId,
        _tokenHash(memberToken),
        now.toUtc().toIso8601String(),
      ],
    );
    if (rows.isEmpty) return null;
    final row = rows.single;
    return MemberCacheView(
      memberRef: row['member_ref']! as String,
      maskedLabel: row['masked_label']! as String,
      levelCode: row['level_code'] as String?,
      rightsDigest: row['rights_digest']! as String,
      snapshotVersion: row['snapshot_version']! as int,
      expiresAt: DateTime.parse(row['expires_at']! as String).toUtc(),
      entitlementSnapshotId: row['entitlement_snapshot_id'] as String?,
    );
  }

  void revoke(String memberRef, DateTime revokedAt) {
    _local.database.execute(
      '''UPDATE local_member_cache SET revoked_at=?
      WHERE tenant_id=? AND store_id=? AND member_ref=? AND revoked_at IS NULL''',
      [
        revokedAt.toUtc().toIso8601String(),
        _local.binding.tenantId,
        _local.binding.storeId,
        memberRef,
      ],
    );
    // 无记录时保持幂等成功。
  }

  int purge(DateTime now) {
    _local.database.execute(
      '''DELETE FROM local_member_cache
      WHERE tenant_id=? AND store_id=? AND (revoked_at IS NOT NULL OR expires_at<=?)''',
      [
        _local.binding.tenantId,
        _local.binding.storeId,
        now.toUtc().toIso8601String(),
      ],
    );
    return _local.database.updatedRows;
  }

  String _tokenHash(String value) =>
      sha256.convert(utf8.encode(value)).toString();
}

/// 服务端签发的最小会员缓存项；memberToken 只在写入时换算为摘要。
final class MemberCacheEntry {
  const MemberCacheEntry({
    required this.tenantId,
    required this.storeId,
    required this.memberRef,
    required this.memberToken,
    required this.maskedLabel,
    this.levelCode,
    required this.rightsDigest,
    required this.snapshotVersion,
    required this.expiresAt,
    this.revokedAt,
    required this.receivedAt,
    this.entitlementSnapshotId,
  });

  final String tenantId;
  final String storeId;
  final String memberRef;
  final String memberToken;
  final String maskedLabel;
  final String? levelCode;
  final String rightsDigest;
  final int snapshotVersion;
  final DateTime expiresAt;
  final DateTime? revokedAt;
  final DateTime receivedAt;
  final String? entitlementSnapshotId;

  void validate(String trustedTenantId, String trustedStoreId) {
    if (tenantId != trustedTenantId || storeId != trustedStoreId) {
      throw StateError('TENANT_CONTEXT_REQUIRED: member cache scope mismatch');
    }
    if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(memberRef) ||
        memberToken.isEmpty ||
        memberToken.length > 256 ||
        maskedLabel.length > 32 ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(rightsDigest) ||
        (entitlementSnapshotId != null &&
            !RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$')
                .hasMatch(entitlementSnapshotId!)) ||
        snapshotVersion <= 0 ||
        !expiresAt.isAfter(receivedAt)) {
      throw ArgumentError('MEMBER_CACHE_INVALID');
    }
  }
}

/// POS 对业务层只提供脱敏字段和权益摘要，不提供身份明文或积分余额。
final class MemberCacheView {
  const MemberCacheView({
    required this.memberRef,
    required this.maskedLabel,
    this.levelCode,
    required this.rightsDigest,
    required this.snapshotVersion,
    required this.expiresAt,
    this.entitlementSnapshotId,
  });
  final String memberRef;
  final String maskedLabel;
  final String? levelCode;
  final String rightsDigest;
  final int snapshotVersion;
  final DateTime expiresAt;
  final String? entitlementSnapshotId;
}
