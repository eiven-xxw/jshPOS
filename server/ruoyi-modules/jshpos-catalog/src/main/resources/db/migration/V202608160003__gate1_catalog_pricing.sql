CREATE TABLE cat_category (
    category_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    parent_id BIGINT NULL,
    category_code VARCHAR(64) NOT NULL,
    category_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sort_no INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (category_id),
    UNIQUE KEY uk_cat_category_tenant_id (tenant_id, category_id),
    UNIQUE KEY uk_cat_category_code (tenant_id, category_code),
    CONSTRAINT fk_cat_category_parent FOREIGN KEY (tenant_id, parent_id) REFERENCES cat_category (tenant_id, category_id),
    CONSTRAINT ck_cat_category_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_cat_category_not_self CHECK (parent_id IS NULL OR parent_id <> category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_brand (
    brand_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    brand_code VARCHAR(64) NOT NULL,
    brand_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (brand_id),
    UNIQUE KEY uk_cat_brand_tenant_id (tenant_id, brand_id),
    UNIQUE KEY uk_cat_brand_code (tenant_id, brand_code),
    CONSTRAINT ck_cat_brand_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_unit (
    unit_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    unit_code VARCHAR(64) NOT NULL,
    unit_name VARCHAR(100) NOT NULL,
    decimal_scale SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (unit_id),
    UNIQUE KEY uk_cat_unit_tenant_id (tenant_id, unit_id),
    UNIQUE KEY uk_cat_unit_code (tenant_id, unit_code),
    CONSTRAINT ck_cat_unit_scale CHECK (decimal_scale BETWEEN 0 AND 6),
    CONSTRAINT ck_cat_unit_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_spu (
    spu_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    spu_code VARCHAR(64) NOT NULL,
    spu_name VARCHAR(200) NOT NULL,
    category_id BIGINT NOT NULL,
    brand_id BIGINT NULL,
    attributes_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (spu_id),
    UNIQUE KEY uk_cat_spu_tenant_id (tenant_id, spu_id),
    UNIQUE KEY uk_cat_spu_code (tenant_id, spu_code),
    KEY idx_cat_spu_category (tenant_id, category_id, status),
    CONSTRAINT fk_cat_spu_category FOREIGN KEY (tenant_id, category_id) REFERENCES cat_category (tenant_id, category_id),
    CONSTRAINT fk_cat_spu_brand FOREIGN KEY (tenant_id, brand_id) REFERENCES cat_brand (tenant_id, brand_id),
    CONSTRAINT ck_cat_spu_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_sku (
    sku_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    spu_id BIGINT NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(200) NOT NULL,
    product_type VARCHAR(16) NOT NULL,
    attributes_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sku_id),
    UNIQUE KEY uk_cat_sku_tenant_id (tenant_id, sku_id),
    UNIQUE KEY uk_cat_sku_code (tenant_id, sku_code),
    KEY idx_cat_sku_status (tenant_id, status, sku_code),
    CONSTRAINT fk_cat_sku_spu FOREIGN KEY (tenant_id, spu_id) REFERENCES cat_spu (tenant_id, spu_id),
    CONSTRAINT ck_cat_sku_type CHECK (product_type IN ('STANDARD', 'WEIGHT', 'COUNT')),
    CONSTRAINT ck_cat_sku_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_sku_unit (
    sku_unit_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    sku_id BIGINT NOT NULL,
    unit_id BIGINT NOT NULL,
    ratio_numerator BIGINT NOT NULL,
    ratio_denominator BIGINT NOT NULL,
    primary_unit BOOLEAN NOT NULL DEFAULT FALSE,
    primary_slot BIGINT GENERATED ALWAYS AS (IF(primary_unit, sku_id, NULL)) STORED,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sku_unit_id),
    UNIQUE KEY uk_cat_sku_unit_tenant_id (tenant_id, sku_unit_id),
    UNIQUE KEY uk_cat_sku_unit (tenant_id, sku_id, unit_id),
    UNIQUE KEY uk_cat_sku_primary (tenant_id, primary_slot),
    CONSTRAINT fk_cat_sku_unit_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_cat_sku_unit_unit FOREIGN KEY (tenant_id, unit_id) REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_cat_sku_unit_ratio CHECK (ratio_numerator > 0 AND ratio_denominator > 0),
    CONSTRAINT ck_cat_primary_ratio CHECK (NOT primary_unit OR (ratio_numerator = 1 AND ratio_denominator = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_barcode (
    barcode_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    sku_id BIGINT NOT NULL,
    sku_unit_id BIGINT NOT NULL,
    barcode_value VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    barcode_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (barcode_id),
    UNIQUE KEY uk_cat_barcode_tenant_id (tenant_id, barcode_id),
    UNIQUE KEY uk_cat_barcode_value (tenant_id, barcode_value),
    KEY idx_cat_barcode_sku (tenant_id, sku_id, status),
    CONSTRAINT fk_cat_barcode_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_cat_barcode_unit FOREIGN KEY (tenant_id, sku_unit_id) REFERENCES cat_sku_unit (tenant_id, sku_unit_id),
    CONSTRAINT ck_cat_barcode_type CHECK (barcode_type IN ('STANDARD', 'WEIGHT', 'INTERNAL')),
    CONSTRAINT ck_cat_barcode_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_import_batch (
    import_batch_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    row_count INT NOT NULL,
    error_count INT NOT NULL,
    state VARCHAR(20) NOT NULL,
    previous_batch_id BIGINT NULL,
    published_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (import_batch_id),
    UNIQUE KEY uk_cat_import_tenant_id (tenant_id, import_batch_id),
    UNIQUE KEY uk_cat_import_idempotency (tenant_id, idempotency_key),
    CONSTRAINT fk_cat_import_previous FOREIGN KEY (tenant_id, previous_batch_id) REFERENCES cat_import_batch (tenant_id, import_batch_id),
    CONSTRAINT ck_cat_import_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cat_import_counts CHECK (row_count BETWEEN 0 AND 100000 AND error_count BETWEEN 0 AND 10000),
    CONSTRAINT ck_cat_import_state CHECK (state IN ('PRECHECKED', 'REJECTED', 'PUBLISHED', 'ROLLED_BACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_import_record (
    import_record_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    import_batch_id BIGINT NOT NULL,
    source_row_no INT NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    canonical_json JSON NOT NULL,
    record_sha256 CHAR(64) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (import_record_id),
    UNIQUE KEY uk_cat_import_record_id (tenant_id, import_record_id),
    UNIQUE KEY uk_cat_import_record_row (tenant_id, import_batch_id, source_row_no),
    UNIQUE KEY uk_cat_import_record_sku (tenant_id, import_batch_id, sku_code),
    CONSTRAINT fk_cat_import_record_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES cat_import_batch (tenant_id, import_batch_id),
    CONSTRAINT ck_cat_import_record_hash CHECK (record_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_import_error (
    import_error_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    import_batch_id BIGINT NOT NULL,
    source_row_no INT NOT NULL,
    field_code VARCHAR(32) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (import_error_id),
    UNIQUE KEY uk_cat_import_error_id (tenant_id, import_error_id),
    KEY idx_cat_import_error_batch (tenant_id, import_batch_id, source_row_no),
    CONSTRAINT fk_cat_import_error_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES cat_import_batch (tenant_id, import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_catalog_binding (
    catalog_binding_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    current_batch_id BIGINT NOT NULL,
    previous_batch_id BIGINT NULL,
    activated_at DATETIME(6) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (catalog_binding_id),
    UNIQUE KEY uk_cat_binding_tenant_id (tenant_id, catalog_binding_id),
    UNIQUE KEY uk_cat_binding_tenant (tenant_id),
    CONSTRAINT fk_cat_binding_current FOREIGN KEY (tenant_id, current_batch_id) REFERENCES cat_import_batch (tenant_id, import_batch_id),
    CONSTRAINT fk_cat_binding_previous FOREIGN KEY (tenant_id, previous_batch_id) REFERENCES cat_import_batch (tenant_id, import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE prc_price_book (
    price_book_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    book_code VARCHAR(64) NOT NULL,
    book_name VARCHAR(200) NOT NULL,
    version_no INT NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    store_id BIGINT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    content_sha256 CHAR(64) NULL,
    published_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL, update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (price_book_id),
    UNIQUE KEY uk_prc_book_tenant_id (tenant_id, price_book_id),
    UNIQUE KEY uk_prc_book_version (tenant_id, book_code, version_no),
    KEY idx_prc_book_scope (tenant_id, scope_type, store_id, state),
    CONSTRAINT fk_prc_book_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_prc_book_scope CHECK ((scope_type = 'TENANT_BASE' AND store_id IS NULL) OR (scope_type = 'STORE' AND store_id IS NOT NULL)),
    CONSTRAINT ck_prc_book_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_prc_book_version CHECK (version_no > 0),
    CONSTRAINT ck_prc_book_hash CHECK (content_sha256 IS NULL OR content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE prc_price_item (
    price_item_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    price_book_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    unit_id BIGINT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (price_item_id),
    UNIQUE KEY uk_prc_item_tenant_id (tenant_id, price_item_id),
    UNIQUE KEY uk_prc_item_key (tenant_id, price_book_id, sku_id, unit_id, effective_from),
    KEY idx_prc_item_resolve (tenant_id, sku_id, unit_id, effective_from, effective_to),
    CONSTRAINT fk_prc_item_book FOREIGN KEY (tenant_id, price_book_id) REFERENCES prc_price_book (tenant_id, price_book_id),
    CONSTRAINT fk_prc_item_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_prc_item_unit FOREIGN KEY (tenant_id, unit_id) REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_prc_item_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_prc_item_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_prc_item_window CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dpk_catalog_package (
    package_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    store_id BIGINT NOT NULL,
    package_version BIGINT NOT NULL,
    previous_version BIGINT NOT NULL DEFAULT 0,
    schema_version VARCHAR(16) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    signature_algorithm VARCHAR(16) NOT NULL,
    signing_key_id VARCHAR(128) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    record_count INT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    generated_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (package_id),
    UNIQUE KEY uk_dpk_package_tenant_id (tenant_id, package_id),
    UNIQUE KEY uk_dpk_package_version (tenant_id, store_id, package_version),
    CONSTRAINT fk_dpk_package_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_dpk_package_versions CHECK (package_version > 0 AND previous_version >= 0 AND previous_version < package_version),
    CONSTRAINT ck_dpk_package_schema CHECK (schema_version IN ('1.0', '0.9')),
    CONSTRAINT ck_dpk_package_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dpk_package_signature CHECK (signature_algorithm = 'Ed25519'),
    CONSTRAINT ck_dpk_package_state CHECK (state IN ('AVAILABLE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cat_event_outbox (
    outbox_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'NEW',
    available_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uk_cat_outbox_tenant_id (tenant_id, outbox_id),
    KEY idx_cat_outbox_delivery (tenant_id, delivery_state, available_at, outbox_id),
    CONSTRAINT ck_cat_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_cat_outbox_hash CHECK (payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cat_outbox_state CHECK (delivery_state IN ('NEW', 'DELIVERING', 'DELIVERED', 'DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_prc_published_book_immutable
BEFORE UPDATE ON prc_price_book
FOR EACH ROW
BEGIN
    IF OLD.state = 'PUBLISHED' AND (
        NEW.tenant_id <> OLD.tenant_id OR NEW.book_code <> OLD.book_code OR
        NEW.version_no <> OLD.version_no OR NEW.scope_type <> OLD.scope_type OR
        NOT (NEW.store_id <=> OLD.store_id) OR NEW.content_sha256 <> OLD.content_sha256
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'published price book is immutable';
    END IF;
END$$

CREATE TRIGGER trg_cat_published_import_immutable
BEFORE UPDATE ON cat_import_batch
FOR EACH ROW
BEGIN
    IF OLD.state = 'PUBLISHED' AND (
        NEW.tenant_id <> OLD.tenant_id OR NEW.idempotency_key <> OLD.idempotency_key OR
        NEW.payload_sha256 <> OLD.payload_sha256 OR NEW.row_count <> OLD.row_count OR
        NEW.error_count <> OLD.error_count
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'published catalog import is immutable';
    END IF;
END$$
DELIMITER ;
