CREATE TABLE pay_tender_plan (
  plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '组合支付计划ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Order Owner待支付订单ULID',
  order_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结订单快照SHA-256',
  store_id BIGINT NOT NULL COMMENT '权威订单门店平台主键',
  terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威订单终端ULID',
  shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威订单班次ULID',
  business_date DATE NOT NULL COMMENT '冻结门店业务日',
  status VARCHAR(32) NOT NULL COMMENT 'FROZEN/COLLECTING/UNKNOWN/PAID/FAILED/CANCELLED/MANUAL_RECOVERY_REQUIRED',
  receivable_amount_minor BIGINT NOT NULL COMMENT '订单应收金额最小货币单位分',
  succeeded_amount_minor BIGINT NOT NULL DEFAULT 0 COMMENT '权威成功份额合计分',
  occupied_amount_minor BIGINT NOT NULL DEFAULT 0 COMMENT '成功处理中未知份额占额合计分',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种固定CNY',
  allocation_count TINYINT UNSIGNED NOT NULL COMMENT '冻结份额数量2至8',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '计划规范内容SHA-256',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '端到端关联ULID',
  record_version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观并发版本',
  frozen_at DATETIME(3) NOT NULL COMMENT '计划冻结时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '投影更新时间UTC',
  PRIMARY KEY (plan_id),
  UNIQUE KEY uk_pay_tender_plan_tenant_id (tenant_id,plan_id),
  UNIQUE KEY uk_pay_tender_plan_order (tenant_id,order_id),
  KEY idx_pay_tender_plan_status (tenant_id,store_id,status,updated_at),
  CONSTRAINT fk_pay_tender_plan_order FOREIGN KEY (tenant_id,order_id)
    REFERENCES ord_sales_order(tenant_id,order_id),
  CONSTRAINT fk_pay_tender_plan_store FOREIGN KEY (tenant_id,store_id)
    REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT fk_pay_tender_plan_shift FOREIGN KEY (tenant_id,shift_id)
    REFERENCES shf_shift(tenant_id,shift_id),
  CONSTRAINT ck_pay_tender_plan_status CHECK (status IN
    ('FROZEN','COLLECTING','UNKNOWN','PAID','FAILED','CANCELLED','MANUAL_RECOVERY_REQUIRED')),
  CONSTRAINT ck_pay_tender_plan_amount CHECK (receivable_amount_minor>0
    AND succeeded_amount_minor>=0 AND occupied_amount_minor>=succeeded_amount_minor
    AND occupied_amount_minor<=receivable_amount_minor),
  CONSTRAINT ck_pay_tender_plan_currency CHECK (currency='CNY'),
  CONSTRAINT ck_pay_tender_plan_count CHECK (allocation_count BETWEEN 2 AND 8),
  CONSTRAINT ck_pay_tender_plan_hash CHECK (order_snapshot_sha256 REGEXP '^[a-f0-9]{64}$'
    AND content_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_pay_tender_plan_version CHECK (record_version>0)
) ENGINE=InnoDB COMMENT='Payment Owner组合支付冻结计划';

CREATE TABLE pay_tender_allocation (
  allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付份额ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '组合支付计划ULID',
  sequence_no TINYINT UNSIGNED NOT NULL COMMENT '严格串行顺序1至8',
  tender_type VARCHAR(16) NOT NULL COMMENT 'CASH或ELECTRONIC',
  status VARCHAR(16) NOT NULL COMMENT 'PLANNED/PROCESSING/UNKNOWN/SUCCEEDED/FAILED/CANCELLED',
  amount_minor BIGINT NOT NULL COMMENT '冻结份额金额分',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种固定CNY',
  allocation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '份额规范内容SHA-256',
  owner_fact_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '成功后原Owner资金事实ULID',
  observation_ref CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近可信观察ULID',
  collection_command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次收取稳定命令ULID',
  collection_request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次收取请求SHA-256',
  record_version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观并发版本',
  created_at DATETIME(3) NOT NULL COMMENT '份额冻结时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '投影更新时间UTC',
  PRIMARY KEY (allocation_id),
  UNIQUE KEY uk_pay_tender_allocation_tenant_id (tenant_id,allocation_id),
  UNIQUE KEY uk_pay_tender_allocation_plan_id (tenant_id,plan_id,allocation_id),
  UNIQUE KEY uk_pay_tender_allocation_sequence (tenant_id,plan_id,sequence_no),
  UNIQUE KEY uk_pay_tender_allocation_owner_fact (tenant_id,owner_fact_id),
  KEY idx_pay_tender_allocation_status (tenant_id,plan_id,status,sequence_no),
  CONSTRAINT fk_pay_tender_allocation_plan FOREIGN KEY (tenant_id,plan_id)
    REFERENCES pay_tender_plan(tenant_id,plan_id),
  CONSTRAINT ck_pay_tender_allocation_type CHECK (tender_type IN ('CASH','ELECTRONIC')),
  CONSTRAINT ck_pay_tender_allocation_status CHECK (status IN
    ('PLANNED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED')),
  CONSTRAINT ck_pay_tender_allocation_amount CHECK (amount_minor>0),
  CONSTRAINT ck_pay_tender_allocation_currency CHECK (currency='CNY'),
  CONSTRAINT ck_pay_tender_allocation_hash CHECK (allocation_sha256 REGEXP '^[a-f0-9]{64}$'
    AND (collection_request_sha256 IS NULL OR collection_request_sha256 REGEXP '^[a-f0-9]{64}$')),
  CONSTRAINT ck_pay_tender_allocation_version CHECK (record_version>0)
) ENGINE=InnoDB COMMENT='Payment Owner组合支付冻结份额投影';

CREATE TABLE pay_tender_history (
  history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '只追加历史ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '服务端可信租户标识',
  plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付计划ULID',
  allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '份额ULID；计划事件为空',
  history_scope_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (COALESCE(allocation_id,plan_id)) STORED COMMENT '计划/份额统一唯一作用域',
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定命令或观察ULID',
  aggregate_type VARCHAR(24) NOT NULL COMMENT 'TENDER_PLAN或TENDER_ALLOCATION',
  from_status VARCHAR(32) NULL COMMENT '迁移前状态；创建为空',
  to_status VARCHAR(32) NOT NULL COMMENT '迁移后状态',
  aggregate_version BIGINT NOT NULL COMMENT '迁移后聚合版本',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '迁移输入摘要',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作者平台主键',
  reason_code VARCHAR(32) NOT NULL COMMENT '具名迁移原因',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  PRIMARY KEY (history_id),
  UNIQUE KEY uk_pay_tender_history_tenant_id (tenant_id,history_id),
  UNIQUE KEY uk_pay_tender_history_command_state
    (tenant_id,aggregate_type,history_scope_id,command_id,to_status),
  KEY idx_pay_tender_history_plan (tenant_id,plan_id,occurred_at),
  CONSTRAINT fk_pay_tender_history_plan FOREIGN KEY (tenant_id,plan_id)
    REFERENCES pay_tender_plan(tenant_id,plan_id),
  CONSTRAINT ck_pay_tender_history_type CHECK (aggregate_type IN ('TENDER_PLAN','TENDER_ALLOCATION')),
  CONSTRAINT ck_pay_tender_history_version CHECK (aggregate_version>0),
  CONSTRAINT ck_pay_tender_history_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='组合支付状态只追加历史';

ALTER TABLE pay_payment_intent
  DROP INDEX uk_pay_intent_order,
  ADD COLUMN tender_plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '组合支付计划ULID；旧单支付为空' AFTER order_id,
  ADD COLUMN tender_allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '电子份额ULID；旧单支付为空' AFTER tender_plan_id,
  ADD COLUMN legacy_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN tender_plan_id IS NULL THEN order_id ELSE NULL END) STORED
    COMMENT '保持旧单支付一单一个意图的唯一投影' AFTER tender_allocation_id,
  ADD UNIQUE KEY uk_pay_intent_legacy_order (tenant_id,legacy_order_id),
  ADD UNIQUE KEY uk_pay_intent_tender_allocation (tenant_id,tender_plan_id,tender_allocation_id),
  ADD CONSTRAINT fk_pay_intent_tender_plan FOREIGN KEY (tenant_id,tender_plan_id)
    REFERENCES pay_tender_plan(tenant_id,plan_id),
  ADD CONSTRAINT fk_pay_intent_tender_allocation FOREIGN KEY (tenant_id,tender_plan_id,tender_allocation_id)
    REFERENCES pay_tender_allocation(tenant_id,plan_id,allocation_id),
  ADD CONSTRAINT ck_pay_intent_tender_binding CHECK (
    (tender_plan_id IS NULL AND tender_allocation_id IS NULL)
    OR (tender_plan_id IS NOT NULL AND tender_allocation_id IS NOT NULL)
  );

DELIMITER $$
CREATE TRIGGER trg_pay_tender_plan_immutable BEFORE UPDATE ON pay_tender_plan FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id<=>NEW.tenant_id AND OLD.order_id<=>NEW.order_id
    AND OLD.order_snapshot_sha256<=>NEW.order_snapshot_sha256 AND OLD.store_id<=>NEW.store_id
    AND OLD.terminal_id<=>NEW.terminal_id AND OLD.shift_id<=>NEW.shift_id
    AND OLD.business_date<=>NEW.business_date AND OLD.receivable_amount_minor<=>NEW.receivable_amount_minor
    AND OLD.currency<=>NEW.currency AND OLD.allocation_count<=>NEW.allocation_count
    AND OLD.content_sha256<=>NEW.content_sha256 AND OLD.correlation_id<=>NEW.correlation_id
    AND OLD.frozen_at<=>NEW.frozen_at)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tender plan immutable content cannot change'; END IF;
END$$
CREATE TRIGGER trg_pay_tender_allocation_immutable BEFORE UPDATE ON pay_tender_allocation FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id<=>NEW.tenant_id AND OLD.plan_id<=>NEW.plan_id
    AND OLD.sequence_no<=>NEW.sequence_no AND OLD.tender_type<=>NEW.tender_type
    AND OLD.amount_minor<=>NEW.amount_minor AND OLD.currency<=>NEW.currency
    AND OLD.allocation_sha256<=>NEW.allocation_sha256 AND OLD.created_at<=>NEW.created_at)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tender allocation immutable content cannot change'; END IF;
END$$
CREATE TRIGGER trg_pay_tender_history_no_update BEFORE UPDATE ON pay_tender_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tender history is append-only'; END$$
CREATE TRIGGER trg_pay_tender_history_no_delete BEFORE DELETE ON pay_tender_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tender history is append-only'; END$$
CREATE TRIGGER trg_pay_tender_plan_no_delete BEFORE DELETE ON pay_tender_plan FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tender plan cannot be deleted'; END$$
CREATE TRIGGER trg_pay_tender_allocation_no_delete BEFORE DELETE ON pay_tender_allocation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tender allocation cannot be deleted'; END$$

CREATE PROCEDURE jsh_assert_gate7b_pay004_menu_ids()
BEGIN
  IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200410 AND 9200414 AND NOT (
    (menu_id=9200410 AND perms='payment:tender:create') OR
    (menu_id=9200411 AND perms='payment:tender:read') OR
    (menu_id=9200412 AND perms='payment:tender:collect') OR
    (menu_id=9200413 AND perms='payment:tender:cancel') OR
    (menu_id=9200414 AND perms='payment:tender:recover')
  )) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 7B PAY004 sys_menu reserved ID collision'; END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate7b_pay004_menu_ids();
DROP PROCEDURE jsh_assert_gate7b_pay004_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200410,'创建组合支付计划',9200400,10,'#','',NULL,'',1,0,'F','0','0','payment:tender:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'冻结2至8个精确支付份额'),
(9200411,'查询组合支付计划',9200400,11,'#','',NULL,'',1,0,'F','0','0','payment:tender:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查询原计划和份额权威状态'),
(9200412,'收取支付份额',9200400,12,'#','',NULL,'',1,0,'F','0','0','payment:tender:collect','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'严格顺序收取；电子外部未解阻时失败关闭'),
(9200413,'取消支付计划',9200400,13,'#','',NULL,'',1,0,'F','0','0','payment:tender:cancel','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'仅无成功和无占额份额时允许'),
(9200414,'恢复支付计划',9200400,14,'#','',NULL,'',1,0,'F','0','0','payment:tender:recover','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只观察原命令和推进已确认检查点')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
