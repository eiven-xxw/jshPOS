CREATE TABLE sup_supplier (
    supplier_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    supplier_code VARCHAR(64) NOT NULL,
    supplier_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    creator_user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, supplier_id),
    UNIQUE KEY uk_sup_supplier_code (tenant_id, supplier_code),
    KEY idx_sup_supplier_status (tenant_id, status, supplier_code),
    CONSTRAINT ck_sup_supplier_status CHECK (status IN ('ACTIVE','SUSPENDED','BLOCKED')),
    CONSTRAINT ck_sup_supplier_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_purchase_order (
    order_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    supplier_id CHAR(26) NOT NULL,
    store_id BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    expected_date DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    over_receipt_tolerance_bps INT NOT NULL DEFAULT 0,
    request_sha256 CHAR(64) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    creator_user_id BIGINT NOT NULL,
    approver_user_id BIGINT NULL,
    approved_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, order_id),
    KEY idx_pur_order_store_state (tenant_id, store_id, status, expected_date),
    KEY idx_pur_order_supplier (tenant_id, supplier_id, created_at),
    CONSTRAINT fk_pur_order_supplier FOREIGN KEY (tenant_id, supplier_id)
        REFERENCES sup_supplier (tenant_id, supplier_id),
    CONSTRAINT fk_pur_order_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_pur_order_status CHECK (status IN
      ('DRAFT','SUBMITTED','APPROVED','PARTIALLY_RECEIVED','RECEIVED','CLOSED','CANCELLED')),
    CONSTRAINT ck_pur_order_tolerance CHECK (over_receipt_tolerance_bps BETWEEN 0 AND 1000),
    CONSTRAINT ck_pur_order_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_pur_order_approval CHECK (
      (status IN ('DRAFT','SUBMITTED') AND approver_user_id IS NULL AND approved_at IS NULL) OR
      (status IN ('APPROVED','PARTIALLY_RECEIVED','RECEIVED','CLOSED')
        AND approver_user_id IS NOT NULL AND approved_at IS NOT NULL) OR status='CANCELLED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_purchase_order_line (
    order_line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    purchase_unit_id BIGINT NOT NULL,
    conversion_numerator BIGINT NOT NULL,
    conversion_denominator BIGINT NOT NULL,
    ordered_quantity DECIMAL(19,6) NOT NULL,
    received_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    unit_price_minor BIGINT NOT NULL,
    tax_rate_bps INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, order_line_id),
    UNIQUE KEY uk_pur_order_sku_unit (tenant_id, order_id, sku_id, purchase_unit_id),
    KEY idx_pur_order_line_head (tenant_id, order_id, order_line_id),
    CONSTRAINT fk_pur_order_line_head FOREIGN KEY (tenant_id, order_id)
        REFERENCES pur_purchase_order (tenant_id, order_id),
    CONSTRAINT fk_pur_order_line_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_pur_order_line_unit FOREIGN KEY (tenant_id, purchase_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_pur_order_line_quantity CHECK (ordered_quantity > 0 AND received_quantity >= 0),
    CONSTRAINT ck_pur_order_line_conversion CHECK (conversion_numerator > 0 AND conversion_denominator > 0),
    CONSTRAINT ck_pur_order_line_money CHECK (unit_price_minor >= 0 AND tax_rate_bps BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_receipt (
    receipt_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NULL,
    store_id BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    status VARCHAR(16) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    confirmed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, receipt_id),
    UNIQUE KEY uk_pur_receipt_event (tenant_id, source_event_id),
    KEY idx_pur_receipt_order (tenant_id, order_id, created_at),
    CONSTRAINT fk_pur_receipt_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES pur_purchase_order (tenant_id, order_id),
    CONSTRAINT fk_pur_receipt_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_pur_receipt_status CHECK (status IN ('DRAFT','CONFIRMED')),
    CONSTRAINT ck_pur_receipt_shape CHECK (
      (status='DRAFT' AND source_event_id IS NULL AND confirmed_at IS NULL) OR
      (status='CONFIRMED' AND source_event_id IS NOT NULL AND confirmed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_receipt_line (
    receipt_line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    receipt_id CHAR(26) NOT NULL,
    order_line_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    base_unit_id BIGINT NOT NULL,
    received_quantity DECIMAL(19,6) NOT NULL,
    base_quantity DECIMAL(19,6) NOT NULL,
    returned_quantity DECIMAL(19,6) NOT NULL DEFAULT 0,
    conversion_numerator BIGINT NOT NULL,
    conversion_denominator BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, receipt_line_id),
    UNIQUE KEY uk_pur_receipt_order_line (tenant_id, receipt_id, order_line_id),
    KEY idx_pur_receipt_line_head (tenant_id, receipt_id, receipt_line_id),
    CONSTRAINT fk_pur_receipt_line_head FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES pur_receipt (tenant_id, receipt_id),
    CONSTRAINT fk_pur_receipt_line_order FOREIGN KEY (tenant_id, order_line_id)
        REFERENCES pur_purchase_order_line (tenant_id, order_line_id),
    CONSTRAINT fk_pur_receipt_line_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_pur_receipt_line_unit FOREIGN KEY (tenant_id, base_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_pur_receipt_line_quantity CHECK (
      received_quantity > 0 AND base_quantity > 0 AND returned_quantity >= 0 AND returned_quantity <= received_quantity),
    CONSTRAINT ck_pur_receipt_line_conversion CHECK (conversion_numerator > 0 AND conversion_denominator > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_purchase_return (
    purchase_return_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    receipt_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    requester_user_id BIGINT NOT NULL,
    approver_user_id BIGINT NULL,
    posted_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, purchase_return_id),
    UNIQUE KEY uk_pur_return_event (tenant_id, source_event_id),
    KEY idx_pur_return_receipt (tenant_id, receipt_id, created_at),
    CONSTRAINT fk_pur_return_receipt FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES pur_receipt (tenant_id, receipt_id),
    CONSTRAINT ck_pur_return_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','POSTED')),
    CONSTRAINT ck_pur_return_shape CHECK (
      (status IN ('DRAFT','PENDING_APPROVAL') AND source_event_id IS NULL
        AND approver_user_id IS NULL AND posted_at IS NULL) OR
      (status='POSTED' AND source_event_id IS NOT NULL AND approver_user_id IS NOT NULL AND posted_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_purchase_return_line (
    return_line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    purchase_return_id CHAR(26) NOT NULL,
    receipt_line_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    base_unit_id BIGINT NOT NULL,
    return_quantity DECIMAL(19,6) NOT NULL,
    base_quantity DECIMAL(19,6) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, return_line_id),
    UNIQUE KEY uk_pur_return_receipt_line (tenant_id, purchase_return_id, receipt_line_id),
    CONSTRAINT fk_pur_return_line_head FOREIGN KEY (tenant_id, purchase_return_id)
        REFERENCES pur_purchase_return (tenant_id, purchase_return_id),
    CONSTRAINT fk_pur_return_line_receipt FOREIGN KEY (tenant_id, receipt_line_id)
        REFERENCES pur_receipt_line (tenant_id, receipt_line_id),
    CONSTRAINT fk_pur_return_line_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_pur_return_line_unit FOREIGN KEY (tenant_id, base_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_pur_return_line_quantity CHECK (return_quantity > 0 AND base_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_audit_event (
    audit_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NULL,
    action_code VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id CHAR(26) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_id CHAR(26) NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    before_value VARCHAR(64) NULL,
    after_value VARCHAR(64) NULL,
    request_sha256 CHAR(64) NOT NULL,
    reason_code VARCHAR(256) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    KEY idx_pur_audit_target (tenant_id, aggregate_type, aggregate_id, occurred_at),
    CONSTRAINT fk_pur_audit_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_pur_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pur_event_outbox (
    event_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(26) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    correlation_id VARCHAR(96) NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    delivered_at DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, event_id),
    KEY idx_pur_outbox_delivery (tenant_id, delivery_state, available_at, event_id),
    CONSTRAINT ck_pur_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_pur_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_pur_outbox_state CHECK (delivery_state IN ('PENDING','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_pur_order_line_core_immutable BEFORE UPDATE ON pur_purchase_order_line FOR EACH ROW
BEGIN
    IF NOT (OLD.order_id <=> NEW.order_id) OR NOT (OLD.sku_id <=> NEW.sku_id)
       OR NOT (OLD.purchase_unit_id <=> NEW.purchase_unit_id)
       OR NOT (OLD.conversion_numerator <=> NEW.conversion_numerator)
       OR NOT (OLD.conversion_denominator <=> NEW.conversion_denominator)
       OR NOT (OLD.ordered_quantity <=> NEW.ordered_quantity)
       OR NOT (OLD.unit_price_minor <=> NEW.unit_price_minor) OR NOT (OLD.tax_rate_bps <=> NEW.tax_rate_bps) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='pur_purchase_order_line frozen fact is immutable';
    END IF;
END$$
CREATE TRIGGER trg_pur_receipt_confirmed_immutable BEFORE UPDATE ON pur_receipt FOR EACH ROW
BEGIN IF OLD.status='CONFIRMED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='confirmed pur_receipt is immutable'; END IF; END$$
CREATE TRIGGER trg_pur_return_posted_immutable BEFORE UPDATE ON pur_purchase_return FOR EACH ROW
BEGIN IF OLD.status='POSTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='posted pur_purchase_return is immutable'; END IF; END$$
CREATE TRIGGER trg_pur_return_line_no_update BEFORE UPDATE ON pur_purchase_return_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='pur_purchase_return_line is immutable'; END$$
CREATE TRIGGER trg_pur_return_line_no_delete BEFORE DELETE ON pur_purchase_return_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='pur_purchase_return_line is immutable'; END$$
CREATE TRIGGER trg_pur_receipt_line_core_immutable BEFORE UPDATE ON pur_receipt_line FOR EACH ROW
BEGIN
    IF NOT (OLD.receipt_id <=> NEW.receipt_id) OR NOT (OLD.order_line_id <=> NEW.order_line_id)
       OR NOT (OLD.sku_id <=> NEW.sku_id) OR NOT (OLD.base_unit_id <=> NEW.base_unit_id)
       OR NOT (OLD.received_quantity <=> NEW.received_quantity) OR NOT (OLD.base_quantity <=> NEW.base_quantity)
       OR NOT (OLD.conversion_numerator <=> NEW.conversion_numerator)
       OR NOT (OLD.conversion_denominator <=> NEW.conversion_denominator) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='pur_receipt_line quantity fact is immutable';
    END IF;
END$$
CREATE TRIGGER trg_pur_audit_no_update BEFORE UPDATE ON pur_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='pur_audit_event is immutable'; END$$
CREATE TRIGGER trg_pur_audit_no_delete BEFORE DELETE ON pur_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='pur_audit_event is immutable'; END$$
DELIMITER ;
