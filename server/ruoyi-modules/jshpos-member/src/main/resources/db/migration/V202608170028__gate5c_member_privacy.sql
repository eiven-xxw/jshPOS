CREATE TABLE mbr_member (
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID，不含业务含义',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '会员状态',
  display_alias VARCHAR(64) NOT NULL COMMENT '非真实姓名的脱敏显示别名',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by BIGINT NOT NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (tenant_id,member_id),
  CONSTRAINT ck_mbr_member_state CHECK (state IN ('ACTIVE','SUSPENDED','MERGED','ANONYMIZED'))
) ENGINE=InnoDB COMMENT='Gate 5C会员最小主体；XML_ONLY';

CREATE TABLE mbr_identity (
  identity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '身份绑定ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  identity_type VARCHAR(24) NOT NULL COMMENT '白名单身份类型',
  lookup_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '标准化身份HMAC-SHA256',
  cipher_text TEXT NOT NULL COMMENT '版本化AEAD身份密文',
  masked_value VARCHAR(32) NOT NULL COMMENT '只用于展示的掩码值',
  key_version INT NOT NULL COMMENT '外部密钥版本',
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '绑定状态',
  active_lookup_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN state='ACTIVE' THEN lookup_hmac ELSE NULL END) STORED
    COMMENT '仅活动身份唯一投影，允许撤销后重新绑定',
  bound_by BIGINT NOT NULL COMMENT '绑定操作者',
  bound_at DATETIME(3) NOT NULL COMMENT '绑定时间UTC',
  revoked_by BIGINT NULL COMMENT '撤销操作者',
  revoked_at DATETIME(3) NULL COMMENT '撤销时间UTC',
  PRIMARY KEY (tenant_id,identity_id),
  UNIQUE KEY uk_mbr_identity_active (tenant_id,identity_type,active_lookup_hmac),
  KEY idx_mbr_identity_member (tenant_id,member_id,state),
  CONSTRAINT fk_mbr_identity_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT ck_mbr_identity_type CHECK (identity_type IN ('MOBILE','MEMBER_CODE','CARD','EXTERNAL_OPEN_ID')),
  CONSTRAINT ck_mbr_identity_state CHECK (state IN ('ACTIVE','REVOKED')),
  CONSTRAINT ck_mbr_identity_key_version CHECK (key_version>0)
) ENGINE=InnoDB COMMENT='Gate 5C加密会员身份绑定；XML_ONLY';

CREATE TABLE mbr_consent_ledger (
  consent_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同意流水ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  consent_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次同意命令ULID',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  purpose_code VARCHAR(64) NOT NULL COMMENT '处理目的编码',
  policy_version VARCHAR(64) NOT NULL COMMENT '隐私政策版本',
  state VARCHAR(16) NOT NULL COMMENT '同意或撤回',
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '外部证据摘要，不保存证据明文',
  actor_user_id BIGINT NOT NULL COMMENT '操作人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  PRIMARY KEY (tenant_id,consent_ledger_id),
  UNIQUE KEY uk_mbr_consent_id (tenant_id,consent_id),
  KEY idx_mbr_consent_current (tenant_id,member_id,purpose_code,occurred_at),
  CONSTRAINT fk_mbr_consent_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT ck_mbr_consent_state CHECK (state IN ('GRANTED','REVOKED'))
) ENGINE=InnoDB COMMENT='Gate 5C只追加同意流水；XML_ONLY';

CREATE TABLE mbr_privacy_request (
  request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '隐私权利请求ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员主体ULID',
  request_type VARCHAR(24) NOT NULL COMMENT '访问导出更正或删除请求',
  state VARCHAR(24) NOT NULL COMMENT '请求状态',
  reason VARCHAR(256) NOT NULL COMMENT '经审计的业务原因，不含身份明文',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  submitted_by BIGINT NOT NULL COMMENT '提交人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  submitted_at DATETIME(3) NOT NULL COMMENT '提交时间UTC',
  completed_at DATETIME(3) NULL COMMENT '结束时间UTC',
  PRIMARY KEY (tenant_id,request_id),
  KEY idx_mbr_privacy_member (tenant_id,member_id,state),
  CONSTRAINT fk_mbr_privacy_member FOREIGN KEY (tenant_id,member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT ck_mbr_privacy_type CHECK (request_type IN ('ACCESS','EXPORT','CORRECT','DELETE')),
  CONSTRAINT ck_mbr_privacy_state CHECK (state IN ('REQUESTED','IDENTITY_VERIFIED','IN_PROGRESS','FULFILLED','PARTIALLY_FULFILLED','REJECTED'))
) ENGINE=InnoDB COMMENT='Gate 5C隐私权利请求；XML_ONLY';

CREATE TABLE mbr_privacy_history (
  history_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求状态历史ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '隐私请求ULID',
  from_state VARCHAR(24) NULL COMMENT '原状态，首次提交为空',
  to_state VARCHAR(24) NOT NULL COMMENT '目标状态',
  reason VARCHAR(256) NOT NULL COMMENT '迁移原因，不含身份明文',
  actor_user_id BIGINT NOT NULL COMMENT '操作人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  PRIMARY KEY (tenant_id,history_id),
  KEY idx_mbr_privacy_history (tenant_id,request_id,occurred_at),
  CONSTRAINT fk_mbr_privacy_history_request FOREIGN KEY (tenant_id,request_id) REFERENCES mbr_privacy_request(tenant_id,request_id)
) ENGINE=InnoDB COMMENT='Gate 5C只追加隐私请求历史；XML_ONLY';

CREATE TABLE mbr_member_link_ledger (
  link_ledger_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员关联流水ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  link_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可逆关联ULID',
  source_member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源会员ULID',
  target_member_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标会员ULID',
  action VARCHAR(16) NOT NULL COMMENT '合并或拆分动作',
  reason VARCHAR(256) NOT NULL COMMENT '审批原因，不含身份明文',
  actor_user_id BIGINT NOT NULL COMMENT '操作人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  PRIMARY KEY (tenant_id,link_ledger_id),
  KEY idx_mbr_link_latest (tenant_id,link_id,occurred_at),
  CONSTRAINT fk_mbr_link_source FOREIGN KEY (tenant_id,source_member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT fk_mbr_link_target FOREIGN KEY (tenant_id,target_member_id) REFERENCES mbr_member(tenant_id,member_id),
  CONSTRAINT ck_mbr_link_action CHECK (action IN ('MERGE','SPLIT')),
  CONSTRAINT ck_mbr_link_members CHECK (source_member_id<>target_member_id)
) ENGINE=InnoDB COMMENT='Gate 5C只追加会员合并拆分流水；XML_ONLY';

CREATE TABLE mbr_command_result (
  command_result_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令结果ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  command_type VARCHAR(40) NOT NULL COMMENT '命令类型',
  idempotency_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等键ULID',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏规范请求摘要',
  aggregate_type VARCHAR(24) NOT NULL COMMENT '聚合类型',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '聚合ULID',
  result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果摘要',
  result_json JSON NOT NULL COMMENT '不含身份明文或完整密文的最小结果',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,command_result_id),
  UNIQUE KEY uk_mbr_command_key (tenant_id,command_type,idempotency_key)
) ENGINE=InnoDB COMMENT='Gate 5C会员命令幂等结果；XML_ONLY';

CREATE TABLE mbr_event_outbox (
  outbox_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Outbox事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  event_type VARCHAR(64) NOT NULL COMMENT '版本化事件类型',
  aggregate_type VARCHAR(24) NOT NULL DEFAULT 'MEMBER' COMMENT '聚合类型',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '聚合ULID',
  aggregate_version INT NOT NULL COMMENT '聚合版本',
  payload_json JSON NOT NULL COMMENT '最小化事件载荷，禁止PII和密文',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件载荷摘要',
  delivery_state VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT '投递状态',
  available_at DATETIME(3) NOT NULL COMMENT '可投递时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,outbox_id),
  KEY idx_mbr_outbox_delivery (tenant_id,delivery_state,available_at),
  CONSTRAINT ck_mbr_outbox_state CHECK (delivery_state IN ('NEW','SENDING','ACKED','DEAD'))
) ENGINE=InnoDB COMMENT='Gate 5C去敏会员事件Outbox；XML_ONLY';
