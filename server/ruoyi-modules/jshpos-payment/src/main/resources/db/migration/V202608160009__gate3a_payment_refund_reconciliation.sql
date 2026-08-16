CREATE TABLE pay_payment_intent (
    payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    succeeded_refund_minor BIGINT NOT NULL DEFAULT 0,
    record_version BIGINT NOT NULL DEFAULT 1,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (payment_id),
    UNIQUE KEY uk_pay_intent_tenant_id (tenant_id, payment_id),
    UNIQUE KEY uk_pay_intent_order (tenant_id, order_id),
    KEY idx_pay_intent_store_status (tenant_id, store_id, status, occurred_at),
    CONSTRAINT fk_pay_intent_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT fk_pay_intent_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_pay_intent_status CHECK (status IN ('CREATED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED','CLOSED','PARTIALLY_REFUNDED','REFUNDED')),
    CONSTRAINT ck_pay_intent_amount CHECK (amount_minor > 0 AND succeeded_refund_minor >= 0 AND succeeded_refund_minor <= amount_minor),
    CONSTRAINT ck_pay_intent_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT ck_pay_intent_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_payment_attempt (
    attempt_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_request_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_transaction_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_version BIGINT NOT NULL DEFAULT 1,
    occurred_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_pay_attempt_tenant_id (tenant_id, attempt_id),
    UNIQUE KEY uk_pay_provider_request (tenant_id, provider_code, provider_request_no),
    UNIQUE KEY uk_pay_provider_transaction (tenant_id, provider_code, provider_transaction_no),
    KEY idx_pay_attempt_payment (tenant_id, payment_id, occurred_at),
    CONSTRAINT fk_pay_attempt_payment FOREIGN KEY (tenant_id, payment_id) REFERENCES pay_payment_intent (tenant_id, payment_id),
    CONSTRAINT ck_pay_attempt_status CHECK (status IN ('CREATED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED','CLOSED')),
    CONSTRAINT ck_pay_attempt_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_pay_attempt_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT ck_pay_attempt_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_refund (
    refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    payment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    requester_user_id BIGINT NOT NULL,
    approver_user_id BIGINT NULL,
    provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_request_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_refund_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    record_version BIGINT NOT NULL DEFAULT 1,
    occurred_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (refund_id),
    UNIQUE KEY uk_pay_refund_tenant_id (tenant_id, refund_id),
    UNIQUE KEY uk_pay_refund_request (tenant_id, provider_code, provider_request_no),
    UNIQUE KEY uk_pay_refund_provider_no (tenant_id, provider_code, provider_refund_no),
    KEY idx_pay_refund_payment (tenant_id, payment_id, status, occurred_at),
    CONSTRAINT fk_pay_refund_payment FOREIGN KEY (tenant_id, payment_id) REFERENCES pay_payment_intent (tenant_id, payment_id),
    CONSTRAINT fk_pay_refund_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT fk_pay_refund_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_pay_refund_status CHECK (status IN ('CREATED','PENDING_APPROVAL','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED','CLOSED')),
    CONSTRAINT ck_pay_refund_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_pay_refund_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT ck_pay_refund_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_refund_line (
    refund_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity DECIMAL(19,6) NOT NULL,
    amount_minor BIGINT NOT NULL,
    PRIMARY KEY (refund_line_id),
    UNIQUE KEY uk_pay_refund_line_tenant_id (tenant_id, refund_line_id),
    UNIQUE KEY uk_pay_refund_line_order_line (tenant_id, refund_id, order_line_id),
    KEY idx_pay_refund_line_reserved (tenant_id, order_line_id, refund_id),
    CONSTRAINT fk_pay_refund_line_refund FOREIGN KEY (tenant_id, refund_id) REFERENCES pay_refund (tenant_id, refund_id),
    CONSTRAINT fk_pay_refund_line_order_line FOREIGN KEY (tenant_id, order_line_id) REFERENCES ord_order_line (tenant_id, line_id),
    CONSTRAINT ck_pay_refund_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_pay_refund_line_amount CHECK (amount_minor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_provider_observation (
    observation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source VARCHAR(24) NOT NULL,
    observed_status VARCHAR(16) NOT NULL,
    provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_request_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_transaction_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merge_result VARCHAR(16) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (observation_id),
    UNIQUE KEY uk_pay_observation_tenant_id (tenant_id, observation_id),
    KEY idx_pay_observation_aggregate (tenant_id, aggregate_type, aggregate_id, observed_at),
    KEY idx_pay_observation_provider_ref (tenant_id, provider_code, provider_transaction_no),
    CONSTRAINT fk_pay_observation_attempt FOREIGN KEY (tenant_id, attempt_id) REFERENCES pay_payment_attempt (tenant_id, attempt_id),
    CONSTRAINT ck_pay_observation_aggregate CHECK (aggregate_type IN ('PAYMENT','REFUND')),
    CONSTRAINT ck_pay_observation_source CHECK (source IN ('SYNC_RESPONSE','QUERY','CALLBACK','STATEMENT')),
    CONSTRAINT ck_pay_observation_status CHECK (observed_status IN ('PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED','CLOSED')),
    CONSTRAINT ck_pay_observation_result CHECK (merge_result IN ('APPLIED','IGNORED','CONFLICT')),
    CONSTRAINT ck_pay_observation_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_pay_observation_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_observation_dead_letter (
    dead_letter_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    observation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conflict_type VARCHAR(32) NOT NULL,
    existing_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    received_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_text VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    resolved_at DATETIME(3) NULL,
    PRIMARY KEY (dead_letter_id),
    UNIQUE KEY uk_pay_dead_letter_tenant_id (tenant_id, dead_letter_id),
    UNIQUE KEY uk_pay_dead_letter_observation_hash (tenant_id, observation_id, received_sha256),
    KEY idx_pay_dead_letter_open (tenant_id, resolved_at, created_at),
    CONSTRAINT ck_pay_dead_letter_hash CHECK (received_sha256 REGEXP '^[a-f0-9]{64}$' AND (existing_sha256 IS NULL OR existing_sha256 REGEXP '^[a-f0-9]{64}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_state_history (
    history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (history_id),
    UNIQUE KEY uk_pay_history_tenant_id (tenant_id, history_id),
    UNIQUE KEY uk_pay_history_command_state (tenant_id, aggregate_type, aggregate_id, command_id, to_status),
    KEY idx_pay_history_aggregate (tenant_id, aggregate_type, aggregate_id, occurred_at),
    CONSTRAINT ck_pay_history_type CHECK (aggregate_type IN ('PAYMENT','ATTEMPT','REFUND','RECONCILIATION')),
    CONSTRAINT ck_pay_history_version CHECK (aggregate_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_idempotency (
    idempotency_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (idempotency_id),
    UNIQUE KEY uk_pay_idempotency_tenant_id (tenant_id, idempotency_id),
    UNIQUE KEY uk_pay_idempotency_key (tenant_id, command_type, idempotency_key),
    UNIQUE KEY uk_pay_idempotency_command (tenant_id, command_id),
    CONSTRAINT ck_pay_idempotency_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_reconciliation_run (
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    statement_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    entry_count INT NOT NULL,
    case_count INT NOT NULL DEFAULT 0,
    actor_user_id BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_pay_rec_run_tenant_id (tenant_id, run_id),
    UNIQUE KEY uk_pay_rec_run_provider_date (tenant_id, provider_code, statement_date),
    CONSTRAINT ck_pay_rec_run_status CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_pay_rec_run_counts CHECK (entry_count >= 0 AND entry_count <= 10000 AND case_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_statement_entry (
    entry_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_transaction_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (entry_id),
    UNIQUE KEY uk_pay_statement_tenant_id (tenant_id, entry_id),
    KEY idx_pay_statement_reference (tenant_id, run_id, provider_transaction_no, business_type),
    CONSTRAINT fk_pay_statement_run FOREIGN KEY (tenant_id, run_id) REFERENCES pay_reconciliation_run (tenant_id, run_id),
    CONSTRAINT ck_pay_statement_type CHECK (business_type IN ('PAYMENT','REFUND')),
    CONSTRAINT ck_pay_statement_status CHECK (status IN ('SUCCEEDED','FAILED','UNKNOWN')),
    CONSTRAINT ck_pay_statement_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_pay_statement_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_reconciliation_case (
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    difference_type VARCHAR(32) NOT NULL,
    internal_reference VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_reference VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(24) NOT NULL,
    resolver_user_id BIGINT NULL,
    approver_user_id BIGINT NULL,
    resolution_code VARCHAR(32) NULL,
    resolution_text VARCHAR(512) NULL,
    record_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (case_id),
    UNIQUE KEY uk_pay_rec_case_tenant_id (tenant_id, case_id),
    UNIQUE KEY uk_pay_rec_case_natural (tenant_id, run_id, difference_type, internal_reference, provider_reference),
    KEY idx_pay_rec_case_status (tenant_id, status, created_at),
    CONSTRAINT fk_pay_rec_case_run FOREIGN KEY (tenant_id, run_id) REFERENCES pay_reconciliation_run (tenant_id, run_id),
    CONSTRAINT ck_pay_rec_case_type CHECK (difference_type IN ('INTERNAL_ONLY','PROVIDER_ONLY','AMOUNT_MISMATCH','CURRENCY_MISMATCH','STATUS_MISMATCH','DUPLICATE_PROVIDER_REF','REFUND_MISMATCH')),
    CONSTRAINT ck_pay_rec_case_status CHECK (status IN ('OPEN','INVESTIGATING','WAITING_PROVIDER','RESOLVED','APPROVED','CLOSED')),
    CONSTRAINT ck_pay_rec_case_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_audit_event (
    audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NULL,
    action_code VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(16) NOT NULL,
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
    UNIQUE KEY uk_pay_audit_tenant_id (tenant_id, audit_id),
    KEY idx_pay_audit_aggregate (tenant_id, aggregate_type, aggregate_id, occurred_at),
    CONSTRAINT ck_pay_audit_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pay_event_outbox (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stream_code VARCHAR(64) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_version BIGINT NOT NULL,
    correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    available_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_pay_outbox_tenant_id (tenant_id, event_id),
    UNIQUE KEY uk_pay_outbox_aggregate_version (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type),
    KEY idx_pay_outbox_delivery (tenant_id, delivery_state, available_at),
    CONSTRAINT ck_pay_outbox_state CHECK (delivery_state IN ('PENDING','SENDING','RETRY','ACKED','FINAL_REJECTED')),
    CONSTRAINT ck_pay_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_pay_observation_no_update BEFORE UPDATE ON pay_provider_observation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider observation is immutable'; END$$
CREATE TRIGGER trg_pay_observation_no_delete BEFORE DELETE ON pay_provider_observation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider observation is immutable'; END$$
CREATE TRIGGER trg_pay_dead_letter_no_update BEFORE UPDATE ON pay_observation_dead_letter FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'observation dead letter is append-only'; END$$
CREATE TRIGGER trg_pay_dead_letter_no_delete BEFORE DELETE ON pay_observation_dead_letter FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'observation dead letter is append-only'; END$$
CREATE TRIGGER trg_pay_refund_line_no_update BEFORE UPDATE ON pay_refund_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refund line is immutable'; END$$
CREATE TRIGGER trg_pay_refund_line_no_delete BEFORE DELETE ON pay_refund_line FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refund line is immutable'; END$$
CREATE TRIGGER trg_pay_statement_no_update BEFORE UPDATE ON pay_statement_entry FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'statement entry is immutable'; END$$
CREATE TRIGGER trg_pay_statement_no_delete BEFORE DELETE ON pay_statement_entry FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'statement entry is immutable'; END$$
CREATE TRIGGER trg_pay_history_no_update BEFORE UPDATE ON pay_state_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment state history is append-only'; END$$
CREATE TRIGGER trg_pay_history_no_delete BEFORE DELETE ON pay_state_history FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment state history is append-only'; END$$
CREATE TRIGGER trg_pay_idempotency_no_update BEFORE UPDATE ON pay_idempotency FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'idempotency result is immutable'; END$$
CREATE TRIGGER trg_pay_idempotency_no_delete BEFORE DELETE ON pay_idempotency FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'idempotency result is immutable'; END$$
CREATE TRIGGER trg_pay_audit_no_update BEFORE UPDATE ON pay_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment audit is append-only'; END$$
CREATE TRIGGER trg_pay_audit_no_delete BEFORE DELETE ON pay_audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment audit is append-only'; END$$
DELIMITER ;
