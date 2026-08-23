/// T2-MEM-003 SQLite v16：签名权益包、确定性报价绑定和原成交权益快照。
abstract final class Gate7dMemberBenefitSchema {
  static const int version = 16;

  static const String v16 = r'''
ALTER TABLE local_member_cache ADD COLUMN entitlement_snapshot_id TEXT;

CREATE TABLE local_member_benefit_package_slot (
  slot_code TEXT NOT NULL CHECK(slot_code IN ('A','B')),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  previous_version INTEGER NOT NULL CHECK(previous_version>=0 AND previous_version<package_version),
  schema_version TEXT NOT NULL CHECK(schema_version='1.0'),
  engine_version TEXT NOT NULL CHECK(engine_version='member-benefit-engine-1.0.0'),
  payload_blob BLOB NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  signature_blob BLOB NOT NULL,
  signing_key_id TEXT NOT NULL,
  generated_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  installed_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('STAGED','ACTIVE','RETIRED')),
  PRIMARY KEY(tenant_id,store_id,slot_code),
  UNIQUE(tenant_id,store_id,package_version)
) STRICT;

CREATE TABLE local_member_benefit_package_binding (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id=1),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  active_slot TEXT NOT NULL CHECK(active_slot IN ('A','B')),
  active_package_version INTEGER NOT NULL CHECK(active_package_version>0),
  active_payload_sha256 TEXT NOT NULL CHECK(length(active_payload_sha256)=64),
  switched_at TEXT NOT NULL,
  record_version INTEGER NOT NULL DEFAULT 1 CHECK(record_version>0),
  UNIQUE(tenant_id,store_id)
) STRICT;

CREATE TABLE local_member_benefit_level (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  benefit_version_id TEXT NOT NULL CHECK(length(benefit_version_id)=26),
  level_code TEXT NOT NULL CHECK(length(level_code) BETWEEN 1 AND 32),
  member_price_eligible INTEGER NOT NULL CHECK(member_price_eligible IN (0,1)),
  stacking_allowed INTEGER NOT NULL CHECK(stacking_allowed IN (0,1)),
  default_combination_policy TEXT NOT NULL CHECK(default_combination_policy IN ('BEST_PRICE','NORMAL_ONLY','MEMBER_ONLY')),
  policy_allow_stacking INTEGER NOT NULL CHECK(policy_allow_stacking IN (0,1)),
  revocation_epoch INTEGER NOT NULL CHECK(revocation_epoch>=0),
  effective_at TEXT NOT NULL,
  expires_at TEXT,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
  PRIMARY KEY(tenant_id,store_id,package_version,benefit_version_id,level_code)
) STRICT;

CREATE TABLE local_member_price_item (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  version_id TEXT NOT NULL CHECK(length(version_id)=26),
  version_no INTEGER NOT NULL CHECK(version_no>0),
  level_code TEXT NOT NULL,
  sku_id TEXT NOT NULL,
  unit_id TEXT NOT NULL,
  scope_store_id TEXT,
  amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),
  effective_at TEXT NOT NULL,
  expires_at TEXT,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
  PRIMARY KEY(tenant_id,store_id,package_version,version_id,level_code,sku_id,unit_id)
) STRICT;
CREATE INDEX idx_local_member_price_lookup ON local_member_price_item
  (tenant_id,store_id,package_version,level_code,sku_id,unit_id,scope_store_id,version_no);

CREATE TABLE local_promotion_quote_member_benefit (
  tenant_id TEXT NOT NULL,
  quote_id TEXT NOT NULL CHECK(length(quote_id)=26),
  entitlement_snapshot_id TEXT NOT NULL CHECK(length(entitlement_snapshot_id)=26),
  benefit_version_id TEXT NOT NULL CHECK(length(benefit_version_id)=26),
  selected_path TEXT NOT NULL CHECK(selected_path IN ('NORMAL_PATH','MEMBER_PATH','STACKED_MEMBER_PATH')),
  member_price_versions_json TEXT NOT NULL,
  capability_config_version INTEGER NOT NULL CHECK(capability_config_version>0),
  capability_sha256 TEXT NOT NULL CHECK(length(capability_sha256)=64),
  rights_digest TEXT NOT NULL CHECK(length(rights_digest)=64),
  explanation_sha256 TEXT NOT NULL CHECK(length(explanation_sha256)=64),
  package_version INTEGER NOT NULL CHECK(package_version>0),
  package_sha256 TEXT NOT NULL CHECK(length(package_sha256)=64),
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
  occurred_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,quote_id),
  FOREIGN KEY(tenant_id,quote_id) REFERENCES local_promotion_quote(tenant_id,quote_id)
) STRICT;

CREATE TABLE local_order_member_benefit_snapshot (
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL CHECK(length(order_id)=26),
  quote_id TEXT NOT NULL CHECK(length(quote_id)=26),
  entitlement_snapshot_id TEXT NOT NULL CHECK(length(entitlement_snapshot_id)=26),
  benefit_version_id TEXT NOT NULL CHECK(length(benefit_version_id)=26),
  selected_path TEXT NOT NULL CHECK(selected_path IN ('NORMAL_PATH','MEMBER_PATH','STACKED_MEMBER_PATH')),
  member_price_versions_json TEXT NOT NULL,
  capability_config_version INTEGER NOT NULL CHECK(capability_config_version>0),
  capability_sha256 TEXT NOT NULL CHECK(length(capability_sha256)=64),
  rights_digest TEXT NOT NULL CHECK(length(rights_digest)=64),
  explanation_sha256 TEXT NOT NULL CHECK(length(explanation_sha256)=64),
  package_version INTEGER NOT NULL CHECK(package_version>0),
  package_sha256 TEXT NOT NULL CHECK(length(package_sha256)=64),
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
  occurred_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,order_id),
  UNIQUE(tenant_id,quote_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id)
) STRICT;

CREATE TRIGGER local_member_benefit_binding_guard BEFORE INSERT ON local_member_benefit_package_binding
WHEN NOT EXISTS(SELECT 1 FROM local_device_binding b WHERE b.singleton_id=1
  AND b.tenant_id=NEW.tenant_id AND b.store_id=NEW.store_id)
BEGIN SELECT RAISE(ABORT,'member benefit package binding mismatch'); END;
CREATE TRIGGER local_member_benefit_quote_no_update BEFORE UPDATE ON local_promotion_quote_member_benefit
BEGIN SELECT RAISE(ABORT,'member benefit quote binding is immutable'); END;
CREATE TRIGGER local_member_benefit_quote_no_delete BEFORE DELETE ON local_promotion_quote_member_benefit
BEGIN SELECT RAISE(ABORT,'member benefit quote binding is immutable'); END;
CREATE TRIGGER local_order_member_benefit_no_update BEFORE UPDATE ON local_order_member_benefit_snapshot
BEGIN SELECT RAISE(ABORT,'order member benefit snapshot is immutable'); END;
CREATE TRIGGER local_order_member_benefit_no_delete BEFORE DELETE ON local_order_member_benefit_snapshot
BEGIN SELECT RAISE(ABORT,'order member benefit snapshot is immutable'); END;
''';
}
