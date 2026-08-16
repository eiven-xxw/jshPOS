CREATE TABLE jsh_org_unit (
    org_unit_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    parent_id BIGINT NULL,
    unit_code VARCHAR(32) NOT NULL,
    unit_name VARCHAR(100) NOT NULL,
    unit_type VARCHAR(20) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tree_depth SMALLINT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (org_unit_id),
    UNIQUE KEY uk_jsh_org_tenant_id (tenant_id, org_unit_id),
    UNIQUE KEY uk_jsh_org_tenant_code (tenant_id, unit_code),
    KEY idx_jsh_org_tenant_parent (tenant_id, parent_id),
    CONSTRAINT fk_jsh_org_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES jsh_org_unit (tenant_id, org_unit_id),
    CONSTRAINT ck_jsh_org_type CHECK (unit_type IN ('HEADQUARTERS', 'REGION', 'COMPANY', 'OTHER')),
    CONSTRAINT ck_jsh_org_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_jsh_org_depth CHECK (tree_depth BETWEEN 1 AND 8),
    CONSTRAINT ck_jsh_org_not_self CHECK (parent_id IS NULL OR parent_id <> org_unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE jsh_store (
    store_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    org_unit_id BIGINT NOT NULL,
    platform_dept_id BIGINT NULL,
    store_code VARCHAR(32) NOT NULL,
    store_name VARCHAR(100) NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    business_day_start TIME NOT NULL DEFAULT '00:00:00',
    status VARCHAR(16) NOT NULL DEFAULT 'PREPARING',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (store_id),
    UNIQUE KEY uk_jsh_store_tenant_id (tenant_id, store_id),
    UNIQUE KEY uk_jsh_store_tenant_code (tenant_id, store_code),
    KEY idx_jsh_store_tenant_org (tenant_id, org_unit_id, status),
    CONSTRAINT fk_jsh_store_org FOREIGN KEY (tenant_id, org_unit_id)
        REFERENCES jsh_org_unit (tenant_id, org_unit_id),
    CONSTRAINT ck_jsh_store_status CHECK (status IN ('PREPARING', 'ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE jsh_staff_scope (
    staff_scope_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    user_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    org_unit_id BIGINT NULL,
    store_id BIGINT NULL,
    scope_key VARCHAR(64) GENERATED ALWAYS AS (
        CONCAT(scope_type, ':', IFNULL(org_unit_id, IFNULL(store_id, 0)))
    ) STORED,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (staff_scope_id),
    UNIQUE KEY uk_jsh_scope_tenant_id (tenant_id, staff_scope_id),
    UNIQUE KEY uk_jsh_scope_assignment (tenant_id, user_id, scope_key),
    KEY idx_jsh_scope_user_status (tenant_id, user_id, status),
    CONSTRAINT fk_jsh_scope_org FOREIGN KEY (tenant_id, org_unit_id)
        REFERENCES jsh_org_unit (tenant_id, org_unit_id),
    CONSTRAINT fk_jsh_scope_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_jsh_scope_type CHECK (scope_type IN ('TENANT', 'ORG_SUBTREE', 'STORE')),
    CONSTRAINT ck_jsh_scope_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_jsh_scope_shape CHECK (
        (scope_type = 'TENANT' AND org_unit_id IS NULL AND store_id IS NULL) OR
        (scope_type = 'ORG_SUBTREE' AND org_unit_id IS NOT NULL AND store_id IS NULL) OR
        (scope_type = 'STORE' AND org_unit_id IS NULL AND store_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE jsh_config_template (
    template_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    template_code VARCHAR(32) NOT NULL,
    template_name VARCHAR(100) NOT NULL,
    industry VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (template_id),
    UNIQUE KEY uk_jsh_template_tenant_id (tenant_id, template_id),
    UNIQUE KEY uk_jsh_template_code (tenant_id, template_code),
    CONSTRAINT ck_jsh_template_industry CHECK (industry IN ('CONVENIENCE', 'SNACK_DISCOUNT', 'COMMUNITY_SUPERMARKET')),
    CONSTRAINT ck_jsh_template_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE jsh_config_template_version (
    config_version_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    template_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    content_json JSON NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    published_by BIGINT NULL,
    published_at DATETIME(6) NULL,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (config_version_id),
    UNIQUE KEY uk_jsh_cfgver_tenant_id (tenant_id, config_version_id),
    UNIQUE KEY uk_jsh_cfgver_composite (tenant_id, template_id, config_version_id),
    UNIQUE KEY uk_jsh_cfgver_no (tenant_id, template_id, version_no),
    KEY idx_jsh_cfgver_state (tenant_id, template_id, state),
    CONSTRAINT fk_jsh_cfgver_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES jsh_config_template (tenant_id, template_id),
    CONSTRAINT ck_jsh_cfgver_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_jsh_cfgver_number CHECK (version_no > 0),
    CONSTRAINT ck_jsh_cfgver_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE jsh_config_binding (
    binding_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    template_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NULL,
    target_key BIGINT GENERATED ALWAYS AS (IFNULL(target_id, 0)) STORED,
    current_version_id BIGINT NOT NULL,
    previous_version_id BIGINT NULL,
    activated_at DATETIME(6) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (binding_id),
    UNIQUE KEY uk_jsh_binding_tenant_id (tenant_id, binding_id),
    UNIQUE KEY uk_jsh_binding_target (tenant_id, template_id, target_type, target_key),
    KEY idx_jsh_binding_current (tenant_id, template_id, current_version_id),
    CONSTRAINT fk_jsh_binding_current FOREIGN KEY (tenant_id, template_id, current_version_id)
        REFERENCES jsh_config_template_version (tenant_id, template_id, config_version_id),
    CONSTRAINT fk_jsh_binding_previous FOREIGN KEY (tenant_id, template_id, previous_version_id)
        REFERENCES jsh_config_template_version (tenant_id, template_id, config_version_id),
    CONSTRAINT fk_jsh_binding_store FOREIGN KEY (tenant_id, target_id)
        REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_jsh_binding_target CHECK (
        (target_type = 'TENANT' AND target_id IS NULL) OR
        (target_type = 'STORE' AND target_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE jsh_audit_event (
    audit_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_name VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    before_sha256 CHAR(64) NULL,
    after_sha256 CHAR(64) NULL,
    summary_json JSON NULL,
    occurred_at DATETIME(6) NOT NULL,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (audit_id),
    UNIQUE KEY uk_jsh_audit_tenant_id (tenant_id, audit_id),
    KEY idx_jsh_audit_tenant_time (tenant_id, occurred_at, audit_id),
    KEY idx_jsh_audit_target (tenant_id, target_type, target_id, occurred_at),
    CONSTRAINT ck_jsh_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_jsh_audit_before_hash CHECK (before_sha256 IS NULL OR before_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_jsh_audit_after_hash CHECK (after_sha256 IS NULL OR after_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER trg_jsh_audit_no_update
BEFORE UPDATE ON jsh_audit_event
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'jsh_audit_event is append-only';
END$$

CREATE TRIGGER trg_jsh_audit_no_delete
BEFORE DELETE ON jsh_audit_event
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'jsh_audit_event is append-only';
END$$

CREATE TRIGGER trg_jsh_config_published_immutable
BEFORE UPDATE ON jsh_config_template_version
FOR EACH ROW
BEGIN
    IF OLD.state = 'PUBLISHED' AND (
        NEW.tenant_id <> OLD.tenant_id OR
        NEW.template_id <> OLD.template_id OR
        NEW.version_no <> OLD.version_no OR
        NEW.schema_version <> OLD.schema_version OR
        NEW.content_sha256 <> OLD.content_sha256 OR
        NOT (NEW.content_json <=> OLD.content_json)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'published config version is immutable';
    END IF;
END$$
DELIMITER ;
