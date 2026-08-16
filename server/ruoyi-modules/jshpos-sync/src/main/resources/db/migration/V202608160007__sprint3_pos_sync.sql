-- Sprint S3 formal synchronization tables. T1 syn_* probe tables are intentionally not reused.
CREATE TABLE pos_sync_device (
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bound_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    min_protocol_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    max_protocol_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    blocked_reason VARCHAR(64) NULL,
    last_seen_at DATETIME(3) NULL,
    record_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id),
    UNIQUE KEY uk_pos_sync_device_tenant_id (tenant_id, device_id),
    UNIQUE KEY uk_pos_sync_device_terminal (tenant_id, store_id, terminal_id),
    KEY idx_pos_sync_device_user (tenant_id, bound_user_id, status),
    CONSTRAINT fk_pos_sync_device_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_pos_sync_device_status CHECK (status IN ('ACTIVE','BLOCKED','REVOKED')),
    CONSTRAINT ck_pos_sync_device_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_inbox (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_sequence BIGINT NOT NULL,
    stream_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_version INT NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_version BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processing_status VARCHAR(24) NOT NULL,
    result_code VARCHAR(64) NULL,
    processing_attempts INT NOT NULL DEFAULT 0,
    received_at DATETIME(3) NOT NULL,
    processed_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_pos_sync_inbox_tenant_id (tenant_id, event_id),
    KEY idx_pos_sync_inbox_device_seq (tenant_id, device_id, device_sequence),
    KEY idx_pos_sync_inbox_status (tenant_id, processing_status, received_at),
    KEY idx_pos_sync_inbox_aggregate (tenant_id, aggregate_id, aggregate_version, event_type),
    CONSTRAINT fk_pos_sync_inbox_device FOREIGN KEY (tenant_id, device_id) REFERENCES pos_sync_device (tenant_id, device_id),
    CONSTRAINT ck_pos_sync_inbox_sequence CHECK (device_sequence > 0),
    CONSTRAINT ck_pos_sync_inbox_event_version CHECK (event_version > 0),
    CONSTRAINT ck_pos_sync_inbox_aggregate_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_pos_sync_inbox_attempts CHECK (processing_attempts >= 0),
    CONSTRAINT ck_pos_sync_inbox_status CHECK (processing_status IN ('RECEIVED','APPLIED','RETRY','CONFLICT','FINAL_REJECTED','DEAD_LETTER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_business_fact (
    fact_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stream_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_at DATETIME(3) NOT NULL,
    PRIMARY KEY (fact_id),
    UNIQUE KEY uk_pos_sync_fact_event (tenant_id, source_event_id),
    UNIQUE KEY uk_pos_sync_fact_effect (tenant_id, aggregate_id, aggregate_version, event_type),
    CONSTRAINT fk_pos_sync_fact_inbox FOREIGN KEY (tenant_id, source_event_id) REFERENCES pos_sync_inbox (tenant_id, event_id),
    CONSTRAINT ck_pos_sync_fact_version CHECK (aggregate_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_change_feed (
    change_sequence BIGINT NOT NULL AUTO_INCREMENT,
    change_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stream_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (change_sequence),
    UNIQUE KEY uk_pos_sync_change_id (tenant_id, change_id),
    UNIQUE KEY uk_pos_sync_change_effect (tenant_id, stream_code, aggregate_id, aggregate_version, event_type),
    KEY idx_pos_sync_change_pull (tenant_id, stream_code, change_sequence),
    CONSTRAINT ck_pos_sync_change_version CHECK (aggregate_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_pull_page (
    cursor_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stream_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_sequence BIGINT NOT NULL,
    to_sequence BIGINT NOT NULL,
    change_ids_json JSON NOT NULL,
    page_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OFFERED',
    offered_at DATETIME(3) NOT NULL,
    acked_at DATETIME(3) NULL,
    PRIMARY KEY (cursor_token),
    KEY idx_pos_sync_page_device (tenant_id, device_id, stream_code, offered_at),
    CONSTRAINT fk_pos_sync_page_device FOREIGN KEY (tenant_id, device_id) REFERENCES pos_sync_device (tenant_id, device_id),
    CONSTRAINT ck_pos_sync_page_range CHECK (from_sequence >= 0 AND to_sequence >= from_sequence),
    CONSTRAINT ck_pos_sync_page_status CHECK (status IN ('OFFERED','ACKED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_cursor (
    tenant_id VARCHAR(20) NOT NULL,
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stream_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    acked_sequence BIGINT NOT NULL DEFAULT 0,
    acked_cursor_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    page_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, device_id, stream_code),
    CONSTRAINT fk_pos_sync_cursor_device FOREIGN KEY (tenant_id, device_id) REFERENCES pos_sync_device (tenant_id, device_id),
    CONSTRAINT ck_pos_sync_cursor_sequence CHECK (acked_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_dead_letter (
    dead_letter_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    failure_summary VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    repair_attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    resolved_at DATETIME(3) NULL,
    PRIMARY KEY (dead_letter_id),
    UNIQUE KEY uk_pos_sync_dead_event (tenant_id, event_id),
    CONSTRAINT fk_pos_sync_dead_inbox FOREIGN KEY (tenant_id, event_id) REFERENCES pos_sync_inbox (tenant_id, event_id),
    CONSTRAINT ck_pos_sync_dead_status CHECK (status IN ('OPEN','RETRYING','RESOLVED')),
    CONSTRAINT ck_pos_sync_dead_attempts CHECK (repair_attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pos_sync_security_event (
    security_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action_code VARCHAR(64) NOT NULL,
    evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (security_event_id),
    KEY idx_pos_sync_security_device (tenant_id, device_id, occurred_at),
    CONSTRAINT fk_pos_sync_security_device FOREIGN KEY (tenant_id, device_id) REFERENCES pos_sync_device (tenant_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER pos_sync_fact_no_update BEFORE UPDATE ON pos_sync_business_fact
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sync business fact is append-only'; END$$
CREATE TRIGGER pos_sync_fact_no_delete BEFORE DELETE ON pos_sync_business_fact
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sync business fact is append-only'; END$$
CREATE TRIGGER pos_sync_change_no_update BEFORE UPDATE ON pos_sync_change_feed
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sync change feed is append-only'; END$$
CREATE TRIGGER pos_sync_change_no_delete BEFORE DELETE ON pos_sync_change_feed
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sync change feed is append-only'; END$$
CREATE TRIGGER pos_sync_security_no_update BEFORE UPDATE ON pos_sync_security_event
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sync security event is append-only'; END$$
CREATE TRIGGER pos_sync_security_no_delete BEFORE DELETE ON pos_sync_security_event
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sync security event is append-only'; END$$
DELIMITER ;
