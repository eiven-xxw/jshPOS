CREATE TABLE prm_quote_member_benefit (
  binding_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员权益询价绑定ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '促销报价ULID',
  entitlement_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '无PII权益快照；非会员路径为空',
  benefit_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '权益版本ULID',
  selected_path VARCHAR(32) NOT NULL COMMENT 'NORMAL_PATH/MEMBER_PATH/STACKED_MEMBER_PATH',
  member_price_versions_json JSON NOT NULL COMMENT '有序会员价版本引用数组',
  capability_config_version BIGINT NOT NULL COMMENT '可信能力配置版本；0表示默认关闭',
  capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '能力配置摘要',
  rights_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '无PII权益摘要；非会员为零摘要',
  explanation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '解释链摘要',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整绑定摘要',
  occurred_at DATETIME(3) NOT NULL COMMENT '询价时间UTC',
  PRIMARY KEY (tenant_id,binding_id),
  UNIQUE KEY uk_prm_quote_member_benefit (tenant_id,quote_id),
  CONSTRAINT fk_prm_member_benefit_quote FOREIGN KEY (tenant_id,quote_id) REFERENCES prm_quote(tenant_id,quote_id),
  CONSTRAINT ck_prm_member_benefit_path CHECK (selected_path IN ('NORMAL_PATH','MEMBER_PATH','STACKED_MEMBER_PATH')),
  CONSTRAINT ck_prm_member_benefit_capability CHECK (capability_config_version>=0)
) ENGINE=InnoDB COMMENT='T2-MEM-003 Promotion Owner不可变会员权益询价绑定；无PII；XML_ONLY';

DELIMITER $$
CREATE TRIGGER trg_prm_quote_member_benefit_no_update BEFORE UPDATE ON prm_quote_member_benefit FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member benefit quote binding is immutable'; END$$
CREATE TRIGGER trg_prm_quote_member_benefit_no_delete BEFORE DELETE ON prm_quote_member_benefit FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member benefit quote binding cannot be deleted'; END$$
DELIMITER ;
