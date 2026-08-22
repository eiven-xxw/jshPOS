CREATE TABLE ord_tender_settlement (
  settlement_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner结算完成事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Payment Owner已付计划只读引用',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原不可变订单ULID',
  effective_status VARCHAR(24) NOT NULL COMMENT '派生订单有效状态固定COMPLETED',
  effective_payment_status VARCHAR(16) NOT NULL COMMENT '派生支付状态固定PAID',
  received_amount_minor BIGINT NOT NULL COMMENT '权威成功份额合计分',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种固定CNY',
  order_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原订单快照SHA-256',
  plan_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付计划内容SHA-256',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  order_aggregate_version BIGINT NOT NULL COMMENT '追加结算事实后的订单聚合版本',
  occurred_at DATETIME(3) NOT NULL COMMENT '结算发生时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端落账时间UTC',
  PRIMARY KEY (settlement_id),
  UNIQUE KEY uk_ord_tender_settlement_tenant_id (tenant_id,settlement_id),
  UNIQUE KEY uk_ord_tender_settlement_plan (tenant_id,plan_id),
  UNIQUE KEY uk_ord_tender_settlement_order (tenant_id,order_id),
  CONSTRAINT fk_ord_tender_settlement_order FOREIGN KEY (tenant_id,order_id)
    REFERENCES ord_sales_order(tenant_id,order_id),
  CONSTRAINT ck_ord_tender_settlement_state CHECK
    (effective_status='COMPLETED' AND effective_payment_status='PAID'),
  CONSTRAINT ck_ord_tender_settlement_amount CHECK (received_amount_minor>0),
  CONSTRAINT ck_ord_tender_settlement_currency CHECK (currency='CNY'),
  CONSTRAINT ck_ord_tender_settlement_hash CHECK
    (order_snapshot_sha256 REGEXP '^[a-f0-9]{64}$' AND plan_content_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ord_tender_settlement_version CHECK (order_aggregate_version>0)
) ENGINE=InnoDB COMMENT='Order Owner组合支付完成只追加事实；原订单快照不更新';

CREATE TABLE ord_cash_tender (
  cash_tender_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner部分现金事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Payment Owner支付计划只读引用',
  allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Payment Owner现金份额只读引用',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '待支付订单ULID',
  shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际收现OPEN班次ULID',
  store_id BIGINT NOT NULL COMMENT '可信门店平台主键',
  terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信POS终端ULID',
  cashier_user_id BIGINT NOT NULL COMMENT '可信收银员平台主键',
  business_date DATE NOT NULL COMMENT '冻结门店业务日',
  status VARCHAR(16) NOT NULL COMMENT '现金份额状态仅SUCCEEDED',
  amount_minor BIGINT NOT NULL COMMENT '应用到订单的现金份额分',
  tendered_minor BIGINT NOT NULL COMMENT '顾客实际交付现金分',
  change_minor BIGINT NOT NULL COMMENT '找零分；不进入班次净现金',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种固定CNY',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收取命令SHA-256',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '收现时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端落账时间UTC',
  PRIMARY KEY (cash_tender_id),
  UNIQUE KEY uk_ord_cash_tender_tenant_id (tenant_id,cash_tender_id),
  UNIQUE KEY uk_ord_cash_tender_allocation (tenant_id,allocation_id),
  KEY idx_ord_cash_tender_order (tenant_id,order_id,occurred_at),
  CONSTRAINT fk_ord_cash_tender_order FOREIGN KEY (tenant_id,order_id)
    REFERENCES ord_sales_order(tenant_id,order_id),
  CONSTRAINT fk_ord_cash_tender_shift FOREIGN KEY (tenant_id,shift_id)
    REFERENCES shf_shift(tenant_id,shift_id),
  CONSTRAINT fk_ord_cash_tender_store FOREIGN KEY (tenant_id,store_id)
    REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_ord_cash_tender_status CHECK (status='SUCCEEDED'),
  CONSTRAINT ck_ord_cash_tender_amount CHECK (amount_minor>0 AND tendered_minor>=amount_minor
    AND change_minor=tendered_minor-amount_minor),
  CONSTRAINT ck_ord_cash_tender_currency CHECK (currency='CNY'),
  CONSTRAINT ck_ord_cash_tender_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='Order和Shift Owner部分现金只追加事实';

ALTER TABLE shf_cash_ledger
  DROP CHECK ck_cash_ledger_type,
  DROP CHECK ck_cash_ledger_amount,
  ADD COLUMN cash_tender_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '部分现金份额ULID；传统现金和退款为空' AFTER cash_refund_id,
  ADD UNIQUE KEY uk_cash_ledger_tender (tenant_id,cash_tender_id,movement_type),
  ADD CONSTRAINT fk_cash_ledger_tender FOREIGN KEY (tenant_id,cash_tender_id)
    REFERENCES ord_cash_tender(tenant_id,cash_tender_id),
  ADD CONSTRAINT ck_cash_ledger_type CHECK (movement_type IN ('SALE_RECEIPT','CASH_REFUND','TENDER_RECEIPT')),
  ADD CONSTRAINT ck_cash_ledger_amount CHECK (
    (movement_type='SALE_RECEIPT' AND signed_amount_minor>=0 AND cash_payment_id IS NOT NULL
        AND cash_refund_id IS NULL AND cash_tender_id IS NULL)
    OR (movement_type='CASH_REFUND' AND signed_amount_minor<0 AND cash_payment_id IS NOT NULL
        AND cash_refund_id IS NOT NULL AND cash_tender_id IS NULL)
    OR (movement_type='TENDER_RECEIPT' AND signed_amount_minor>0 AND cash_refund_id IS NULL
        AND cash_payment_id IS NULL AND cash_tender_id IS NOT NULL)
  );

DELIMITER $$
CREATE TRIGGER trg_ord_tender_settlement_no_update BEFORE UPDATE ON ord_tender_settlement FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_tender_settlement is immutable'; END$$
CREATE TRIGGER trg_ord_tender_settlement_no_delete BEFORE DELETE ON ord_tender_settlement FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_tender_settlement is immutable'; END$$
CREATE TRIGGER trg_ord_cash_tender_no_update BEFORE UPDATE ON ord_cash_tender FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_cash_tender is immutable'; END$$
CREATE TRIGGER trg_ord_cash_tender_no_delete BEFORE DELETE ON ord_cash_tender FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_cash_tender is immutable'; END$$
DELIMITER ;
