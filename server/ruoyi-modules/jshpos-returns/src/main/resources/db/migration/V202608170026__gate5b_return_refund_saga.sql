CREATE TABLE ret_order_guard (
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交订单ULID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '守卫首次创建时间UTC',
  PRIMARY KEY (tenant_id,order_id)
) ENGINE=InnoDB COMMENT='Return Owner按订单串行校验累计退货上限的锁锚点';

CREATE TABLE ret_return (
  return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货退款Saga ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  idempotency_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '终端稳定申请幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原申请规范内容SHA-256',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner原成交订单ULID只读引用',
  store_id BIGINT NOT NULL COMMENT '可信门店平台ID',
  terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '办理退货POS终端ULID',
  refund_shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '办理退货OPEN班次ULID',
  warehouse_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货入库仓ULID',
  business_date DATE NOT NULL COMMENT '退货门店业务日',
  settlement_kind VARCHAR(24) NOT NULL COMMENT '结算类型CASH或PROVIDER_NEUTRAL',
  payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Payment Owner原支付ULID；现金为空',
  original_cash_payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Order Owner原现金收款ULID；电子支付为空',
  promotion_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Promotion Owner原成交快照ULID只读引用',
  promotion_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交快照SHA-256摘要',
  status VARCHAR(24) NOT NULL COMMENT 'Saga持久化检查点状态',
  gross_amount_minor BIGINT NULL COMMENT '本次退货原金额分；Promotion Owner确认后冻结',
  recovered_discount_minor BIGINT NULL COMMENT '按原快照恢复优惠分；Promotion Owner确认后冻结',
  refundable_amount_minor BIGINT NULL COMMENT '本次应退款金额分；Promotion Owner确认后冻结',
  promotion_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定促销恢复Owner事件ULID',
  payment_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定现金或支付退款Owner事件ULID',
  inventory_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定退货入库Owner事件ULID',
  requester_user_id BIGINT NOT NULL COMMENT '可信申请人平台ID',
  approver_user_id BIGINT NULL COMMENT '独立审批人平台ID',
  reason_code VARCHAR(32) NOT NULL COMMENT '退货退款原因码',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '申请发生时间UTC',
  record_version BIGINT NOT NULL COMMENT 'Saga乐观锁版本',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近检查点更新时间UTC',
  PRIMARY KEY (tenant_id,return_id),
  UNIQUE KEY uk_ret_idempotency (tenant_id,idempotency_key),
  UNIQUE KEY uk_ret_promotion_event (tenant_id,promotion_event_id),
  UNIQUE KEY uk_ret_payment_event (tenant_id,payment_event_id),
  UNIQUE KEY uk_ret_inventory_event (tenant_id,inventory_event_id),
  KEY idx_ret_order_state (tenant_id,order_id,status,return_id),
  KEY idx_ret_store_day (tenant_id,store_id,business_date,status),
  CONSTRAINT ck_ret_kind CHECK (settlement_kind IN ('CASH','PROVIDER_NEUTRAL')),
  CONSTRAINT ck_ret_payment_shape CHECK (
    (settlement_kind='CASH' AND payment_id IS NULL AND original_cash_payment_id IS NOT NULL)
    OR (settlement_kind='PROVIDER_NEUTRAL' AND payment_id IS NOT NULL AND original_cash_payment_id IS NULL)
  ),
  CONSTRAINT ck_ret_status CHECK (status IN ('PENDING_APPROVAL','PROMOTION_PENDING','CASH_REFUND_PENDING',
    'PAYMENT_PENDING','PAYMENT_UNKNOWN','INVENTORY_PENDING','COMPLETED','FAILED')),
  CONSTRAINT ck_ret_amounts CHECK (
    (gross_amount_minor IS NULL AND recovered_discount_minor IS NULL AND refundable_amount_minor IS NULL)
    OR (gross_amount_minor>=0 AND recovered_discount_minor>=0 AND refundable_amount_minor>=0
      AND gross_amount_minor-recovered_discount_minor=refundable_amount_minor)
  ),
  CONSTRAINT ck_ret_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'
    AND promotion_snapshot_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ret_version CHECK (record_version>0),
  CONSTRAINT ck_ret_approval CHECK ((status='PENDING_APPROVAL' AND approver_user_id IS NULL)
    OR (status<>'PENDING_APPROVAL' AND approver_user_id IS NOT NULL AND approver_user_id<>requester_user_id))
) ENGINE=InnoDB COMMENT='Return Owner原单退货退款可恢复Saga头';

CREATE TABLE ret_return_line (
  return_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货行ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货退款Saga ULID',
  order_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交订单行ULID',
  sku_id BIGINT NOT NULL COMMENT '原成交SKU平台ID',
  unit_id BIGINT NOT NULL COMMENT '原成交基础单位平台ID',
  requested_quantity DECIMAL(19,6) NOT NULL COMMENT '本次退货精确数量',
  gross_amount_minor BIGINT NULL COMMENT '本行原金额分；Promotion Owner确认后冻结',
  recovered_discount_minor BIGINT NULL COMMENT '本行恢复优惠分；Promotion Owner确认后冻结',
  refundable_amount_minor BIGINT NULL COMMENT '本行应退款金额分；Promotion Owner确认后冻结',
  cumulative_quantity DECIMAL(19,6) NULL COMMENT '执行后该原订单行累计退货数量',
  cumulative_payable_amount_minor BIGINT NULL COMMENT '执行后该原订单行累计退款金额分',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端创建时间UTC',
  PRIMARY KEY (tenant_id,return_line_id),
  UNIQUE KEY uk_ret_line_source (tenant_id,return_id,order_line_id),
  CONSTRAINT fk_ret_line_header FOREIGN KEY (tenant_id,return_id) REFERENCES ret_return(tenant_id,return_id),
  CONSTRAINT ck_ret_line_qty CHECK (requested_quantity>0),
  CONSTRAINT ck_ret_line_amount CHECK (
    (gross_amount_minor IS NULL AND recovered_discount_minor IS NULL AND refundable_amount_minor IS NULL
      AND cumulative_quantity IS NULL AND cumulative_payable_amount_minor IS NULL)
    OR (gross_amount_minor>=0 AND recovered_discount_minor>=0 AND refundable_amount_minor>=0
      AND gross_amount_minor-recovered_discount_minor=refundable_amount_minor
      AND cumulative_quantity>=requested_quantity AND cumulative_payable_amount_minor>=refundable_amount_minor)
  )
) ENGINE=InnoDB COMMENT='Return Owner退货行及Promotion Owner恢复结果检查点';

CREATE TABLE ret_state_history (
  history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态历史ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货退款Saga ULID',
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '触发状态迁移事件ULID',
  from_status VARCHAR(24) NULL COMMENT '迁移前状态；创建时为空',
  to_status VARCHAR(24) NOT NULL COMMENT '迁移后状态',
  aggregate_version BIGINT NOT NULL COMMENT '迁移后的Saga版本',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作者平台ID',
  reason_code VARCHAR(32) NOT NULL COMMENT '迁移原因码',
  occurred_at DATETIME(3) NOT NULL COMMENT '迁移发生时间UTC',
  PRIMARY KEY (tenant_id,history_id),
  UNIQUE KEY uk_ret_history_version (tenant_id,return_id,aggregate_version),
  CONSTRAINT fk_ret_history_header FOREIGN KEY (tenant_id,return_id) REFERENCES ret_return(tenant_id,return_id)
) ENGINE=InnoDB COMMENT='Return Owner只追加Saga状态历史';

CREATE TABLE ret_inbox (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner结果事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  owner_code VARCHAR(24) NOT NULL COMMENT '结果来源Owner代码',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货退款Saga ULID',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果载荷SHA-256摘要',
  received_at DATETIME(3) NOT NULL COMMENT '服务端接收时间UTC',
  PRIMARY KEY (tenant_id,event_id),
  KEY idx_ret_inbox_aggregate (tenant_id,aggregate_id,received_at),
  CONSTRAINT ck_ret_inbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='Return Owner跨Owner结果幂等Inbox';

CREATE TABLE ret_outbox (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定跨Owner事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  event_type VARCHAR(64) NOT NULL COMMENT '版本化事件类型',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货退款Saga ULID',
  aggregate_version BIGINT NOT NULL COMMENT '发布时Saga版本',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  payload_json JSON NOT NULL COMMENT '脱敏规范事件载荷',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '载荷SHA-256摘要',
  delivery_state VARCHAR(16) NOT NULL COMMENT 'PENDING或DELIVERED',
  available_at DATETIME(3) NOT NULL COMMENT '允许投递时间UTC',
  delivered_at DATETIME(3) NULL COMMENT 'Owner结果被持久化时间UTC',
  PRIMARY KEY (tenant_id,event_id),
  KEY idx_ret_outbox_delivery (delivery_state,available_at,tenant_id,event_id),
  CONSTRAINT ck_ret_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ret_outbox_state CHECK (delivery_state IN ('PENDING','DELIVERED')),
  CONSTRAINT ck_ret_outbox_delivery CHECK ((delivery_state='PENDING' AND delivered_at IS NULL)
    OR (delivery_state='DELIVERED' AND delivered_at IS NOT NULL))
) ENGINE=InnoDB COMMENT='Return Owner跨Owner至少一次投递Outbox';

CREATE TABLE ret_idempotency (
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  command_type VARCHAR(48) NOT NULL COMMENT '命令类型',
  idempotency_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令规范内容SHA-256',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次成功创建的退货退款ULID',
  created_at DATETIME(3) NOT NULL COMMENT '服务端创建时间UTC',
  PRIMARY KEY (tenant_id,command_type,idempotency_key),
  CONSTRAINT ck_ret_idem_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='Return Owner申请命令不可变幂等绑定';

DELIMITER $$
CREATE TRIGGER trg_ret_guard_no_update BEFORE UPDATE ON ret_order_guard FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_order_guard is immutable'; END$$
CREATE TRIGGER trg_ret_guard_no_delete BEFORE DELETE ON ret_order_guard FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_order_guard is immutable'; END$$
CREATE TRIGGER trg_ret_return_identity BEFORE UPDATE ON ret_return FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id<=>NEW.tenant_id AND OLD.return_id<=>NEW.return_id AND OLD.idempotency_key<=>NEW.idempotency_key
    AND OLD.request_sha256<=>NEW.request_sha256 AND OLD.order_id<=>NEW.order_id AND OLD.store_id<=>NEW.store_id
    AND OLD.terminal_id<=>NEW.terminal_id AND OLD.refund_shift_id<=>NEW.refund_shift_id
    AND OLD.warehouse_id<=>NEW.warehouse_id AND OLD.business_date<=>NEW.business_date
    AND OLD.settlement_kind<=>NEW.settlement_kind AND OLD.payment_id<=>NEW.payment_id
    AND OLD.original_cash_payment_id<=>NEW.original_cash_payment_id
    AND OLD.promotion_snapshot_id<=>NEW.promotion_snapshot_id
    AND OLD.promotion_snapshot_sha256<=>NEW.promotion_snapshot_sha256
    AND OLD.requester_user_id<=>NEW.requester_user_id AND OLD.reason_code<=>NEW.reason_code
    AND OLD.correlation_id<=>NEW.correlation_id AND OLD.occurred_at<=>NEW.occurred_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_return immutable identity changed';
  END IF;
END$$
CREATE TRIGGER trg_ret_return_no_delete BEFORE DELETE ON ret_return FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_return cannot be deleted'; END$$
CREATE TRIGGER trg_ret_line_guard BEFORE UPDATE ON ret_return_line FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id<=>NEW.tenant_id AND OLD.return_line_id<=>NEW.return_line_id
    AND OLD.return_id<=>NEW.return_id AND OLD.order_line_id<=>NEW.order_line_id
    AND OLD.sku_id<=>NEW.sku_id AND OLD.unit_id<=>NEW.unit_id
    AND OLD.requested_quantity<=>NEW.requested_quantity) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_return_line immutable input changed';
  END IF;
END$$
CREATE TRIGGER trg_ret_line_no_delete BEFORE DELETE ON ret_return_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_return_line cannot be deleted'; END$$
CREATE TRIGGER trg_ret_history_no_update BEFORE UPDATE ON ret_state_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_state_history is append-only'; END$$
CREATE TRIGGER trg_ret_history_no_delete BEFORE DELETE ON ret_state_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_state_history is append-only'; END$$
CREATE TRIGGER trg_ret_inbox_no_update BEFORE UPDATE ON ret_inbox FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_inbox is immutable'; END$$
CREATE TRIGGER trg_ret_inbox_no_delete BEFORE DELETE ON ret_inbox FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_inbox is immutable'; END$$
CREATE TRIGGER trg_ret_outbox_guard BEFORE UPDATE ON ret_outbox FOR EACH ROW
BEGIN
  IF NOT (OLD.event_id<=>NEW.event_id AND OLD.tenant_id<=>NEW.tenant_id AND OLD.event_type<=>NEW.event_type
    AND OLD.aggregate_id<=>NEW.aggregate_id AND OLD.aggregate_version<=>NEW.aggregate_version
    AND OLD.correlation_id<=>NEW.correlation_id AND OLD.payload_json<=>NEW.payload_json
    AND OLD.payload_sha256<=>NEW.payload_sha256 AND OLD.available_at<=>NEW.available_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_outbox payload is immutable';
  END IF;
END$$
CREATE TRIGGER trg_ret_outbox_no_delete BEFORE DELETE ON ret_outbox FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_outbox cannot be deleted'; END$$
CREATE TRIGGER trg_ret_idem_no_update BEFORE UPDATE ON ret_idempotency FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_idempotency is immutable'; END$$
CREATE TRIGGER trg_ret_idem_no_delete BEFORE DELETE ON ret_idempotency FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_idempotency is immutable'; END$$
DELIMITER ;
