CREATE TABLE onb_plan (
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信服务端注入的租户标识',
    source_store_id BIGINT NULL COMMENT '同租户来源门店ID；空表示仅使用行业模板',
    target_store_id BIGINT NOT NULL COMMENT '目标 PREPARING 门店ID',
    template_id BIGINT NOT NULL COMMENT 'Foundation 行业模板ID',
    template_version_id BIGINT NOT NULL COMMENT '冻结的已发布行业模板版本ID',
    source_store_version INT NULL COMMENT '预检冻结的来源门店乐观锁版本',
    target_store_version INT NOT NULL COMMENT '预检冻结的目标门店乐观锁版本',
    template_version_no INT NOT NULL COMMENT '冻结的行业模板业务版本号',
    template_sha256 CHAR(64) NOT NULL COMMENT '冻结模板原始内容 SHA-256',
    industry VARCHAR(32) NOT NULL COMMENT 'CONVENIENCE/SNACK_DISCOUNT/COMMUNITY_SUPERMARKET',
    snapshot_sha256 CHAR(64) NOT NULL COMMENT '复制白名单配置规范摘要 SHA-256',
    state VARCHAR(32) NOT NULL COMMENT 'DRAFT 至 OPENED 的具名计划状态',
    idempotency_key VARCHAR(64) NOT NULL COMMENT '创建计划稳定幂等键',
    request_sha256 CHAR(64) NOT NULL COMMENT '创建请求规范摘要 SHA-256',
    creator_user_id BIGINT NOT NULL COMMENT '计划创建人；不得审批自己的计划',
    check_run INT NOT NULL DEFAULT 0 COMMENT '最近检查运行序号',
    record_version INT NOT NULL DEFAULT 0 COMMENT '状态乐观锁版本',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC 最近状态推进时间',
    PRIMARY KEY (plan_id),
    UNIQUE KEY uk_onb_plan_tenant_id (tenant_id,plan_id),
    UNIQUE KEY uk_onb_plan_idempotency (tenant_id,idempotency_key),
    KEY idx_onb_plan_target_state (tenant_id,target_store_id,state,updated_at),
    CONSTRAINT fk_onb_plan_source FOREIGN KEY (tenant_id,source_store_id) REFERENCES jsh_store(tenant_id,store_id),
    CONSTRAINT fk_onb_plan_target FOREIGN KEY (tenant_id,target_store_id) REFERENCES jsh_store(tenant_id,store_id),
    CONSTRAINT fk_onb_plan_template FOREIGN KEY (tenant_id,template_id,template_version_id)
        REFERENCES jsh_config_template_version(tenant_id,template_id,config_version_id),
    CONSTRAINT ck_onb_plan_ulid CHECK (plan_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_onb_plan_hashes CHECK (template_sha256 REGEXP '^[a-f0-9]{64}$'
        AND snapshot_sha256 REGEXP '^[a-f0-9]{64}$' AND request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_onb_plan_stores CHECK (source_store_id IS NULL OR source_store_id<>target_store_id),
    CONSTRAINT ck_onb_plan_industry CHECK (industry IN ('CONVENIENCE','SNACK_DISCOUNT','COMMUNITY_SUPERMARKET')),
    CONSTRAINT ck_onb_plan_state CHECK (state IN ('DRAFT','PREFLIGHTING','PREFLIGHT_FAILED','READY','APPROVED',
        'APPLYING','APPLIED','CHECKING','CHECK_FAILED','READY_TO_OPEN','OPENED','FAILED',
        'COMPENSATION_REQUIRED','CANCELLED')),
    CONSTRAINT ck_onb_plan_versions CHECK (target_store_version>=0 AND template_version_no>0
        AND check_run>=0 AND record_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Onboarding Owner 门店开通计划';

CREATE TABLE onb_config_snapshot (
    snapshot_id CHAR(26) NOT NULL COMMENT '白名单快照项 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '所属开店计划 ULID',item_key VARCHAR(64) NOT NULL COMMENT '批准的配置白名单键',
    content_json JSON NOT NULL COMMENT '冻结配置值；不得包含 Secret/PII/历史事实',
    content_sha256 CHAR(64) NOT NULL COMMENT '单项规范内容 SHA-256',created_at DATETIME(6) NOT NULL COMMENT 'UTC 冻结时间',
    PRIMARY KEY(snapshot_id),UNIQUE KEY uk_onb_snapshot_tenant_id(tenant_id,snapshot_id),
    UNIQUE KEY uk_onb_snapshot_item(tenant_id,plan_id,item_key),
    CONSTRAINT fk_onb_snapshot_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_snapshot_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店复制白名单配置快照';

CREATE TABLE onb_approval (
    approval_id CHAR(26) NOT NULL COMMENT '审批事实 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',approver_user_id BIGINT NOT NULL COMMENT '与创建人分离的审批人',
    reason VARCHAR(200) NOT NULL COMMENT '脱敏审批原因',idempotency_key VARCHAR(64) NOT NULL COMMENT '审批幂等键',
    request_sha256 CHAR(64) NOT NULL COMMENT '审批请求 SHA-256',approved_at DATETIME(6) NOT NULL COMMENT 'UTC 审批时间',
    PRIMARY KEY(approval_id),UNIQUE KEY uk_onb_approval_tenant_id(tenant_id,approval_id),
    UNIQUE KEY uk_onb_approval_command(tenant_id,idempotency_key),UNIQUE KEY uk_onb_approval_actor(tenant_id,plan_id,approver_user_id),
    CONSTRAINT fk_onb_approval_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_approval_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加开店审批事实';

CREATE TABLE onb_step_checkpoint (
    checkpoint_id CHAR(26) NOT NULL COMMENT 'Owner 步骤检查点 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',step_code VARCHAR(64) NOT NULL COMMENT '具名 Owner 应用步骤',
    idempotency_key VARCHAR(64) NOT NULL COMMENT '步骤稳定幂等键',request_sha256 CHAR(64) NOT NULL COMMENT 'Owner 请求摘要',
    result_sha256 CHAR(64) NOT NULL COMMENT 'Owner 稳定结果摘要',state VARCHAR(16) NOT NULL COMMENT 'APPLIED/FAILED',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 记录时间',PRIMARY KEY(checkpoint_id),
    UNIQUE KEY uk_onb_checkpoint_tenant_id(tenant_id,checkpoint_id),UNIQUE KEY uk_onb_checkpoint_step(tenant_id,plan_id,step_code),
    CONSTRAINT fk_onb_checkpoint_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_checkpoint_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$' AND result_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_onb_checkpoint_state CHECK(state IN ('APPLIED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加跨 Owner 可恢复步骤检查点';

CREATE TABLE onb_check_result (
    check_id CHAR(26) NOT NULL COMMENT '开店检查结果 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',run_no INT NOT NULL COMMENT '只增检查运行序号',
    check_code VARCHAR(64) NOT NULL COMMENT '冻结检查代码',owner_type VARCHAR(32) NOT NULL COMMENT '权威事实 Owner',
    required_flag BOOLEAN NOT NULL COMMENT '是否为开店必需项',external_flag BOOLEAN NOT NULL COMMENT '是否为外部 P0',
    fact_version VARCHAR(64) NOT NULL COMMENT '权威事实版本或 BLOCKED/UNAVAILABLE',fact_sha256 CHAR(64) NOT NULL COMMENT '权威事实脱敏摘要',
    status VARCHAR(16) NOT NULL COMMENT 'PASS/FAIL/BLOCKED/UNAVAILABLE/WARN',masked_message VARCHAR(256) NOT NULL COMMENT '不含 Secret/PII 的说明',
    checked_at DATETIME(6) NOT NULL COMMENT 'UTC 检查时间',PRIMARY KEY(check_id),UNIQUE KEY uk_onb_check_tenant_id(tenant_id,check_id),
    UNIQUE KEY uk_onb_check_run_code(tenant_id,plan_id,run_no,check_code),KEY idx_onb_check_plan_run(tenant_id,plan_id,run_no,status),
    CONSTRAINT fk_onb_check_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_check_run CHECK(run_no>0),CONSTRAINT ck_onb_check_hash CHECK(fact_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_onb_check_status CHECK(status IN ('PASS','FAIL','BLOCKED','UNAVAILABLE','WARN')),
    CONSTRAINT ck_onb_external_warn CHECK(NOT(external_flag=1 AND status='WARN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店开店检查历史';

CREATE TABLE onb_command_result (
    command_id CHAR(26) NOT NULL COMMENT '命令结果 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',operation VARCHAR(32) NOT NULL COMMENT 'PREFLIGHT/APPROVE/APPLY/CHECK/OPEN/CANCEL',
    idempotency_key VARCHAR(64) NOT NULL COMMENT '稳定幂等键',request_sha256 CHAR(64) NOT NULL COMMENT '命令请求摘要',
    result_state VARCHAR(32) NOT NULL COMMENT '命令完成后的计划状态',result_sha256 CHAR(64) NOT NULL COMMENT '命令稳定结果摘要',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 完成时间',PRIMARY KEY(command_id),UNIQUE KEY uk_onb_command_tenant_id(tenant_id,command_id),
    UNIQUE KEY uk_onb_command_key(tenant_id,operation,idempotency_key),
    CONSTRAINT fk_onb_command_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_command_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$' AND result_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加开店幂等命令结果';

CREATE TABLE onb_state_event (
    event_id CHAR(26) NOT NULL COMMENT '状态事件 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',from_state VARCHAR(32) NULL COMMENT '原状态；创建事件为空',
    to_state VARCHAR(32) NOT NULL COMMENT '目标状态',request_sha256 CHAR(64) NOT NULL COMMENT '触发请求摘要',
    correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',actor_user_id BIGINT NOT NULL COMMENT '可信操作者',
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC 发生时间',PRIMARY KEY(event_id),UNIQUE KEY uk_onb_state_tenant_id(tenant_id,event_id),
    KEY idx_onb_state_plan(tenant_id,plan_id,occurred_at),CONSTRAINT fk_onb_state_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_state_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加开店状态事件';

CREATE TABLE onb_audit_event (
    audit_id CHAR(26) NOT NULL COMMENT '领域审计 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',action_code VARCHAR(64) NOT NULL COMMENT '具名审计动作',
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILURE/DENIED',request_sha256 CHAR(64) NOT NULL COMMENT '请求摘要',
    correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',actor_user_id BIGINT NOT NULL COMMENT '可信操作者',
    masked_summary VARCHAR(256) NOT NULL COMMENT '不含 Secret/PII 的摘要',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC 发生时间',
    PRIMARY KEY(audit_id),UNIQUE KEY uk_onb_audit_tenant_id(tenant_id,audit_id),KEY idx_onb_audit_plan(tenant_id,plan_id,occurred_at),
    CONSTRAINT fk_onb_audit_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_audit_result CHECK(result IN ('SUCCESS','FAILURE','DENIED')),CONSTRAINT ck_onb_audit_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加开店领域审计';

CREATE TABLE onb_outbox (
    outbox_id CHAR(26) NOT NULL COMMENT 'Outbox 事件 ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    plan_id CHAR(26) NOT NULL COMMENT '开店计划 ULID',event_type VARCHAR(80) NOT NULL COMMENT '版本化事件类型',
    schema_version INT NOT NULL COMMENT '事件 Schema 版本',payload_json JSON NOT NULL COMMENT '不含 tenantId/Secret/PII 的事件载荷',
    payload_sha256 CHAR(64) NOT NULL COMMENT '事件载荷 SHA-256',correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
    delivery_state VARCHAR(16) NOT NULL COMMENT 'PENDING/DELIVERED/DEAD',attempts INT NOT NULL COMMENT '投递尝试次数',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 创建时间',delivered_at DATETIME(6) NULL COMMENT 'UTC 投递完成时间',
    PRIMARY KEY(outbox_id),UNIQUE KEY uk_onb_outbox_tenant_id(tenant_id,outbox_id),KEY idx_onb_outbox_delivery(tenant_id,delivery_state,created_at),
    CONSTRAINT fk_onb_outbox_plan FOREIGN KEY(tenant_id,plan_id) REFERENCES onb_plan(tenant_id,plan_id),
    CONSTRAINT ck_onb_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_onb_outbox_state CHECK(delivery_state IN ('PENDING','DELIVERED','DEAD')),CONSTRAINT ck_onb_outbox_attempts CHECK(attempts>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控更新开店 Outbox';

DELIMITER $$
CREATE TRIGGER trg_onb_plan_guard BEFORE UPDATE ON onb_plan FOR EACH ROW BEGIN
    IF NOT(NEW.tenant_id<=>OLD.tenant_id) OR NOT(NEW.source_store_id<=>OLD.source_store_id)
       OR NOT(NEW.target_store_id<=>OLD.target_store_id) OR NOT(NEW.template_id<=>OLD.template_id)
       OR NOT(NEW.template_version_id<=>OLD.template_version_id)
       OR NOT(NEW.source_store_version<=>OLD.source_store_version)
       OR NOT(NEW.target_store_version<=>OLD.target_store_version)
       OR NOT(NEW.template_version_no<=>OLD.template_version_no)
       OR NOT(NEW.template_sha256<=>OLD.template_sha256) OR NOT(NEW.industry<=>OLD.industry)
       OR NOT(NEW.snapshot_sha256<=>OLD.snapshot_sha256) OR NOT(NEW.creator_user_id<=>OLD.creator_user_id)
       OR NOT(NEW.idempotency_key<=>OLD.idempotency_key) OR NOT(NEW.request_sha256<=>OLD.request_sha256) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_plan immutable identity changed'; END IF;
END$$
CREATE TRIGGER trg_onb_snapshot_no_update BEFORE UPDATE ON onb_config_snapshot FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_config_snapshot append-only'; END$$
CREATE TRIGGER trg_onb_snapshot_no_delete BEFORE DELETE ON onb_config_snapshot FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_config_snapshot append-only'; END$$
CREATE TRIGGER trg_onb_approval_no_update BEFORE UPDATE ON onb_approval FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_approval append-only'; END$$
CREATE TRIGGER trg_onb_approval_no_delete BEFORE DELETE ON onb_approval FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_approval append-only'; END$$
CREATE TRIGGER trg_onb_checkpoint_no_update BEFORE UPDATE ON onb_step_checkpoint FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_step_checkpoint append-only'; END$$
CREATE TRIGGER trg_onb_checkpoint_no_delete BEFORE DELETE ON onb_step_checkpoint FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_step_checkpoint append-only'; END$$
CREATE TRIGGER trg_onb_check_no_update BEFORE UPDATE ON onb_check_result FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_check_result append-only'; END$$
CREATE TRIGGER trg_onb_check_no_delete BEFORE DELETE ON onb_check_result FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_check_result append-only'; END$$
CREATE TRIGGER trg_onb_command_no_update BEFORE UPDATE ON onb_command_result FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_command_result append-only'; END$$
CREATE TRIGGER trg_onb_command_no_delete BEFORE DELETE ON onb_command_result FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_command_result append-only'; END$$
CREATE TRIGGER trg_onb_state_no_update BEFORE UPDATE ON onb_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_state_event append-only'; END$$
CREATE TRIGGER trg_onb_state_no_delete BEFORE DELETE ON onb_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_state_event append-only'; END$$
CREATE TRIGGER trg_onb_audit_no_update BEFORE UPDATE ON onb_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_audit_event append-only'; END$$
CREATE TRIGGER trg_onb_audit_no_delete BEFORE DELETE ON onb_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='onb_audit_event append-only'; END$$
DELIMITER ;
