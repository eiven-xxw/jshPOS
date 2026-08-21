CREATE TABLE shf_cash_movement (
    movement_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '现金动作ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '班次ULID',
    store_id BIGINT NOT NULL COMMENT '门店主键',
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '终端ULID',
    cashier_user_id BIGINT NOT NULL COMMENT '操作员工主键',
    business_date DATE NOT NULL COMMENT '冻结业务日',
    movement_type VARCHAR(16) NOT NULL COMMENT 'CASH_IN/CASH_OUT/SAFE_DROP',
    signed_amount_minor BIGINT NOT NULL COMMENT '最小货币单位带符号金额',
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY' COMMENT '币种',
    reason_code VARCHAR(32) NOT NULL COMMENT '业务原因编码',
    reason_text VARCHAR(256) NOT NULL COMMENT '业务原因说明',
    authorization_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '授权会话引用',
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令ULID',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',
    shift_version BIGINT NOT NULL COMMENT '动作后的班次版本',
    occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
    PRIMARY KEY (movement_id),
    UNIQUE KEY uk_shf_movement_tenant_id (tenant_id,movement_id),
    UNIQUE KEY uk_shf_movement_command (tenant_id,command_id),
    UNIQUE KEY uk_shf_movement_version (tenant_id,shift_id,shift_version),
    KEY idx_shf_movement_shift (tenant_id,shift_id,occurred_at),
    CONSTRAINT fk_shf_movement_shift FOREIGN KEY (tenant_id,shift_id) REFERENCES shf_shift (tenant_id,shift_id),
    CONSTRAINT ck_shf_movement_type CHECK (movement_type IN ('CASH_IN','CASH_OUT','SAFE_DROP')),
    CONSTRAINT ck_shf_movement_sign CHECK (
      (movement_type='CASH_IN' AND signed_amount_minor>0) OR
      (movement_type IN ('CASH_OUT','SAFE_DROP') AND signed_amount_minor<0)
    ),
    CONSTRAINT ck_shf_movement_currency CHECK (currency='CNY'),
    CONSTRAINT ck_shf_movement_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_shf_movement_version CHECK (shift_version>1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='班次非销售现金只追加流水';

CREATE TABLE shf_drawer_event (
    drawer_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '钱箱事件ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '班次ULID',
    store_id BIGINT NOT NULL COMMENT '门店主键',
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '终端ULID',
    cashier_user_id BIGINT NOT NULL COMMENT '操作员工主键',
    business_date DATE NOT NULL COMMENT '冻结业务日',
    event_type VARCHAR(32) NOT NULL COMMENT '钱箱请求类型',
    reason_code VARCHAR(32) NOT NULL COMMENT '业务原因编码',
    reason_text VARCHAR(256) NOT NULL COMMENT '业务原因说明',
    authorization_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '授权会话引用',
    device_execution_status VARCHAR(24) NOT NULL COMMENT '真实设备执行状态',
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令ULID',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',
    shift_version BIGINT NOT NULL COMMENT '动作后的班次版本',
    occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
    PRIMARY KEY (drawer_event_id),
    UNIQUE KEY uk_shf_drawer_tenant_id (tenant_id,drawer_event_id),
    UNIQUE KEY uk_shf_drawer_command (tenant_id,command_id),
    UNIQUE KEY uk_shf_drawer_version (tenant_id,shift_id,shift_version),
    KEY idx_shf_drawer_shift (tenant_id,shift_id,occurred_at),
    CONSTRAINT fk_shf_drawer_shift FOREIGN KEY (tenant_id,shift_id) REFERENCES shf_shift (tenant_id,shift_id),
    CONSTRAINT ck_shf_drawer_type CHECK (event_type='NO_SALE_OPEN_REQUESTED'),
    CONSTRAINT ck_shf_drawer_device CHECK (device_execution_status='BLOCKED_EXTERNAL'),
    CONSTRAINT ck_shf_drawer_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_shf_drawer_version CHECK (shift_version>1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='非销售开钱箱受审计请求';

DELIMITER $$
CREATE TRIGGER trg_shf_cash_movement_no_update BEFORE UPDATE ON shf_cash_movement FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shift cash movement is append-only'; END$$
CREATE TRIGGER trg_shf_cash_movement_no_delete BEFORE DELETE ON shf_cash_movement FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shift cash movement is append-only'; END$$
CREATE TRIGGER trg_shf_drawer_event_no_update BEFORE UPDATE ON shf_drawer_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='drawer event is append-only'; END$$
CREATE TRIGGER trg_shf_drawer_event_no_delete BEFORE DELETE ON shf_drawer_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='drawer event is append-only'; END$$

CREATE PROCEDURE jsh_assert_gate7b_pos010_menu_ids()
BEGIN
    IF EXISTS (
      SELECT 1 FROM sys_menu WHERE menu_id IN (9200208,9200209) AND NOT (
        (menu_id=9200208 AND perms='pos:shift:cash-manage') OR
        (menu_id=9200209 AND perms='pos:drawer:no-sale')
      )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 7B POS010 sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate7b_pos010_menu_ids();
DROP PROCEDURE jsh_assert_gate7b_pos010_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200208,'班次现金管理',9200200,8,'#','',NULL,'',1,0,'F','0','0','pos:shift:cash-manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'存入取出与缴款只追加事实'),
(9200209,'非销售开钱箱',9200200,9,'#','',NULL,'',1,0,'F','0','0','pos:drawer:no-sale','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'仅登记受审计请求，真实外设保持阻断')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
