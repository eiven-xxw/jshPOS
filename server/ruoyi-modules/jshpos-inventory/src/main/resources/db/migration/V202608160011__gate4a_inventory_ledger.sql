CREATE TABLE inv_stock_policy_version (
    policy_version_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    negative_stock_mode VARCHAR(32) NOT NULL,
    effective_from DATETIME(3) NOT NULL,
    publisher_user_id BIGINT NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, policy_version_id),
    UNIQUE KEY uk_inv_policy_scope_time (tenant_id, warehouse_id, effective_from),
    KEY idx_inv_policy_effective (tenant_id, store_id, warehouse_id, effective_from),
    CONSTRAINT fk_inv_policy_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_policy_mode CHECK (negative_stock_mode IN ('DENY','ALLOW_WITH_PERMISSION','ALLOW_AND_ALERT')),
    CONSTRAINT ck_inv_policy_ulids CHECK (
        policy_version_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$' AND
        warehouse_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stock_command (
    source_event_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id CHAR(26) NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    store_id BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    affected_lines INT NOT NULL DEFAULT 0,
    negative_alert TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    applied_at DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, source_event_id),
    UNIQUE KEY uk_inv_command_source (tenant_id, source_type, source_id, source_event_id),
    KEY idx_inv_command_source_id (tenant_id, source_type, source_id),
    CONSTRAINT fk_inv_command_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_command_source CHECK (source_type IN ('ORDER','REFUND')),
    CONSTRAINT ck_inv_command_status CHECK (status IN ('PROCESSING','APPLIED')),
    CONSTRAINT ck_inv_command_lines CHECK (affected_lines BETWEEN 0 AND 500),
    CONSTRAINT ck_inv_command_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_command_shape CHECK (
        (status='PROCESSING' AND applied_at IS NULL) OR
        (status='APPLIED' AND applied_at IS NOT NULL AND affected_lines > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stock_balance (
    tenant_id VARCHAR(20) NOT NULL,
    stock_dimension_key CHAR(64) NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    stock_status VARCHAR(16) NOT NULL,
    on_hand_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    reserved_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    frozen_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    safety_stock_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    last_ledger_sequence BIGINT NOT NULL DEFAULT 0,
    record_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, stock_dimension_key),
    UNIQUE KEY uk_inv_balance_dimension (tenant_id, warehouse_id, sku_id, stock_status),
    KEY idx_inv_balance_store_sku (tenant_id, warehouse_id, sku_id),
    CONSTRAINT fk_inv_balance_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT ck_inv_balance_hash CHECK (stock_dimension_key REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_balance_status CHECK (stock_status='SALEABLE'),
    CONSTRAINT ck_inv_balance_reserved CHECK (reserved_quantity >= 0 AND frozen_quantity >= 0 AND safety_stock_quantity >= 0),
    CONSTRAINT ck_inv_balance_sequence CHECK (last_ledger_sequence >= 0 AND record_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stock_ledger (
    ledger_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stock_dimension_key CHAR(64) NOT NULL,
    ledger_sequence BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    base_unit_id BIGINT NOT NULL,
    stock_status VARCHAR(16) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity_before DECIMAL(19,6) NOT NULL,
    quantity_delta DECIMAL(19,6) NOT NULL,
    quantity_after DECIMAL(19,6) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id CHAR(26) NOT NULL,
    source_line_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    policy_version_id CHAR(26) NOT NULL,
    business_date DATE NOT NULL,
    actor_user_id BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, ledger_id),
    UNIQUE KEY uk_inv_ledger_sequence (tenant_id, stock_dimension_key, ledger_sequence),
    UNIQUE KEY uk_inv_ledger_source_line (tenant_id, source_type, source_id, source_line_id, movement_type),
    KEY idx_inv_ledger_source_event (tenant_id, source_event_id),
    KEY idx_inv_ledger_business_date (tenant_id, warehouse_id, business_date, ledger_sequence),
    CONSTRAINT fk_inv_ledger_balance FOREIGN KEY (tenant_id, stock_dimension_key)
        REFERENCES inv_stock_balance (tenant_id, stock_dimension_key),
    CONSTRAINT fk_inv_ledger_command FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES inv_stock_command (tenant_id, source_event_id),
    CONSTRAINT fk_inv_ledger_policy FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES inv_stock_policy_version (tenant_id, policy_version_id),
    CONSTRAINT fk_inv_ledger_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_inv_ledger_unit FOREIGN KEY (tenant_id, base_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_inv_ledger_movement CHECK (movement_type IN ('SALE_OUT','SALE_RETURN_IN')),
    CONSTRAINT ck_inv_ledger_source CHECK (source_type IN ('ORDER','REFUND')),
    CONSTRAINT ck_inv_ledger_direction CHECK (
        (movement_type='SALE_OUT' AND quantity_delta < 0) OR
        (movement_type='SALE_RETURN_IN' AND quantity_delta > 0)
    ),
    CONSTRAINT ck_inv_ledger_equation CHECK (quantity_before + quantity_delta = quantity_after),
    CONSTRAINT ck_inv_ledger_sequence CHECK (ledger_sequence > 0),
    CONSTRAINT ck_inv_ledger_status CHECK (stock_status='SALEABLE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stock_anomaly (
    anomaly_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    anomaly_type VARCHAR(32) NOT NULL,
    observed_quantity DECIMAL(19,6) NOT NULL,
    policy_version_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    status VARCHAR(16) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, anomaly_id),
    KEY idx_inv_anomaly_open (tenant_id, status, occurred_at),
    CONSTRAINT fk_inv_anomaly_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_inv_anomaly_policy FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES inv_stock_policy_version (tenant_id, policy_version_id),
    CONSTRAINT fk_inv_anomaly_command FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES inv_stock_command (tenant_id, source_event_id),
    CONSTRAINT ck_inv_anomaly_type CHECK (anomaly_type='NEGATIVE_STOCK'),
    CONSTRAINT ck_inv_anomaly_status CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    CONSTRAINT ck_inv_anomaly_quantity CHECK (observed_quantity < 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_audit_event (
    audit_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_id CHAR(26) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    before_value VARCHAR(64) NULL,
    after_value VARCHAR(64) NULL,
    request_sha256 CHAR(64) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    KEY idx_inv_audit_target (tenant_id, aggregate_type, aggregate_id, occurred_at),
    KEY idx_inv_audit_command (tenant_id, command_id),
    CONSTRAINT fk_inv_audit_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_event_outbox (
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
    KEY idx_inv_outbox_delivery (tenant_id, delivery_state, available_at, event_id),
    CONSTRAINT ck_inv_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_inv_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_outbox_state CHECK (delivery_state IN ('PENDING','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_inv_ledger_no_update BEFORE UPDATE ON inv_stock_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stock_ledger is immutable'; END$$
CREATE TRIGGER trg_inv_ledger_no_delete BEFORE DELETE ON inv_stock_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stock_ledger is immutable'; END$$
CREATE TRIGGER trg_inv_policy_no_update BEFORE UPDATE ON inv_stock_policy_version FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stock_policy_version is immutable'; END$$
CREATE TRIGGER trg_inv_policy_no_delete BEFORE DELETE ON inv_stock_policy_version FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stock_policy_version is immutable'; END$$
CREATE TRIGGER trg_inv_audit_no_update BEFORE UPDATE ON inv_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_audit_event is immutable'; END$$
CREATE TRIGGER trg_inv_audit_no_delete BEFORE DELETE ON inv_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_audit_event is immutable'; END$$
CREATE TRIGGER trg_inv_command_applied_immutable BEFORE UPDATE ON inv_stock_command FOR EACH ROW
BEGIN
    IF OLD.status='APPLIED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='applied inv_stock_command is immutable';
    END IF;
END$$
DELIMITER ;
