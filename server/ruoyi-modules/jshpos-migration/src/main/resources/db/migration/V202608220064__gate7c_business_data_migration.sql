CREATE TABLE mig_batch (
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信上下文注入的租户标识',
    requested_types JSON NOT NULL COMMENT '冻结资料类型集合JSON',
    state VARCHAR(32) NOT NULL COMMENT '迁移批次具名状态',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '租户内创建幂等键',
    request_sha256 CHAR(64) NOT NULL COMMENT '创建请求规范SHA-256',
    correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
    creator_user_id BIGINT NOT NULL COMMENT '创建操作者用户主键',
    version INT NOT NULL DEFAULT 0 COMMENT '批次乐观锁版本',
    created_at DATETIME(6) NOT NULL COMMENT '创建UTC时间',
    updated_at DATETIME(6) NULL COMMENT '最近状态迁移UTC时间',
    PRIMARY KEY (batch_id),
    UNIQUE KEY uk_mig_batch_tenant_idem (tenant_id,idempotency_key),
    UNIQUE KEY uk_mig_batch_tenant_batch (tenant_id,batch_id),
    KEY idx_mig_batch_tenant_state (tenant_id,state,created_at),
    CONSTRAINT ck_mig_batch_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='Migration Owner开业资料迁移批次';

CREATE TABLE mig_file (
    file_id VARCHAR(26) NOT NULL COMMENT '文件登记ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '所属迁移批次ULID',
    data_type VARCHAR(32) NOT NULL COMMENT 'CATALOG/SUPPLIER/OPENING_INVENTORY/MEMBER',
    mapping_version VARCHAR(32) NOT NULL COMMENT '冻结字段映射版本',
    source_sha256 CHAR(64) NOT NULL COMMENT '原文件SHA-256，不保存原文件',
    safe_filename VARCHAR(180) NOT NULL COMMENT '去除路径后的安全逻辑文件名',
    charset_name VARCHAR(16) NOT NULL COMMENT 'CSV字符集或XLSX标记',
    row_count INT NOT NULL COMMENT '有效数据行数',
    error_count INT NOT NULL COMMENT '阻断预检错误数',
    state VARCHAR(24) NOT NULL COMMENT 'PREFLIGHT_PASSED/PREFLIGHT_FAILED',
    source_system VARCHAR(80) NOT NULL COMMENT '来源系统名称',
    custody_reference VARCHAR(256) NOT NULL COMMENT '批准受控渠道中的保管引用',
    file_bytes BIGINT NOT NULL COMMENT '上传文件字节数',
    uploader_user_id BIGINT NOT NULL COMMENT '上传操作者用户主键',
    created_at DATETIME(6) NOT NULL COMMENT '登记UTC时间',
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_mig_file_tenant_file (tenant_id,batch_id,file_id),
    UNIQUE KEY uk_mig_file_tenant_sha (tenant_id,batch_id,data_type,source_sha256),
    UNIQUE KEY uk_mig_file_tenant_type (tenant_id,batch_id,data_type),
    KEY idx_mig_file_batch (tenant_id,batch_id,created_at),
    CONSTRAINT fk_mig_file_batch FOREIGN KEY (tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT ck_mig_file_hash CHECK (source_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_mig_file_count CHECK (row_count BETWEEN 0 AND 100000 AND error_count>=0),
    CONSTRAINT ck_mig_file_bytes CHECK (file_bytes BETWEEN 0 AND 67108864)
) ENGINE=InnoDB COMMENT='原文件最小化登记，不保存文件内容';

CREATE TABLE mig_staging_row (
    row_id VARCHAR(26) NOT NULL COMMENT '规范化暂存行ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID',
    file_id VARCHAR(26) NOT NULL COMMENT '来源文件登记ULID',
    data_type VARCHAR(32) NOT NULL COMMENT '资料类型',
    source_row_number INT NOT NULL COMMENT '来源文件1基数据行号',
    row_sha256 CHAR(64) NOT NULL COMMENT '规范行SHA-256',
    cipher_text MEDIUMTEXT NOT NULL COMMENT 'AES-256-GCM规范行密文，清理后为空串',
    key_version VARCHAR(64) NOT NULL COMMENT '仓库外staging密钥版本',
    content_hmac CHAR(64) NOT NULL COMMENT '绑定AAD与密文的HMAC-SHA256，清理后为空串',
    state VARCHAR(16) NOT NULL COMMENT 'READY/CLEANED',
    expires_at DATETIME(6) NOT NULL COMMENT '暂存到期UTC时间',
    created_at DATETIME(6) NOT NULL COMMENT '暂存UTC时间',
    cleaned_at DATETIME(6) NULL COMMENT '受审计清理UTC时间',
    PRIMARY KEY (row_id),
    UNIQUE KEY uk_mig_stage_tenant_row (tenant_id,batch_id,row_id),
    UNIQUE KEY uk_mig_stage_file_number (tenant_id,file_id,source_row_number),
    KEY idx_mig_stage_batch_type (tenant_id,batch_id,data_type,source_row_number),
    KEY idx_mig_stage_expiry (state,expires_at),
    CONSTRAINT fk_mig_stage_batch FOREIGN KEY (tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT fk_mig_stage_file FOREIGN KEY (tenant_id,batch_id,file_id)
      REFERENCES mig_file(tenant_id,batch_id,file_id),
    CONSTRAINT ck_mig_stage_hash CHECK (row_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_mig_stage_row CHECK (source_row_number BETWEEN 2 AND 100001)
) ENGINE=InnoDB COMMENT='Migration Owner加密隔离规范行暂存';

CREATE TABLE mig_preflight_error (
    error_id VARCHAR(26) NOT NULL COMMENT '预检错误ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', file_id VARCHAR(26) NOT NULL COMMENT '文件登记ULID',
    data_type VARCHAR(32) NOT NULL COMMENT '资料类型', source_row_number INT NOT NULL COMMENT '来源数据行号，文件级为0',
    field_name VARCHAR(64) NULL COMMENT '错误字段名，不含原始值', error_code VARCHAR(64) NOT NULL COMMENT '稳定错误码',
    masked_message VARCHAR(512) NOT NULL COMMENT '不含PII的脱敏错误说明', created_at DATETIME(6) NOT NULL COMMENT '记录UTC时间',
    PRIMARY KEY(error_id), KEY idx_mig_error_batch(tenant_id,batch_id,data_type,source_row_number),
    CONSTRAINT fk_mig_error_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT fk_mig_error_file FOREIGN KEY(tenant_id,batch_id,file_id)
      REFERENCES mig_file(tenant_id,batch_id,file_id)
) ENGINE=InnoDB COMMENT='只追加预检错误明细';

CREATE TABLE mig_approval (
    approval_id VARCHAR(26) NOT NULL COMMENT '审批事实ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', approver_user_id BIGINT NOT NULL COMMENT '审批用户主键',
    reason_sha256 CHAR(64) NOT NULL COMMENT '审批理由SHA-256，不保存可能敏感原文',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '审批幂等键', correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',
    approved_at DATETIME(6) NOT NULL COMMENT '审批UTC时间', PRIMARY KEY(approval_id),
    UNIQUE KEY uk_mig_approval_user(tenant_id,batch_id,approver_user_id),
    UNIQUE KEY uk_mig_approval_idem(tenant_id,batch_id,idempotency_key),
    CONSTRAINT fk_mig_approval_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT ck_mig_approval_hash CHECK(reason_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='只追加双人审批事实';

CREATE TABLE mig_owner_checkpoint (
    checkpoint_id VARCHAR(26) NOT NULL COMMENT 'Owner检查点ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', row_id VARCHAR(26) NOT NULL COMMENT '规范行ULID',
    owner_type VARCHAR(24) NOT NULL COMMENT 'CATALOG/PROCUREMENT/MEMBER/INVENTORY', data_type VARCHAR(32) NOT NULL COMMENT '资料类型',
    command_id VARCHAR(64) NOT NULL COMMENT 'Owner稳定命令或事件标识', request_sha256 CHAR(64) NOT NULL COMMENT 'Owner请求SHA-256',
    result_sha256 CHAR(64) NOT NULL COMMENT 'Owner稳定结果SHA-256', state VARCHAR(16) NOT NULL COMMENT 'APPLIED/FAILED',
    correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识', created_at DATETIME(6) NOT NULL COMMENT '记录UTC时间',
    PRIMARY KEY(checkpoint_id), UNIQUE KEY uk_mig_checkpoint_row(tenant_id,batch_id,row_id),
    KEY idx_mig_checkpoint_owner(tenant_id,batch_id,owner_type,state),
    CONSTRAINT fk_mig_checkpoint_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT fk_mig_checkpoint_row FOREIGN KEY(tenant_id,batch_id,row_id)
      REFERENCES mig_staging_row(tenant_id,batch_id,row_id),
    CONSTRAINT ck_mig_checkpoint_request CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_mig_checkpoint_result CHECK(result_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='只追加跨Owner可恢复Saga检查点';

CREATE TABLE mig_reconciliation (
    reconciliation_id VARCHAR(26) NOT NULL COMMENT '对账运行ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', expected_rows INT NOT NULL COMMENT '应写Owner行数',
    applied_rows INT NOT NULL COMMENT '已落Owner检查点行数', difference_count INT NOT NULL COMMENT 'P0/P1差异数',
    result_sha256 CHAR(64) NOT NULL COMMENT '冻结对账结果SHA-256', state VARCHAR(16) NOT NULL COMMENT 'MATCHED/MISMATCH',
    actor_user_id BIGINT NOT NULL COMMENT '执行对账用户主键', correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',
    created_at DATETIME(6) NOT NULL COMMENT '对账UTC时间', PRIMARY KEY(reconciliation_id),
    KEY idx_mig_reconcile_batch(tenant_id,batch_id,created_at),
    CONSTRAINT fk_mig_reconcile_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT ck_mig_reconcile_counts CHECK(expected_rows>=0 AND applied_rows>=0 AND difference_count>=0),
    CONSTRAINT ck_mig_reconcile_hash CHECK(result_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='只追加迁移逐Owner对账结果';

CREATE TABLE mig_state_event (
    event_id VARCHAR(26) NOT NULL COMMENT '状态事件ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', from_state VARCHAR(32) NOT NULL COMMENT '前状态',
    to_state VARCHAR(32) NOT NULL COMMENT '后状态', batch_version INT NOT NULL COMMENT '迁移后批次版本',
    actor_user_id BIGINT NOT NULL COMMENT '操作者用户主键', correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',
    occurred_at DATETIME(6) NOT NULL COMMENT '发生UTC时间', PRIMARY KEY(event_id),
    KEY idx_mig_state_batch(tenant_id,batch_id,batch_version),
    CONSTRAINT fk_mig_state_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id)
) ENGINE=InnoDB COMMENT='只追加迁移批次状态历史';

CREATE TABLE mig_audit_event (
    audit_id VARCHAR(26) NOT NULL COMMENT '迁移审计ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', action VARCHAR(64) NOT NULL COMMENT '受控操作动作',
    actor_user_id BIGINT NOT NULL COMMENT '操作者用户主键', summary_sha256 CHAR(64) NOT NULL COMMENT '脱敏摘要SHA-256',
    correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识', occurred_at DATETIME(6) NOT NULL COMMENT '发生UTC时间',
    PRIMARY KEY(audit_id), KEY idx_mig_audit_batch(tenant_id,batch_id,occurred_at),
    CONSTRAINT fk_mig_audit_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT ck_mig_audit_hash CHECK(summary_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='只追加开业资料迁移审计';

CREATE TABLE mig_outbox (
    outbox_id VARCHAR(26) NOT NULL COMMENT '事件Outbox ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID', event_type VARCHAR(96) NOT NULL COMMENT '版本化迁移事件类型',
    aggregate_version INT NOT NULL COMMENT '批次聚合版本', payload_json JSON NOT NULL COMMENT '不含原文件和PII的事件载荷',
    payload_sha256 CHAR(64) NOT NULL COMMENT '事件载荷SHA-256', delivery_state VARCHAR(16) NOT NULL COMMENT 'NEW/SENT',
    correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识', available_at DATETIME(6) NOT NULL COMMENT '可投递UTC时间',
    PRIMARY KEY(outbox_id), UNIQUE KEY uk_mig_outbox_event(tenant_id,batch_id,aggregate_version,event_type),
    KEY idx_mig_outbox_delivery(delivery_state,available_at),
    CONSTRAINT fk_mig_outbox_batch FOREIGN KEY(tenant_id,batch_id) REFERENCES mig_batch(tenant_id,batch_id),
    CONSTRAINT ck_mig_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='迁移版本化事件受控投递Outbox';

DELIMITER $$
CREATE TRIGGER trg_mig_file_no_update BEFORE UPDATE ON mig_file FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_file is append-only'; END$$
CREATE TRIGGER trg_mig_file_no_delete BEFORE DELETE ON mig_file FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_file is append-only'; END$$
CREATE TRIGGER trg_mig_error_no_update BEFORE UPDATE ON mig_preflight_error FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_preflight_error is append-only'; END$$
CREATE TRIGGER trg_mig_error_no_delete BEFORE DELETE ON mig_preflight_error FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_preflight_error is append-only'; END$$
CREATE TRIGGER trg_mig_approval_no_update BEFORE UPDATE ON mig_approval FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_approval is append-only'; END$$
CREATE TRIGGER trg_mig_approval_no_delete BEFORE DELETE ON mig_approval FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_approval is append-only'; END$$
CREATE TRIGGER trg_mig_checkpoint_no_update BEFORE UPDATE ON mig_owner_checkpoint FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_owner_checkpoint is append-only'; END$$
CREATE TRIGGER trg_mig_checkpoint_no_delete BEFORE DELETE ON mig_owner_checkpoint FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_owner_checkpoint is append-only'; END$$
CREATE TRIGGER trg_mig_reconcile_no_update BEFORE UPDATE ON mig_reconciliation FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_reconciliation is append-only'; END$$
CREATE TRIGGER trg_mig_reconcile_no_delete BEFORE DELETE ON mig_reconciliation FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_reconciliation is append-only'; END$$
CREATE TRIGGER trg_mig_state_no_update BEFORE UPDATE ON mig_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_state_event is append-only'; END$$
CREATE TRIGGER trg_mig_state_no_delete BEFORE DELETE ON mig_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_state_event is append-only'; END$$
CREATE TRIGGER trg_mig_audit_no_update BEFORE UPDATE ON mig_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_audit_event is append-only'; END$$
CREATE TRIGGER trg_mig_audit_no_delete BEFORE DELETE ON mig_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_audit_event is append-only'; END$$
CREATE TRIGGER trg_mig_stage_guard BEFORE UPDATE ON mig_staging_row FOR EACH ROW BEGIN
  IF NOT (OLD.state='READY' AND NEW.state='CLEANED' AND NEW.cipher_text='' AND NEW.content_hmac=''
      AND OLD.row_id=NEW.row_id AND OLD.tenant_id=NEW.tenant_id AND OLD.batch_id=NEW.batch_id
      AND OLD.file_id=NEW.file_id AND OLD.data_type=NEW.data_type AND OLD.source_row_number=NEW.source_row_number
      AND OLD.row_sha256=NEW.row_sha256 AND OLD.key_version=NEW.key_version) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_staging_row only permits audited cleanup';
  END IF;
END$$
CREATE TRIGGER trg_mig_stage_no_delete BEFORE DELETE ON mig_staging_row FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mig_staging_row cannot be deleted'; END$$
DELIMITER ;
