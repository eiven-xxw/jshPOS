CREATE TABLE mbr_points_account (
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  available_points DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '可用积分投影',
  frozen_points DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '冻结积分投影',
  debt_points DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '退货扣回不足形成的显式债务',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  last_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最后已投影流水ULID',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (tenant_id,member_id),
  CONSTRAINT fk_mbr_points_account_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT ck_mbr_points_account_nonnegative CHECK (available_points>=0 AND frozen_points>=0 AND debt_points>=0)
) ENGINE=InnoDB COMMENT='Gate 5C可从积分流水重建的账户投影；XML_ONLY';

CREATE TABLE mbr_points_ledger (
  ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '积分流水ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  event_type VARCHAR(32) NOT NULL COMMENT '积分流水类型',
  amount DECIMAL(19,6) NOT NULL COMMENT '动作绝对积分数量',
  available_delta DECIMAL(19,6) NOT NULL COMMENT '可用积分增量',
  frozen_delta DECIMAL(19,6) NOT NULL COMMENT '冻结积分增量',
  debt_delta DECIMAL(19,6) NOT NULL COMMENT '积分债务增量',
  source_type VARCHAR(24) NOT NULL COMMENT 'ORDER RETURN ONLINE_REDEMPTION SYSTEM_EXPIRY MANUAL',
  source_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源事实ULID',
  original_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '冲正或冻结结算引用的原流水ULID',
  policy_version VARCHAR(64) NOT NULL COMMENT '命中时冻结的策略版本',
  idempotency_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令幂等键ULID',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范请求摘要',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  store_id BIGINT NOT NULL COMMENT '经权限校验的业务门店',
  business_date DATE NOT NULL COMMENT '按门店时区和日切计算的业务日',
  reason_code VARCHAR(32) NOT NULL COMMENT '结构化积分变动原因码',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作人或内部执行器',
  approval_user_id BIGINT NULL COMMENT '受权人工动作审批人',
  approval_ref CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受权审批事实ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '业务发生时间UTC',
  expires_at DATETIME(3) NULL COMMENT '新积分批次到期时间UTC',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变流水内容摘要',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,ledger_id),
  UNIQUE KEY uk_mbr_points_command (tenant_id,idempotency_key),
  KEY idx_mbr_points_member_time (tenant_id,member_id,occurred_at,ledger_id),
  KEY idx_mbr_points_store_day (tenant_id,store_id,business_date,ledger_id),
  KEY idx_mbr_points_original (tenant_id,original_ledger_id,event_type),
  KEY idx_mbr_points_source (tenant_id,source_type,source_id),
  CONSTRAINT fk_mbr_points_ledger_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT fk_mbr_points_ledger_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_mbr_points_type CHECK (event_type IN ('EARN','FREEZE','UNFREEZE','SPEND','EXPIRE','RETURN_EARN_REVERSAL','RETURN_SPEND_REVERSAL','MANUAL_ADJUST')),
  CONSTRAINT ck_mbr_points_source CHECK (source_type IN ('ORDER','RETURN','ONLINE_REDEMPTION','SYSTEM_EXPIRY','MANUAL')),
  CONSTRAINT ck_mbr_points_amount CHECK (amount>0),
  CONSTRAINT ck_mbr_points_actor CHECK (actor_user_id>0),
  CONSTRAINT ck_mbr_points_approval CHECK ((approval_user_id IS NULL AND approval_ref IS NULL) OR (approval_user_id>0 AND approval_ref IS NOT NULL))
) ENGINE=InnoDB COMMENT='Gate 5C只追加积分流水；XML_ONLY';

CREATE TABLE mbr_points_lot (
  lot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '积分批次ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  earn_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '产生批次的正向流水ULID',
  original_points DECIMAL(19,6) NOT NULL COMMENT '批次原始积分',
  available_points DECIMAL(19,6) NOT NULL COMMENT '批次可用投影',
  frozen_points DECIMAL(19,6) NOT NULL COMMENT '批次冻结投影',
  policy_version VARCHAR(64) NOT NULL COMMENT '产生批次时策略版本',
  expires_at DATETIME(3) NULL COMMENT '到期时间UTC；空值最后消费',
  occurred_at DATETIME(3) NOT NULL COMMENT '批次发生时间UTC',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (tenant_id,lot_id),
  UNIQUE KEY uk_mbr_points_lot_earn (tenant_id,earn_ledger_id),
  KEY idx_mbr_points_lot_fefo (tenant_id,member_id,expires_at,occurred_at,lot_id),
  CONSTRAINT fk_mbr_points_lot_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT ck_mbr_points_lot_nonnegative CHECK (original_points>=0 AND available_points>=0 AND frozen_points>=0 AND available_points+frozen_points<=original_points)
) ENGINE=InnoDB COMMENT='Gate 5C可从流水与分配重建的FEFO批次投影；XML_ONLY';

CREATE TABLE mbr_points_allocation (
  allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '积分分配ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当前动作流水ULID',
  lot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原积分批次ULID',
  parent_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原冻结或原消费流水ULID',
  points DECIMAL(19,6) NOT NULL COMMENT '本次分配积分',
  allocation_type VARCHAR(32) NOT NULL COMMENT '冻结消费解冻到期或退还类型',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,allocation_id),
  UNIQUE KEY uk_mbr_points_allocation (tenant_id,ledger_id,lot_id,allocation_type),
  KEY idx_mbr_points_allocation_parent (tenant_id,parent_ledger_id,lot_id,allocation_type),
  CONSTRAINT fk_mbr_points_allocation_lot FOREIGN KEY (tenant_id,lot_id) REFERENCES mbr_points_lot(tenant_id,lot_id),
  CONSTRAINT ck_mbr_points_allocation_type CHECK (allocation_type IN ('FREEZE','SPEND','UNFREEZE','EXPIRE','RETURN_EARN_REVERSAL','RETURN_SPEND_REVERSAL','MANUAL_ADJUST')),
  CONSTRAINT ck_mbr_points_allocation_positive CHECK (points>0)
) ENGINE=InnoDB COMMENT='Gate 5C只追加积分批次分配事实；XML_ONLY';

CREATE TABLE mbr_level_history (
  history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '等级历史ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  level_code VARCHAR(32) NOT NULL COMMENT '等级编码',
  policy_version VARCHAR(64) NOT NULL COMMENT '等级策略版本',
  reason_code VARCHAR(32) NOT NULL COMMENT '变更原因编码',
  store_id BIGINT NOT NULL COMMENT '经权限校验的业务门店',
  business_date DATE NOT NULL COMMENT '等级事实业务日',
  actor_user_id BIGINT NOT NULL COMMENT '操作人',
  approval_user_id BIGINT NOT NULL COMMENT '独立审批人',
  approval_ref CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审批事实ULID',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  effective_at DATETIME(3) NOT NULL COMMENT '生效时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,history_id),
  KEY idx_mbr_level_current (tenant_id,member_id,effective_at,history_id),
  KEY idx_mbr_level_store_day (tenant_id,store_id,business_date,history_id),
  CONSTRAINT fk_mbr_level_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT fk_mbr_level_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_mbr_level_actors CHECK (actor_user_id>0 AND approval_user_id>0 AND actor_user_id<>approval_user_id)
) ENGINE=InnoDB COMMENT='Gate 5C只追加会员等级历史；XML_ONLY';

DELIMITER $$
CREATE TRIGGER trg_mbr_points_ledger_no_update BEFORE UPDATE ON mbr_points_ledger
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member points ledger is immutable'; END$$
CREATE TRIGGER trg_mbr_points_ledger_no_delete BEFORE DELETE ON mbr_points_ledger
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member points ledger cannot be deleted'; END$$
CREATE TRIGGER trg_mbr_points_allocation_no_update BEFORE UPDATE ON mbr_points_allocation
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member points allocation is immutable'; END$$
CREATE TRIGGER trg_mbr_points_allocation_no_delete BEFORE DELETE ON mbr_points_allocation
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member points allocation cannot be deleted'; END$$
CREATE TRIGGER trg_mbr_level_history_no_update BEFORE UPDATE ON mbr_level_history
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member level history is immutable'; END$$
CREATE TRIGGER trg_mbr_level_history_no_delete BEFORE DELETE ON mbr_level_history
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member level history cannot be deleted'; END$$
DELIMITER ;
