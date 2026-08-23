-- T2-SAA-001 商户开户、套餐权益、技术租户引用、商业生命周期与原子配额。
CREATE TABLE saas_plan (
  plan_id BIGINT NOT NULL COMMENT '套餐平台 BIGINT 主键',
  plan_code VARCHAR(64) NOT NULL COMMENT '全局唯一套餐代码',
  plan_name VARCHAR(64) NOT NULL COMMENT '套餐展示名称',
  platform_package_id BIGINT NOT NULL COMMENT 'RuoYi 技术菜单套餐引用，不承载商业权益',
  account_limit BIGINT NOT NULL COMMENT '技术租户员工账号上限，单位为个',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE/RETIRED',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (plan_id), UNIQUE KEY uk_saas_plan_code (plan_code),
  CONSTRAINT ck_saas_plan_account CHECK (account_limit > 0),
  CONSTRAINT ck_saas_plan_status CHECK (status IN ('ACTIVE','RETIRED'))
) ENGINE=InnoDB COMMENT='SaaS 商业套餐主数据';

CREATE TABLE saas_merchant_application (
  application_id CHAR(26) NOT NULL COMMENT '商户申请 ULID',
  application_code VARCHAR(64) NOT NULL COMMENT '全局唯一业务申请号',
  tenant_id VARCHAR(20) NULL COMMENT 'Foundation 服务端分配租户号，创建前为空',
  technical_tenant_id BIGINT NULL COMMENT 'RuoYi 技术租户记录主键引用',
  company_name VARCHAR(128) NOT NULL COMMENT '企业展示名称，不保存联系密钥',
  industry VARCHAR(64) NOT NULL COMMENT '版本化行业模板类型',
  plan_id BIGINT NOT NULL COMMENT '申请绑定商业套餐主键',
  state VARCHAR(32) NOT NULL COMMENT '商户申请具名状态',
  submitter_user_id BIGINT NOT NULL COMMENT '平台提交人用户主键',
  approver_user_id BIGINT NULL COMMENT '独立审批人用户主键',
  record_version INT NOT NULL COMMENT '乐观锁版本，从零递增',
  content_sha256 CHAR(64) NOT NULL COMMENT '申请内容 SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (application_id), UNIQUE KEY uk_saas_app_code (application_code), UNIQUE KEY uk_saas_app_tenant (tenant_id),
  KEY idx_saas_app_state_time (state,created_at),
  CONSTRAINT fk_saas_app_plan FOREIGN KEY (plan_id) REFERENCES saas_plan(plan_id),
  CONSTRAINT ck_saas_app_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_saas_app_state CHECK (state IN ('DRAFT','PREFLIGHTING','PREFLIGHT_FAILED','READY','APPROVED','PROVISIONING','INITIALIZING','ACTIVE','FAILED','COMPENSATION_REQUIRED','CANCELLED'))
) ENGINE=InnoDB COMMENT='SaaS 商户开户申请';

CREATE TABLE saas_application_state_event (
  event_id CHAR(26) NOT NULL COMMENT '申请状态事件 ULID', application_id CHAR(26) NOT NULL COMMENT '商户申请 ULID',
  tenant_id VARCHAR(20) NULL COMMENT '服务端分配租户号，创建前为空', from_state VARCHAR(32) NULL COMMENT '迁移前状态，创建事实为空',
  to_state VARCHAR(32) NOT NULL COMMENT '迁移后状态', request_sha256 CHAR(64) NOT NULL COMMENT '命令内容 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识', actor_user_id BIGINT NOT NULL COMMENT '平台操作者主键',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC', PRIMARY KEY(event_id), KEY idx_saas_app_event (application_id,occurred_at),
  CONSTRAINT fk_saas_state_app FOREIGN KEY(application_id) REFERENCES saas_merchant_application(application_id),
  CONSTRAINT ck_saas_state_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='商户申请只追加状态事实';

CREATE TABLE saas_entitlement_version (
  version_id CHAR(26) NOT NULL COMMENT '权益版本 ULID', plan_id BIGINT NOT NULL COMMENT '所属套餐主键',
  version_no INT NOT NULL COMMENT '套餐内严格递增版本号', state VARCHAR(24) NOT NULL COMMENT '权益发布状态',
  effective_at DATETIME(3) NOT NULL COMMENT '生效时间 UTC', expires_at DATETIME(3) NULL COMMENT '失效时间 UTC，空为长期',
  content_sha256 CHAR(64) NOT NULL COMMENT '完整权益内容 SHA-256', creator_user_id BIGINT NOT NULL COMMENT '版本创建人主键',
  approver_user_id BIGINT NULL COMMENT '独立审批人主键', record_version INT NOT NULL COMMENT '乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC', updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY(version_id), UNIQUE KEY uk_saas_ent_plan_version(plan_id,version_no), KEY idx_saas_ent_window(plan_id,state,effective_at,expires_at),
  CONSTRAINT fk_saas_ent_plan FOREIGN KEY(plan_id) REFERENCES saas_plan(plan_id), CONSTRAINT ck_saas_ent_version CHECK(version_no>0),
  CONSTRAINT ck_saas_ent_window CHECK(expires_at IS NULL OR expires_at>effective_at), CONSTRAINT ck_saas_ent_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_saas_ent_state CHECK(state IN ('DRAFT','VALIDATING','VALIDATION_FAILED','READY','APPROVED','PUBLISHED','EFFECTIVE','SUSPENDED','RETIRED'))
) ENGINE=InnoDB COMMENT='版本化 SaaS 套餐权益';

CREATE TABLE saas_entitlement_item (
  item_id CHAR(26) NOT NULL COMMENT '权益条目 ULID', version_id CHAR(26) NOT NULL COMMENT '权益版本 ULID',
  feature_code VARCHAR(64) NOT NULL COMMENT '服务端功能权益代码', enabled_flag BOOLEAN NOT NULL COMMENT '该版本是否启用功能',
  quota_limit BIGINT NULL COMMENT '配额上限，单位由功能代码冻结，空表示非配额权益', item_sha256 CHAR(64) NOT NULL COMMENT '条目 SHA-256',
  PRIMARY KEY(item_id), UNIQUE KEY uk_saas_ent_item(version_id,feature_code),
  CONSTRAINT fk_saas_item_version FOREIGN KEY(version_id) REFERENCES saas_entitlement_version(version_id),
  CONSTRAINT ck_saas_item_quota CHECK(quota_limit IS NULL OR quota_limit>=0), CONSTRAINT ck_saas_item_hash CHECK(item_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='权益版本不可变条目';

CREATE TABLE saas_tenant_entitlement (
  tenant_id VARCHAR(20) NOT NULL COMMENT 'Foundation 服务端分配可信租户号', plan_id BIGINT NOT NULL COMMENT '绑定套餐主键',
  version_id CHAR(26) NOT NULL COMMENT '冻结权益版本 ULID', lifecycle_state VARCHAR(32) NOT NULL COMMENT '租户商业生命周期当前投影',
  lifecycle_version INT NOT NULL COMMENT '生命周期乐观锁版本', binding_sha256 CHAR(64) NOT NULL COMMENT '租户套餐绑定 SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '绑定时间 UTC', updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY(tenant_id), KEY idx_saas_tenant_lifecycle(lifecycle_state,updated_at),
  CONSTRAINT fk_saas_tenant_plan FOREIGN KEY(plan_id) REFERENCES saas_plan(plan_id), CONSTRAINT fk_saas_tenant_version FOREIGN KEY(version_id) REFERENCES saas_entitlement_version(version_id),
  CONSTRAINT ck_saas_binding_hash CHECK(binding_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_saas_lifecycle CHECK(lifecycle_state IN ('PENDING_ACTIVATION','ACTIVE','SUSPENSION_PENDING','SUSPENDED','DEACTIVATION_PENDING','DEACTIVATED','RESTORING','TERMINATION_REQUESTED','RETENTION_HOLD','TERMINATED_LOGICAL'))
) ENGINE=InnoDB COMMENT='可信租户权益绑定与商业生命周期投影';

CREATE TABLE saas_tenant_lifecycle_event (
  event_id CHAR(26) NOT NULL COMMENT '生命周期事件 ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  from_state VARCHAR(32) NULL COMMENT '迁移前状态，创建事实为空', to_state VARCHAR(32) NOT NULL COMMENT '迁移后状态',
  reason VARCHAR(256) NOT NULL COMMENT '脱敏处置原因', request_sha256 CHAR(64) NOT NULL COMMENT '命令内容 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识', actor_user_id BIGINT NOT NULL COMMENT '平台操作者主键',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC', PRIMARY KEY(event_id), KEY idx_saas_lifecycle(tenant_id,occurred_at),
  CONSTRAINT ck_saas_life_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='租户商业生命周期只追加事实';

CREATE TABLE saas_initialization_checkpoint (
  checkpoint_id CHAR(26) NOT NULL COMMENT '初始化检查点 ULID', application_id CHAR(26) NOT NULL COMMENT '商户申请 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号', step_code VARCHAR(64) NOT NULL COMMENT '具名 Saga 步骤代码',
  result_sha256 CHAR(64) NOT NULL COMMENT '步骤稳定结果 SHA-256', completed_at DATETIME(3) NOT NULL COMMENT '完成时间 UTC',
  PRIMARY KEY(checkpoint_id), UNIQUE KEY uk_saas_checkpoint(application_id,step_code), KEY idx_saas_checkpoint_tenant(tenant_id,completed_at),
  CONSTRAINT fk_saas_checkpoint_app FOREIGN KEY(application_id) REFERENCES saas_merchant_application(application_id),
  CONSTRAINT ck_saas_checkpoint_hash CHECK(result_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='商户初始化可恢复 Saga 检查点';

CREATE TABLE saas_command_result (
  command_id CHAR(26) NOT NULL COMMENT '幂等命令 ULID', authority_scope VARCHAR(64) NOT NULL COMMENT 'PLATFORM 或可信 tenant_id 授权域',
  operation VARCHAR(64) NOT NULL COMMENT '具名命令操作', idempotency_key VARCHAR(64) NOT NULL COMMENT '调用方稳定幂等键',
  request_sha256 CHAR(64) NOT NULL COMMENT '命令内容 SHA-256', result_ref VARCHAR(64) NOT NULL COMMENT '稳定聚合结果引用',
  result_state VARCHAR(32) NOT NULL COMMENT '命令完成时稳定状态', created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  PRIMARY KEY(command_id), UNIQUE KEY uk_saas_command(authority_scope,operation,idempotency_key),
  CONSTRAINT ck_saas_command_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='SaaS 稳定幂等命令结果';

CREATE TABLE saas_quota_usage (
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号', feature_code VARCHAR(64) NOT NULL COMMENT '配额功能代码',
  used_count BIGINT NOT NULL COMMENT '已消费配额，单位由功能代码冻结', quota_limit BIGINT NOT NULL COMMENT '绑定版本配额上限',
  updated_at DATETIME(3) NOT NULL COMMENT '最近原子变更时间 UTC', PRIMARY KEY(tenant_id,feature_code),
  CONSTRAINT ck_saas_quota CHECK(used_count>=0 AND quota_limit>=0 AND used_count<=quota_limit)
) ENGINE=InnoDB COMMENT='租户权益配额原子使用投影';

CREATE TABLE saas_audit_event (
  audit_id CHAR(26) NOT NULL COMMENT '审计 ULID', tenant_id VARCHAR(20) NULL COMMENT '可信租户号，平台申请阶段为空',
  aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型', aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合稳定标识',
  action_code VARCHAR(64) NOT NULL COMMENT '具名操作代码', result VARCHAR(16) NOT NULL COMMENT '操作结果',
  request_sha256 CHAR(64) NOT NULL COMMENT '脱敏命令内容 SHA-256', correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
  actor_user_id BIGINT NOT NULL COMMENT '可信平台操作者主键', masked_summary VARCHAR(256) NOT NULL COMMENT '不含 Secret/PII 的摘要',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC', PRIMARY KEY(audit_id), KEY idx_saas_audit(tenant_id,occurred_at),
  CONSTRAINT ck_saas_audit_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='SaaS 关键操作只追加审计';

CREATE TABLE saas_outbox (
  outbox_id CHAR(26) NOT NULL COMMENT 'Outbox 事件 ULID', tenant_id VARCHAR(20) NULL COMMENT '可信租户号，平台事实可为空',
  aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型', aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合稳定标识',
  event_type VARCHAR(96) NOT NULL COMMENT '版本化事件类型', schema_version VARCHAR(16) NOT NULL COMMENT '事件 Schema 版本',
  payload_json JSON NOT NULL COMMENT '不含 Secret/PII 的不可变事件载荷', payload_sha256 CHAR(64) NOT NULL COMMENT '载荷 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识', delivery_state VARCHAR(16) NOT NULL COMMENT 'PENDING/SENT/DEAD',
  attempts INT NOT NULL COMMENT '投递尝试次数', created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  PRIMARY KEY(outbox_id), KEY idx_saas_outbox_delivery(delivery_state,created_at),
  CONSTRAINT ck_saas_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$'), CONSTRAINT ck_saas_outbox_attempt CHECK(attempts>=0)
) ENGINE=InnoDB COMMENT='SaaS 版本化事件 Outbox';

CREATE TRIGGER trg_saas_state_no_update BEFORE UPDATE ON saas_application_state_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas application state history is append only';
CREATE TRIGGER trg_saas_state_no_delete BEFORE DELETE ON saas_application_state_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas application state history cannot be deleted';
CREATE TRIGGER trg_saas_lifecycle_no_update BEFORE UPDATE ON saas_tenant_lifecycle_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas lifecycle history is append only';
CREATE TRIGGER trg_saas_lifecycle_no_delete BEFORE DELETE ON saas_tenant_lifecycle_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas lifecycle history cannot be deleted';
CREATE TRIGGER trg_saas_audit_no_update BEFORE UPDATE ON saas_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas audit is append only';
CREATE TRIGGER trg_saas_audit_no_delete BEFORE DELETE ON saas_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas audit cannot be deleted';
