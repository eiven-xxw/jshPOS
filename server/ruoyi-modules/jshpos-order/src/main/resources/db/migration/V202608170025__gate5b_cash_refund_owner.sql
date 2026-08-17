CREATE TABLE ord_cash_refund (
  cash_refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner现金退款事实ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Return Owner退货退款ULID只读引用',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交订单ULID',
  original_cash_payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成功现金收款ULID',
  refund_shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际退款OPEN班次ULID',
  store_id BIGINT NOT NULL COMMENT '可信门店平台ID',
  terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际退款POS终端ULID',
  business_date DATE NOT NULL COMMENT '实际退款门店业务日',
  status VARCHAR(16) NOT NULL COMMENT '现金退款状态仅SUCCEEDED',
  amount_minor BIGINT NOT NULL COMMENT '现金退款金额最小货币单位分',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种固定CNY',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '跨Owner请求SHA-256摘要',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  actor_user_id BIGINT NOT NULL COMMENT '可信退款操作人平台ID',
  occurred_at DATETIME(3) NOT NULL COMMENT '退款发生时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端落账时间UTC',
  PRIMARY KEY (tenant_id,cash_refund_id),
  UNIQUE KEY uk_ord_cash_refund_id (tenant_id,refund_id),
  KEY idx_ord_cash_refund_payment (tenant_id,original_cash_payment_id,occurred_at),
  CONSTRAINT fk_ord_cash_refund_order FOREIGN KEY (tenant_id,order_id)
    REFERENCES ord_sales_order(tenant_id,order_id),
  CONSTRAINT fk_ord_cash_refund_payment FOREIGN KEY (tenant_id,original_cash_payment_id)
    REFERENCES ord_cash_payment(tenant_id,cash_payment_id),
  CONSTRAINT fk_ord_cash_refund_shift FOREIGN KEY (tenant_id,refund_shift_id)
    REFERENCES shf_shift(tenant_id,shift_id),
  CONSTRAINT ck_ord_cash_refund_status CHECK (status='SUCCEEDED'),
  CONSTRAINT ck_ord_cash_refund_amount CHECK (amount_minor>0),
  CONSTRAINT ck_ord_cash_refund_currency CHECK (currency='CNY'),
  CONSTRAINT ck_ord_cash_refund_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='Order Owner只追加现金退款事实';

ALTER TABLE shf_cash_ledger
  DROP INDEX uk_cash_ledger_payment,
  DROP CHECK ck_cash_ledger_type,
  DROP CHECK ck_cash_ledger_amount,
  ADD COLUMN cash_refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '现金退款事实ULID；销售收款为空' AFTER cash_payment_id,
  ADD UNIQUE KEY uk_cash_ledger_refund (tenant_id,cash_refund_id,movement_type),
  ADD CONSTRAINT fk_cash_ledger_refund FOREIGN KEY (tenant_id,cash_refund_id)
    REFERENCES ord_cash_refund(tenant_id,cash_refund_id),
  ADD CONSTRAINT ck_cash_ledger_type CHECK (movement_type IN ('SALE_RECEIPT','CASH_REFUND')),
  ADD CONSTRAINT ck_cash_ledger_amount CHECK (
    (movement_type='SALE_RECEIPT' AND signed_amount_minor>=0 AND cash_refund_id IS NULL)
    OR (movement_type='CASH_REFUND' AND signed_amount_minor<0 AND cash_refund_id IS NOT NULL)
  );

DELIMITER $$
CREATE TRIGGER trg_ord_cash_refund_no_update
BEFORE UPDATE ON ord_cash_refund FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_cash_refund is immutable'; END$$
CREATE TRIGGER trg_ord_cash_refund_no_delete
BEFORE DELETE ON ord_cash_refund FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_cash_refund is immutable'; END$$
DELIMITER ;
