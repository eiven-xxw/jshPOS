ALTER TABLE ord_sales_order
  DROP CHECK ck_ord_amounts,
  ADD CONSTRAINT ck_ord_amounts CHECK (
    gross_amount_minor >= 0 AND discount_amount_minor >= 0 AND surcharge_amount_minor >= 0
    AND receivable_amount_minor >= 0
    AND receivable_amount_minor = gross_amount_minor - discount_amount_minor + surcharge_amount_minor
    AND received_amount_minor >= 0
  );

ALTER TABLE ord_order_line
  DROP CHECK ck_ord_line_amount,
  ADD CONSTRAINT ck_ord_line_amount CHECK (
    unit_price_minor >= 0 AND gross_amount_minor >= 0 AND discount_amount_minor >= 0
    AND surcharge_amount_minor >= 0 AND payable_amount_minor >= 0
    AND payable_amount_minor = gross_amount_minor - discount_amount_minor + surcharge_amount_minor
  );

CREATE TABLE ord_promotion_binding (
  binding_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单促销绑定ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner订单ULID',
  promotion_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Promotion Owner成交快照ULID只读引用',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Promotion Owner原报价ULID只读引用',
  store_id BIGINT NOT NULL COMMENT '可信门店平台ID',
  terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信POS终端ULID',
  business_date DATE NOT NULL COMMENT '门店业务日',
  quote_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原报价SHA-256十六进制摘要',
  settlement_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '人工优惠后最终SHA-256十六进制摘要',
  package_version BIGINT NOT NULL COMMENT '成交使用的促销规则包单调版本',
  promotion_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '促销成交快照SHA-256十六进制摘要',
  order_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'POS订单快照SHA-256十六进制摘要',
  gross_amount_minor BIGINT NOT NULL COMMENT '订单原金额最小货币单位分',
  discount_amount_minor BIGINT NOT NULL COMMENT '订单优惠最小货币单位分',
  surcharge_amount_minor BIGINT NOT NULL COMMENT '订单附加费最小货币单位分',
  receivable_amount_minor BIGINT NOT NULL COMMENT '订单应收最小货币单位分',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原结算命令关联ULID',
  created_at DATETIME(3) NOT NULL COMMENT '服务端核验绑定时间UTC',
  PRIMARY KEY (tenant_id,binding_id),
  UNIQUE KEY uk_ord_promotion_order (tenant_id,order_id),
  UNIQUE KEY uk_ord_promotion_snapshot (tenant_id,promotion_snapshot_id),
  KEY idx_ord_promotion_store_day (tenant_id,store_id,business_date,order_id),
  CONSTRAINT fk_ord_promotion_order FOREIGN KEY (tenant_id,order_id)
    REFERENCES ord_sales_order(tenant_id,order_id),
  CONSTRAINT ck_ord_promotion_version CHECK (package_version>0),
  CONSTRAINT ck_ord_promotion_hash CHECK (
    quote_fingerprint REGEXP '^[a-f0-9]{64}$'
    AND settlement_fingerprint REGEXP '^[a-f0-9]{64}$'
    AND promotion_snapshot_sha256 REGEXP '^[a-f0-9]{64}$'
    AND order_snapshot_sha256 REGEXP '^[a-f0-9]{64}$'
  ),
  CONSTRAINT ck_ord_promotion_amount CHECK (
    gross_amount_minor>=0 AND discount_amount_minor>=0 AND surcharge_amount_minor>=0
    AND receivable_amount_minor>=0
    AND receivable_amount_minor=gross_amount_minor-discount_amount_minor+surcharge_amount_minor
  )
) ENGINE=InnoDB COMMENT='ORD-003订单对不可变促销成交快照的核验绑定';

DELIMITER $$
CREATE TRIGGER trg_ord_promotion_binding_no_update
BEFORE UPDATE ON ord_promotion_binding FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_promotion_binding is immutable'; END$$
CREATE TRIGGER trg_ord_promotion_binding_no_delete
BEFORE DELETE ON ord_promotion_binding FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ord_promotion_binding is immutable'; END$$
DELIMITER ;
