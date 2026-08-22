ALTER TABLE pur_purchase_order
    ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT '来源类型：人工或补货建议' AFTER over_receipt_tolerance_bps,
    ADD COLUMN source_id CHAR(26) NULL COMMENT '来源聚合标识；补货时为建议ID' AFTER source_type,
    ADD UNIQUE KEY uk_pur_order_source (tenant_id,source_type,source_id),
    ADD CONSTRAINT ck_pur_order_source CHECK (
      (source_type='MANUAL' AND source_id IS NULL) OR
      (source_type='REPLENISHMENT' AND source_id IS NOT NULL));

CREATE TABLE rpl_policy_version (
    policy_version_id CHAR(26) NOT NULL COMMENT '补货规则版本ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID，由可信上下文注入',
    store_id BIGINT NOT NULL COMMENT '适用门店',
    warehouse_id CHAR(26) NOT NULL COMMENT '适用仓库',
    version_no INT NOT NULL COMMENT '业务版本号',
    state VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED/RETIRED',
    effective_from DATETIME(3) NOT NULL COMMENT '生效时刻UTC',
    idempotency_key VARCHAR(96) NOT NULL COMMENT '创建幂等键',
    request_sha256 CHAR(64) NOT NULL COMMENT '创建请求摘要',
    content_sha256 CHAR(64) NULL COMMENT '发布内容摘要',
    actor_user_id BIGINT NOT NULL COMMENT '创建人',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC',
    PRIMARY KEY (tenant_id,policy_version_id),
    UNIQUE KEY uk_rpl_policy_version (tenant_id,store_id,warehouse_id,version_no),
    UNIQUE KEY uk_rpl_policy_idem (tenant_id,idempotency_key),
    KEY idx_rpl_policy_effective (tenant_id,store_id,state,effective_from),
    CONSTRAINT fk_rpl_policy_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
    CONSTRAINT ck_rpl_policy_state CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT ck_rpl_policy_version CHECK (version_no>0 AND version>=0),
    CONSTRAINT ck_rpl_policy_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'
      AND (content_sha256 IS NULL OR content_sha256 REGEXP '^[a-f0-9]{64}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化确定性补货规则';

CREATE TABLE rpl_policy_item (
    policy_item_id CHAR(26) NOT NULL COMMENT '规则项ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    policy_version_id CHAR(26) NOT NULL COMMENT '规则版本ID',
    sku_id BIGINT NOT NULL COMMENT '商品SKU',
    sku_code VARCHAR(64) NOT NULL COMMENT '冻结SKU编码',
    base_unit_id BIGINT NOT NULL COMMENT '冻结基础单位',
    purchase_unit_id BIGINT NOT NULL COMMENT '冻结采购单位',
    conversion_numerator BIGINT NOT NULL COMMENT '采购单位转基础单位分子',
    conversion_denominator BIGINT NOT NULL COMMENT '采购单位转基础单位分母',
    supplier_id CHAR(26) NOT NULL COMMENT '唯一供应商',
    minimum_base_quantity DECIMAL(19,6) NOT NULL COMMENT '最低基础库存',
    maximum_base_quantity DECIMAL(19,6) NOT NULL COMMENT '最高基础库存',
    minimum_order_quantity DECIMAL(19,6) NOT NULL COMMENT '最小采购量',
    order_multiple DECIMAL(19,6) NOT NULL COMMENT '采购倍数',
    include_confirmed_in_transit BOOLEAN NOT NULL COMMENT '是否抵扣已确认在途',
    unit_price_minor BIGINT NOT NULL COMMENT '采购单位价格最小货币单位',
    tax_rate_bps INT NOT NULL COMMENT '税率基点',
    item_sha256 CHAR(64) NOT NULL COMMENT '规则项摘要',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC',
    PRIMARY KEY (tenant_id,policy_item_id),
    UNIQUE KEY uk_rpl_policy_sku (tenant_id,policy_version_id,sku_id),
    CONSTRAINT fk_rpl_item_policy FOREIGN KEY (tenant_id,policy_version_id)
      REFERENCES rpl_policy_version(tenant_id,policy_version_id),
    CONSTRAINT fk_rpl_item_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES cat_sku(tenant_id,sku_id),
    CONSTRAINT fk_rpl_item_base_unit FOREIGN KEY (tenant_id,base_unit_id) REFERENCES cat_unit(tenant_id,unit_id),
    CONSTRAINT fk_rpl_item_purchase_unit FOREIGN KEY (tenant_id,purchase_unit_id) REFERENCES cat_unit(tenant_id,unit_id),
    CONSTRAINT fk_rpl_item_supplier FOREIGN KEY (tenant_id,supplier_id) REFERENCES sup_supplier(tenant_id,supplier_id),
    CONSTRAINT ck_rpl_item_qty CHECK (minimum_base_quantity>=0 AND maximum_base_quantity>=minimum_base_quantity
      AND minimum_order_quantity>0 AND order_multiple>0),
    CONSTRAINT ck_rpl_item_conversion CHECK (conversion_numerator>0 AND conversion_denominator>0),
    CONSTRAINT ck_rpl_item_money CHECK (unit_price_minor>=0 AND tax_rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_rpl_item_hash CHECK (item_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补货规则冻结明细';

CREATE TABLE rpl_generation_run (
    generation_run_id CHAR(26) NOT NULL COMMENT '生成运行ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    policy_version_id CHAR(26) NOT NULL COMMENT '使用的规则版本',
    store_id BIGINT NOT NULL COMMENT '门店',
    warehouse_id CHAR(26) NOT NULL COMMENT '仓库',
    calculation_at DATETIME(3) NOT NULL COMMENT '计算时点UTC',
    idempotency_key VARCHAR(96) NOT NULL COMMENT '运行幂等键',
    request_sha256 CHAR(64) NOT NULL COMMENT '运行请求摘要',
    state VARCHAR(16) NOT NULL COMMENT 'RUNNING/COMPLETED/FAILED',
    suggestion_count INT NOT NULL DEFAULT 0 COMMENT '生成建议数',
    actor_user_id BIGINT NOT NULL COMMENT '执行人',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC',
    PRIMARY KEY (tenant_id,generation_run_id),
    UNIQUE KEY uk_rpl_run_idem (tenant_id,idempotency_key),
    KEY idx_rpl_run_policy (tenant_id,policy_version_id,calculation_at),
    CONSTRAINT fk_rpl_run_policy FOREIGN KEY (tenant_id,policy_version_id)
      REFERENCES rpl_policy_version(tenant_id,policy_version_id),
    CONSTRAINT ck_rpl_run_state CHECK (state IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_rpl_run_count CHECK (suggestion_count>=0),
    CONSTRAINT ck_rpl_run_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补货建议生成运行';

CREATE TABLE rpl_suggestion (
    suggestion_id CHAR(26) NOT NULL COMMENT '补货建议ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    generation_run_id CHAR(26) NOT NULL COMMENT '生成运行ID', policy_version_id CHAR(26) NOT NULL COMMENT '规则版本ID',
    policy_item_id CHAR(26) NOT NULL COMMENT '规则项ID', store_id BIGINT NOT NULL COMMENT '门店',
    warehouse_id CHAR(26) NOT NULL COMMENT '仓库', sku_id BIGINT NOT NULL COMMENT 'SKU', sku_code VARCHAR(64) NOT NULL COMMENT 'SKU编码快照',
    base_unit_id BIGINT NOT NULL COMMENT '基础单位快照', purchase_unit_id BIGINT NOT NULL COMMENT '采购单位快照',
    supplier_id CHAR(26) NOT NULL COMMENT '供应商快照', on_hand_quantity DECIMAL(19,6) NOT NULL COMMENT '在手量快照',
    reserved_quantity DECIMAL(19,6) NOT NULL COMMENT '预占量快照', frozen_quantity DECIMAL(19,6) NOT NULL COMMENT '冻结量快照',
    safety_stock_quantity DECIMAL(19,6) NOT NULL COMMENT '安全库存快照', available_quantity DECIMAL(19,6) NOT NULL COMMENT '可用量快照',
    confirmed_in_transit_quantity DECIMAL(19,6) NOT NULL COMMENT '确认在途量快照', effective_quantity DECIMAL(19,6) NOT NULL COMMENT '有效量',
    minimum_base_quantity DECIMAL(19,6) NOT NULL COMMENT '最低库存', maximum_base_quantity DECIMAL(19,6) NOT NULL COMMENT '最高库存',
    required_base_quantity DECIMAL(19,6) NOT NULL COMMENT '基础单位缺口', suggested_purchase_quantity DECIMAL(19,6) NOT NULL COMMENT '建议采购量',
    minimum_order_quantity DECIMAL(19,6) NOT NULL COMMENT '最小采购量', order_multiple DECIMAL(19,6) NOT NULL COMMENT '采购倍数',
    conversion_numerator BIGINT NOT NULL COMMENT '换算分子', conversion_denominator BIGINT NOT NULL COMMENT '换算分母',
    input_ledger_sequence BIGINT NOT NULL COMMENT '库存流水检查点', input_balance_version BIGINT NOT NULL COMMENT '库存投影版本',
    reason_code VARCHAR(64) NOT NULL COMMENT '解释原因', state VARCHAR(24) NOT NULL COMMENT '建议状态',
    content_sha256 CHAR(64) NOT NULL COMMENT '建议内容摘要', purchase_order_id CHAR(26) NULL COMMENT '采购草稿ID',
    failure_code VARCHAR(64) NULL COMMENT '失败原因', reviewer_user_id BIGINT NULL COMMENT '复核人', approver_user_id BIGINT NULL COMMENT '审批人',
    actor_user_id BIGINT NOT NULL COMMENT '生成人', version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC', updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC',
    PRIMARY KEY (tenant_id,suggestion_id), UNIQUE KEY uk_rpl_suggestion_run_item (tenant_id,generation_run_id,policy_item_id),
    KEY idx_rpl_suggestion_work (tenant_id,store_id,state,created_at),
    CONSTRAINT fk_rpl_suggestion_run FOREIGN KEY (tenant_id,generation_run_id) REFERENCES rpl_generation_run(tenant_id,generation_run_id),
    CONSTRAINT fk_rpl_suggestion_policy_item FOREIGN KEY (tenant_id,policy_item_id) REFERENCES rpl_policy_item(tenant_id,policy_item_id),
    CONSTRAINT ck_rpl_suggestion_state CHECK (state IN ('GENERATED','REVIEWED','APPROVED','REJECTED','STALE','PURCHASE_DRAFTED','FAILED')),
    CONSTRAINT ck_rpl_suggestion_qty CHECK (suggested_purchase_quantity>0 AND required_base_quantity>0
      AND minimum_order_quantity>0 AND order_multiple>0),
    CONSTRAINT ck_rpl_suggestion_shape CHECK ((state='PURCHASE_DRAFTED' AND purchase_order_id IS NOT NULL)
      OR (state='FAILED' AND failure_code IS NOT NULL) OR state IN ('GENERATED','REVIEWED','APPROVED','REJECTED','STALE')),
    CONSTRAINT ck_rpl_suggestion_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可解释确定性补货建议';

CREATE TABLE rpl_suggestion_event (
    event_id CHAR(26) NOT NULL COMMENT '状态事件ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    store_id BIGINT NOT NULL COMMENT '门店ID',
    suggestion_id CHAR(26) NOT NULL COMMENT '补货建议ID',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    idempotency_key VARCHAR(96) NOT NULL COMMENT '稳定幂等键',
    command_sha256 CHAR(64) NOT NULL COMMENT '命令内容摘要',
    result_state VARCHAR(24) NOT NULL COMMENT '命令完成后的建议状态',
    result_reference_id CHAR(26) NULL COMMENT '采购草稿等结果引用',
    actor_user_id BIGINT NOT NULL COMMENT '操作人',
    correlation_id VARCHAR(96) NOT NULL COMMENT '全链路关联标识',
    payload_json JSON NOT NULL COMMENT '不可变事件载荷',
    payload_sha256 CHAR(64) NOT NULL COMMENT '事件载荷摘要',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC',
    PRIMARY KEY (tenant_id,event_id), UNIQUE KEY uk_rpl_event_idem (tenant_id,idempotency_key),
    KEY idx_rpl_event_target (tenant_id,suggestion_id,created_at),
    CONSTRAINT fk_rpl_event_suggestion FOREIGN KEY (tenant_id,suggestion_id) REFERENCES rpl_suggestion(tenant_id,suggestion_id),
    CONSTRAINT ck_rpl_event_hash CHECK (command_sha256 REGEXP '^[a-f0-9]{64}$' AND payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补货建议只追加状态事件与幂等结果';

CREATE TABLE rpl_audit_event (
    audit_id CHAR(26) NOT NULL COMMENT '审计事件ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    store_id BIGINT NOT NULL COMMENT '门店ID',
    action_code VARCHAR(64) NOT NULL COMMENT '关键操作编码',
    aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型',
    aggregate_id CHAR(26) NOT NULL COMMENT '聚合标识',
    actor_user_id BIGINT NOT NULL COMMENT '操作人',
    command_id VARCHAR(96) NOT NULL COMMENT '业务命令或幂等标识',
    correlation_id VARCHAR(96) NOT NULL COMMENT '全链路关联标识',
    before_value VARCHAR(64) NULL COMMENT '操作前状态',
    after_value VARCHAR(64) NULL COMMENT '操作后状态',
    request_sha256 CHAR(64) NOT NULL COMMENT '请求内容摘要',
    reason_code VARCHAR(256) NOT NULL COMMENT '原因或解释',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC',
    PRIMARY KEY (tenant_id,audit_id),
    UNIQUE KEY uk_rpl_audit_command (tenant_id,command_id),
    KEY idx_rpl_audit_target (tenant_id,aggregate_type,aggregate_id,created_at),
    CONSTRAINT ck_rpl_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补货关键操作只追加审计';

CREATE TABLE rpl_event_outbox (
    event_id CHAR(26) NOT NULL COMMENT 'Outbox事件ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    event_type VARCHAR(64) NOT NULL COMMENT '版本化领域事件类型',
    aggregate_id CHAR(26) NOT NULL COMMENT '聚合标识',
    aggregate_version BIGINT NOT NULL COMMENT '聚合版本',
    correlation_id VARCHAR(96) NOT NULL COMMENT '全链路关联标识',
    payload_json JSON NOT NULL COMMENT '事件载荷',
    payload_sha256 CHAR(64) NOT NULL COMMENT '事件载荷摘要',
    status VARCHAR(16) NOT NULL COMMENT '投递状态',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻UTC',
    delivered_at DATETIME(3) NULL COMMENT '投递完成时刻UTC',
    PRIMARY KEY (tenant_id,event_id), KEY idx_rpl_outbox_delivery (tenant_id,status,created_at,event_id),
    CONSTRAINT ck_rpl_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_rpl_outbox_status CHECK (status IN ('PENDING','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补货领域事件Outbox';

DELIMITER $$
CREATE TRIGGER trg_pur_order_source_immutable BEFORE UPDATE ON pur_purchase_order FOR EACH ROW
BEGIN IF NOT (OLD.source_type <=> NEW.source_type) OR NOT (OLD.source_id <=> NEW.source_id)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='purchase order source is immutable'; END IF; END$$
CREATE TRIGGER trg_rpl_policy_item_no_update BEFORE UPDATE ON rpl_policy_item FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpl_policy_item is immutable'; END$$
CREATE TRIGGER trg_rpl_suggestion_fact_immutable BEFORE UPDATE ON rpl_suggestion FOR EACH ROW
BEGIN IF NOT (OLD.generation_run_id <=> NEW.generation_run_id) OR NOT (OLD.policy_item_id <=> NEW.policy_item_id)
  OR NOT (OLD.content_sha256 <=> NEW.content_sha256) OR NOT (OLD.suggested_purchase_quantity <=> NEW.suggested_purchase_quantity)
  OR NOT (OLD.input_ledger_sequence <=> NEW.input_ledger_sequence) OR NOT (OLD.input_balance_version <=> NEW.input_balance_version)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpl_suggestion frozen fact is immutable'; END IF; END$$
CREATE TRIGGER trg_rpl_event_no_update BEFORE UPDATE ON rpl_suggestion_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpl_suggestion_event is immutable'; END$$
CREATE TRIGGER trg_rpl_event_no_delete BEFORE DELETE ON rpl_suggestion_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpl_suggestion_event is immutable'; END$$
CREATE TRIGGER trg_rpl_audit_no_update BEFORE UPDATE ON rpl_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpl_audit_event is immutable'; END$$
CREATE TRIGGER trg_rpl_audit_no_delete BEFORE DELETE ON rpl_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpl_audit_event is immutable'; END$$
DELIMITER ;

DELIMITER $$
CREATE PROCEDURE jsh_assert_gate7c_rpl_menu_ids()
BEGIN IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200534 AND 9200539)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 7C RPL sys_menu reserved ID collision'; END IF; END$$
DELIMITER ;
CALL jsh_assert_gate7c_rpl_menu_ids();
DROP PROCEDURE jsh_assert_gate7c_rpl_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200534,'补货规则管理',9200520,14,'#','',NULL,'',1,0,'F','0','0','procurement:replenishment:policy','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建、发布和停用补货规则'),
(9200535,'补货建议生成',9200520,15,'#','',NULL,'',1,0,'F','0','0','procurement:replenishment:generate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按冻结规则生成确定性建议'),
(9200536,'补货建议查询',9200520,16,'#','',NULL,'',1,0,'F','0','0','procurement:replenishment:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看解释明细和状态'),
(9200537,'补货建议复核',9200520,17,'#','',NULL,'',1,0,'F','0','0','procurement:replenishment:review','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'复核或驳回建议'),
(9200538,'补货建议审批',9200520,18,'#','',NULL,'',1,0,'F','0','0','procurement:replenishment:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'职责分离审批建议'),
(9200539,'转采购草稿',9200520,19,'#','',NULL,'',1,0,'F','0','0','procurement:replenishment:draft','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'经正式端口创建无库存效果采购草稿');
