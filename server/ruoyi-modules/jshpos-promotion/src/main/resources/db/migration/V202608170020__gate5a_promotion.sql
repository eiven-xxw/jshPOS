CREATE TABLE prm_rule (
  rule_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  rule_code VARCHAR(64) NOT NULL COMMENT '租户内规则编码',
  rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '规则身份状态',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by BIGINT NOT NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (tenant_id,rule_id),
  UNIQUE KEY uk_prm_rule_code (tenant_id,rule_code),
  CONSTRAINT ck_prm_rule_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB COMMENT='Gate 5A促销规则身份';

CREATE TABLE prm_rule_version (
  rule_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则版本ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  rule_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属规则ULID',
  version_no INT NOT NULL COMMENT '规则版本号',
  rule_type VARCHAR(32) NOT NULL COMMENT '白名单规则类型',
  priority INT NOT NULL COMMENT '优先级数值越大越先执行',
  stack_mode VARCHAR(20) NOT NULL COMMENT '叠加模式',
  exclusive_group VARCHAR(64) NULL COMMENT '互斥组',
  effective_from DATETIME(3) NOT NULL COMMENT '生效开始UTC含',
  effective_to DATETIME(3) NULL COMMENT '生效结束UTC不含',
  state VARCHAR(16) NOT NULL COMMENT '版本状态',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'canonical内容摘要',
  engine_version VARCHAR(32) NOT NULL COMMENT '引擎兼容版本',
  approved_by BIGINT NULL COMMENT '审批人',
  approved_at DATETIME(3) NULL COMMENT '审批时间UTC',
  published_at DATETIME(3) NULL COMMENT '发布时间UTC',
  paused_at DATETIME(3) NULL COMMENT '暂停时间UTC',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by BIGINT NOT NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (tenant_id,rule_version_id),
  UNIQUE KEY uk_prm_rule_version_no (tenant_id,rule_id,version_no),
  CONSTRAINT fk_prm_version_rule FOREIGN KEY (tenant_id,rule_id) REFERENCES prm_rule(tenant_id,rule_id),
  CONSTRAINT ck_prm_version_type CHECK (rule_type IN ('SPECIAL_PRICE','PERCENT_OFF','AMOUNT_OFF','NTH_ITEM_DISCOUNT','BUNDLE_PRICE','THRESHOLD_AMOUNT_OFF','THRESHOLD_QUANTITY_OFF')),
  CONSTRAINT ck_prm_version_stack CHECK (stack_mode IN ('EXCLUSIVE','STACKABLE','BEST_OF_GROUP')),
  CONSTRAINT ck_prm_version_state CHECK (state IN ('DRAFT','VALIDATED','APPROVED','PUBLISHED','PAUSED','RETIRED','REJECTED')),
  CONSTRAINT ck_prm_version_time CHECK (effective_to IS NULL OR effective_to>effective_from),
  CONSTRAINT ck_prm_version_group CHECK (stack_mode<>'BEST_OF_GROUP' OR exclusive_group IS NOT NULL)
) ENGINE=InnoDB COMMENT='Gate 5A版本化促销规则';

CREATE TABLE prm_rule_scope (
  scope_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '范围事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  rule_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则版本ULID',
  dimension_type VARCHAR(16) NOT NULL COMMENT '范围维度',
  dimension_value VARCHAR(64) NOT NULL COMMENT '维度值',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,scope_id),
  UNIQUE KEY uk_prm_scope_value (tenant_id,rule_version_id,dimension_type,dimension_value),
  CONSTRAINT fk_prm_scope_version FOREIGN KEY (tenant_id,rule_version_id) REFERENCES prm_rule_version(tenant_id,rule_version_id),
  CONSTRAINT ck_prm_scope_dimension CHECK (dimension_type IN ('SKU','CATEGORY','BRAND','STORE','CHANNEL','BUSINESS_DAY'))
) ENGINE=InnoDB COMMENT='Gate 5A规则适用范围事实';

CREATE TABLE prm_rule_benefit (
  benefit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  rule_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则版本ULID',
  amount_minor BIGINT NULL COMMENT '固定金额最小货币单位',
  discount_rate DECIMAL(12,8) NULL COMMENT '折扣率0到1',
  nth_value INT NULL COMMENT '第N件参数',
  threshold_minor BIGINT NULL COMMENT '金额门槛最小货币单位',
  threshold_quantity DECIMAL(20,6) NULL COMMENT '数量门槛',
  bundle_price_minor BIGINT NULL COMMENT '组合价最小货币单位',
  bundle_components_json JSON NULL COMMENT '组合组件JSON',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,benefit_id),
  UNIQUE KEY uk_prm_benefit_version (tenant_id,rule_version_id),
  CONSTRAINT fk_prm_benefit_version FOREIGN KEY (tenant_id,rule_version_id) REFERENCES prm_rule_version(tenant_id,rule_version_id),
  CONSTRAINT ck_prm_benefit_money CHECK ((amount_minor IS NULL OR amount_minor>=0) AND (threshold_minor IS NULL OR threshold_minor>=0) AND (bundle_price_minor IS NULL OR bundle_price_minor>=0)),
  CONSTRAINT ck_prm_benefit_rate CHECK (discount_rate IS NULL OR (discount_rate>=0 AND discount_rate<=1)),
  CONSTRAINT ck_prm_benefit_quantity CHECK (threshold_quantity IS NULL OR threshold_quantity>0)
) ENGINE=InnoDB COMMENT='Gate 5A规则优惠参数事实';

CREATE TABLE prm_rule_package (
  package_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '离线规则包ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  store_id BIGINT NOT NULL COMMENT '绑定门店',
  package_version BIGINT NOT NULL COMMENT '单调包版本',
  previous_version BIGINT NOT NULL COMMENT '前一包版本',
  schema_version VARCHAR(16) NOT NULL COMMENT '包Schema版本',
  engine_version VARCHAR(32) NOT NULL COMMENT '兼容引擎版本',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '载荷摘要',
  signature_algorithm VARCHAR(16) NOT NULL COMMENT '签名算法',
  signing_key_id VARCHAR(64) NOT NULL COMMENT '签名密钥标识',
  object_key VARCHAR(512) NOT NULL COMMENT '租户命名空间对象键',
  record_count INT NOT NULL COMMENT '规则记录数',
  generated_at DATETIME(3) NOT NULL COMMENT '生成时间UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '过期时间UTC',
  state VARCHAR(16) NOT NULL COMMENT '包状态',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,package_id),
  UNIQUE KEY uk_prm_package_version (tenant_id,store_id,package_version),
  UNIQUE KEY uk_prm_package_identity (tenant_id,package_id,store_id,package_version),
  CONSTRAINT fk_prm_package_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_prm_package_version CHECK (package_version>0 AND previous_version>=0 AND previous_version<package_version),
  CONSTRAINT ck_prm_package_time CHECK (expires_at>generated_at),
  CONSTRAINT ck_prm_package_state CHECK (state IN ('BUILDING','AVAILABLE','ACTIVE','RETIRED','REJECTED'))
) ENGINE=InnoDB COMMENT='Gate 5A门店绑定离线促销规则包';

CREATE TABLE prm_rule_package_item (
  package_item_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则包条目ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  package_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属离线规则包ULID',
  store_id BIGINT NOT NULL COMMENT '绑定门店，与规则包一致',
  package_version BIGINT NOT NULL COMMENT '冻结的规则包版本',
  ordinal_no INT NOT NULL COMMENT '规则包内稳定序号',
  rule_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结的规则版本ULID',
  rule_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结规则AST摘要',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,package_item_id),
  UNIQUE KEY uk_prm_package_item_ordinal (tenant_id,package_id,ordinal_no),
  UNIQUE KEY uk_prm_package_item_rule (tenant_id,package_id,rule_version_id),
  CONSTRAINT fk_prm_package_item_package FOREIGN KEY (tenant_id,package_id,store_id,package_version) REFERENCES prm_rule_package(tenant_id,package_id,store_id,package_version),
  CONSTRAINT fk_prm_package_item_version FOREIGN KEY (tenant_id,rule_version_id) REFERENCES prm_rule_version(tenant_id,rule_version_id),
  CONSTRAINT ck_prm_package_item_version CHECK (package_version>0 AND ordinal_no>0)
) ENGINE=InnoDB COMMENT='Gate 5A离线规则包冻结条目';

CREATE TABLE prm_quote (
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '询价ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  store_id BIGINT NOT NULL COMMENT '门店',
  terminal_id VARCHAR(64) NOT NULL COMMENT '终端标识',
  idempotency_key VARCHAR(128) NOT NULL COMMENT '询价幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',
  engine_version VARCHAR(32) NOT NULL COMMENT '引擎版本',
  package_version BIGINT NOT NULL COMMENT '规则包版本',
  business_time DATETIME(3) NOT NULL COMMENT '业务时间UTC',
  gross_amount_minor BIGINT NOT NULL COMMENT '原总额',
  discount_amount_minor BIGINT NOT NULL COMMENT '优惠总额',
  payable_amount_minor BIGINT NOT NULL COMMENT '应付总额',
  currency CHAR(3) NOT NULL COMMENT 'ISO4217币种',
  result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果摘要',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,quote_id),
  UNIQUE KEY uk_prm_quote_idempotency (tenant_id,store_id,terminal_id,idempotency_key),
  CONSTRAINT fk_prm_quote_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT fk_prm_quote_package FOREIGN KEY (tenant_id,store_id,package_version) REFERENCES prm_rule_package(tenant_id,store_id,package_version),
  CONSTRAINT ck_prm_quote_amount CHECK (gross_amount_minor>=0 AND discount_amount_minor>=0 AND payable_amount_minor>=0 AND gross_amount_minor=discount_amount_minor+payable_amount_minor)
) ENGINE=InnoDB COMMENT='Gate 5A确定性促销询价事实';

CREATE TABLE prm_quote_line (
  quote_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '询价行事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '询价ULID',
  source_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '购物行ULID',
  line_no INT NOT NULL COMMENT '稳定行号',
  sku_id BIGINT NOT NULL COMMENT 'SKU',
  quantity DECIMAL(20,6) NOT NULL COMMENT '基础单位数量',
  unit_price_minor BIGINT NOT NULL COMMENT '基础或门店单价',
  gross_amount_minor BIGINT NOT NULL COMMENT '行原金额',
  discount_amount_minor BIGINT NOT NULL COMMENT '行优惠金额',
  payable_amount_minor BIGINT NOT NULL COMMENT '行应付金额',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,quote_line_id),
  UNIQUE KEY uk_prm_quote_line_no (tenant_id,quote_id,line_no),
  UNIQUE KEY uk_prm_quote_source_line (tenant_id,quote_id,source_line_id),
  CONSTRAINT fk_prm_quote_line_quote FOREIGN KEY (tenant_id,quote_id) REFERENCES prm_quote(tenant_id,quote_id),
  CONSTRAINT ck_prm_quote_line_amount CHECK (gross_amount_minor>=0 AND discount_amount_minor>=0 AND payable_amount_minor>=0 AND gross_amount_minor=discount_amount_minor+payable_amount_minor)
) ENGINE=InnoDB COMMENT='Gate 5A促销询价行事实';

CREATE TABLE prm_adjustment (
  adjustment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '优惠调整事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '询价ULID',
  source_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '购物行ULID空表示整单',
  source_type VARCHAR(24) NOT NULL COMMENT '优惠来源类型',
  source_id VARCHAR(64) NOT NULL COMMENT '规则或授权来源标识',
  calculation_stage VARCHAR(24) NOT NULL COMMENT '固定计算阶段',
  amount_minor BIGINT NOT NULL COMMENT '优惠金额',
  explanation_code VARCHAR(48) NOT NULL COMMENT '解释码',
  applied_flag TINYINT(1) NOT NULL COMMENT '是否实际采用',
  ordinal_no INT NOT NULL COMMENT '稳定解释序号',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,adjustment_id),
  UNIQUE KEY uk_prm_adjustment_ordinal (tenant_id,quote_id,ordinal_no),
  CONSTRAINT fk_prm_adjustment_quote FOREIGN KEY (tenant_id,quote_id) REFERENCES prm_quote(tenant_id,quote_id),
  CONSTRAINT ck_prm_adjustment_amount CHECK (amount_minor>=0)
) ENGINE=InnoDB COMMENT='Gate 5A优惠采用与排除解释事实';

CREATE TABLE prm_command_result (
  command_result_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令结果ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  command_type VARCHAR(48) NOT NULL COMMENT '命令类型',
  idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',
  aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型',
  aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合标识',
  result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果摘要',
  result_json JSON NOT NULL COMMENT '脱敏结果JSON',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,command_result_id),
  UNIQUE KEY uk_prm_command_key (tenant_id,command_type,idempotency_key)
) ENGINE=InnoDB COMMENT='Gate 5A命令幂等结果事实';

CREATE TABLE prm_audit_event (
  audit_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '领域审计ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  action_code VARCHAR(64) NOT NULL COMMENT '动作码',
  target_type VARCHAR(32) NOT NULL COMMENT '目标类型',
  target_id VARCHAR(64) NOT NULL COMMENT '目标标识',
  actor_user_id BIGINT NOT NULL COMMENT '操作者',
  correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',
  before_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '变更前摘要',
  after_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '变更后摘要',
  summary_json JSON NOT NULL COMMENT '脱敏审计摘要',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  PRIMARY KEY (tenant_id,audit_event_id),
  KEY idx_prm_audit_target (tenant_id,target_type,target_id,occurred_at)
) ENGINE=InnoDB COMMENT='Gate 5A促销领域不可变审计事件';

CREATE TABLE prm_event_outbox (
  outbox_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Outbox事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  event_type VARCHAR(64) NOT NULL COMMENT '版本化事件类型',
  aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型',
  aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合标识',
  aggregate_version BIGINT NOT NULL COMMENT '聚合版本',
  payload_json JSON NOT NULL COMMENT '事件载荷JSON',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '载荷摘要',
  delivery_state VARCHAR(16) NOT NULL COMMENT '投递状态',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  available_at DATETIME(3) NOT NULL COMMENT '下次可投时间UTC',
  delivered_at DATETIME(3) NULL COMMENT '投递完成时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,outbox_id),
  UNIQUE KEY uk_prm_outbox_aggregate (tenant_id,event_type,aggregate_id,aggregate_version),
  KEY idx_prm_outbox_delivery (tenant_id,delivery_state,available_at),
  CONSTRAINT ck_prm_outbox_state CHECK (delivery_state IN ('NEW','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB COMMENT='Gate 5A促销领域事件Outbox';

DELIMITER $$
CREATE TRIGGER trg_prm_rule_version_content_immutable BEFORE UPDATE ON prm_rule_version FOR EACH ROW
BEGIN
  IF NOT (OLD.rule_id<=>NEW.rule_id) OR OLD.version_no<>NEW.version_no OR OLD.rule_type<>NEW.rule_type
     OR OLD.priority<>NEW.priority OR OLD.stack_mode<>NEW.stack_mode
     OR NOT (OLD.exclusive_group<=>NEW.exclusive_group) OR OLD.effective_from<>NEW.effective_from
     OR NOT (OLD.effective_to<=>NEW.effective_to) OR OLD.content_sha256<>NEW.content_sha256
     OR OLD.engine_version<>NEW.engine_version OR OLD.created_by<>NEW.created_by THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='published rule content is immutable';
  END IF;
END$$
CREATE TRIGGER trg_prm_scope_no_update BEFORE UPDATE ON prm_rule_scope FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_rule_scope is immutable'; END$$
CREATE TRIGGER trg_prm_scope_no_delete BEFORE DELETE ON prm_rule_scope FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_rule_scope is immutable'; END$$
CREATE TRIGGER trg_prm_benefit_no_update BEFORE UPDATE ON prm_rule_benefit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_rule_benefit is immutable'; END$$
CREATE TRIGGER trg_prm_benefit_no_delete BEFORE DELETE ON prm_rule_benefit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_rule_benefit is immutable'; END$$
CREATE TRIGGER trg_prm_package_item_no_update BEFORE UPDATE ON prm_rule_package_item FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_rule_package_item is immutable'; END$$
CREATE TRIGGER trg_prm_package_item_no_delete BEFORE DELETE ON prm_rule_package_item FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_rule_package_item is immutable'; END$$
CREATE TRIGGER trg_prm_quote_no_update BEFORE UPDATE ON prm_quote FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_quote is immutable'; END$$
CREATE TRIGGER trg_prm_quote_no_delete BEFORE DELETE ON prm_quote FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_quote is immutable'; END$$
CREATE TRIGGER trg_prm_audit_no_update BEFORE UPDATE ON prm_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_audit_event is immutable'; END$$
CREATE TRIGGER trg_prm_audit_no_delete BEFORE DELETE ON prm_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_audit_event is immutable'; END$$
DELIMITER ;
