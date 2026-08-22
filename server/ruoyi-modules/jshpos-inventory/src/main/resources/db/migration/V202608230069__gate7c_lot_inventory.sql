CREATE TABLE inv_lot_identity (
    lot_id CHAR(26) NOT NULL COMMENT '批次ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    store_id BIGINT NOT NULL COMMENT '适用门店',
    warehouse_id CHAR(26) NOT NULL COMMENT '仓库ULID',
    sku_id BIGINT NOT NULL COMMENT 'SKU主键',
    base_unit_id BIGINT NOT NULL COMMENT '冻结基础单位',
    supplier_lot_code VARCHAR(96) NULL COMMENT '供应商批号',
    internal_lot_code VARCHAR(96) NOT NULL COMMENT '内部批号',
    production_date DATE NULL COMMENT '生产日期',
    received_date DATE NOT NULL COMMENT '入库日期',
    expiry_date DATE NOT NULL COMMENT '冻结到期日',
    policy_version_id CHAR(26) NOT NULL COMMENT '批次策略版本',
    near_expiry_days INT NOT NULL COMMENT '建批时冻结的临期自然日阈值',
    content_sha256 CHAR(64) NOT NULL COMMENT '身份内容摘要',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, lot_id),
    UNIQUE KEY uk_inv_lot_identity_hash (tenant_id, warehouse_id, sku_id, content_sha256),
    KEY idx_inv_lot_fefo (tenant_id, warehouse_id, sku_id, expiry_date, received_date, lot_id),
    CONSTRAINT fk_inv_lot_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_inv_lot_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_inv_lot_unit FOREIGN KEY (tenant_id, base_unit_id) REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT fk_inv_lot_policy FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES cat_lot_policy_version (tenant_id, policy_version_id),
    CONSTRAINT ck_inv_lot_dates CHECK (expiry_date >= received_date),
    CONSTRAINT ck_inv_lot_near_days CHECK (near_expiry_days BETWEEN 0 AND 3650),
    CONSTRAINT ck_inv_lot_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_lot_ulid CHECK (lot_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Inventory Owner不可变批次身份';

CREATE TABLE inv_lot_command (
    source_event_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id CHAR(26) NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    store_id BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    affected_lines INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    applied_at DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, source_event_id),
    KEY idx_inv_lot_command_source (tenant_id, source_type, source_id),
    CONSTRAINT fk_inv_lot_command_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_lot_command_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_lot_command_status CHECK (status IN ('PROCESSING','APPLIED')),
    CONSTRAINT ck_inv_lot_command_lines CHECK (affected_lines BETWEEN 0 AND 500),
    CONSTRAINT ck_inv_lot_command_shape CHECK ((status='PROCESSING' AND applied_at IS NULL)
        OR (status='APPLIED' AND applied_at IS NOT NULL AND affected_lines > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次命令幂等事实';

CREATE TABLE inv_lot_balance (
    tenant_id VARCHAR(20) NOT NULL,
    lot_id CHAR(26) NOT NULL,
    on_hand_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    last_ledger_sequence BIGINT NOT NULL DEFAULT 0,
    record_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, lot_id),
    CONSTRAINT fk_inv_lot_balance_identity FOREIGN KEY (tenant_id, lot_id)
        REFERENCES inv_lot_identity (tenant_id, lot_id),
    CONSTRAINT ck_inv_lot_balance_nonnegative CHECK (on_hand_quantity >= 0),
    CONSTRAINT ck_inv_lot_balance_version CHECK (last_ledger_sequence >= 0 AND record_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可由批次流水重建的余额投影';

CREATE TABLE inv_lot_ledger (
    ledger_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    lot_id CHAR(26) NOT NULL,
    ledger_sequence BIGINT NOT NULL,
    quantity_before DECIMAL(19,6) NOT NULL,
    quantity_delta DECIMAL(19,6) NOT NULL,
    quantity_after DECIMAL(19,6) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id CHAR(26) NOT NULL,
    source_line_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    business_date DATE NOT NULL,
    actor_user_id BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, ledger_id),
    UNIQUE KEY uk_inv_lot_ledger_sequence (tenant_id, lot_id, ledger_sequence),
    UNIQUE KEY uk_inv_lot_ledger_source (tenant_id, source_event_id, source_line_id, lot_id, movement_type),
    CONSTRAINT fk_inv_lot_ledger_balance FOREIGN KEY (tenant_id, lot_id)
        REFERENCES inv_lot_balance (tenant_id, lot_id),
    CONSTRAINT fk_inv_lot_ledger_command FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES inv_lot_command (tenant_id, source_event_id),
    CONSTRAINT ck_inv_lot_ledger_equation CHECK (quantity_before + quantity_delta = quantity_after),
    CONSTRAINT ck_inv_lot_ledger_nonzero CHECK (quantity_delta <> 0 AND quantity_after >= 0),
    CONSTRAINT ck_inv_lot_ledger_sequence CHECK (ledger_sequence > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变批次数量流水';

CREATE TABLE inv_lot_allocation (
    allocation_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    allocation_type VARCHAR(16) NOT NULL,
    source_id CHAR(26) NOT NULL,
    source_line_id CHAR(26) NOT NULL,
    original_source_id CHAR(26) NULL,
    original_source_line_id CHAR(26) NULL,
    lot_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity DECIMAL(19,6) NOT NULL,
    policy_version_id CHAR(26) NOT NULL,
    expiry_date DATE NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, allocation_id),
    UNIQUE KEY uk_inv_lot_allocation (tenant_id, source_event_id, source_line_id, lot_id, allocation_type),
    KEY idx_inv_lot_original (tenant_id, original_source_id, original_source_line_id, lot_id),
    CONSTRAINT fk_inv_lot_allocation_identity FOREIGN KEY (tenant_id, lot_id)
        REFERENCES inv_lot_identity (tenant_id, lot_id),
    CONSTRAINT fk_inv_lot_allocation_command FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES inv_lot_command (tenant_id, source_event_id),
    CONSTRAINT ck_inv_lot_allocation_type CHECK (allocation_type IN ('SALE','RETURN','EXPLICIT')),
    CONSTRAINT ck_inv_lot_allocation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inv_lot_return_reference CHECK ((allocation_type='RETURN' AND original_source_id IS NOT NULL
        AND original_source_line_id IS NOT NULL) OR allocation_type<>'RETURN')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成交及退货冻结批次分配';

CREATE TABLE inv_lot_expiry_projection (
    tenant_id VARCHAR(20) NOT NULL,
    lot_id CHAR(26) NOT NULL,
    expiry_status VARCHAR(16) NOT NULL,
    as_of_business_date DATE NOT NULL,
    near_expiry_days INT NOT NULL,
    on_hand_quantity DECIMAL(19,6) NOT NULL,
    last_ledger_sequence BIGINT NOT NULL COMMENT '批次流水检查点',
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, lot_id),
    KEY idx_inv_lot_alert (tenant_id, expiry_status, as_of_business_date, lot_id),
    CONSTRAINT fk_inv_lot_expiry_identity FOREIGN KEY (tenant_id, lot_id)
        REFERENCES inv_lot_identity (tenant_id, lot_id),
    CONSTRAINT ck_inv_lot_expiry_status CHECK (expiry_status IN ('AVAILABLE','NEAR_EXPIRY','EXPIRED','DEPLETED','BLOCKED')),
    CONSTRAINT ck_inv_lot_expiry_values CHECK (near_expiry_days BETWEEN 0 AND 3650 AND on_hand_quantity >= 0
        AND last_ledger_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可重建批次效期预警投影';

CREATE TABLE inv_lot_audit_event (
    audit_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_id CHAR(26) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    KEY idx_inv_lot_audit_target (tenant_id, aggregate_id, occurred_at),
    CONSTRAINT fk_inv_lot_audit_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_lot_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变批次操作审计';

CREATE TABLE inv_lot_outbox (
    event_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    delivered_at DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, event_id),
    KEY idx_inv_lot_outbox_delivery (tenant_id, delivery_state, available_at, event_id),
    CONSTRAINT ck_inv_lot_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_lot_outbox_state CHECK (delivery_state IN ('PENDING','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次领域Outbox';

CREATE TABLE inv_lot_package_release (
    release_id CHAR(26) NOT NULL COMMENT '发布命令ULID及稳定幂等键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    store_id BIGINT NOT NULL COMMENT '适用门店',
    warehouse_id CHAR(26) NOT NULL COMMENT '适用仓库',
    package_version BIGINT NOT NULL COMMENT '仓库批次包单调版本',
    previous_version BIGINT NOT NULL COMMENT '前一批次包版本',
    source_sha256 CHAR(64) NOT NULL COMMENT '发布输入事实摘要',
    payload_sha256 CHAR(64) NOT NULL COMMENT '包载荷摘要',
    payload_bytes LONGBLOB NOT NULL COMMENT '已签名原始载荷',
    signing_key_id VARCHAR(128) NOT NULL COMMENT '签名密钥版本引用',
    signature_bytes VARBINARY(64) NOT NULL COMMENT 'Ed25519签名',
    record_count INT NOT NULL COMMENT '策略与批次记录总数',
    generated_at DATETIME(3) NOT NULL COMMENT '生成时间UTC',
    PRIMARY KEY (tenant_id, release_id),
    UNIQUE KEY uk_inv_lot_package_version (tenant_id, store_id, warehouse_id, package_version),
    KEY idx_inv_lot_package_latest (tenant_id, store_id, warehouse_id, package_version DESC),
    CONSTRAINT fk_inv_lot_package_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_lot_package_versions CHECK (package_version > 0 AND previous_version >= 0
        AND previous_version = package_version - 1),
    CONSTRAINT ck_inv_lot_package_hashes CHECK (source_sha256 REGEXP '^[a-f0-9]{64}$'
        AND payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_lot_package_signature CHECK (OCTET_LENGTH(signature_bytes)=64),
    CONSTRAINT ck_inv_lot_package_records CHECK (record_count BETWEEN 1 AND 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变批次数据包发布版本';

DELIMITER $$
CREATE TRIGGER trg_inv_lot_identity_no_update BEFORE UPDATE ON inv_lot_identity FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_identity is immutable'; END$$
CREATE TRIGGER trg_inv_lot_identity_no_delete BEFORE DELETE ON inv_lot_identity FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_identity is immutable'; END$$
CREATE TRIGGER trg_inv_lot_ledger_no_update BEFORE UPDATE ON inv_lot_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_ledger is immutable'; END$$
CREATE TRIGGER trg_inv_lot_ledger_no_delete BEFORE DELETE ON inv_lot_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_ledger is immutable'; END$$
CREATE TRIGGER trg_inv_lot_allocation_no_update BEFORE UPDATE ON inv_lot_allocation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_allocation is immutable'; END$$
CREATE TRIGGER trg_inv_lot_allocation_no_delete BEFORE DELETE ON inv_lot_allocation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_allocation is immutable'; END$$
CREATE TRIGGER trg_inv_lot_audit_no_update BEFORE UPDATE ON inv_lot_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_audit_event is immutable'; END$$
CREATE TRIGGER trg_inv_lot_audit_no_delete BEFORE DELETE ON inv_lot_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_audit_event is immutable'; END$$
CREATE TRIGGER trg_inv_lot_command_applied_immutable BEFORE UPDATE ON inv_lot_command FOR EACH ROW
BEGIN IF OLD.status='APPLIED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='applied inv_lot_command is immutable'; END IF; END$$
CREATE TRIGGER trg_inv_lot_package_no_update BEFORE UPDATE ON inv_lot_package_release FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_package_release is immutable'; END$$
CREATE TRIGGER trg_inv_lot_package_no_delete BEFORE DELETE ON inv_lot_package_release FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_lot_package_release is immutable'; END$$
DELIMITER ;
