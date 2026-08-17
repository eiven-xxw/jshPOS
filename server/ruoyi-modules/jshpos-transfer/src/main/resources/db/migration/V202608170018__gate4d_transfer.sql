ALTER TABLE inv_stock_command DROP CHECK ck_inv_command_source;
ALTER TABLE inv_stock_command ADD CONSTRAINT ck_inv_command_source
    CHECK (source_type IN ('ORDER','REFUND','STOCKTAKE','PURCHASE_RECEIPT','PURCHASE_RETURN',
      'TRANSFER_DISPATCH','TRANSFER_RECEIPT'));

ALTER TABLE inv_stock_ledger DROP CHECK ck_inv_ledger_movement;
ALTER TABLE inv_stock_ledger DROP CHECK ck_inv_ledger_source;
ALTER TABLE inv_stock_ledger DROP CHECK ck_inv_ledger_direction;
ALTER TABLE inv_stock_ledger ADD CONSTRAINT ck_inv_ledger_movement
    CHECK (movement_type IN ('SALE_OUT','SALE_RETURN_IN','STOCKTAKE_GAIN','STOCKTAKE_LOSS',
      'PURCHASE_RECEIPT_IN','PURCHASE_RETURN_OUT','TRANSFER_OUT','TRANSFER_IN'));
ALTER TABLE inv_stock_ledger ADD CONSTRAINT ck_inv_ledger_source
    CHECK (source_type IN ('ORDER','REFUND','STOCKTAKE','PURCHASE_RECEIPT','PURCHASE_RETURN',
      'TRANSFER_DISPATCH','TRANSFER_RECEIPT'));
ALTER TABLE inv_stock_ledger ADD CONSTRAINT ck_inv_ledger_direction CHECK (
    (movement_type IN ('SALE_OUT','STOCKTAKE_LOSS','PURCHASE_RETURN_OUT','TRANSFER_OUT') AND quantity_delta < 0) OR
    (movement_type IN ('SALE_RETURN_IN','STOCKTAKE_GAIN','PURCHASE_RECEIPT_IN','TRANSFER_IN') AND quantity_delta > 0)
);

ALTER TABLE inv_cost_ledger DROP CHECK ck_inv_cost_ledger_movement;
ALTER TABLE inv_cost_ledger DROP CHECK ck_inv_cost_ledger_source;
ALTER TABLE inv_cost_ledger ADD CONSTRAINT ck_inv_cost_ledger_movement CHECK (movement_type IN
    ('PURCHASE_RECEIPT_IN','PURCHASE_RETURN_OUT','SALE_OUT','SALE_RETURN_IN','STOCKTAKE_GAIN',
     'STOCKTAKE_LOSS','TRANSFER_OUT','TRANSFER_IN','REVERSAL'));
ALTER TABLE inv_cost_ledger ADD CONSTRAINT ck_inv_cost_ledger_source CHECK (source_type IN
    ('PURCHASE_RECEIPT','PURCHASE_RETURN','ORDER','REFUND','STOCKTAKE',
     'TRANSFER_DISPATCH','TRANSFER_RECEIPT','REVERSAL'));

CREATE TABLE inv_transfer_order (
    transfer_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    source_store_id BIGINT NOT NULL,
    source_warehouse_id CHAR(26) NOT NULL,
    destination_store_id BIGINT NOT NULL,
    destination_warehouse_id CHAR(26) NOT NULL,
    status VARCHAR(24) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    creator_user_id BIGINT NOT NULL,
    approver_user_id BIGINT NULL,
    approved_at DATETIME(3) NULL,
    dispatched_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, transfer_id),
    KEY idx_trf_source_state (tenant_id, source_store_id, status, created_at),
    KEY idx_trf_destination_state (tenant_id, destination_store_id, status, created_at),
    CONSTRAINT fk_trf_source_store FOREIGN KEY (tenant_id, source_store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_trf_destination_store FOREIGN KEY (tenant_id, destination_store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_trf_route CHECK (source_warehouse_id <> destination_warehouse_id),
    CONSTRAINT ck_trf_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','IN_TRANSIT',
      'PARTIALLY_RECEIVED','DIFFERENCE_PENDING','CLOSED','CANCELLED')),
    CONSTRAINT ck_trf_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_trf_version CHECK (version >= 0),
    CONSTRAINT ck_trf_approval CHECK (
      (status IN ('DRAFT','SUBMITTED') AND approver_user_id IS NULL AND approved_at IS NULL) OR
      (status IN ('APPROVED','IN_TRANSIT','PARTIALLY_RECEIVED','DIFFERENCE_PENDING','CLOSED')
        AND approver_user_id IS NOT NULL AND approved_at IS NOT NULL) OR status='CANCELLED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_line (
    transfer_line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    transfer_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    requested_unit_id BIGINT NOT NULL,
    conversion_numerator BIGINT NOT NULL,
    conversion_denominator BIGINT NOT NULL,
    input_quantity DECIMAL(19,6) NOT NULL,
    base_unit_id BIGINT NOT NULL,
    requested_quantity DECIMAL(19,6) NOT NULL,
    dispatched_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    received_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    difference_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, transfer_line_id),
    UNIQUE KEY uk_trf_line_sku (tenant_id, transfer_id, sku_id),
    KEY idx_trf_line_head (tenant_id, transfer_id, transfer_line_id),
    CONSTRAINT fk_trf_line_head FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inv_transfer_order (tenant_id, transfer_id),
    CONSTRAINT fk_trf_line_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_trf_line_requested_unit FOREIGN KEY (tenant_id, requested_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT fk_trf_line_unit FOREIGN KEY (tenant_id, base_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_trf_line_conversion CHECK (conversion_numerator > 0 AND conversion_denominator > 0
      AND requested_quantity = ROUND(input_quantity * conversion_numerator / conversion_denominator, 6)),
    CONSTRAINT ck_trf_line_quantities CHECK (input_quantity > 0 AND requested_quantity > 0 AND dispatched_quantity >= 0
      AND received_quantity >= 0 AND difference_quantity >= 0
      AND dispatched_quantity <= requested_quantity
      AND received_quantity + difference_quantity <= dispatched_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_command (
    command_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    transfer_id CHAR(26) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    applied_at DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, command_id),
    KEY idx_trf_command_head (tenant_id, transfer_id, created_at),
    CONSTRAINT fk_trf_command_head FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inv_transfer_order (tenant_id, transfer_id),
    CONSTRAINT ck_trf_command_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_trf_command_status CHECK (status IN ('PROCESSING','APPLIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_dispatch (
    dispatch_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    transfer_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    status VARCHAR(16) NOT NULL,
    business_date DATE NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    posted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, dispatch_id),
    UNIQUE KEY uk_trf_dispatch_head (tenant_id, transfer_id),
    UNIQUE KEY uk_trf_dispatch_event (tenant_id, source_event_id),
    CONSTRAINT fk_trf_dispatch_head FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inv_transfer_order (tenant_id, transfer_id),
    CONSTRAINT ck_trf_dispatch_status CHECK (status='POSTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_dispatch_line (
    dispatch_line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    dispatch_id CHAR(26) NOT NULL,
    transfer_line_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    base_unit_id BIGINT NOT NULL,
    base_quantity DECIMAL(19,6) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, dispatch_line_id),
    UNIQUE KEY uk_trf_dispatch_line (tenant_id, dispatch_id, transfer_line_id),
    CONSTRAINT fk_trf_dispatch_line_head FOREIGN KEY (tenant_id, dispatch_id)
        REFERENCES inv_transfer_dispatch (tenant_id, dispatch_id),
    CONSTRAINT fk_trf_dispatch_line_transfer FOREIGN KEY (tenant_id, transfer_line_id)
        REFERENCES inv_transfer_line (tenant_id, transfer_line_id),
    CONSTRAINT ck_trf_dispatch_line_qty CHECK (base_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_receipt (
    receipt_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    transfer_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    status VARCHAR(16) NOT NULL,
    final_receipt TINYINT(1) NOT NULL,
    business_date DATE NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    posted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, receipt_id),
    UNIQUE KEY uk_trf_receipt_event (tenant_id, source_event_id),
    KEY idx_trf_receipt_head (tenant_id, transfer_id, posted_at),
    CONSTRAINT fk_trf_receipt_head FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inv_transfer_order (tenant_id, transfer_id),
    CONSTRAINT ck_trf_receipt_status CHECK (status='POSTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_receipt_line (
    receipt_line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    receipt_id CHAR(26) NOT NULL,
    transfer_line_id CHAR(26) NOT NULL,
    dispatch_line_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    base_unit_id BIGINT NOT NULL,
    base_quantity DECIMAL(19,6) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, receipt_line_id),
    UNIQUE KEY uk_trf_receipt_line (tenant_id, receipt_id, transfer_line_id),
    CONSTRAINT fk_trf_receipt_line_head FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES inv_transfer_receipt (tenant_id, receipt_id),
    CONSTRAINT fk_trf_receipt_line_transfer FOREIGN KEY (tenant_id, transfer_line_id)
        REFERENCES inv_transfer_line (tenant_id, transfer_line_id),
    CONSTRAINT fk_trf_receipt_line_dispatch FOREIGN KEY (tenant_id, dispatch_line_id)
        REFERENCES inv_transfer_dispatch_line (tenant_id, dispatch_line_id),
    CONSTRAINT ck_trf_receipt_line_qty CHECK (base_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_transit_ledger (
    transit_ledger_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    transfer_id CHAR(26) NOT NULL,
    transfer_line_id CHAR(26) NOT NULL,
    fact_type VARCHAR(24) NOT NULL,
    source_fact_id CHAR(26) NOT NULL,
    quantity DECIMAL(19,6) NOT NULL,
    business_date DATE NOT NULL,
    reason_code VARCHAR(32) NULL,
    correlation_id VARCHAR(96) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, transit_ledger_id),
    UNIQUE KEY uk_trf_transit_fact (tenant_id, fact_type, source_fact_id, transfer_line_id),
    KEY idx_trf_transit_line (tenant_id, transfer_line_id, occurred_at),
    CONSTRAINT fk_trf_transit_head FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inv_transfer_order (tenant_id, transfer_id),
    CONSTRAINT fk_trf_transit_line FOREIGN KEY (tenant_id, transfer_line_id)
        REFERENCES inv_transfer_line (tenant_id, transfer_line_id),
    CONSTRAINT ck_trf_transit_type CHECK (fact_type IN ('DISPATCHED','RECEIVED','DIFFERENCE_APPROVED')),
    CONSTRAINT ck_trf_transit_qty CHECK (quantity > 0),
    CONSTRAINT ck_trf_transit_reason CHECK (
        (fact_type='DIFFERENCE_APPROVED' AND reason_code IN ('SHORTAGE','DAMAGED','REJECTED','TRANSIT_LOSS'))
        OR (fact_type IN ('DISPATCHED','RECEIVED') AND reason_code IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_audit_event (
    audit_id CHAR(26) NOT NULL, tenant_id VARCHAR(20) NOT NULL, store_id BIGINT NULL,
    action_code VARCHAR(64) NOT NULL, aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id CHAR(26) NOT NULL, actor_user_id BIGINT NOT NULL, command_id CHAR(26) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL, before_value VARCHAR(64) NULL, after_value VARCHAR(64) NULL,
    content_sha256 CHAR(64) NOT NULL, reason_code VARCHAR(256) NOT NULL, occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    KEY idx_trf_audit_target (tenant_id, aggregate_type, aggregate_id, occurred_at),
    CONSTRAINT fk_trf_audit_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_trf_audit_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_transfer_event_outbox (
    event_id CHAR(26) NOT NULL, tenant_id VARCHAR(20) NOT NULL, event_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(26) NOT NULL, aggregate_version BIGINT NOT NULL, correlation_id VARCHAR(96) NOT NULL,
    payload_json JSON NOT NULL, payload_sha256 CHAR(64) NOT NULL, delivery_state VARCHAR(16) NOT NULL,
    available_at DATETIME(3) NOT NULL, delivered_at DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, event_id), KEY idx_trf_outbox_delivery (tenant_id, delivery_state, available_at, event_id),
    CONSTRAINT ck_trf_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_trf_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_trf_outbox_state CHECK (delivery_state IN ('PENDING','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_trf_dispatch_no_update BEFORE UPDATE ON inv_transfer_dispatch FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_dispatch is immutable'; END$$
CREATE TRIGGER trg_trf_dispatch_no_delete BEFORE DELETE ON inv_transfer_dispatch FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_dispatch is immutable'; END$$
CREATE TRIGGER trg_trf_receipt_no_update BEFORE UPDATE ON inv_transfer_receipt FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_receipt is immutable'; END$$
CREATE TRIGGER trg_trf_receipt_no_delete BEFORE DELETE ON inv_transfer_receipt FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_receipt is immutable'; END$$
CREATE TRIGGER trg_trf_transit_no_update BEFORE UPDATE ON inv_transfer_transit_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_transit_ledger is immutable'; END$$
CREATE TRIGGER trg_trf_transit_no_delete BEFORE DELETE ON inv_transfer_transit_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_transit_ledger is immutable'; END$$
CREATE TRIGGER trg_trf_audit_no_update BEFORE UPDATE ON inv_transfer_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_audit_event is immutable'; END$$
CREATE TRIGGER trg_trf_audit_no_delete BEFORE DELETE ON inv_transfer_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_transfer_audit_event is immutable'; END$$
DELIMITER ;
