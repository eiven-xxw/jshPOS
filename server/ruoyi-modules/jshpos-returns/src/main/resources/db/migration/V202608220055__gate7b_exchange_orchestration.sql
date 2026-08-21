-- EXG-001：为新退货保存原始稳定命令身份；历史行保持NULL并禁止事后补写。
ALTER TABLE ret_return
  ADD COLUMN request_command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT 'POS原始退货命令ULID；V55前历史行为空' AFTER request_sha256,
  ADD KEY idx_ret_return_request_command (tenant_id,request_command_id);

-- EXG-001：只保存原退货退款与新销售两条腿的可恢复关联，不创建第三笔资金或库存事实。
CREATE TABLE ret_exchange (
  exchange_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货Saga ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信上下文注入的租户ID',
  idempotency_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建换货稳定幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建命令规范内容SHA-256',
  return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Return Owner原退货退款ULID只读引用',
  original_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner原成交订单ULID只读引用',
  original_return_command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '必须复用的原退货命令ULID',
  new_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner新销售订单ULID只读引用',
  new_sale_command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '必须复用的新销售命令ULID',
  store_id BIGINT NOT NULL COMMENT '可信门店平台ID',
  terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '办理换货POS终端ULID',
  business_date DATE NOT NULL COMMENT '新销售所属门店业务日',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '两条腿币种；商业V1固定CNY',
  expected_refund_amount_minor BIGINT NOT NULL COMMENT '冻结原退货预期金额，单位分',
  actual_refund_amount_minor BIGINT NULL COMMENT 'Return Owner完成后观察的权威退款金额，单位分',
  expected_sale_receivable_minor BIGINT NOT NULL COMMENT '冻结新销售应收金额，单位分',
  actual_sale_receivable_minor BIGINT NULL COMMENT 'Order Owner完成后观察的权威应收金额，单位分',
  quote_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '新销售冻结报价SHA-256',
  new_sale_plan_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'POS冻结新销售计划SHA-256，成交后另存Order权威快照',
  actual_new_order_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Order Owner观察的新订单快照SHA-256',
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '具名可恢复Saga检查点',
  requester_user_id BIGINT NOT NULL COMMENT '可信申请员工平台ID',
  approver_user_id BIGINT NULL COMMENT '独立审批员工平台ID',
  reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货原因码',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '换货创建UTC时间',
  record_version BIGINT NOT NULL COMMENT 'Saga乐观锁版本',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端创建UTC时间',
  updated_at DATETIME(3) NOT NULL COMMENT '最近检查点UTC时间',
  PRIMARY KEY (tenant_id,exchange_id),
  UNIQUE KEY uk_ret_exchange_idempotency (tenant_id,idempotency_key),
  UNIQUE KEY uk_ret_exchange_return (tenant_id,return_id),
  UNIQUE KEY uk_ret_exchange_new_order (tenant_id,new_order_id),
  KEY idx_ret_exchange_store_state (tenant_id,store_id,status,updated_at,exchange_id),
  CONSTRAINT ck_ret_exchange_orders CHECK (original_order_id<>new_order_id),
  CONSTRAINT ck_ret_exchange_currency CHECK (currency='CNY'),
  CONSTRAINT ck_ret_exchange_amount CHECK (expected_refund_amount_minor>0 AND expected_sale_receivable_minor>0
    AND (actual_refund_amount_minor IS NULL OR actual_refund_amount_minor>=0)
    AND (actual_sale_receivable_minor IS NULL OR actual_sale_receivable_minor>=0)),
  CONSTRAINT ck_ret_exchange_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'
    AND quote_fingerprint REGEXP '^[a-f0-9]{64}$'
    AND new_sale_plan_sha256 REGEXP '^[a-f0-9]{64}$'
    AND (actual_new_order_snapshot_sha256 IS NULL OR actual_new_order_snapshot_sha256 REGEXP '^[a-f0-9]{64}$')),
  CONSTRAINT ck_ret_exchange_status CHECK (status IN ('DRAFT','APPROVED','RETURN_PENDING','RETURN_UNKNOWN',
    'RETURN_COMPLETED','SALE_PENDING','SALE_UNKNOWN','COMPLETED','FAILED','MANUAL_RECOVERY_REQUIRED','CLOSED')),
  CONSTRAINT ck_ret_exchange_version CHECK (record_version>0),
  CONSTRAINT ck_ret_exchange_approval CHECK ((status='DRAFT' AND approver_user_id IS NULL)
    OR (status<>'DRAFT' AND approver_user_id IS NOT NULL AND approver_user_id<>requester_user_id))
) ENGINE=InnoDB COMMENT='ReturnOrchestration Owner换货可恢复Saga头；不保存第三笔资金库存事实';

CREATE TABLE ret_exchange_leg (
  leg_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货腿ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信上下文注入的租户ID',
  exchange_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货Saga ULID',
  leg_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RETURN原退货腿或SALE新销售腿',
  owner_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威Owner代码RETURN或ORDER',
  owner_aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner聚合ULID只读引用',
  owner_command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '必须复用的Owner命令ULID',
  expected_amount_minor BIGINT NOT NULL COMMENT '该腿冻结预期金额，单位分',
  frozen_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该腿身份和金额规范SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '冻结UTC时间',
  PRIMARY KEY (tenant_id,leg_id),
  UNIQUE KEY uk_ret_exchange_leg_type (tenant_id,exchange_id,leg_type),
  UNIQUE KEY uk_ret_exchange_leg_owner (tenant_id,owner_code,owner_aggregate_id),
  CONSTRAINT fk_ret_exchange_leg FOREIGN KEY (tenant_id,exchange_id)
    REFERENCES ret_exchange(tenant_id,exchange_id),
  CONSTRAINT ck_ret_exchange_leg_type CHECK ((leg_type='RETURN' AND owner_code='RETURN')
    OR (leg_type='SALE' AND owner_code='ORDER')),
  CONSTRAINT ck_ret_exchange_leg_amount CHECK (expected_amount_minor>0),
  CONSTRAINT ck_ret_exchange_leg_hash CHECK (frozen_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='换货两条不可变Owner腿；只追加且不保存Owner可变状态';

CREATE TABLE ret_exchange_event (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信上下文注入的租户ID',
  exchange_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货Saga ULID',
  from_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '迁移前状态；创建时为空',
  to_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '迁移后状态',
  owner_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '触发来源EXCHANGE、RETURN或ORDER',
  owner_aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源Owner聚合ULID',
  owner_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源观察或命令ULID',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源观察规范SHA-256',
  aggregate_version BIGINT NOT NULL COMMENT '迁移后换货Saga版本',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作者平台ID',
  reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '具名迁移原因',
  occurred_at DATETIME(3) NOT NULL COMMENT '迁移UTC时间',
  PRIMARY KEY (tenant_id,event_id),
  UNIQUE KEY uk_ret_exchange_event_version (tenant_id,exchange_id,aggregate_version),
  KEY idx_ret_exchange_event_owner (tenant_id,owner_code,owner_aggregate_id,occurred_at),
  CONSTRAINT fk_ret_exchange_event FOREIGN KEY (tenant_id,exchange_id)
    REFERENCES ret_exchange(tenant_id,exchange_id),
  CONSTRAINT ck_ret_exchange_event_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ret_exchange_event_version CHECK (aggregate_version>0)
) ENGINE=InnoDB COMMENT='换货Saga只追加状态、观察和人工恢复事件';

CREATE TABLE ret_exchange_idempotency (
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信上下文注入的租户ID',
  command_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '换货命令类型',
  idempotency_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '终端稳定幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令规范内容SHA-256',
  exchange_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次成功换货Saga ULID',
  created_at DATETIME(3) NOT NULL COMMENT '首次成功UTC时间',
  PRIMARY KEY (tenant_id,command_type,idempotency_key),
  CONSTRAINT ck_ret_exchange_idem_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='换货创建命令不可变幂等绑定';

DELIMITER $$
CREATE TRIGGER trg_ret_return_request_command_immutable BEFORE UPDATE ON ret_return FOR EACH ROW
BEGIN
  IF NOT (OLD.request_command_id<=>NEW.request_command_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_return request command is immutable';
  END IF;
END$$
CREATE TRIGGER trg_ret_exchange_identity BEFORE UPDATE ON ret_exchange FOR EACH ROW
BEGIN
  IF NOT (OLD.exchange_id<=>NEW.exchange_id AND OLD.tenant_id<=>NEW.tenant_id
    AND OLD.idempotency_key<=>NEW.idempotency_key AND OLD.request_sha256<=>NEW.request_sha256
    AND OLD.return_id<=>NEW.return_id AND OLD.original_order_id<=>NEW.original_order_id
    AND OLD.original_return_command_id<=>NEW.original_return_command_id
    AND OLD.new_order_id<=>NEW.new_order_id AND OLD.new_sale_command_id<=>NEW.new_sale_command_id
    AND OLD.store_id<=>NEW.store_id AND OLD.terminal_id<=>NEW.terminal_id
    AND OLD.business_date<=>NEW.business_date AND OLD.currency<=>NEW.currency
    AND OLD.expected_refund_amount_minor<=>NEW.expected_refund_amount_minor
    AND OLD.expected_sale_receivable_minor<=>NEW.expected_sale_receivable_minor
    AND OLD.quote_fingerprint<=>NEW.quote_fingerprint
    AND OLD.new_sale_plan_sha256<=>NEW.new_sale_plan_sha256
    AND OLD.requester_user_id<=>NEW.requester_user_id AND OLD.reason_code<=>NEW.reason_code
    AND OLD.correlation_id<=>NEW.correlation_id AND OLD.occurred_at<=>NEW.occurred_at
    AND OLD.created_at<=>NEW.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange immutable identity changed';
  END IF;
END$$
CREATE TRIGGER trg_ret_exchange_no_delete BEFORE DELETE ON ret_exchange FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange cannot be deleted'; END$$
CREATE TRIGGER trg_ret_exchange_leg_no_update BEFORE UPDATE ON ret_exchange_leg FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange_leg is append-only'; END$$
CREATE TRIGGER trg_ret_exchange_leg_no_delete BEFORE DELETE ON ret_exchange_leg FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange_leg is append-only'; END$$
CREATE TRIGGER trg_ret_exchange_event_no_update BEFORE UPDATE ON ret_exchange_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange_event is append-only'; END$$
CREATE TRIGGER trg_ret_exchange_event_no_delete BEFORE DELETE ON ret_exchange_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange_event is append-only'; END$$
CREATE TRIGGER trg_ret_exchange_idem_no_update BEFORE UPDATE ON ret_exchange_idempotency FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange_idempotency is append-only'; END$$
CREATE TRIGGER trg_ret_exchange_idem_no_delete BEFORE DELETE ON ret_exchange_idempotency FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ret_exchange_idempotency is append-only'; END$$
DELIMITER ;
