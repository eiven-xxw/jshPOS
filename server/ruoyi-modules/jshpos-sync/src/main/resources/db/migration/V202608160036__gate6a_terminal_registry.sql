-- Gate 6A Terminal Registry：前向扩展 Sprint S3 设备唯一事实源。
ALTER TABLE pos_sync_device
    ADD COLUMN org_unit_id BIGINT NULL COMMENT '服务端绑定的组织单元ID' AFTER tenant_id,
    ADD COLUMN activation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次激活授权ULID，旧设备为空' AFTER bound_user_id,
    ADD COLUMN terminal_profile_code VARCHAR(64) NOT NULL DEFAULT 'LEGACY' COMMENT '终端能力模板代码' AFTER activation_id,
    ADD COLUMN fingerprint_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '不可逆设备指纹SHA-256摘要' AFTER terminal_profile_code,
    ADD COLUMN public_key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '设备公钥SHA-256摘要，禁止保存私钥' AFTER fingerprint_sha256,
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 COMMENT '当前有效设备凭据版本，零表示旧设备' AFTER public_key_sha256,
    ADD COLUMN app_version VARCHAR(32) NOT NULL DEFAULT '0.0.0' COMMENT '最近验证的POS应用数字版本' AFTER max_protocol_version,
    ADD COLUMN schema_version VARCHAR(16) NOT NULL DEFAULT '0' COMMENT '最近验证的POS本地Schema数字版本' AFTER app_version,
    ADD COLUMN capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前规范化能力快照SHA-256摘要' AFTER schema_version,
    ADD COLUMN clock_skew_seconds BIGINT NULL COMMENT '客户端时间减服务端UTC时间的秒数' AFTER capability_sha256,
    ADD COLUMN activation_evidence_level VARCHAR(24) NOT NULL DEFAULT 'LEGACY_IMPORTED' COMMENT '激活证据等级：旧导入、合成或真实设备' AFTER clock_skew_seconds,
    ADD COLUMN activated_at DATETIME(3) NULL COMMENT '服务端UTC激活时间' AFTER activation_evidence_level,
    ADD COLUMN revoked_at DATETIME(3) NULL COMMENT '服务端UTC不可逆吊销时间' AFTER activated_at,
    ADD COLUMN retired_at DATETIME(3) NULL COMMENT '服务端UTC退役时间' AFTER revoked_at;

UPDATE pos_sync_device d
JOIN jsh_store s ON s.tenant_id=d.tenant_id AND s.store_id=d.store_id
SET d.org_unit_id=s.org_unit_id
WHERE d.org_unit_id IS NULL;

ALTER TABLE pos_sync_device
    MODIFY COLUMN org_unit_id BIGINT NOT NULL COMMENT '服务端绑定的组织单元ID',
    ADD UNIQUE KEY uk_pos_sync_device_activation (activation_id),
    ADD KEY idx_pos_sync_device_scope (tenant_id, org_unit_id, store_id, status),
    ADD CONSTRAINT fk_pos_sync_device_org FOREIGN KEY (tenant_id, org_unit_id) REFERENCES jsh_org_unit (tenant_id, org_unit_id),
    DROP CHECK ck_pos_sync_device_status,
    ADD CONSTRAINT ck_pos_sync_device_status CHECK (status IN ('ACTIVE','BLOCKED','REVOKED','RETIRED')),
    ADD CONSTRAINT ck_pos_sync_device_credential_version CHECK (credential_version >= 0),
    ADD CONSTRAINT ck_pos_sync_device_evidence CHECK (activation_evidence_level IN ('LEGACY_IMPORTED','SYNTHETIC','REAL_DEVICE'));

CREATE TABLE dev_terminal_activation (
    activation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的激活授权ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信会话注入的租户标识',
    org_unit_id BIGINT NOT NULL COMMENT '授权绑定组织单元ID',
    store_id BIGINT NOT NULL COMMENT '授权绑定门店ID',
    bound_user_id BIGINT NOT NULL COMMENT '授权绑定的POS服务用户ID',
    terminal_profile_code VARCHAR(64) NOT NULL COMMENT '受权终端能力模板代码',
    secret_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '外部pepper计算的激活秘密HMAC-SHA-256',
    status VARCHAR(16) NOT NULL COMMENT '授权状态：ISSUED/CONSUMED/EXPIRED/CANCELLED',
    expires_at DATETIME(3) NOT NULL COMMENT '服务端UTC失效时间',
    consumed_device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '成功消费后生成的终端ULID',
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户内签发命令幂等键',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化签发命令SHA-256摘要',
    evidence_level VARCHAR(24) NOT NULL DEFAULT 'SYNTHETIC' COMMENT '激活证据等级，本Gate仅SYNTHETIC',
    created_by BIGINT NOT NULL COMMENT '签发操作用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '服务端UTC签发时间',
    consumed_at DATETIME(3) NULL COMMENT '服务端UTC消费时间',
    cancelled_at DATETIME(3) NULL COMMENT '服务端UTC取消时间',
    record_version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本，从1递增',
    PRIMARY KEY (activation_id),
    UNIQUE KEY uk_dev_activation_command (tenant_id, idempotency_key),
    KEY idx_dev_activation_expiry (tenant_id, status, expires_at),
    KEY idx_dev_activation_store (tenant_id, store_id, status),
    CONSTRAINT fk_dev_activation_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_dev_activation_org FOREIGN KEY (tenant_id, org_unit_id) REFERENCES jsh_org_unit (tenant_id, org_unit_id),
    CONSTRAINT ck_dev_activation_status CHECK (status IN ('ISSUED','CONSUMED','EXPIRED','CANCELLED')),
    CONSTRAINT ck_dev_activation_evidence CHECK (evidence_level IN ('SYNTHETIC','REAL_DEVICE')),
    CONSTRAINT ck_dev_activation_version CHECK (record_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端一次性激活授权';

CREATE TABLE dev_terminal_credential (
    credential_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设备凭据版本记录ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '由激活授权派生的租户标识',
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端分配的终端ULID',
    credential_version BIGINT NOT NULL COMMENT '终端内严格递增的凭据版本',
    secret_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '外部pepper计算的设备秘密HMAC-SHA-256',
    fingerprint_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定设备指纹SHA-256摘要',
    public_key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定设备公钥SHA-256摘要',
    status VARCHAR(16) NOT NULL COMMENT '凭据状态：ACTIVE/ROTATED/REVOKED/EXPIRED',
    issued_at DATETIME(3) NOT NULL COMMENT '服务端UTC签发时间',
    expires_at DATETIME(3) NOT NULL COMMENT '服务端UTC失效时间',
    invalidated_at DATETIME(3) NULL COMMENT '服务端UTC轮换或吊销时间',
    PRIMARY KEY (credential_id),
    UNIQUE KEY uk_dev_credential_version (tenant_id, device_id, credential_version),
    KEY idx_dev_credential_active (tenant_id, device_id, status, expires_at),
    CONSTRAINT fk_dev_credential_device FOREIGN KEY (tenant_id, device_id) REFERENCES pos_sync_device (tenant_id, device_id),
    CONSTRAINT ck_dev_credential_version CHECK (credential_version > 0),
    CONSTRAINT ck_dev_credential_status CHECK (status IN ('ACTIVE','ROTATED','REVOKED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端设备凭据版本历史';

CREATE TABLE dev_capability_snapshot (
    snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '能力快照ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信终端上下文租户标识',
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端分配的终端ULID',
    sequence_no BIGINT NOT NULL COMMENT '终端内严格递增能力序号',
    app_version VARCHAR(32) NOT NULL COMMENT 'POS应用数字版本',
    protocol_version VARCHAR(16) NOT NULL COMMENT '同步协议数字版本',
    schema_version VARCHAR(16) NOT NULL COMMENT 'POS本地Schema数字版本',
    capability_json JSON NOT NULL COMMENT '规范化设备能力JSON，不含秘密或PII',
    capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化能力JSON的SHA-256摘要',
    client_time DATETIME(3) NOT NULL COMMENT '终端声明的UTC时间，仅用于偏移测量',
    clock_skew_seconds BIGINT NOT NULL COMMENT '客户端时间减服务端UTC时间的秒数',
    reported_at DATETIME(3) NOT NULL COMMENT '服务端UTC接收时间',
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_dev_capability_sequence (tenant_id, device_id, sequence_no),
    UNIQUE KEY uk_dev_capability_digest (tenant_id, device_id, capability_sha256),
    CONSTRAINT fk_dev_capability_device FOREIGN KEY (tenant_id, device_id) REFERENCES pos_sync_device (tenant_id, device_id),
    CONSTRAINT ck_dev_capability_sequence CHECK (sequence_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端不可变能力快照';

CREATE TABLE dev_terminal_command_result (
    command_result_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令结果记录ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信或由激活派生的租户标识',
    command_type VARCHAR(48) NOT NULL COMMENT '终端命令类型代码',
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户和命令类型内幂等键',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化命令SHA-256摘要',
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令影响的终端或授权ULID',
    result_code VARCHAR(48) NOT NULL COMMENT '稳定结果代码，不含原始秘密',
    result_json JSON NOT NULL COMMENT '去除原始秘密后的稳定结果JSON',
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果JSON的SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '服务端UTC写入时间',
    PRIMARY KEY (command_result_id),
    UNIQUE KEY uk_dev_terminal_command (tenant_id, command_type, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端不可变命令幂等结果';

CREATE TABLE dev_terminal_audit (
    audit_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '终端审计事件ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信或由激活派生的租户标识',
    device_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '相关终端ULID，签发阶段可为空',
    store_id BIGINT NULL COMMENT '相关门店ID',
    action_code VARCHAR(64) NOT NULL COMMENT '终端安全动作代码',
    before_status VARCHAR(16) NULL COMMENT '动作前状态',
    after_status VARCHAR(16) NULL COMMENT '动作后状态',
    evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏审计证据SHA-256摘要',
    actor_type VARCHAR(24) NOT NULL COMMENT '操作者类型：USER/DEVICE/SYSTEM',
    actor_id VARCHAR(64) NOT NULL COMMENT '操作者内部标识，不含PII',
    reason VARCHAR(256) NULL COMMENT '受控状态变更原因',
    correlation_id VARCHAR(64) NOT NULL COMMENT '跨服务关联标识',
    occurred_at DATETIME(3) NOT NULL COMMENT '服务端UTC发生时间',
    PRIMARY KEY (audit_event_id),
    KEY idx_dev_terminal_audit_device (tenant_id, device_id, occurred_at),
    KEY idx_dev_terminal_audit_action (tenant_id, action_code, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端不可变安全审计链';

DELIMITER $$
CREATE TRIGGER dev_capability_no_update BEFORE UPDATE ON dev_capability_snapshot
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal capability snapshot is append-only'; END$$
CREATE TRIGGER dev_capability_no_delete BEFORE DELETE ON dev_capability_snapshot
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal capability snapshot cannot be deleted'; END$$
CREATE TRIGGER dev_command_no_update BEFORE UPDATE ON dev_terminal_command_result
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal command result is append-only'; END$$
CREATE TRIGGER dev_command_no_delete BEFORE DELETE ON dev_terminal_command_result
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal command result cannot be deleted'; END$$
CREATE TRIGGER dev_audit_no_update BEFORE UPDATE ON dev_terminal_audit
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal audit is append-only'; END$$
CREATE TRIGGER dev_audit_no_delete BEFORE DELETE ON dev_terminal_audit
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal audit cannot be deleted'; END$$
CREATE TRIGGER dev_credential_no_delete BEFORE DELETE ON dev_terminal_credential
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='terminal credential history cannot be deleted'; END$$
DELIMITER ;
