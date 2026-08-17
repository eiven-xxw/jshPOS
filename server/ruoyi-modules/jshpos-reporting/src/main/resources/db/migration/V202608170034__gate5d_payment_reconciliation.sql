CREATE TABLE rpt_payment_fact_inbox (
  source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Payment或Refund来源事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  source_owner VARCHAR(16) NOT NULL COMMENT 'PAYMENT或REFUND Owner',
  source_sequence BIGINT UNSIGNED NOT NULL COMMENT '来源分区内单调序号',
  partition_key VARCHAR(96) NOT NULL COMMENT '来源分区键',
  schema_version VARCHAR(16) NOT NULL COMMENT '事件Schema版本',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范内容SHA-256',
  occurred_at DATETIME(3) NOT NULL COMMENT '来源事实发生时间UTC',
  business_date DATE NOT NULL COMMENT '来源Owner冻结业务日',
  org_id BIGINT NOT NULL COMMENT '组织标识',
  store_id BIGINT NOT NULL COMMENT '门店标识',
  terminal_id VARCHAR(64) NOT NULL COMMENT '终端标识',
  fact_type VARCHAR(16) NOT NULL COMMENT 'PAYMENT或REFUND事实类型',
  reconciliation_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付尝试或退款ULID匹配键',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原订单ULID',
  amount_minor BIGINT NOT NULL COMMENT '最小货币单位金额',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种CNY',
  lifecycle_status VARCHAR(16) NOT NULL COMMENT 'SUCCEEDED FAILED UNKNOWN',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  applied_at DATETIME(3) NOT NULL COMMENT 'Reporting接收时间UTC',
  PRIMARY KEY (tenant_id,source_event_id),
  UNIQUE KEY uk_rpt_pay_fact_key (tenant_id,reconciliation_key),
  UNIQUE KEY uk_rpt_pay_fact_sequence (tenant_id,source_owner,partition_key,source_sequence),
  KEY idx_rpt_pay_fact_day (tenant_id,store_id,business_date,fact_type),
  CONSTRAINT ck_rpt_pay_fact_owner CHECK (source_owner IN ('PAYMENT','REFUND')),
  CONSTRAINT ck_rpt_pay_fact_type CHECK (fact_type IN ('PAYMENT','REFUND')),
  CONSTRAINT ck_rpt_pay_fact_owner_type CHECK (source_owner=fact_type),
  CONSTRAINT ck_rpt_pay_fact_amount CHECK (amount_minor>=0),
  CONSTRAINT ck_rpt_pay_fact_currency CHECK (currency='CNY'),
  CONSTRAINT ck_rpt_pay_fact_status CHECK (lifecycle_status IN ('SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB COMMENT='Gate 5D Provider无关支付退款事实只追加Inbox；XML_ONLY';

CREATE TABLE rpt_internal_bill_inbox (
  bill_entry_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部合成账单条目ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  batch_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部合成批次ULID',
  source_type VARCHAR(32) NOT NULL COMMENT '固定INTERNAL_SYNTHETIC',
  synthetic TINYINT(1) NOT NULL COMMENT '必须为1且不构成SANDBOX证据',
  schema_version VARCHAR(16) NOT NULL COMMENT '内部账单Schema版本',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范内容SHA-256',
  business_date DATE NOT NULL COMMENT '合成账单业务日',
  org_id BIGINT NOT NULL COMMENT '组织标识',
  store_id BIGINT NOT NULL COMMENT '门店标识',
  terminal_id VARCHAR(64) NOT NULL COMMENT '终端标识',
  fact_type VARCHAR(16) NOT NULL COMMENT 'PAYMENT或REFUND账单类型',
  reconciliation_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付尝试或退款ULID匹配键',
  amount_minor BIGINT NOT NULL COMMENT '最小货币单位金额',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种CNY',
  lifecycle_status VARCHAR(16) NOT NULL COMMENT 'SUCCEEDED FAILED UNKNOWN',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  imported_by BIGINT NOT NULL COMMENT '可信导入用户标识',
  imported_at DATETIME(3) NOT NULL COMMENT '内部合成账单导入时间UTC',
  PRIMARY KEY (tenant_id,bill_entry_id),
  UNIQUE KEY uk_rpt_bill_key (tenant_id,reconciliation_key),
  KEY idx_rpt_bill_batch (tenant_id,batch_id,bill_entry_id),
  KEY idx_rpt_bill_day (tenant_id,store_id,business_date,fact_type),
  CONSTRAINT ck_rpt_bill_source CHECK (source_type='INTERNAL_SYNTHETIC' AND synthetic=1),
  CONSTRAINT ck_rpt_bill_type CHECK (fact_type IN ('PAYMENT','REFUND')),
  CONSTRAINT ck_rpt_bill_amount CHECK (amount_minor>=0),
  CONSTRAINT ck_rpt_bill_currency CHECK (currency='CNY'),
  CONSTRAINT ck_rpt_bill_status CHECK (lifecycle_status IN ('SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB COMMENT='Gate 5D内部合成账单只追加Inbox；不等同渠道账单或SANDBOX；XML_ONLY';

CREATE TABLE rpt_payment_reconciliation (
  reconciliation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '与规范匹配键一致的对账ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  reconciliation_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付尝试或退款匹配键',
  fact_type VARCHAR(16) NOT NULL COMMENT 'PAYMENT或REFUND',
  source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Provider无关内部事实事件ULID',
  bill_entry_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '内部合成账单条目ULID',
  business_date DATE NOT NULL COMMENT '查询归属业务日；优先内部事实',
  org_id BIGINT NOT NULL COMMENT '组织标识',
  store_id BIGINT NOT NULL COMMENT '门店标识',
  terminal_id VARCHAR(64) NOT NULL COMMENT '终端标识',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '查询归属币种',
  internal_amount_minor BIGINT NULL COMMENT '内部事实最小货币单位金额',
  bill_amount_minor BIGINT NULL COMMENT '合成账单最小货币单位金额',
  internal_status VARCHAR(16) NULL COMMENT '内部事实SUCCEEDED FAILED UNKNOWN',
  bill_status VARCHAR(16) NULL COMMENT '合成账单SUCCEEDED FAILED UNKNOWN',
  internal_business_date DATE NULL COMMENT '内部事实冻结业务日',
  bill_business_date DATE NULL COMMENT '合成账单业务日',
  difference_type VARCHAR(32) NOT NULL COMMENT 'MATCHED或固定差异分类',
  handling_state VARCHAR(16) NOT NULL COMMENT 'MATCHED OPEN ASSIGNED RESOLVED IGNORED',
  handler_id BIGINT NULL COMMENT '可信差异处理人',
  source_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '内部事实内容摘要',
  bill_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '合成账单内容摘要',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  detected_at DATETIME(3) NOT NULL COMMENT '当前结论首次检出时间UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '当前结论更新时间UTC',
  PRIMARY KEY (tenant_id,reconciliation_id),
  UNIQUE KEY uk_rpt_recon_key (tenant_id,reconciliation_key),
  KEY idx_rpt_recon_query (tenant_id,store_id,business_date,difference_type,handling_state),
  CONSTRAINT ck_rpt_recon_side CHECK (source_event_id IS NOT NULL OR bill_entry_id IS NOT NULL),
  CONSTRAINT ck_rpt_recon_type CHECK (fact_type IN ('PAYMENT','REFUND')),
  CONSTRAINT ck_rpt_recon_currency CHECK (currency='CNY'),
  CONSTRAINT ck_rpt_recon_difference CHECK (difference_type IN ('MATCHED','MISSING_BILL','MISSING_INTERNAL','AMOUNT_MISMATCH','CURRENCY_MISMATCH','STATUS_MISMATCH','BUSINESS_DATE_MISMATCH')),
  CONSTRAINT ck_rpt_recon_handling CHECK (handling_state IN ('MATCHED','OPEN','ASSIGNED','RESOLVED','IGNORED')),
  CONSTRAINT ck_rpt_recon_match_state CHECK ((difference_type='MATCHED' AND handling_state='MATCHED') OR (difference_type<>'MATCHED' AND handling_state<>'MATCHED'))
) ENGINE=InnoDB COMMENT='Gate 5D可丢弃Provider无关内部合成对账投影；READ_PROJECTION';

CREATE TABLE rpt_reconciliation_audit (
  audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对账审计ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  reconciliation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对账ULID',
  action_type VARCHAR(24) NOT NULL COMMENT 'SYSTEM_CLASSIFIED或MANUAL_TRANSITION',
  from_difference_type VARCHAR(32) NULL COMMENT '变更前差异类型',
  to_difference_type VARCHAR(32) NOT NULL COMMENT '变更后差异类型',
  from_handling_state VARCHAR(16) NULL COMMENT '变更前处理状态',
  to_handling_state VARCHAR(16) NOT NULL COMMENT '变更后处理状态',
  operator_id BIGINT NOT NULL COMMENT '0表示系统或可信操作人',
  reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏原因SHA-256',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '审计发生时间UTC',
  PRIMARY KEY (tenant_id,audit_id),
  KEY idx_rpt_recon_audit (tenant_id,reconciliation_id,occurred_at,audit_id),
  CONSTRAINT ck_rpt_recon_audit_action CHECK (action_type IN ('SYSTEM_CLASSIFIED','MANUAL_TRANSITION')),
  CONSTRAINT ck_rpt_recon_audit_operator CHECK (operator_id>=0)
) ENGINE=InnoDB COMMENT='Gate 5D对账处理与系统分类只追加审计链；XML_ONLY';

ALTER TABLE rpt_export_request MODIFY report_type VARCHAR(32) NOT NULL
  COMMENT 'SALES_DAILY、INVENTORY_COST_DAILY或PAYMENT_RECONCILIATION';

DELIMITER $$
CREATE TRIGGER trg_rpt_payment_fact_no_update BEFORE UPDATE ON rpt_payment_fact_inbox FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='payment reconciliation fact is immutable'$$
CREATE TRIGGER trg_rpt_payment_fact_no_delete BEFORE DELETE ON rpt_payment_fact_inbox FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='payment reconciliation fact cannot be deleted'$$
CREATE TRIGGER trg_rpt_internal_bill_no_update BEFORE UPDATE ON rpt_internal_bill_inbox FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='internal synthetic bill is immutable'$$
CREATE TRIGGER trg_rpt_internal_bill_no_delete BEFORE DELETE ON rpt_internal_bill_inbox FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='internal synthetic bill cannot be deleted'$$
CREATE TRIGGER trg_rpt_recon_audit_no_update BEFORE UPDATE ON rpt_reconciliation_audit FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reconciliation audit is immutable'$$
CREATE TRIGGER trg_rpt_recon_audit_no_delete BEFORE DELETE ON rpt_reconciliation_audit FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reconciliation audit cannot be deleted'$$
DELIMITER ;
