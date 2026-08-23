-- T2-MEM-003：门店绑定、无 PII、签名的会员权益与会员价离线包元数据。
CREATE TABLE prm_member_benefit_package (
  package_id CHAR(26) NOT NULL COMMENT '包 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户',
  store_id BIGINT NOT NULL COMMENT '绑定门店',
  package_version BIGINT NOT NULL COMMENT '严格单调包版本',
  previous_version BIGINT NOT NULL COMMENT '上一包版本',
  schema_version VARCHAR(16) NOT NULL COMMENT '包 Schema 版本',
  engine_version VARCHAR(64) NOT NULL COMMENT '兼容计算引擎版本',
  payload_sha256 CHAR(64) NOT NULL COMMENT '载荷 SHA-256',
  signature_algorithm VARCHAR(16) NOT NULL COMMENT '签名算法',
  signing_key_id VARCHAR(128) NOT NULL COMMENT '签名密钥版本标识',
  object_key VARCHAR(512) NOT NULL COMMENT '租户命名空间对象键',
  benefit_count INT NOT NULL COMMENT '权益行数',
  member_price_count INT NOT NULL COMMENT '会员价行数',
  generated_at DATETIME(3) NOT NULL COMMENT '生成时间 UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '失效时间 UTC',
  state VARCHAR(16) NOT NULL COMMENT 'AVAILABLE/RETIRED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (package_id),
  UNIQUE KEY uk_prm_mbp_tenant_store_version (tenant_id, store_id, package_version),
  UNIQUE KEY uk_prm_mbp_tenant_object (tenant_id, object_key),
  CONSTRAINT ck_prm_mbp_version CHECK (package_version > 0 AND previous_version >= 0 AND package_version = previous_version + 1),
  CONSTRAINT ck_prm_mbp_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_prm_mbp_count CHECK (benefit_count >= 0 AND member_price_count >= 0),
  CONSTRAINT ck_prm_mbp_state CHECK (state IN ('AVAILABLE','RETIRED'))
) ENGINE=InnoDB COMMENT='会员权益会员价签名离线包元数据';

CREATE TRIGGER trg_prm_mbp_no_update BEFORE UPDATE ON prm_member_benefit_package
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member benefit package is immutable';
CREATE TRIGGER trg_prm_mbp_no_delete BEFORE DELETE ON prm_member_benefit_package
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member benefit package cannot be deleted';
