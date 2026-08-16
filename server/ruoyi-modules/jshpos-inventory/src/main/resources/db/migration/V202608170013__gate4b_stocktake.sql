ALTER TABLE inv_stock_command DROP CHECK ck_inv_command_source;
ALTER TABLE inv_stock_command ADD CONSTRAINT ck_inv_command_source
    CHECK (source_type IN ('ORDER','REFUND','STOCKTAKE','PURCHASE_RECEIPT','PURCHASE_RETURN'));

ALTER TABLE inv_stock_ledger DROP CHECK ck_inv_ledger_movement;
ALTER TABLE inv_stock_ledger DROP CHECK ck_inv_ledger_source;
ALTER TABLE inv_stock_ledger DROP CHECK ck_inv_ledger_direction;
ALTER TABLE inv_stock_ledger ADD CONSTRAINT ck_inv_ledger_movement
    CHECK (movement_type IN ('SALE_OUT','SALE_RETURN_IN','STOCKTAKE_GAIN','STOCKTAKE_LOSS',
      'PURCHASE_RECEIPT_IN','PURCHASE_RETURN_OUT'));
ALTER TABLE inv_stock_ledger ADD CONSTRAINT ck_inv_ledger_source
    CHECK (source_type IN ('ORDER','REFUND','STOCKTAKE','PURCHASE_RECEIPT','PURCHASE_RETURN'));
ALTER TABLE inv_stock_ledger ADD CONSTRAINT ck_inv_ledger_direction CHECK (
    (movement_type IN ('SALE_OUT','STOCKTAKE_LOSS','PURCHASE_RETURN_OUT') AND quantity_delta < 0) OR
    (movement_type IN ('SALE_RETURN_IN','STOCKTAKE_GAIN','PURCHASE_RECEIPT_IN') AND quantity_delta > 0)
);

CREATE TABLE inv_stocktake (
    stocktake_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    blind_count TINYINT(1) NOT NULL,
    status VARCHAR(24) NOT NULL,
    recount_threshold DECIMAL(19,6) NOT NULL,
    adjustment_event_id CHAR(26) NULL,
    correlation_id VARCHAR(96) NOT NULL,
    creator_user_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NULL,
    approver_user_id BIGINT NULL,
    snapshot_at DATETIME(3) NOT NULL,
    cutoff_at DATETIME(3) NULL,
    posted_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, stocktake_id),
    KEY idx_inv_stocktake_status (tenant_id, store_id, status, updated_at),
    CONSTRAINT fk_inv_stocktake_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_inv_stocktake_mode CHECK (mode='DYNAMIC' AND scope_type='SELECTED'),
    CONSTRAINT ck_inv_stocktake_status CHECK (status IN
      ('COUNTING','RECOUNT_REQUIRED','PENDING_REVIEW','REVIEWED','POSTED','CANCELLED')),
    CONSTRAINT ck_inv_stocktake_threshold CHECK (recount_threshold >= 0),
    CONSTRAINT ck_inv_stocktake_shape CHECK (
      (status IN ('COUNTING','RECOUNT_REQUIRED') AND posted_at IS NULL) OR
      (status IN ('PENDING_REVIEW','REVIEWED') AND cutoff_at IS NOT NULL AND posted_at IS NULL) OR
      (status='POSTED' AND cutoff_at IS NOT NULL AND posted_at IS NOT NULL AND adjustment_event_id IS NOT NULL) OR
      (status='CANCELLED' AND posted_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stocktake_line (
    line_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stocktake_id CHAR(26) NOT NULL,
    stock_dimension_key CHAR(64) NOT NULL,
    warehouse_id CHAR(26) NOT NULL,
    sku_id BIGINT NOT NULL,
    base_unit_id BIGINT NOT NULL,
    snapshot_quantity DECIMAL(19,6) NOT NULL,
    snapshot_ledger_sequence BIGINT NOT NULL,
    counted_quantity DECIMAL(19,6) NULL,
    counted_at DATETIME(3) NULL,
    adjusted_book_quantity DECIMAL(19,6) NULL,
    cutoff_ledger_sequence BIGINT NULL,
    variance_quantity DECIMAL(19,6) NULL,
    count_revision INT NOT NULL DEFAULT 0,
    last_counter_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, line_id),
    UNIQUE KEY uk_inv_stocktake_sku (tenant_id, stocktake_id, sku_id),
    KEY idx_inv_stocktake_line_head (tenant_id, stocktake_id, line_id),
    CONSTRAINT fk_inv_stocktake_line_head FOREIGN KEY (tenant_id, stocktake_id)
        REFERENCES inv_stocktake (tenant_id, stocktake_id),
    CONSTRAINT fk_inv_stocktake_line_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_inv_stocktake_line_unit FOREIGN KEY (tenant_id, base_unit_id)
        REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_inv_stocktake_line_sequence CHECK (
      snapshot_ledger_sequence >= 0 AND (cutoff_ledger_sequence IS NULL OR cutoff_ledger_sequence >= snapshot_ledger_sequence)),
    CONSTRAINT ck_inv_stocktake_line_count CHECK (
      (count_revision=0 AND counted_quantity IS NULL AND last_counter_user_id IS NULL) OR
      (count_revision>0 AND counted_quantity >= 0 AND last_counter_user_id IS NOT NULL)),
    CONSTRAINT ck_inv_stocktake_line_cutoff CHECK (
      (adjusted_book_quantity IS NULL AND cutoff_ledger_sequence IS NULL AND variance_quantity IS NULL) OR
      (adjusted_book_quantity IS NOT NULL AND cutoff_ledger_sequence IS NOT NULL
        AND counted_quantity-adjusted_book_quantity=variance_quantity))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stocktake_count (
    count_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stocktake_id CHAR(26) NOT NULL,
    line_id CHAR(26) NOT NULL,
    revision_no INT NOT NULL,
    counted_quantity DECIMAL(19,6) NOT NULL,
    counter_user_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    reason VARCHAR(256) NULL,
    correlation_id VARCHAR(96) NOT NULL,
    counted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, count_id),
    UNIQUE KEY uk_inv_stocktake_count_revision (tenant_id, line_id, revision_no),
    KEY idx_inv_stocktake_count_head (tenant_id, stocktake_id, counted_at),
    CONSTRAINT fk_inv_stocktake_count_head FOREIGN KEY (tenant_id, stocktake_id)
        REFERENCES inv_stocktake (tenant_id, stocktake_id),
    CONSTRAINT fk_inv_stocktake_count_line FOREIGN KEY (tenant_id, line_id)
        REFERENCES inv_stocktake_line (tenant_id, line_id),
    CONSTRAINT ck_inv_stocktake_count CHECK (revision_no > 0 AND counted_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inv_stocktake_adjustment (
    adjustment_id CHAR(26) NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    stocktake_id CHAR(26) NOT NULL,
    line_id CHAR(26) NOT NULL,
    source_event_id CHAR(26) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity DECIMAL(19,6) NOT NULL,
    signed_variance DECIMAL(19,6) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, adjustment_id),
    UNIQUE KEY uk_inv_stocktake_adjustment_line (tenant_id, stocktake_id, line_id),
    CONSTRAINT fk_inv_stocktake_adjustment_head FOREIGN KEY (tenant_id, stocktake_id)
        REFERENCES inv_stocktake (tenant_id, stocktake_id),
    CONSTRAINT fk_inv_stocktake_adjustment_line FOREIGN KEY (tenant_id, line_id)
        REFERENCES inv_stocktake_line (tenant_id, line_id),
    CONSTRAINT fk_inv_stocktake_adjustment_command FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES inv_stock_command (tenant_id, source_event_id),
    CONSTRAINT ck_inv_stocktake_adjustment_type CHECK (movement_type IN ('STOCKTAKE_GAIN','STOCKTAKE_LOSS')),
    CONSTRAINT ck_inv_stocktake_adjustment_value CHECK (
      quantity > 0 AND ((movement_type='STOCKTAKE_GAIN' AND signed_variance=quantity)
        OR (movement_type='STOCKTAKE_LOSS' AND signed_variance=-quantity)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_inv_stocktake_count_no_update BEFORE UPDATE ON inv_stocktake_count FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stocktake_count is immutable'; END$$
CREATE TRIGGER trg_inv_stocktake_count_no_delete BEFORE DELETE ON inv_stocktake_count FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stocktake_count is immutable'; END$$
CREATE TRIGGER trg_inv_stocktake_adjustment_no_update BEFORE UPDATE ON inv_stocktake_adjustment FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stocktake_adjustment is immutable'; END$$
CREATE TRIGGER trg_inv_stocktake_adjustment_no_delete BEFORE DELETE ON inv_stocktake_adjustment FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stocktake_adjustment is immutable'; END$$
CREATE TRIGGER trg_inv_stocktake_posted_immutable BEFORE UPDATE ON inv_stocktake FOR EACH ROW
BEGIN
    IF OLD.status='POSTED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='posted inv_stocktake is immutable';
    END IF;
END$$
CREATE TRIGGER trg_inv_stocktake_no_delete BEFORE DELETE ON inv_stocktake FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='inv_stocktake cannot be deleted'; END$$
DELIMITER ;
