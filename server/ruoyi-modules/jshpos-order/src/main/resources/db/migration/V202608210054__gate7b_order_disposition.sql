-- Gate 7B ORD-004：取消墓碑与成交后反向处置路由；只追加且不回写成交事实。
CREATE TABLE ord_order_finality_guard (
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信上下文注入的租户ID',
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单ULID',
    finality_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首个终局意图：CANCELLED或COMPLETED',
    source_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '取消源事件或成交命令ULID',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首个终局意图规范摘要',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC写入时间',
    PRIMARY KEY (tenant_id, order_id),
    UNIQUE KEY uk_order_finality_source (tenant_id, source_id),
    CONSTRAINT ck_order_finality_type CHECK (finality_type IN ('CANCELLED','COMPLETED')),
    CONSTRAINT ck_order_finality_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单取消与成交的数据库级先到终局仲裁；只追加且同租户订单唯一';

CREATE TABLE ord_order_disposition (
    disposition_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '处置事实ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信上下文注入的租户ID',
    source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'POS同步源事件ULID',
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原订单ULID；取消墓碑允许先于订单到达',
    store_id BIGINT NOT NULL COMMENT '可信门店ID',
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信终端ULID',
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行处置的班次ULID',
    actor_user_id BIGINT NOT NULL COMMENT '可信员工用户ID',
    business_date DATE NOT NULL COMMENT '执行处置的门店业务日',
    disposition_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '取消或受控反向处置路由',
    from_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令观察到的订单状态',
    effective_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '处置后的有效订单状态；成交后保持原值',
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '具名原因代码',
    reason_text VARCHAR(256) NOT NULL COMMENT '人工填写且已审计的原因说明',
    authorization_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '高风险处置授权引用，不保存口令',
    order_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'POS冻结订单快照SHA-256',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范命令内容SHA-256',
    order_aggregate_version BIGINT NOT NULL COMMENT 'POS观察到的订单聚合版本',
    occurred_at DATETIME(3) NOT NULL COMMENT 'POS处置发生UTC时间',
    PRIMARY KEY (disposition_id),
    UNIQUE KEY uk_order_disposition_tenant (tenant_id, disposition_id),
    UNIQUE KEY uk_order_disposition_source (tenant_id, source_event_id),
    KEY idx_order_disposition_order (tenant_id, order_id, occurred_at),
    CONSTRAINT ck_order_disposition_type CHECK (disposition_type IN (
      'CANCEL_BEFORE_COMPLETION','RETURN_REFUND_REQUIRED',
      'PAYMENT_REVERSAL_OBSERVATION_REQUIRED','EXPLICIT_COMPENSATION_REQUIRED')),
    CONSTRAINT ck_order_disposition_state CHECK (
      (disposition_type='CANCEL_BEFORE_COMPLETION'
       AND from_status IN ('DRAFT','PENDING_PAYMENT') AND effective_status='CANCELLED')
      OR
      (disposition_type<>'CANCEL_BEFORE_COMPLETION'
       AND from_status IN ('CONFIRMED','COMPLETED') AND effective_status=from_status)),
    CONSTRAINT ck_order_disposition_hash CHECK (
      order_snapshot_sha256 REGEXP '^[a-f0-9]{64}$' AND request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_order_disposition_version CHECK (order_aggregate_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='取消墓碑及成交后受控反向处置路由；只追加；不覆盖成交与Owner事实';

CREATE UNIQUE INDEX uk_order_disposition_single_cancel
  ON ord_order_disposition(tenant_id, order_id,
    (CASE WHEN disposition_type='CANCEL_BEFORE_COMPLETION' THEN 1 ELSE NULL END));

DELIMITER $$
CREATE TRIGGER trg_order_finality_no_update BEFORE UPDATE ON ord_order_finality_guard FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order finality guard is append-only'; END$$
CREATE TRIGGER trg_order_finality_no_delete BEFORE DELETE ON ord_order_finality_guard FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order finality guard is append-only'; END$$
CREATE TRIGGER trg_order_disposition_no_update BEFORE UPDATE ON ord_order_disposition FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order disposition is append-only'; END$$
CREATE TRIGGER trg_order_disposition_no_delete BEFORE DELETE ON ord_order_disposition FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order disposition is append-only'; END$$
DELIMITER ;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9200212,'成交前取消',9200200,12,'#','',NULL,'',1,0,'F','0','0','pos:order:cancel','#','只允许无未决资金和库存效果的未完成交易',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200212 OR perms='pos:order:cancel');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9200213,'成交后处置路由',9200200,13,'#','',NULL,'',1,0,'F','0','0','pos:order:dispose','#','只追加原单退货退款或显式补偿路由',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200213 OR perms='pos:order:dispose');
