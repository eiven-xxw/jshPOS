CREATE TABLE ops_exception_case (
  case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信服务端注入租户标识',
  store_id BIGINT NOT NULL COMMENT '可信门店平台主键',
  source_owner VARCHAR(32) NOT NULL COMMENT '来源Owner代码',
  source_type VARCHAR(64) NOT NULL COMMENT 'Owner定义异常类型',
  source_fact_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner稳定事实身份',
  dedup_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner加业务维度稳定去重键',
  severity CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '严重级别P0至P3',
  state VARCHAR(24) NOT NULL COMMENT '异常案件具名状态',
  latest_source_event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最近Owner事件身份',
  latest_source_sequence BIGINT NOT NULL COMMENT '最近Owner单调或稳定序号',
  latest_source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最近Owner内容摘要',
  assignee_user_id BIGINT NULL COMMENT '当前租约认领人',
  lease_expires_at DATETIME(6) NULL COMMENT '认领租约UTC到期时间',
  resolver_user_id BIGINT NULL COMMENT '获得Owner成功结果的处置人',
  reviewer_user_id BIGINT NULL COMMENT '独立复核人',
  record_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  first_observed_at DATETIME(6) NOT NULL COMMENT 'Owner首次观察UTC时间',
  last_observed_at DATETIME(6) NOT NULL COMMENT 'Owner最近观察UTC时间',
  created_at DATETIME(6) NOT NULL COMMENT '案件创建UTC时间',
  updated_at DATETIME(6) NOT NULL COMMENT '案件控制面更新时间',
  PRIMARY KEY(case_id),
  UNIQUE KEY uk_ops_exc_case_tenant_id(tenant_id,case_id),
  UNIQUE KEY uk_ops_exc_case_dedup(tenant_id,store_id,dedup_key),
  KEY idx_ops_exc_queue(tenant_id,store_id,state,severity,last_observed_at,case_id),
  KEY idx_ops_exc_lease(tenant_id,state,lease_expires_at),
  CONSTRAINT fk_ops_exc_case_store FOREIGN KEY(tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_ops_exc_case_ulid CHECK(case_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$'),
  CONSTRAINT ck_ops_exc_owner CHECK(source_owner IN('SYNC','DATA_PACKAGE','PAYMENT_REFUND','INVENTORY','COSTING','REPORTING','DAILY_CLOSE')),
  CONSTRAINT ck_ops_exc_severity CHECK(severity IN('P0','P1','P2','P3')),
  CONSTRAINT ck_ops_exc_state CHECK(state IN('OPEN','CLAIMED','IN_PROGRESS','WAITING_OWNER','RESOLVED','CLOSED','REOPENED','FAILED')),
  CONSTRAINT ck_ops_exc_source CHECK(latest_source_sequence>=0 AND latest_source_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ops_exc_time CHECK(last_observed_at>=first_observed_at AND record_version>=0),
  CONSTRAINT ck_ops_exc_lease_shape CHECK((assignee_user_id IS NULL AND lease_expires_at IS NULL) OR (assignee_user_id IS NOT NULL AND lease_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Operations异常案件受控写控制面；CONTROLLED_WRITE+XML';

CREATE TABLE ops_exception_observation (
  observation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源观察ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',owner_code VARCHAR(32) NOT NULL COMMENT '来源Owner代码',
  source_event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner事件稳定身份',source_sequence BIGINT NOT NULL COMMENT 'Owner序号',
  source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner内容摘要',correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识',
  masked_summary VARCHAR(256) NOT NULL COMMENT '不含Secret和PII的摘要',conflict_flag VARCHAR(32) NOT NULL COMMENT 'INITIAL/SAME_CONTENT_NEW_EVENT/CONTENT_CHANGED/OUT_OF_ORDER/SEQUENCE_CONFLICT',
  observed_at DATETIME(6) NOT NULL COMMENT 'Owner观察UTC时间',created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '写入UTC时间',
  PRIMARY KEY(observation_id),UNIQUE KEY uk_ops_exc_obs_tenant_id(tenant_id,observation_id),UNIQUE KEY uk_ops_exc_obs_event(tenant_id,owner_code,source_event_id),
  KEY idx_ops_exc_obs_case(tenant_id,case_id,observed_at,observation_id),CONSTRAINT fk_ops_exc_obs_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),
  CONSTRAINT ck_ops_exc_obs_sequence CHECK(source_sequence>=0),CONSTRAINT ck_ops_exc_obs_hash CHECK(source_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ops_exc_obs_conflict CHECK(conflict_flag IN('INITIAL','SAME_CONTENT_NEW_EVENT','CONTENT_CHANGED','OUT_OF_ORDER','SEQUENCE_CONFLICT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Owner异常来源只追加观察；APPEND_ONLY+XML';

CREATE TABLE ops_exception_lease_event (
  lease_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租约事件ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',event_type VARCHAR(24) NOT NULL COMMENT 'CLAIMED/RECLAIMED/TRANSFERRED',
  from_user_id BIGINT NULL COMMENT '原认领人',to_user_id BIGINT NOT NULL COMMENT '新认领人',expires_at DATETIME(6) NOT NULL COMMENT 'UTC到期时间',
  reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏原因或请求摘要',actor_user_id BIGINT NOT NULL COMMENT '可信操作者',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC发生时间',
  PRIMARY KEY(lease_event_id),UNIQUE KEY uk_ops_exc_lease_tenant_id(tenant_id,lease_event_id),KEY idx_ops_exc_lease_case(tenant_id,case_id,occurred_at),
  CONSTRAINT fk_ops_exc_lease_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),
  CONSTRAINT ck_ops_exc_lease_event CHECK(event_type IN('CLAIMED','RECLAIMED','TRANSFERRED')),CONSTRAINT ck_ops_exc_lease_hash CHECK(reason_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常认领租约只追加事件；APPEND_ONLY+XML';

CREATE TABLE ops_exception_action_plan (
  plan_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '处置计划ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',
  action_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner具名修复动作',summary_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏计划摘要',
  planner_user_id BIGINT NOT NULL COMMENT '可信计划人',state VARCHAR(16) NOT NULL COMMENT 'ACTIVE/SUPERSEDED',created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时间',
  PRIMARY KEY(plan_id),UNIQUE KEY uk_ops_exc_plan_tenant_id(tenant_id,plan_id),KEY idx_ops_exc_plan_case(tenant_id,case_id,state,created_at),
  CONSTRAINT fk_ops_exc_plan_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),CONSTRAINT ck_ops_exc_plan_state CHECK(state IN('ACTIVE','SUPERSEDED')),
  CONSTRAINT ck_ops_exc_plan_hash CHECK(summary_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常处置计划受控写控制面；CONTROLLED_WRITE+XML';

CREATE TABLE ops_exception_repair_command (
  repair_command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner修复命令ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',
  owner_code VARCHAR(32) NOT NULL COMMENT '目标Owner',action_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '具名修复动作',request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '修复请求摘要',
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等键',correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识',
  state VARCHAR(24) NOT NULL COMMENT 'REQUESTED/WAITING_OWNER/UNAVAILABLE/SUCCEEDED/FAILED',owner_result_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Owner结果稳定引用',
  owner_result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Owner结果摘要',requested_at DATETIME(6) NOT NULL COMMENT 'UTC请求时间',observed_at DATETIME(6) NULL COMMENT 'UTC结果观察时间',
  PRIMARY KEY(repair_command_id),UNIQUE KEY uk_ops_exc_repair_tenant_id(tenant_id,repair_command_id),UNIQUE KEY uk_ops_exc_repair_key(tenant_id,idempotency_key),
  KEY idx_ops_exc_repair_case(tenant_id,case_id,requested_at),CONSTRAINT fk_ops_exc_repair_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),
  CONSTRAINT ck_ops_exc_repair_state CHECK(state IN('REQUESTED','WAITING_OWNER','UNAVAILABLE','SUCCEEDED','FAILED')),
  CONSTRAINT ck_ops_exc_repair_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$' AND (owner_result_sha256 IS NULL OR owner_result_sha256 REGEXP '^[a-f0-9]{64}$')),
  CONSTRAINT ck_ops_exc_repair_result CHECK((state='REQUESTED' AND observed_at IS NULL AND owner_result_sha256 IS NULL) OR (state<>'REQUESTED' AND observed_at IS NOT NULL AND owner_result_sha256 IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Owner修复命令与结果受控状态；CONTROLLED_WRITE+XML';

CREATE TABLE ops_exception_review (
  review_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立复核ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',
  reviewer_user_id BIGINT NOT NULL COMMENT '独立复核人',decision VARCHAR(16) NOT NULL COMMENT 'APPROVED/REJECTED',reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏原因摘要',reviewed_at DATETIME(6) NOT NULL COMMENT 'UTC复核时间',
  PRIMARY KEY(review_id),UNIQUE KEY uk_ops_exc_review_tenant_id(tenant_id,review_id),KEY idx_ops_exc_review_case(tenant_id,case_id,reviewed_at),
  CONSTRAINT fk_ops_exc_review_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),CONSTRAINT ck_ops_exc_review_decision CHECK(decision IN('APPROVED','REJECTED')),
  CONSTRAINT ck_ops_exc_review_hash CHECK(reason_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常独立复核只追加事实；APPEND_ONLY+XML';

CREATE TABLE ops_exception_state_event (
  state_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态事件ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',
  from_state VARCHAR(24) NULL COMMENT '原状态；创建时为空',to_state VARCHAR(24) NOT NULL COMMENT '新状态',reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏原因摘要',actor_user_id BIGINT NOT NULL COMMENT '可信操作者',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC发生时间',
  PRIMARY KEY(state_event_id),UNIQUE KEY uk_ops_exc_state_tenant_id(tenant_id,state_event_id),KEY idx_ops_exc_state_case(tenant_id,case_id,occurred_at),
  CONSTRAINT fk_ops_exc_state_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),CONSTRAINT ck_ops_exc_state_hash CHECK(reason_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常状态只追加历史；APPEND_ONLY+XML';

CREATE TABLE ops_exception_audit_event (
  audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审计ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',
  action_code VARCHAR(64) NOT NULL COMMENT '动作代码',result_code VARCHAR(32) NOT NULL COMMENT '结果代码',request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏请求摘要',correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联标识',actor_user_id BIGINT NOT NULL COMMENT '可信操作者',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC发生时间',
  PRIMARY KEY(audit_id),UNIQUE KEY uk_ops_exc_audit_tenant_id(tenant_id,audit_id),KEY idx_ops_exc_audit_case(tenant_id,case_id,occurred_at),
  CONSTRAINT fk_ops_exc_audit_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),CONSTRAINT ck_ops_exc_audit_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常完整只追加审计；APPEND_ONLY+XML';

CREATE TABLE ops_exception_command (
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '案件命令ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',operation VARCHAR(32) NOT NULL COMMENT '案件操作',
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等键',request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范请求摘要',result_state VARCHAR(24) NOT NULL COMMENT '首次执行结果状态',created_at DATETIME(6) NOT NULL COMMENT 'UTC执行时间',
  PRIMARY KEY(command_id),UNIQUE KEY uk_ops_exc_command_tenant_id(tenant_id,command_id),UNIQUE KEY uk_ops_exc_command_key(tenant_id,operation,idempotency_key),KEY idx_ops_exc_command_case(tenant_id,case_id,created_at),
  CONSTRAINT fk_ops_exc_command_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),CONSTRAINT ck_ops_exc_command_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='案件稳定幂等结果只追加事实；APPEND_ONLY+XML';

CREATE TABLE ops_exception_outbox (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Outbox事件ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '异常案件ULID',event_type VARCHAR(96) NOT NULL COMMENT '版本化事件类型',
  payload_json JSON NOT NULL COMMENT '不含Secret/PII的事件负载',payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件摘要',delivery_state VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/DELIVERING/DELIVERED/DEAD',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC发生时间',
  PRIMARY KEY(event_id),UNIQUE KEY uk_ops_exc_outbox_tenant_id(tenant_id,event_id),KEY idx_ops_exc_outbox_delivery(tenant_id,delivery_state,occurred_at),
  CONSTRAINT fk_ops_exc_outbox_case FOREIGN KEY(tenant_id,case_id) REFERENCES ops_exception_case(tenant_id,case_id),CONSTRAINT ck_ops_exc_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ops_exc_outbox_state CHECK(delivery_state IN('NEW','DELIVERING','DELIVERED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常中心Outbox受控投递状态；CONTROLLED_WRITE+XML';

DELIMITER $$
CREATE TRIGGER trg_ops_exc_observation_immutable BEFORE UPDATE ON ops_exception_observation FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception observation is append-only'; END$$
CREATE TRIGGER trg_ops_exc_observation_no_delete BEFORE DELETE ON ops_exception_observation FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception observation cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_state_immutable BEFORE UPDATE ON ops_exception_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception state event is append-only'; END$$
CREATE TRIGGER trg_ops_exc_state_no_delete BEFORE DELETE ON ops_exception_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception state event cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_audit_immutable BEFORE UPDATE ON ops_exception_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception audit is append-only'; END$$
CREATE TRIGGER trg_ops_exc_audit_no_delete BEFORE DELETE ON ops_exception_audit_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception audit cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_review_immutable BEFORE UPDATE ON ops_exception_review FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception review is append-only'; END$$
CREATE TRIGGER trg_ops_exc_review_no_delete BEFORE DELETE ON ops_exception_review FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception review cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_command_immutable BEFORE UPDATE ON ops_exception_command FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception command is append-only'; END$$
CREATE TRIGGER trg_ops_exc_command_no_delete BEFORE DELETE ON ops_exception_command FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception command cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_case_no_delete BEFORE DELETE ON ops_exception_case FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception case cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_repair_no_delete BEFORE DELETE ON ops_exception_repair_command FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception repair command cannot be deleted'; END$$
CREATE TRIGGER trg_ops_exc_outbox_no_delete BEFORE DELETE ON ops_exception_outbox FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops exception outbox cannot be deleted'; END$$
DELIMITER ;
