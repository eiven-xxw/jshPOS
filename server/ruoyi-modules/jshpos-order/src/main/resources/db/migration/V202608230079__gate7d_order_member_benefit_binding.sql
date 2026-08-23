CREATE TABLE ord_member_benefit_binding (
  binding_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner会员权益成交绑定ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单ULID',
  promotion_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原促销成交快照ULID',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原促销报价ULID',
  entitlement_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Member Owner无PII权益快照ULID',
  benefit_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原权益版本ULID',
  selected_path VARCHAR(32) NOT NULL COMMENT 'NORMAL_PATH MEMBER_PATH或STACKED_MEMBER_PATH',
  member_price_versions_json JSON NOT NULL COMMENT '原会员价版本ULID有序集合',
  capability_config_version BIGINT NOT NULL COMMENT '成交能力配置版本',
  capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成交能力配置SHA-256',
  rights_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '无PII权益摘要',
  explanation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '确定性解释链SHA-256',
  promotion_binding_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Promotion Owner原绑定SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '服务端冻结时间UTC',
  PRIMARY KEY (tenant_id,binding_id),
  UNIQUE KEY uk_ord_member_benefit_order (tenant_id,order_id),
  KEY idx_ord_member_benefit_snapshot (tenant_id,promotion_snapshot_id),
  CONSTRAINT fk_ord_member_benefit_order FOREIGN KEY (tenant_id,order_id)
    REFERENCES ord_sales_order(tenant_id,order_id),
  CONSTRAINT ck_ord_member_benefit_path CHECK (
    selected_path IN ('NORMAL_PATH','MEMBER_PATH','STACKED_MEMBER_PATH')
  ),
  CONSTRAINT ck_ord_member_benefit_hash CHECK (
    capability_sha256 REGEXP '^[a-f0-9]{64}$'
    AND rights_digest REGEXP '^[a-f0-9]{64}$'
    AND explanation_sha256 REGEXP '^[a-f0-9]{64}$'
    AND promotion_binding_sha256 REGEXP '^[a-f0-9]{64}$'
  )
) ENGINE=InnoDB COMMENT='T2-MEM-003订单冻结的原会员权益会员价与促销路径无PII绑定';

DELIMITER $$
CREATE TRIGGER trg_ord_member_benefit_no_update
BEFORE UPDATE ON ord_member_benefit_binding FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_member_benefit_binding is immutable'; END$$
CREATE TRIGGER trg_ord_member_benefit_no_delete
BEFORE DELETE ON ord_member_benefit_binding FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_member_benefit_binding is immutable'; END$$
DELIMITER ;
