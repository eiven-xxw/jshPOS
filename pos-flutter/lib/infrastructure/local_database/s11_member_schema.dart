/// Gate 5C POS 会员最小缓存；禁止保存手机号、卡号、OpenID、身份密文或积分可用余额。
abstract final class S11MemberSchema {
  static const int version = 7;

  static const String v7 = r'''
CREATE TABLE local_member_cache (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  member_ref TEXT NOT NULL CHECK(length(member_ref)=26),
  member_token_hash TEXT NOT NULL CHECK(length(member_token_hash)=64),
  masked_label TEXT NOT NULL CHECK(length(masked_label)<=32),
  level_code TEXT,
  rights_digest TEXT NOT NULL CHECK(length(rights_digest)=64),
  snapshot_version INTEGER NOT NULL CHECK(snapshot_version>0),
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  received_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,store_id,member_ref),
  UNIQUE(tenant_id,store_id,member_token_hash)
) STRICT;

CREATE TRIGGER local_member_cache_binding_insert
BEFORE INSERT ON local_member_cache
WHEN NOT EXISTS(
  SELECT 1 FROM local_device_binding b
  WHERE b.singleton_id=1 AND b.tenant_id=NEW.tenant_id AND b.store_id=NEW.store_id
)
BEGIN SELECT RAISE(ABORT,'member cache device binding mismatch'); END;

CREATE TRIGGER local_member_cache_binding_update
BEFORE UPDATE ON local_member_cache
WHEN OLD.tenant_id<>NEW.tenant_id OR OLD.store_id<>NEW.store_id OR OLD.member_ref<>NEW.member_ref
BEGIN SELECT RAISE(ABORT,'member cache owner identity is immutable'); END;
''';
}
