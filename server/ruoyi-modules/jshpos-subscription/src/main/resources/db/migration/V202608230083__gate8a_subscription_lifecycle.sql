-- T2-SUB-001 订阅期限、只追加状态、提醒意图、调度检查点与 SaaS 受控访问投影。
CREATE TABLE sub_subscription (
  subscription_id CHAR(26) NOT NULL COMMENT '订阅 ULID，由 Subscription Owner 生成',
  tenant_id VARCHAR(20) NOT NULL COMMENT 'SaaS 正式端口确认的可信租户号',
  plan_id BIGINT NOT NULL COMMENT '创建时冻结的 SaaS 套餐 BIGINT 引用',
  entitlement_version_id CHAR(26) NOT NULL COMMENT '创建时冻结的 SaaS 权益版本 ULID 引用',
  contract_ref VARCHAR(128) NOT NULL COMMENT '合同系统不透明引用，不保存合同原文',
  external_order_ref VARCHAR(128) NOT NULL COMMENT '外部商业订单不透明引用，不代表资金成功',
  state VARCHAR(32) NOT NULL COMMENT '订阅具名当前状态投影',
  state_version INT NOT NULL COMMENT '状态乐观锁版本，从零单调递增',
  current_term_version INT NOT NULL COMMENT '当前期限版本，从一单调递增',
  starts_at DATETIME(3) NOT NULL COMMENT '当前订阅期限开始时间 UTC',
  ends_at DATETIME(3) NOT NULL COMMENT '当前订阅期限结束时间 UTC',
  grace_ends_at DATETIME(3) NOT NULL COMMENT '当前宽限期结束时间 UTC',
  business_time_zone VARCHAR(64) NOT NULL COMMENT 'IANA 业务时区，仅用于解释边界',
  degradation_policy_version VARCHAR(64) NOT NULL COMMENT '受控降级白名单策略版本',
  content_sha256 CHAR(64) NOT NULL COMMENT '当前投影内容 SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近变更时间 UTC',
  PRIMARY KEY(subscription_id), UNIQUE KEY uk_sub_tenant(tenant_id),
  KEY idx_sub_due(state,ends_at,grace_ends_at), KEY idx_sub_plan(plan_id,entitlement_version_id),
  CONSTRAINT ck_sub_state CHECK(state IN ('DRAFT','PENDING_ACTIVATION','ACTIVE','GRACE_PERIOD','SUSPENDED','EXPIRED','TERMINATION_PENDING','TERMINATED','RESTORED')),
  CONSTRAINT ck_sub_window CHECK(ends_at>starts_at AND grace_ends_at>=ends_at),
  CONSTRAINT ck_sub_versions CHECK(state_version>=0 AND current_term_version>0),
  CONSTRAINT ck_sub_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='商户订阅当前受控投影';

CREATE TABLE sub_subscription_term (
  term_id CHAR(26) NOT NULL COMMENT '期限事实 ULID', subscription_id CHAR(26) NOT NULL COMMENT '订阅 ULID',
  term_version INT NOT NULL COMMENT '订阅内只增期限版本', starts_at DATETIME(3) NOT NULL COMMENT '期限开始 UTC',
  ends_at DATETIME(3) NOT NULL COMMENT '期限结束 UTC', grace_ends_at DATETIME(3) NOT NULL COMMENT '宽限结束 UTC',
  business_time_zone VARCHAR(64) NOT NULL COMMENT '冻结 IANA 业务时区', contract_ref VARCHAR(128) NOT NULL COMMENT '该期限合同不透明引用',
  external_order_ref VARCHAR(128) NOT NULL COMMENT '该期限外部订单不透明引用', term_sha256 CHAR(64) NOT NULL COMMENT '期限内容 SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '追加时间 UTC', PRIMARY KEY(term_id),
  UNIQUE KEY uk_sub_term(subscription_id,term_version), KEY idx_sub_term_window(subscription_id,ends_at),
  CONSTRAINT fk_sub_term_header FOREIGN KEY(subscription_id) REFERENCES sub_subscription(subscription_id),
  CONSTRAINT ck_sub_term_window CHECK(ends_at>starts_at AND grace_ends_at>=ends_at),
  CONSTRAINT ck_sub_term_hash CHECK(term_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='订阅只追加期限版本';

CREATE TABLE sub_subscription_state_event (
  event_id CHAR(26) NOT NULL COMMENT '状态事件 ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  subscription_id CHAR(26) NOT NULL COMMENT '订阅 ULID', from_state VARCHAR(32) NULL COMMENT '迁移前状态，创建事实为空',
  to_state VARCHAR(32) NOT NULL COMMENT '迁移后状态', state_version INT NOT NULL COMMENT '迁移后状态版本',
  term_version INT NOT NULL COMMENT '迁移时冻结期限版本', action_code VARCHAR(64) NOT NULL COMMENT '具名命令代码',
  reason VARCHAR(256) NOT NULL COMMENT '不含 PII/Secret 的原因', request_sha256 CHAR(64) NOT NULL COMMENT '状态事实 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识', actor_user_id BIGINT NOT NULL COMMENT '操作者主键，系统任务为零',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC', PRIMARY KEY(event_id),
  UNIQUE KEY uk_sub_state_version(subscription_id,state_version), KEY idx_sub_state_tenant(tenant_id,occurred_at),
  CONSTRAINT fk_sub_state_header FOREIGN KEY(subscription_id) REFERENCES sub_subscription(subscription_id),
  CONSTRAINT ck_sub_state_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='订阅只追加状态事实';

CREATE TABLE sub_notification_intent (
  intent_id CHAR(26) NOT NULL COMMENT '通知意图 ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  subscription_id CHAR(26) NOT NULL COMMENT '订阅 ULID', term_version INT NOT NULL COMMENT '绑定期限版本',
  notification_type VARCHAR(64) NOT NULL COMMENT '到期提醒类型，不表示真实发送', scheduled_at DATETIME(3) NOT NULL COMMENT '计划提醒时间 UTC',
  payload_sha256 CHAR(64) NOT NULL COMMENT '脱敏提醒载荷 SHA-256', intent_state VARCHAR(16) NOT NULL COMMENT 'PLANNED，只追加；实际投递状态由 Outbox 消费方另行留痕',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC', PRIMARY KEY(intent_id),
  UNIQUE KEY uk_sub_notice(subscription_id,term_version,notification_type), KEY idx_sub_notice_due(intent_state,scheduled_at),
  CONSTRAINT fk_sub_notice_header FOREIGN KEY(subscription_id) REFERENCES sub_subscription(subscription_id),
  CONSTRAINT ck_sub_notice_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_sub_notice_state CHECK(intent_state='PLANNED')
) ENGINE=InnoDB COMMENT='订阅只追加通知意图，未装配真实渠道';

CREATE TABLE sub_schedule_checkpoint (
  job_code VARCHAR(64) NOT NULL COMMENT '具名订阅任务代码', last_scanned_at DATETIME(3) NULL COMMENT '最近完成扫描时间 UTC',
  lease_owner VARCHAR(64) NULL COMMENT '当前租约执行器标识', lease_until DATETIME(3) NULL COMMENT '租约到期时间 UTC',
  last_result_sha256 CHAR(64) NULL COMMENT '最近扫描结果 SHA-256', record_version INT NOT NULL COMMENT '租约检查点乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC', updated_at DATETIME(3) NOT NULL COMMENT '最近变更时间 UTC',
  PRIMARY KEY(job_code), KEY idx_sub_job_lease(lease_until),
  CONSTRAINT ck_sub_job_hash CHECK(last_result_sha256 IS NULL OR last_result_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='订阅任务持久化租约与单调检查点';

CREATE TABLE sub_command_result (
  command_id CHAR(26) NOT NULL COMMENT '幂等命令 ULID', authority_scope VARCHAR(64) NOT NULL COMMENT '平台或可信租户授权域',
  operation VARCHAR(64) NOT NULL COMMENT '具名订阅操作', idempotency_key VARCHAR(64) NOT NULL COMMENT '稳定幂等键',
  request_sha256 CHAR(64) NOT NULL COMMENT '请求内容 SHA-256', result_ref VARCHAR(64) NOT NULL COMMENT '稳定订阅结果引用',
  result_state VARCHAR(32) NOT NULL COMMENT '命令完成状态', created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  PRIMARY KEY(command_id), UNIQUE KEY uk_sub_command(authority_scope,operation,idempotency_key),
  CONSTRAINT ck_sub_command_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='订阅稳定幂等命令结果';

CREATE TABLE sub_audit_event (
  audit_id CHAR(26) NOT NULL COMMENT '审计 ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  subscription_id CHAR(26) NOT NULL COMMENT '订阅 ULID', action_code VARCHAR(64) NOT NULL COMMENT '具名操作代码',
  result VARCHAR(16) NOT NULL COMMENT '操作结果', request_sha256 CHAR(64) NOT NULL COMMENT '脱敏请求 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识', actor_user_id BIGINT NOT NULL COMMENT '操作者主键，系统任务为零',
  masked_summary VARCHAR(256) NOT NULL COMMENT '不含 Secret/PII 的中文摘要', occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC',
  PRIMARY KEY(audit_id), KEY idx_sub_audit(tenant_id,occurred_at),
  CONSTRAINT ck_sub_audit_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='订阅关键操作只追加审计';

CREATE TABLE sub_outbox (
  outbox_id CHAR(26) NOT NULL COMMENT 'Outbox ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  subscription_id CHAR(26) NOT NULL COMMENT '订阅 ULID', event_type VARCHAR(96) NOT NULL COMMENT '版本化订阅事件类型',
  schema_version VARCHAR(16) NOT NULL COMMENT '事件 Schema 版本', payload_json JSON NOT NULL COMMENT '脱敏不可变事件载荷',
  payload_sha256 CHAR(64) NOT NULL COMMENT '事件载荷 SHA-256', correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
  delivery_state VARCHAR(16) NOT NULL COMMENT 'PENDING/SENT/DEAD', attempts INT NOT NULL COMMENT '投递尝试次数',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC', PRIMARY KEY(outbox_id), KEY idx_sub_outbox(delivery_state,created_at),
  CONSTRAINT ck_sub_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$'), CONSTRAINT ck_sub_outbox_attempts CHECK(attempts>=0)
) ENGINE=InnoDB COMMENT='订阅版本化事件 Outbox';

CREATE TABLE saas_subscription_access (
  tenant_id VARCHAR(20) NOT NULL COMMENT 'SaaS 可信租户号', subscription_id CHAR(26) NOT NULL COMMENT '绑定 Subscription ULID',
  access_mode VARCHAR(32) NOT NULL COMMENT 'NORMAL/GRACE/RECOVERY_ONLY/TERMINATED_RECOVERY',
  source_version INT NOT NULL COMMENT 'Subscription 状态来源版本', source_sha256 CHAR(64) NOT NULL COMMENT '来源状态事实 SHA-256',
  record_version INT NOT NULL COMMENT 'SaaS 投影乐观锁版本', created_at DATETIME(3) NOT NULL COMMENT '首次绑定时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近切换时间 UTC', PRIMARY KEY(tenant_id), UNIQUE KEY uk_saas_sub_access_sub(subscription_id),
  CONSTRAINT ck_saas_sub_access_mode CHECK(access_mode IN ('NORMAL','GRACE','RECOVERY_ONLY','TERMINATED_RECOVERY')),
  CONSTRAINT ck_saas_sub_access_hash CHECK(source_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='SaaS Owner 订阅访问模式受控投影';

CREATE TABLE saas_subscription_access_event (
  event_id CHAR(26) NOT NULL COMMENT '访问模式事件 ULID', tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  subscription_id CHAR(26) NOT NULL COMMENT 'Subscription ULID', from_mode VARCHAR(32) NULL COMMENT '切换前模式，首次为空',
  to_mode VARCHAR(32) NOT NULL COMMENT '切换后模式', source_version INT NOT NULL COMMENT 'Subscription 来源状态版本',
  source_sha256 CHAR(64) NOT NULL COMMENT '来源状态事实 SHA-256', correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC', PRIMARY KEY(event_id),
  UNIQUE KEY uk_saas_sub_access_event(tenant_id,subscription_id,source_version), KEY idx_saas_sub_access_time(tenant_id,occurred_at),
  CONSTRAINT ck_saas_sub_access_event_hash CHECK(source_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='SaaS 订阅访问模式只追加事实';

CREATE TRIGGER trg_sub_term_no_update BEFORE UPDATE ON sub_subscription_term FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription term is append only';
CREATE TRIGGER trg_sub_term_no_delete BEFORE DELETE ON sub_subscription_term FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription term cannot be deleted';
CREATE TRIGGER trg_sub_state_no_update BEFORE UPDATE ON sub_subscription_state_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription state history is append only';
CREATE TRIGGER trg_sub_state_no_delete BEFORE DELETE ON sub_subscription_state_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription state history cannot be deleted';
CREATE TRIGGER trg_sub_notice_no_update BEFORE UPDATE ON sub_notification_intent FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription notification intent is append only';
CREATE TRIGGER trg_sub_notice_no_delete BEFORE DELETE ON sub_notification_intent FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription notification intent cannot be deleted';
CREATE TRIGGER trg_sub_audit_no_update BEFORE UPDATE ON sub_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription audit is append only';
CREATE TRIGGER trg_sub_audit_no_delete BEFORE DELETE ON sub_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription audit cannot be deleted';
CREATE TRIGGER trg_saas_sub_access_no_update BEFORE UPDATE ON saas_subscription_access_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas subscription access event is append only';
CREATE TRIGGER trg_saas_sub_access_no_delete BEFORE DELETE ON saas_subscription_access_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='saas subscription access event cannot be deleted';
