-- T2-SVC-001 服务目录、实施项目、工单、附件元数据、审计与 Outbox。
CREATE TABLE svc_catalog_version (
  catalog_id CHAR(26) NOT NULL COMMENT '服务目录版本 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  catalog_code VARCHAR(64) NOT NULL COMMENT '租户内稳定目录代码',
  version_no INT NOT NULL COMMENT '目录正整数版本号',
  industry_template VARCHAR(64) NOT NULL COMMENT '适用行业模板代码',
  catalog_name VARCHAR(128) NOT NULL COMMENT '目录名称',
  state VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED',
  content_sha256 CHAR(64) NOT NULL COMMENT '目录内容 SHA-256',
  creator_user_id BIGINT NOT NULL COMMENT '创建人员主键',
  publisher_user_id BIGINT NULL COMMENT '发布人员主键',
  published_at DATETIME(3) NULL COMMENT '发布时间 UTC',
  record_version INT NOT NULL COMMENT '乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (catalog_id),
  UNIQUE KEY uk_svc_catalog_version (tenant_id,catalog_code,version_no),
  KEY idx_svc_catalog_state (tenant_id,state,created_at),
  CONSTRAINT ck_svc_catalog_version CHECK(version_no>0 AND record_version>=0),
  CONSTRAINT ck_svc_catalog_state CHECK(state IN ('DRAFT','PUBLISHED')),
  CONSTRAINT ck_svc_catalog_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='版本化服务目录头';

CREATE TABLE svc_catalog_item (
  item_id CHAR(26) NOT NULL COMMENT '服务目录项 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  catalog_id CHAR(26) NOT NULL COMMENT '所属服务目录版本 ULID',
  item_code VARCHAR(64) NOT NULL COMMENT '目录版本内稳定检查项代码',
  item_name VARCHAR(128) NOT NULL COMMENT '检查项名称',
  mandatory TINYINT(1) NOT NULL COMMENT '是否必需检查项',
  sequence_no INT NOT NULL COMMENT '显示顺序',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  PRIMARY KEY (item_id),
  UNIQUE KEY uk_svc_catalog_item (tenant_id,catalog_id,item_code),
  KEY idx_svc_catalog_item_order (tenant_id,catalog_id,sequence_no),
  CONSTRAINT fk_svc_catalog_item_header FOREIGN KEY(catalog_id) REFERENCES svc_catalog_version(catalog_id),
  CONSTRAINT ck_svc_catalog_item_sequence CHECK(sequence_no>=0)
) ENGINE=InnoDB COMMENT='发布后不可变的服务目录检查项';

CREATE TABLE svc_implementation_project (
  project_id CHAR(26) NOT NULL COMMENT '实施项目 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  store_id BIGINT NOT NULL COMMENT '可信门店主键',
  catalog_id CHAR(26) NOT NULL COMMENT '冻结服务目录版本 ULID',
  state VARCHAR(32) NOT NULL COMMENT '实施项目具名状态',
  owner_user_id BIGINT NOT NULL COMMENT '项目责任人员主键',
  target_date DATE NOT NULL COMMENT '内部目标日期，不构成合同 SLA',
  record_version INT NOT NULL COMMENT '乐观锁版本',
  content_sha256 CHAR(64) NOT NULL COMMENT '当前投影内容 SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (project_id),
  KEY idx_svc_project_store (tenant_id,store_id,state,created_at),
  KEY idx_svc_project_owner (tenant_id,owner_user_id,state),
  CONSTRAINT fk_svc_project_catalog FOREIGN KEY(catalog_id) REFERENCES svc_catalog_version(catalog_id),
  CONSTRAINT ck_svc_project_state CHECK(state IN ('DRAFT','PREFLIGHTING','PREFLIGHT_FAILED','READY','IN_PROGRESS','BLOCKED','READY_TO_HANDOVER','HANDED_OVER','CANCELLED')),
  CONSTRAINT ck_svc_project_version CHECK(record_version>=0),
  CONSTRAINT ck_svc_project_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='标准实施项目受控投影';

CREATE TABLE svc_project_check_item (
  check_id CHAR(26) NOT NULL COMMENT '项目检查项 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  project_id CHAR(26) NOT NULL COMMENT '实施项目 ULID',
  source_item_id CHAR(26) NOT NULL COMMENT '来源服务目录项 ULID',
  item_code VARCHAR(64) NOT NULL COMMENT '冻结检查项代码',
  item_name VARCHAR(128) NOT NULL COMMENT '冻结检查项名称',
  mandatory TINYINT(1) NOT NULL COMMENT '冻结是否必需',
  sequence_no INT NOT NULL COMMENT '冻结显示顺序',
  state VARCHAR(16) NOT NULL COMMENT 'PENDING/COMPLETED',
  completed_by BIGINT NULL COMMENT '完成人员主键',
  completion_note VARCHAR(512) NULL COMMENT '不含敏感信息的完成说明',
  completed_at DATETIME(3) NULL COMMENT '完成时间 UTC',
  record_version INT NOT NULL COMMENT '乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (check_id),
  UNIQUE KEY uk_svc_project_check (tenant_id,project_id,item_code),
  KEY idx_svc_project_check_state (tenant_id,project_id,state,sequence_no),
  CONSTRAINT fk_svc_project_check_header FOREIGN KEY(project_id) REFERENCES svc_implementation_project(project_id),
  CONSTRAINT ck_svc_project_check_state CHECK(state IN ('PENDING','COMPLETED')),
  CONSTRAINT ck_svc_project_check_version CHECK(record_version>=0)
) ENGINE=InnoDB COMMENT='实施项目冻结检查项';

CREATE TABLE svc_work_order (
  ticket_id CHAR(26) NOT NULL COMMENT '服务工单 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  store_id BIGINT NOT NULL COMMENT '可信门店主键',
  project_id CHAR(26) NULL COMMENT '可选关联实施项目 ULID',
  service_type VARCHAR(64) NOT NULL COMMENT '服务类型代码',
  priority VARCHAR(16) NOT NULL COMMENT 'P0/P1/P2/P3 内部优先级',
  subject VARCHAR(128) NOT NULL COMMENT '不含敏感信息的工单主题',
  description VARCHAR(1024) NOT NULL COMMENT '不含 Secret/PII 的工单说明',
  state VARCHAR(32) NOT NULL COMMENT '工单具名状态',
  assignee_user_id BIGINT NULL COMMENT '当前责任人员主键',
  lease_until DATETIME(3) NULL COMMENT '当前认领租约到期时间 UTC',
  resolved_by BIGINT NULL COMMENT '解决人员主键',
  closed_by BIGINT NULL COMMENT '独立复核关闭人员主键',
  resolution_summary VARCHAR(512) NULL COMMENT '不含敏感信息的解决摘要',
  target_at DATETIME(3) NULL COMMENT '内部时间目标 UTC，不构成合同 SLA',
  record_version INT NOT NULL COMMENT '乐观锁版本',
  content_sha256 CHAR(64) NOT NULL COMMENT '当前投影内容 SHA-256',
  creator_user_id BIGINT NOT NULL COMMENT '创建人员主键',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (ticket_id),
  KEY idx_svc_ticket_queue (tenant_id,store_id,state,priority,created_at),
  KEY idx_svc_ticket_assignee (tenant_id,assignee_user_id,state,lease_until),
  KEY idx_svc_ticket_project (tenant_id,project_id),
  CONSTRAINT ck_svc_ticket_priority CHECK(priority IN ('P0','P1','P2','P3')),
  CONSTRAINT ck_svc_ticket_state CHECK(state IN ('OPEN','ASSIGNED','IN_PROGRESS','WAITING_INPUT','RESOLVED','CLOSED','REOPENED','CANCELLED')),
  CONSTRAINT ck_svc_ticket_version CHECK(record_version>=0),
  CONSTRAINT ck_svc_ticket_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='服务工单受控当前投影';

CREATE TABLE svc_work_order_history (
  history_id CHAR(26) NOT NULL COMMENT '服务状态历史 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  store_id BIGINT NULL COMMENT '可信门店主键，平台目录操作可为空',
  aggregate_type VARCHAR(32) NOT NULL COMMENT 'CATALOG/PROJECT/TICKET/ATTACHMENT',
  aggregate_id CHAR(26) NOT NULL COMMENT '聚合 ULID',
  action_code VARCHAR(64) NOT NULL COMMENT '具名操作代码',
  from_state VARCHAR(32) NULL COMMENT '操作前状态',
  to_state VARCHAR(32) NULL COMMENT '操作后状态',
  from_user_id BIGINT NULL COMMENT '原责任人员主键',
  to_user_id BIGINT NULL COMMENT '新责任人员主键',
  note VARCHAR(512) NOT NULL COMMENT '不含敏感信息的处理记录',
  request_sha256 CHAR(64) NOT NULL COMMENT '脱敏请求 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
  actor_user_id BIGINT NOT NULL COMMENT '操作者主键',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC',
  PRIMARY KEY (history_id),
  KEY idx_svc_history_aggregate (tenant_id,aggregate_type,aggregate_id,occurred_at),
  KEY idx_svc_history_store (tenant_id,store_id,occurred_at),
  CONSTRAINT ck_svc_history_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='服务责任流转与处理记录只追加历史';

CREATE TABLE svc_attachment (
  attachment_id CHAR(26) NOT NULL COMMENT '附件元数据 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  store_id BIGINT NOT NULL COMMENT '可信门店主键',
  ticket_id CHAR(26) NOT NULL COMMENT '所属工单 ULID',
  object_key VARCHAR(512) NOT NULL COMMENT '服务端生成的受控对象存储键',
  file_name VARCHAR(255) NOT NULL COMMENT '安全化展示文件名',
  media_type VARCHAR(128) NOT NULL COMMENT '白名单媒体类型',
  size_bytes BIGINT NOT NULL COMMENT '附件正文大小字节数',
  sha256 CHAR(64) NOT NULL COMMENT '附件正文 SHA-256',
  state VARCHAR(16) NOT NULL COMMENT 'STORED/CLEANED',
  uploader_user_id BIGINT NOT NULL COMMENT '上传人员主键',
  retention_until DATETIME(3) NOT NULL COMMENT '最早清理时间 UTC',
  cleaned_at DATETIME(3) NULL COMMENT '正文清理时间 UTC',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间 UTC',
  PRIMARY KEY (attachment_id),
  UNIQUE KEY uk_svc_attachment_object (object_key),
  KEY idx_svc_attachment_ticket (tenant_id,ticket_id,state,created_at),
  KEY idx_svc_attachment_cleanup (tenant_id,state,retention_until),
  CONSTRAINT fk_svc_attachment_ticket FOREIGN KEY(ticket_id) REFERENCES svc_work_order(ticket_id),
  CONSTRAINT ck_svc_attachment_size CHECK(size_bytes>0 AND size_bytes<=10485760),
  CONSTRAINT ck_svc_attachment_state CHECK(state IN ('STORED','CLEANED')),
  CONSTRAINT ck_svc_attachment_hash CHECK(sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='服务工单附件受控元数据，不保存正文或公开链接';

CREATE TABLE svc_command_result (
  command_id CHAR(26) NOT NULL COMMENT '幂等命令结果 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  operation_code VARCHAR(64) NOT NULL COMMENT '具名服务操作代码',
  idempotency_key VARCHAR(64) NOT NULL COMMENT '稳定幂等键',
  request_sha256 CHAR(64) NOT NULL COMMENT '请求内容 SHA-256',
  result_type VARCHAR(32) NOT NULL COMMENT '稳定结果聚合类型',
  result_id CHAR(26) NOT NULL COMMENT '稳定结果聚合 ULID',
  result_state VARCHAR(32) NOT NULL COMMENT '命令完成时结果状态',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  PRIMARY KEY (command_id),
  UNIQUE KEY uk_svc_command (tenant_id,operation_code,idempotency_key),
  CONSTRAINT ck_svc_command_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='服务 Owner 只追加幂等命令结果';

CREATE TABLE svc_audit_event (
  audit_id CHAR(26) NOT NULL COMMENT '服务审计 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  store_id BIGINT NULL COMMENT '可信门店主键，平台目录操作可为空',
  aggregate_type VARCHAR(32) NOT NULL COMMENT '被审计聚合类型',
  aggregate_id CHAR(26) NOT NULL COMMENT '被审计聚合 ULID',
  action_code VARCHAR(64) NOT NULL COMMENT '具名操作代码',
  result_code VARCHAR(16) NOT NULL COMMENT '操作结果代码',
  request_sha256 CHAR(64) NOT NULL COMMENT '脱敏请求 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
  actor_user_id BIGINT NOT NULL COMMENT '操作者主键',
  summary VARCHAR(512) NOT NULL COMMENT '不含 Secret/PII 的中文摘要',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间 UTC',
  PRIMARY KEY (audit_id),
  KEY idx_svc_audit_scope (tenant_id,store_id,occurred_at),
  KEY idx_svc_audit_aggregate (tenant_id,aggregate_type,aggregate_id,occurred_at),
  CONSTRAINT ck_svc_audit_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='服务关键操作只追加审计';

CREATE TABLE svc_outbox (
  event_id CHAR(26) NOT NULL COMMENT '服务领域事件 ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户号',
  aggregate_type VARCHAR(32) NOT NULL COMMENT '事件聚合类型',
  aggregate_id CHAR(26) NOT NULL COMMENT '事件聚合 ULID',
  aggregate_version INT NOT NULL COMMENT '聚合状态版本',
  event_type VARCHAR(96) NOT NULL COMMENT '版本化服务事件类型',
  payload_json JSON NOT NULL COMMENT '脱敏不可变事件载荷',
  payload_sha256 CHAR(64) NOT NULL COMMENT '事件载荷 SHA-256',
  correlation_id VARCHAR(64) NOT NULL COMMENT '端到端关联标识',
  delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/DEAD',
  attempts INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
  occurred_at DATETIME(3) NOT NULL COMMENT '领域发生时间 UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Outbox 创建时间 UTC',
  PRIMARY KEY (event_id),
  KEY idx_svc_outbox_delivery (delivery_state,created_at),
  KEY idx_svc_outbox_aggregate (tenant_id,aggregate_type,aggregate_id,aggregate_version),
  CONSTRAINT ck_svc_outbox_version CHECK(aggregate_version>=0),
  CONSTRAINT ck_svc_outbox_attempts CHECK(attempts>=0),
  CONSTRAINT ck_svc_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='服务版本化事件 Outbox';

CREATE TRIGGER trg_svc_catalog_item_no_update BEFORE UPDATE ON svc_catalog_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service catalog item is append only';
CREATE TRIGGER trg_svc_catalog_item_no_delete BEFORE DELETE ON svc_catalog_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service catalog item cannot be deleted';
CREATE TRIGGER trg_svc_history_no_update BEFORE UPDATE ON svc_work_order_history FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service history is append only';
CREATE TRIGGER trg_svc_history_no_delete BEFORE DELETE ON svc_work_order_history FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service history cannot be deleted';
CREATE TRIGGER trg_svc_command_no_update BEFORE UPDATE ON svc_command_result FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service command result is append only';
CREATE TRIGGER trg_svc_command_no_delete BEFORE DELETE ON svc_command_result FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service command result cannot be deleted';
CREATE TRIGGER trg_svc_audit_no_update BEFORE UPDATE ON svc_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service audit is append only';
CREATE TRIGGER trg_svc_audit_no_delete BEFORE DELETE ON svc_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='service audit cannot be deleted';
