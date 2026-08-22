CREATE TABLE cat_lot_policy_version (
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识，只能由服务端上下文注入',
  policy_version_id VARCHAR(26) NOT NULL COMMENT '不可变批次策略版本ULID',
  store_id BIGINT NOT NULL COMMENT '适用门店平台主键',
  sku_id BIGINT NOT NULL COMMENT '适用SKU平台主键',
  enabled BOOLEAN NOT NULL COMMENT '是否启用批次效期路径，只有社区超市允许为1',
  expiry_basis VARCHAR(32) NOT NULL COMMENT '到期日基准：生产日、收货日或显式到期日',
  shelf_life_days INT NULL COMMENT '冻结保质期自然日数，显式到期日模式必须为空',
  near_expiry_days INT NOT NULL COMMENT '临期自然日阈值，包含阈值日',
  industry VARCHAR(32) NOT NULL COMMENT '由Foundation可信模板绑定冻结的行业代码',
  template_version_id BIGINT NOT NULL COMMENT 'Foundation已发布行业模板版本主键',
  effective_from DATETIME(6) NOT NULL COMMENT '策略生效时刻UTC',
  content_sha256 CHAR(64) NOT NULL COMMENT '规范策略内容SHA-256小写十六进制',
  state VARCHAR(16) NOT NULL COMMENT '策略状态；当前仅PUBLISHED且记录不可修改',
  published_by BIGINT NOT NULL COMMENT '发布操作人平台主键',
  published_at DATETIME(6) NOT NULL COMMENT '发布时间UTC',
  PRIMARY KEY (tenant_id, policy_version_id),
  UNIQUE KEY uk_cat_lot_policy_scope (tenant_id, store_id, sku_id, effective_from),
  KEY idx_cat_lot_policy_effective (tenant_id, store_id, sku_id, state, effective_from),
  CONSTRAINT fk_cat_lot_policy_store FOREIGN KEY (tenant_id, store_id)
    REFERENCES jsh_store (tenant_id, store_id),
  CONSTRAINT fk_cat_lot_policy_sku FOREIGN KEY (tenant_id, sku_id)
    REFERENCES cat_sku (tenant_id, sku_id),
  CONSTRAINT fk_cat_lot_policy_template_version FOREIGN KEY (tenant_id, template_version_id)
    REFERENCES jsh_config_template_version (tenant_id, config_version_id),
  CONSTRAINT chk_cat_lot_policy_state CHECK (state='PUBLISHED'),
  CONSTRAINT chk_cat_lot_policy_basis CHECK (expiry_basis IN ('PRODUCTION_DATE','RECEIVED_DATE','EXPLICIT_EXPIRY_DATE')),
  CONSTRAINT chk_cat_lot_policy_days CHECK (near_expiry_days BETWEEN 0 AND 3650 AND
    ((expiry_basis='EXPLICIT_EXPIRY_DATE' AND shelf_life_days IS NULL) OR
     (expiry_basis<>'EXPLICIT_EXPIRY_DATE' AND shelf_life_days BETWEEN 1 AND 36500))),
  CONSTRAINT chk_cat_lot_policy_industry CHECK (enabled=0 OR industry='COMMUNITY_SUPERMARKET'),
  CONSTRAINT chk_cat_lot_policy_hash CHECK (content_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB COMMENT='Catalog Owner不可变商品批次效期策略版本';

DELIMITER $$
CREATE TRIGGER trg_cat_lot_policy_no_update BEFORE UPDATE ON cat_lot_policy_version
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='lot policy versions are immutable'; END$$
CREATE TRIGGER trg_cat_lot_policy_no_delete BEFORE DELETE ON cat_lot_policy_version
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='lot policy versions are immutable'; END$$
DELIMITER ;
