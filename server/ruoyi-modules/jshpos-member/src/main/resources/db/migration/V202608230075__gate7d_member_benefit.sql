CREATE TABLE mbr_benefit_policy (
  policy_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益策略ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  policy_code VARCHAR(64) NOT NULL COMMENT '租户内稳定策略编码',
  display_name VARCHAR(100) NOT NULL COMMENT '权益策略展示名',
  created_by BIGINT NOT NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,policy_id),
  UNIQUE KEY uk_mbr_benefit_policy_code (tenant_id,policy_code),
  CONSTRAINT ck_mbr_benefit_policy_actor CHECK (created_by>0)
) ENGINE=InnoDB COMMENT='T2-MEM-003会员权益策略根；HYBRID';

CREATE TABLE mbr_benefit_version (
  version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益版本ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  policy_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益策略ULID',
  version_no INT NOT NULL COMMENT '策略内版本号',
  state VARCHAR(16) NOT NULL COMMENT '具名版本状态',
  default_combination_policy VARCHAR(24) NOT NULL COMMENT '默认组合策略',
  allow_stacking TINYINT(1) NOT NULL COMMENT '本版本是否允许双向显式叠加',
  effective_at DATETIME(3) NULL COMMENT '生效时间UTC',
  expires_at DATETIME(3) NULL COMMENT '失效时间UTC',
  revocation_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '撤回纪元',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '批准内容摘要',
  created_by BIGINT NOT NULL COMMENT '创建人',
  approved_by BIGINT NULL COMMENT '独立批准人',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后状态变化UTC',
  PRIMARY KEY (tenant_id,version_id),
  UNIQUE KEY uk_mbr_benefit_version_no (tenant_id,policy_id,version_no),
  KEY idx_mbr_benefit_state_window (tenant_id,state,effective_at,expires_at),
  CONSTRAINT fk_mbr_benefit_version_policy FOREIGN KEY (tenant_id,policy_id) REFERENCES mbr_benefit_policy(tenant_id,policy_id),
  CONSTRAINT ck_mbr_benefit_version_state CHECK (state IN ('DRAFT','VALIDATED','APPROVED','SCHEDULED','ACTIVE','PAUSED','RETIRED','REVOKED')),
  CONSTRAINT ck_mbr_benefit_combination CHECK (default_combination_policy='BEST_PRICE'),
  CONSTRAINT ck_mbr_benefit_window CHECK (expires_at IS NULL OR (effective_at IS NOT NULL AND expires_at>effective_at)),
  CONSTRAINT ck_mbr_benefit_actors CHECK (created_by>0 AND (approved_by IS NULL OR (approved_by>0 AND approved_by<>created_by)))
) ENGINE=InnoDB COMMENT='T2-MEM-003版本化权益控制面；XML_ONLY';

CREATE TABLE mbr_benefit_scope (
  scope_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '门店范围ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益版本ULID',
  store_id BIGINT NOT NULL COMMENT '适用门店',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '范围内容摘要',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,scope_id),
  UNIQUE KEY uk_mbr_benefit_scope (tenant_id,version_id,store_id),
  KEY idx_mbr_benefit_scope_store (tenant_id,store_id,version_id),
  CONSTRAINT fk_mbr_benefit_scope_version FOREIGN KEY (tenant_id,version_id) REFERENCES mbr_benefit_version(tenant_id,version_id),
  CONSTRAINT fk_mbr_benefit_scope_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id)
) ENGINE=InnoDB COMMENT='T2-MEM-003权益版本门店范围不可变事实；XML_ONLY';

CREATE TABLE mbr_benefit_level_mapping (
  mapping_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '等级映射ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益版本ULID',
  level_code VARCHAR(32) NOT NULL COMMENT '会员等级编码',
  member_price_eligible TINYINT(1) NOT NULL COMMENT '是否允许解析会员价候选',
  stacking_allowed TINYINT(1) NOT NULL COMMENT '权益侧是否显式允许叠加',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '映射内容摘要',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,mapping_id),
  UNIQUE KEY uk_mbr_benefit_level (tenant_id,version_id,level_code),
  KEY idx_mbr_benefit_level_lookup (tenant_id,level_code,version_id),
  CONSTRAINT fk_mbr_benefit_mapping_version FOREIGN KEY (tenant_id,version_id) REFERENCES mbr_benefit_version(tenant_id,version_id)
) ENGINE=InnoDB COMMENT='T2-MEM-003权益版本等级映射不可变事实；XML_ONLY';

CREATE TABLE mbr_entitlement_snapshot (
  snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益快照ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Member Owner内部会员ULID',
  member_ref_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '跨Owner不可逆会员引用',
  level_history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原等级历史ULID',
  level_code VARCHAR(32) NOT NULL COMMENT '冻结等级编码',
  benefit_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益版本ULID',
  store_id BIGINT NOT NULL COMMENT '适用门店',
  member_price_eligible TINYINT(1) NOT NULL COMMENT '是否允许会员价',
  stacking_allowed TINYINT(1) NOT NULL COMMENT '权益侧叠加开关',
  effective_at DATETIME(3) NOT NULL COMMENT '快照生效时间UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '快照失效时间UTC',
  revocation_epoch BIGINT NOT NULL COMMENT '发行时撤回纪元',
  rights_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最小权益集摘要',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '快照内容摘要',
  issued_at DATETIME(3) NOT NULL COMMENT '发行时间UTC',
  PRIMARY KEY (tenant_id,snapshot_id),
  KEY idx_mbr_entitlement_member (tenant_id,member_id,issued_at,snapshot_id),
  KEY idx_mbr_entitlement_store_expiry (tenant_id,store_id,expires_at,snapshot_id),
  CONSTRAINT fk_mbr_entitlement_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT fk_mbr_entitlement_version FOREIGN KEY (tenant_id,benefit_version_id) REFERENCES mbr_benefit_version(tenant_id,version_id),
  CONSTRAINT ck_mbr_entitlement_window CHECK (expires_at>effective_at)
) ENGINE=InnoDB COMMENT='T2-MEM-003只追加最小权益快照；XML_ONLY';

CREATE TABLE mbr_benefit_state_event (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益版本ULID',
  from_state VARCHAR(16) NULL COMMENT '原状态',
  to_state VARCHAR(16) NOT NULL COMMENT '目标状态',
  reason_code VARCHAR(64) NOT NULL COMMENT '结构化原因码',
  reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原因摘要',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件内容摘要',
  PRIMARY KEY (tenant_id,event_id),
  KEY idx_mbr_benefit_state_version (tenant_id,version_id,occurred_at,event_id),
  CONSTRAINT fk_mbr_benefit_state_version FOREIGN KEY (tenant_id,version_id) REFERENCES mbr_benefit_version(tenant_id,version_id),
  CONSTRAINT ck_mbr_benefit_state_actor CHECK (actor_user_id>0)
) ENGINE=InnoDB COMMENT='T2-MEM-003只追加权益状态历史；XML_ONLY';

CREATE TABLE mbr_benefit_command (
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令记录ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  command_type VARCHAR(64) NOT NULL COMMENT '命令类型',
  idempotency_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等键ULID',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果聚合ULID',
  result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果摘要',
  result_json JSON NOT NULL COMMENT '无PII结果最小JSON',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,command_id),
  UNIQUE KEY uk_mbr_benefit_idempotency (tenant_id,command_type,idempotency_key)
) ENGINE=InnoDB COMMENT='T2-MEM-003命令幂等与稳定结果；XML_ONLY';

CREATE TABLE mbr_benefit_audit_event (
  audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权益审计ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  action VARCHAR(64) NOT NULL COMMENT '权益动作',
  target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标ULID',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作人',
  reason_code VARCHAR(64) NOT NULL COMMENT '结构化原因码',
  summary_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审计摘要',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  PRIMARY KEY (tenant_id,audit_id),
  KEY idx_mbr_benefit_audit_target (tenant_id,target_id,occurred_at,audit_id),
  CONSTRAINT ck_mbr_benefit_audit_actor CHECK (actor_user_id>0)
) ENGINE=InnoDB COMMENT='T2-MEM-003只追加权益审计；XML_ONLY';

CREATE TABLE mbr_benefit_outbox (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Outbox事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  event_type VARCHAR(96) NOT NULL COMMENT '版本化事件类型',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '聚合ULID',
  aggregate_version INT NOT NULL COMMENT '聚合版本',
  payload_json JSON NOT NULL COMMENT '无PII事件载荷',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件载荷摘要',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态',
  occurred_at DATETIME(3) NOT NULL COMMENT '业务发生时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,event_id),
  KEY idx_mbr_benefit_outbox_delivery (tenant_id,status,occurred_at,event_id),
  CONSTRAINT ck_mbr_benefit_outbox_status CHECK (status IN ('PENDING','SENDING','SENT','DEAD'))
) ENGINE=InnoDB COMMENT='T2-MEM-003权益事件Outbox；XML_ONLY';

DELIMITER $$
CREATE TRIGGER trg_mbr_benefit_scope_no_update BEFORE UPDATE ON mbr_benefit_scope FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit scope is immutable'; END$$
CREATE TRIGGER trg_mbr_benefit_scope_no_delete BEFORE DELETE ON mbr_benefit_scope FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit scope cannot be deleted'; END$$
CREATE TRIGGER trg_mbr_benefit_mapping_no_update BEFORE UPDATE ON mbr_benefit_level_mapping FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit level mapping is immutable'; END$$
CREATE TRIGGER trg_mbr_benefit_mapping_no_delete BEFORE DELETE ON mbr_benefit_level_mapping FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit level mapping cannot be deleted'; END$$
CREATE TRIGGER trg_mbr_entitlement_no_update BEFORE UPDATE ON mbr_entitlement_snapshot FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='entitlement snapshot is immutable'; END$$
CREATE TRIGGER trg_mbr_entitlement_no_delete BEFORE DELETE ON mbr_entitlement_snapshot FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='entitlement snapshot cannot be deleted'; END$$
CREATE TRIGGER trg_mbr_benefit_state_no_update BEFORE UPDATE ON mbr_benefit_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit state event is immutable'; END$$
CREATE TRIGGER trg_mbr_benefit_state_no_delete BEFORE DELETE ON mbr_benefit_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit state event cannot be deleted'; END$$
CREATE TRIGGER trg_mbr_benefit_audit_no_update BEFORE UPDATE ON mbr_benefit_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit audit is immutable'; END$$
CREATE TRIGGER trg_mbr_benefit_audit_no_delete BEFORE DELETE ON mbr_benefit_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='benefit audit cannot be deleted'; END$$
DELIMITER ;
