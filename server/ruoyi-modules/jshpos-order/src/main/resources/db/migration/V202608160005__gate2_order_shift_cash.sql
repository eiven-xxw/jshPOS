CREATE TABLE shf_shift (
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cashier_user_id BIGINT NOT NULL,
    cashier_name_snapshot VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    store_timezone VARCHAR(64) NOT NULL,
    config_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
    opening_cash_minor BIGINT NOT NULL,
    theoretical_cash_minor BIGINT NOT NULL,
    actual_cash_minor BIGINT NULL,
    difference_minor BIGINT NULL,
    approval_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    opened_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3) NULL,
    record_version BIGINT NOT NULL DEFAULT 1,
    active_terminal_key VARCHAR(96) GENERATED ALWAYS AS (
        CASE WHEN status IN ('OPEN','CLOSING') THEN CONCAT(store_id, ':', terminal_id) ELSE NULL END
    ) STORED,
    active_cashier_key VARCHAR(96) GENERATED ALWAYS AS (
        CASE WHEN status IN ('OPEN','CLOSING') THEN CONCAT(store_id, ':', cashier_user_id) ELSE NULL END
    ) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (shift_id),
    UNIQUE KEY uk_shf_shift_tenant_id (tenant_id, shift_id),
    UNIQUE KEY uk_shf_active_terminal (tenant_id, active_terminal_key),
    UNIQUE KEY uk_shf_active_cashier (tenant_id, active_cashier_key),
    KEY idx_shf_store_business (tenant_id, store_id, business_date, status),
    CONSTRAINT fk_shf_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_shf_status CHECK (status IN ('OPEN','CLOSING','CLOSED')),
    CONSTRAINT ck_shf_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_shf_opening CHECK (opening_cash_minor >= 0),
    CONSTRAINT ck_shf_version CHECK (record_version > 0),
    CONSTRAINT ck_shf_close_shape CHECK (
      (status <> 'CLOSED' AND actual_cash_minor IS NULL AND difference_minor IS NULL AND closed_at IS NULL) OR
      (status = 'CLOSED' AND actual_cash_minor IS NOT NULL AND difference_minor IS NOT NULL AND closed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shf_shift_approval (
    approval_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approver_user_id BIGINT NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(256) NOT NULL,
    theoretical_cash_minor BIGINT NOT NULL,
    actual_cash_minor BIGINT NOT NULL,
    difference_minor BIGINT NOT NULL,
    expected_shift_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    approved_at DATETIME(3) NOT NULL,
    PRIMARY KEY (approval_id),
    UNIQUE KEY uk_shf_approval_tenant_id (tenant_id, approval_id),
    UNIQUE KEY uk_shf_approval_shift (tenant_id, shift_id, approval_id),
    CONSTRAINT fk_shf_approval_shift FOREIGN KEY (tenant_id, shift_id) REFERENCES shf_shift (tenant_id, shift_id),
    CONSTRAINT ck_shf_approval_status CHECK (status = 'APPROVED'),
    CONSTRAINT ck_shf_approval_actual CHECK (actual_cash_minor >= 0),
    CONSTRAINT ck_shf_approval_version CHECK (expected_shift_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_sales_order (
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    local_order_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cashier_user_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    store_timezone VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    draft_disposition VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    payment_status VARCHAR(24) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    gross_amount_minor BIGINT NOT NULL,
    discount_amount_minor BIGINT NOT NULL DEFAULT 0,
    surcharge_amount_minor BIGINT NOT NULL DEFAULT 0,
    receivable_amount_minor BIGINT NOT NULL,
    received_amount_minor BIGINT NOT NULL DEFAULT 0,
    catalog_version BIGINT NOT NULL,
    price_version BIGINT NOT NULL,
    industry_template_version VARCHAR(32) NOT NULL,
    snapshot_schema_version INT NOT NULL,
    snapshot_json JSON NOT NULL,
    snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    record_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_ord_tenant_id (tenant_id, order_id),
    UNIQUE KEY uk_ord_local_no (tenant_id, terminal_id, local_order_no),
    UNIQUE KEY uk_ord_idempotency (tenant_id, idempotency_key),
    KEY idx_ord_store_business (tenant_id, store_id, business_date, status),
    CONSTRAINT fk_ord_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_ord_shift FOREIGN KEY (tenant_id, shift_id) REFERENCES shf_shift (tenant_id, shift_id),
    CONSTRAINT ck_ord_status CHECK (status IN ('DRAFT','PENDING_PAYMENT','CONFIRMED','COMPLETED','CANCELLED','CLOSED')),
    CONSTRAINT ck_ord_draft_disposition CHECK (draft_disposition IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT ck_ord_payment_status CHECK (payment_status IN ('UNPAID','PAID')),
    CONSTRAINT ck_ord_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_ord_amounts CHECK (
      gross_amount_minor >= 0 AND discount_amount_minor = 0 AND surcharge_amount_minor = 0 AND
      receivable_amount_minor = gross_amount_minor AND received_amount_minor >= 0
    ),
    CONSTRAINT ck_ord_hash CHECK (snapshot_sha256 REGEXP '^[a-f0-9]{64}$' AND request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ord_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_order_line (
    line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    line_no INT NOT NULL,
    sku_id BIGINT NOT NULL,
    sku_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    barcode_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    product_name_snapshot VARCHAR(200) NOT NULL,
    unit_id BIGINT NOT NULL,
    unit_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity DECIMAL(19,6) NOT NULL,
    unit_price_minor BIGINT NOT NULL,
    gross_amount_minor BIGINT NOT NULL,
    discount_amount_minor BIGINT NOT NULL DEFAULT 0,
    surcharge_amount_minor BIGINT NOT NULL DEFAULT 0,
    payable_amount_minor BIGINT NOT NULL,
    price_source VARCHAR(24) NOT NULL,
    PRIMARY KEY (line_id),
    UNIQUE KEY uk_ord_line_tenant_id (tenant_id, line_id),
    UNIQUE KEY uk_ord_line_no (tenant_id, order_id, line_no),
    KEY idx_ord_line_sku (tenant_id, sku_id, order_id),
    KEY idx_ord_line_unit (tenant_id, unit_id, order_id),
    CONSTRAINT fk_ord_line_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT fk_ord_line_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_ord_line_unit FOREIGN KEY (tenant_id, unit_id) REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_ord_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_ord_line_amount CHECK (unit_price_minor >= 0 AND gross_amount_minor >= 0 AND discount_amount_minor = 0 AND surcharge_amount_minor = 0 AND payable_amount_minor = gross_amount_minor),
    CONSTRAINT ck_ord_line_price_source CHECK (price_source IN ('TENANT_BASE','STORE_OVERRIDE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_state_history (
    history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (history_id),
    UNIQUE KEY uk_ord_history_tenant_id (tenant_id, history_id),
    UNIQUE KEY uk_ord_history_command_state (tenant_id, order_id, command_id, to_status),
    CONSTRAINT fk_ord_history_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_cash_payment (
    cash_payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receivable_amount_minor BIGINT NOT NULL,
    tendered_amount_minor BIGINT NOT NULL,
    change_amount_minor BIGINT NOT NULL,
    net_amount_minor BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (cash_payment_id),
    UNIQUE KEY uk_cash_payment_tenant_id (tenant_id, cash_payment_id),
    UNIQUE KEY uk_cash_payment_order (tenant_id, order_id),
    CONSTRAINT fk_cash_payment_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT fk_cash_payment_shift FOREIGN KEY (tenant_id, shift_id) REFERENCES shf_shift (tenant_id, shift_id),
    CONSTRAINT ck_cash_payment_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT ck_cash_payment_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_cash_payment_amount CHECK (tendered_amount_minor >= receivable_amount_minor AND change_amount_minor = tendered_amount_minor - receivable_amount_minor AND net_amount_minor = receivable_amount_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shf_cash_ledger (
    cash_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    shift_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cash_payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    movement_type VARCHAR(24) NOT NULL,
    signed_amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (cash_ledger_id),
    UNIQUE KEY uk_cash_ledger_tenant_id (tenant_id, cash_ledger_id),
    UNIQUE KEY uk_cash_ledger_payment (tenant_id, cash_payment_id, movement_type),
    KEY idx_cash_ledger_shift (tenant_id, shift_id, occurred_at),
    CONSTRAINT fk_cash_ledger_shift FOREIGN KEY (tenant_id, shift_id) REFERENCES shf_shift (tenant_id, shift_id),
    CONSTRAINT fk_cash_ledger_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT fk_cash_ledger_payment FOREIGN KEY (tenant_id, cash_payment_id) REFERENCES ord_cash_payment (tenant_id, cash_payment_id),
    CONSTRAINT ck_cash_ledger_type CHECK (movement_type IN ('SALE_RECEIPT')),
    CONSTRAINT ck_cash_ledger_amount CHECK (signed_amount_minor >= 0),
    CONSTRAINT ck_cash_ledger_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_print_job (
    print_job_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    template_version VARCHAR(32) NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (print_job_id),
    UNIQUE KEY uk_print_job_tenant_id (tenant_id, print_job_id),
    UNIQUE KEY uk_print_job_order (tenant_id, order_id),
    CONSTRAINT fk_print_job_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT ck_print_job_state CHECK (status IN ('PENDING','PRINTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_event_outbox (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stream_code VARCHAR(64) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_version BIGINT NOT NULL,
    correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    available_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_ord_outbox_tenant_id (tenant_id, event_id),
    UNIQUE KEY uk_ord_outbox_aggregate_version (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type),
    KEY idx_ord_outbox_delivery (tenant_id, delivery_state, available_at),
    CONSTRAINT ck_ord_outbox_state CHECK (delivery_state IN ('PENDING','SENDING','RETRY','ACKED','FINAL_REJECTED')),
    CONSTRAINT ck_ord_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_idempotency (
    idempotency_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_code VARCHAR(32) NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (idempotency_id),
    UNIQUE KEY uk_ord_idem_tenant_id (tenant_id, idempotency_id),
    UNIQUE KEY uk_ord_idem_key (tenant_id, command_type, idempotency_key),
    UNIQUE KEY uk_ord_idem_command (tenant_id, command_id),
    CONSTRAINT ck_ord_idem_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ord_audit_event (
    audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id BIGINT NOT NULL,
    approver_user_id BIGINT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    before_status VARCHAR(24) NULL,
    after_status VARCHAR(24) NOT NULL,
    amount_minor BIGINT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (audit_id),
    UNIQUE KEY uk_ord_audit_tenant_id (tenant_id, audit_id),
    KEY idx_ord_audit_aggregate (tenant_id, aggregate_type, aggregate_id, occurred_at),
    CONSTRAINT ck_ord_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_order_no_update BEFORE UPDATE ON ord_sales_order FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'submitted order snapshot is immutable'; END$$
CREATE TRIGGER trg_order_no_delete BEFORE DELETE ON ord_sales_order FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'orders cannot be deleted'; END$$
CREATE TRIGGER trg_ord_line_no_update BEFORE UPDATE ON ord_order_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'submitted order lines are immutable'; END$$
CREATE TRIGGER trg_ord_line_no_delete BEFORE DELETE ON ord_order_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'order lines cannot be deleted'; END$$
CREATE TRIGGER trg_cash_payment_no_update BEFORE UPDATE ON ord_cash_payment FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash payment is immutable'; END$$
CREATE TRIGGER trg_cash_payment_no_delete BEFORE DELETE ON ord_cash_payment FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash payment cannot be deleted'; END$$
CREATE TRIGGER trg_cash_ledger_no_update BEFORE UPDATE ON shf_cash_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash ledger is append-only'; END$$
CREATE TRIGGER trg_cash_ledger_no_delete BEFORE DELETE ON shf_cash_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash ledger is append-only'; END$$
CREATE TRIGGER trg_ord_history_no_update BEFORE UPDATE ON ord_state_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'order history is append-only'; END$$
CREATE TRIGGER trg_ord_history_no_delete BEFORE DELETE ON ord_state_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'order history is append-only'; END$$
CREATE TRIGGER trg_ord_audit_no_update BEFORE UPDATE ON ord_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'order audit is append-only'; END$$
CREATE TRIGGER trg_ord_audit_no_delete BEFORE DELETE ON ord_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'order audit is append-only'; END$$
CREATE TRIGGER trg_shf_approval_no_update BEFORE UPDATE ON shf_shift_approval FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'shift approval is immutable'; END$$
CREATE TRIGGER trg_shf_approval_no_delete BEFORE DELETE ON shf_shift_approval FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'shift approval is immutable'; END$$
DELIMITER ;
