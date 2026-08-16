CREATE TABLE inv_cost_policy_version (
    policy_version_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    cost_scope_type VARCHAR(16) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    quantity_scale TINYINT NOT NULL,
    cost_scale TINYINT NOT NULL,
    rounding_mode VARCHAR(16) NOT NULL,
    zero_quantity_mode VARCHAR(48) NOT NULL,
    effective_from DATETIME(3) NOT NULL,
    publisher_user_id BIGINT NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, policy_version_id),
    UNIQUE KEY uk_inv_cost_policy_scope_time (tenant_id, warehouse_id, effective_from),
    KEY idx_inv_cost_policy_effective (tenant_id, store_id, warehouse_id, effective_from),
    CONSTRAINT fk_inv_cost_policy_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_cost_policy_frozen CHECK (
        cost_scope_type='WAREHOUSE' AND currency_code='CNY' AND quantity_scale=6 AND cost_scale=6 AND
        rounding_mode='HALF_EVEN' AND zero_quantity_mode='ZERO_AMOUNT_KEEP_LAST_UNIT_COST'
    ),
    CONSTRAINT ck_inv_cost_policy_ulids CHECK (
        policy_version_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$' AND
        warehouse_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_cost_balance (
    tenant_id VARCHAR(20) NOT NULL,
    cost_dimension_key CHAR(64) NOT NULL,
    cost_scope_id CHAR(26) NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    store_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    currency_code CHAR(3) NOT NULL,
    cost_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    cost_amount_minor DECIMAL(25,6) NOT NULL DEFAULT 0,
    avg_unit_cost_minor DECIMAL(25,6) NOT NULL DEFAULT 0,
    last_unit_cost_minor DECIMAL(25,6) NOT NULL DEFAULT 0,
    last_cost_ledger_sequence BIGINT NOT NULL DEFAULT 0,
    last_inventory_ledger_sequence BIGINT NOT NULL DEFAULT 0,
    policy_version_id CHAR(26) NOT NULL,
    record_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, cost_dimension_key),
    UNIQUE KEY uk_inv_cost_balance_scope (tenant_id, warehouse_id, sku_id, currency_code),
    CONSTRAINT fk_inv_cost_balance_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_inv_cost_balance_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_inv_cost_balance_policy FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES inv_cost_policy_version (tenant_id, policy_version_id),
    CONSTRAINT ck_inv_cost_balance_hash CHECK (cost_dimension_key REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_cost_balance_scope CHECK (cost_scope_id=warehouse_id AND currency_code='CNY'),
    CONSTRAINT ck_inv_cost_balance_equation CHECK (
        avg_unit_cost_minor >= 0 AND last_unit_cost_minor >= 0 AND
        ((cost_quantity=0 AND cost_amount_minor=0) OR
         (cost_quantity > 0 AND cost_amount_minor >= 0) OR
         (cost_quantity < 0 AND cost_amount_minor <= 0))
    ),
    CONSTRAINT ck_inv_cost_balance_sequence CHECK (
        last_cost_ledger_sequence >= 0 AND last_inventory_ledger_sequence >= 0 AND record_version >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_cost_ledger (
    cost_ledger_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    cost_dimension_key CHAR(64) NOT NULL,
    cost_scope_id CHAR(26) NOT NULL,
    cost_ledger_sequence BIGINT NOT NULL,
    inventory_ledger_id CHAR(26) NOT NULL,
    inventory_ledger_sequence BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    currency_code CHAR(3) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity_before DECIMAL(19,6) NOT NULL,
    quantity_delta DECIMAL(19,6) NOT NULL,
    quantity_after DECIMAL(19,6) NOT NULL,
    cost_amount_before_minor DECIMAL(25,6) NOT NULL,
    cost_amount_delta_minor DECIMAL(25,6) NOT NULL,
    cost_amount_after_minor DECIMAL(25,6) NOT NULL,
    unit_cost_minor DECIMAL(25,6) NOT NULL,
    avg_unit_cost_after_minor DECIMAL(25,6) NOT NULL,
    valuation_method VARCHAR(32) NOT NULL,
    cost_estimated TINYINT(1) NOT NULL,
    variance_amount_minor DECIMAL(25,6) NOT NULL DEFAULT 0,
    source_type VARCHAR(24) NOT NULL,
    source_id CHAR(26) NOT NULL,
    source_line_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    policy_version_id CHAR(26) NOT NULL,
    reversal_of_cost_ledger_id CHAR(26) NULL,
    business_date DATE NOT NULL,
    actor_user_id BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, cost_ledger_id),
    UNIQUE KEY uk_inv_cost_ledger_sequence (tenant_id, cost_dimension_key, cost_ledger_sequence),
    UNIQUE KEY uk_inv_cost_inventory_ledger (tenant_id, inventory_ledger_id),
    KEY idx_inv_cost_ledger_source (tenant_id, warehouse_id, sku_id, source_type, source_line_id, movement_type),
    KEY idx_inv_cost_ledger_business_date (tenant_id, warehouse_id, business_date, cost_ledger_sequence),
    CONSTRAINT fk_inv_cost_ledger_balance FOREIGN KEY (tenant_id, cost_dimension_key)
        REFERENCES inv_cost_balance (tenant_id, cost_dimension_key),
    CONSTRAINT fk_inv_cost_ledger_inventory FOREIGN KEY (tenant_id, inventory_ledger_id)
        REFERENCES inv_stock_ledger (tenant_id, ledger_id),
    CONSTRAINT fk_inv_cost_ledger_policy FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES inv_cost_policy_version (tenant_id, policy_version_id),
    CONSTRAINT fk_inv_cost_ledger_reversal FOREIGN KEY (tenant_id, reversal_of_cost_ledger_id)
        REFERENCES inv_cost_ledger (tenant_id, cost_ledger_id),
    CONSTRAINT fk_inv_cost_ledger_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT ck_inv_cost_ledger_scope CHECK (cost_scope_id=warehouse_id AND currency_code='CNY'),
    CONSTRAINT ck_inv_cost_ledger_movement CHECK (movement_type IN
      ('PURCHASE_RECEIPT_IN','PURCHASE_RETURN_OUT','SALE_OUT','SALE_RETURN_IN',
       'STOCKTAKE_GAIN','STOCKTAKE_LOSS','REVERSAL')),
    CONSTRAINT ck_inv_cost_ledger_source CHECK (source_type IN
      ('PURCHASE_RECEIPT','PURCHASE_RETURN','ORDER','REFUND','STOCKTAKE','REVERSAL')),
    CONSTRAINT ck_inv_cost_ledger_quantity CHECK (
        quantity_before + quantity_delta = quantity_after AND quantity_delta <> 0
    ),
    CONSTRAINT ck_inv_cost_ledger_amount CHECK (
        cost_amount_before_minor + cost_amount_delta_minor = cost_amount_after_minor AND
        unit_cost_minor >= 0 AND avg_unit_cost_after_minor >= 0 AND
        ((quantity_after=0 AND cost_amount_after_minor=0) OR
         (quantity_after > 0 AND cost_amount_after_minor >= 0) OR
         (quantity_after < 0 AND cost_amount_after_minor <= 0))
    ),
    CONSTRAINT ck_inv_cost_ledger_sequence CHECK (
        cost_ledger_sequence > 0 AND inventory_ledger_sequence > 0
    ),
    CONSTRAINT ck_inv_cost_ledger_hash CHECK (source_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_cost_ledger_reversal_shape CHECK (
        (movement_type='REVERSAL' AND reversal_of_cost_ledger_id IS NOT NULL) OR
        (movement_type<>'REVERSAL' AND reversal_of_cost_ledger_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_cost_rebuild_run (
    rebuild_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    cost_dimension_key CHAR(64) NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    store_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    previous_quantity DECIMAL(19,6) NOT NULL,
    rebuilt_quantity DECIMAL(19,6) NOT NULL,
    previous_amount_minor DECIMAL(25,6) NOT NULL,
    rebuilt_amount_minor DECIMAL(25,6) NOT NULL,
    ledger_count BIGINT NOT NULL,
    changed TINYINT(1) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, rebuild_id),
    KEY idx_inv_cost_rebuild_dimension (tenant_id, cost_dimension_key, completed_at),
    CONSTRAINT fk_inv_cost_rebuild_balance FOREIGN KEY (tenant_id, cost_dimension_key)
        REFERENCES inv_cost_balance (tenant_id, cost_dimension_key),
    CONSTRAINT fk_inv_cost_rebuild_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_cost_rebuild_state CHECK (status='COMPLETED' AND ledger_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_cost_audit_event (
    audit_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_id CHAR(26) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    before_value VARCHAR(96) NULL,
    after_value VARCHAR(96) NULL,
    request_sha256 CHAR(64) NOT NULL,
    reason_code VARCHAR(48) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    KEY idx_inv_cost_audit_target (tenant_id, aggregate_type, aggregate_id, occurred_at),
    CONSTRAINT fk_inv_cost_audit_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_cost_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_cost_event_outbox (
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
    KEY idx_inv_cost_outbox_delivery (tenant_id, delivery_state, available_at, event_id),
    CONSTRAINT ck_inv_cost_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_inv_cost_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inv_cost_outbox_state CHECK (delivery_state IN ('PENDING','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_inv_cost_ledger_no_update BEFORE UPDATE ON inv_cost_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_cost_ledger is immutable'; END$$
CREATE TRIGGER trg_inv_cost_ledger_no_delete BEFORE DELETE ON inv_cost_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_cost_ledger is immutable'; END$$
CREATE TRIGGER trg_inv_cost_policy_no_update BEFORE UPDATE ON inv_cost_policy_version FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_cost_policy_version is immutable'; END$$
CREATE TRIGGER trg_inv_cost_policy_no_delete BEFORE DELETE ON inv_cost_policy_version FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_cost_policy_version is immutable'; END$$
CREATE TRIGGER trg_inv_cost_audit_no_update BEFORE UPDATE ON inv_cost_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_cost_audit_event is immutable'; END$$
CREATE TRIGGER trg_inv_cost_audit_no_delete BEFORE DELETE ON inv_cost_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_cost_audit_event is immutable'; END$$
DELIMITER ;
