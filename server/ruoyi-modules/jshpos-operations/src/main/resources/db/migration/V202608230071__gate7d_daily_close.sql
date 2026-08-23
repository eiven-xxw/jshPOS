CREATE TABLE ops_daily_close (
  close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信服务端注入租户标识',
  store_id BIGINT NOT NULL COMMENT '可信门店平台主键',
  business_date DATE NOT NULL COMMENT '门店冻结业务日',
  zone_id VARCHAR(64) NOT NULL COMMENT '冻结IANA时区',
  business_day_start TIME NOT NULL COMMENT '冻结业务日起点',
  close_version INT NOT NULL COMMENT '同门店业务日只增关闭版本',
  correction_of_close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '更正版本引用的原关闭日结',
  correction_reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '去敏更正原因摘要',
  state VARCHAR(32) NOT NULL COMMENT '日结具名状态',
  snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结金额与Owner事实摘要',
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结Owner检查点清单摘要',
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建稳定幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建请求摘要',
  creator_user_id BIGINT NOT NULL COMMENT '创建人；不得审批或签署自己的日结',
  preflight_run INT NOT NULL DEFAULT 0 COMMENT '最近冻结预检轮次',
  record_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时间',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC状态更新时间',
  PRIMARY KEY (close_id),
  UNIQUE KEY uk_ops_close_tenant_id (tenant_id,close_id),
  UNIQUE KEY uk_ops_close_version (tenant_id,store_id,business_date,close_version),
  UNIQUE KEY uk_ops_close_idempotency (tenant_id,idempotency_key),
  KEY idx_ops_close_store_state (tenant_id,store_id,business_date,state,updated_at),
  CONSTRAINT fk_ops_close_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT fk_ops_close_correction FOREIGN KEY (tenant_id,correction_of_close_id) REFERENCES ops_daily_close(tenant_id,close_id),
  CONSTRAINT ck_ops_close_ulid CHECK (close_id REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$'),
  CONSTRAINT ck_ops_close_hash CHECK (snapshot_sha256 REGEXP '^[a-f0-9]{64}$' AND manifest_sha256 REGEXP '^[a-f0-9]{64}$'
    AND request_sha256 REGEXP '^[a-f0-9]{64}$' AND (correction_reason_sha256 IS NULL OR correction_reason_sha256 REGEXP '^[a-f0-9]{64}$')),
  CONSTRAINT ck_ops_close_state CHECK (state IN ('DRAFT','PREFLIGHTING','PREFLIGHT_FAILED','READY','APPROVED','CLOSING','CLOSED','FAILED','CORRECTION_REQUIRED','COMPENSATION_REQUIRED')),
  CONSTRAINT ck_ops_close_version CHECK (close_version>0 AND preflight_run>=0 AND record_version>=0),
  CONSTRAINT ck_ops_close_correction_shape CHECK ((correction_of_close_id IS NULL AND correction_reason_sha256 IS NULL)
    OR (correction_of_close_id IS NOT NULL AND correction_reason_sha256 IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Operations Owner门店业务日日结头；XML_ONLY';

CREATE TABLE ops_daily_close_snapshot (
  snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结快照ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  run_no INT NOT NULL COMMENT '预检轮次',currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种CNY',
  order_count BIGINT NOT NULL COMMENT '成交订单数',cancelled_order_count BIGINT NOT NULL COMMENT '取消数',return_count BIGINT NOT NULL COMMENT '退货数',
  gross_minor BIGINT NOT NULL COMMENT '原价金额分',discount_minor BIGINT NOT NULL COMMENT '优惠金额分',surcharge_minor BIGINT NOT NULL COMMENT '附加金额分',
  receivable_minor BIGINT NOT NULL COMMENT '应收金额分',refund_minor BIGINT NOT NULL COMMENT '退款金额分',cash_received_minor BIGINT NOT NULL COMMENT '现金实收分',
  cash_refunded_minor BIGINT NOT NULL COMMENT '现金退款分',electronic_received_minor BIGINT NOT NULL COMMENT 'Provider无关成功电子收款分',
  electronic_refunded_minor BIGINT NOT NULL COMMENT 'Provider无关成功电子退款分',unknown_payment_count BIGINT NOT NULL COMMENT 'UNKNOWN支付数',
  unknown_refund_count BIGINT NOT NULL COMMENT 'UNKNOWN退款数',shift_difference_minor BIGINT NOT NULL COMMENT '班次差异分',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整来源内容摘要',created_at DATETIME(6) NOT NULL COMMENT 'UTC冻结时间',
  PRIMARY KEY(snapshot_id),UNIQUE KEY uk_ops_snapshot_tenant_id(tenant_id,snapshot_id),UNIQUE KEY uk_ops_snapshot_run(tenant_id,close_id,run_no),
  CONSTRAINT fk_ops_snapshot_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),
  CONSTRAINT ck_ops_snapshot_count CHECK(order_count>=0 AND cancelled_order_count>=0 AND return_count>=0),
  CONSTRAINT ck_ops_snapshot_money CHECK(gross_minor>=0 AND discount_minor>=0 AND surcharge_minor>=0 AND receivable_minor>=0 AND refund_minor>=0
    AND cash_received_minor>=0 AND cash_refunded_minor>=0 AND gross_minor-discount_minor+surcharge_minor=receivable_minor),
  CONSTRAINT ck_ops_snapshot_provider CHECK(electronic_received_minor>=0 AND electronic_refunded_minor>=0
    AND unknown_payment_count>=0 AND unknown_refund_count>=0),
  CONSTRAINT ck_ops_snapshot_currency CHECK(currency='CNY'),CONSTRAINT ck_ops_snapshot_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结金额冻结快照';

CREATE TABLE ops_daily_close_checkpoint (
  checkpoint_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner检查点ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',run_no INT NOT NULL COMMENT '预检轮次',owner_code VARCHAR(32) NOT NULL COMMENT 'FOUNDATION/SHIFT_ORDER/PAYMENT_REFUND/SYNC/REPORTING',
  source_version VARCHAR(128) NOT NULL COMMENT 'Owner版本身份',source_sequence BIGINT NOT NULL COMMENT 'Owner最大序号',source_status VARCHAR(16) NOT NULL COMMENT 'CURRENT/INCOMPLETE/UNAVAILABLE',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner内容摘要',created_at DATETIME(6) NOT NULL COMMENT 'UTC冻结时间',
  PRIMARY KEY(checkpoint_id),UNIQUE KEY uk_ops_checkpoint_tenant_id(tenant_id,checkpoint_id),UNIQUE KEY uk_ops_checkpoint_owner(tenant_id,close_id,run_no,owner_code),
  CONSTRAINT fk_ops_checkpoint_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),
  CONSTRAINT ck_ops_checkpoint_run CHECK(run_no>0 AND source_sequence>=0),CONSTRAINT ck_ops_checkpoint_status CHECK(source_status IN('CURRENT','INCOMPLETE','UNAVAILABLE')),
  CONSTRAINT ck_ops_checkpoint_hash CHECK(content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结Owner来源检查点';

CREATE TABLE ops_daily_close_preflight (
  preflight_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预检结果ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',run_no INT NOT NULL COMMENT '预检轮次',check_code VARCHAR(64) NOT NULL COMMENT '冻结检查代码',
  owner_code VARCHAR(32) NOT NULL COMMENT '权威Owner',required_flag BOOLEAN NOT NULL COMMENT '是否内部必需',external_flag BOOLEAN NOT NULL COMMENT '是否外部证据',
  status VARCHAR(16) NOT NULL COMMENT 'PASS/FAIL/BLOCKED/UNAVAILABLE/WARN',evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏证据摘要',
  masked_message VARCHAR(256) NOT NULL COMMENT '不含Secret/PII说明',checked_at DATETIME(6) NOT NULL COMMENT 'UTC检查时间',
  PRIMARY KEY(preflight_id),UNIQUE KEY uk_ops_preflight_tenant_id(tenant_id,preflight_id),UNIQUE KEY uk_ops_preflight_code(tenant_id,close_id,run_no,check_code),
  KEY idx_ops_preflight_close(tenant_id,close_id,run_no,status),CONSTRAINT fk_ops_preflight_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),
  CONSTRAINT ck_ops_preflight_run CHECK(run_no>0),CONSTRAINT ck_ops_preflight_status CHECK(status IN('PASS','FAIL','BLOCKED','UNAVAILABLE','WARN')),
  CONSTRAINT ck_ops_preflight_external CHECK(NOT(external_flag=1 AND status='PASS')),CONSTRAINT ck_ops_preflight_hash CHECK(evidence_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结预检结果';

CREATE TABLE ops_daily_close_difference (
  difference_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '差异ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',difference_type VARCHAR(64) NOT NULL COMMENT '差异类型',state VARCHAR(16) NOT NULL COMMENT 'OPEN',
  expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结预期摘要',actual_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '新观察摘要',
  detail_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏详情摘要',detected_at DATETIME(6) NOT NULL COMMENT 'UTC检出时间',
  PRIMARY KEY(difference_id),UNIQUE KEY uk_ops_difference_tenant_id(tenant_id,difference_id),KEY idx_ops_difference_close(tenant_id,close_id,state,detected_at),
  CONSTRAINT fk_ops_difference_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),CONSTRAINT ck_ops_difference_state CHECK(state='OPEN'),
  CONSTRAINT ck_ops_difference_hash CHECK(expected_sha256 REGEXP '^[a-f0-9]{64}$' AND actual_sha256 REGEXP '^[a-f0-9]{64}$' AND detail_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结差异与晚到事实';

CREATE TABLE ops_daily_close_approval (
  approval_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审批ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',approver_user_id BIGINT NOT NULL COMMENT '独立审批人',reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原因摘要',
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审批幂等键',request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',approved_at DATETIME(6) NOT NULL COMMENT 'UTC审批时间',
  PRIMARY KEY(approval_id),UNIQUE KEY uk_ops_approval_tenant_id(tenant_id,approval_id),UNIQUE KEY uk_ops_approval_close(tenant_id,close_id),UNIQUE KEY uk_ops_approval_key(tenant_id,idempotency_key),
  CONSTRAINT fk_ops_approval_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),CONSTRAINT ck_ops_approval_hash CHECK(reason_sha256 REGEXP '^[a-f0-9]{64}$' AND request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结独立审批事实';

CREATE TABLE ops_daily_close_signature (
  signature_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '签署ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  signatory_user_id BIGINT NOT NULL COMMENT '独立签署人',snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结快照摘要',manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源清单摘要',
  signature_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '软件签署摘要；非外部电子签名',idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '签署幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',signed_at DATETIME(6) NOT NULL COMMENT 'UTC签署时间',
  PRIMARY KEY(signature_id),UNIQUE KEY uk_ops_signature_tenant_id(tenant_id,signature_id),UNIQUE KEY uk_ops_signature_close(tenant_id,close_id),UNIQUE KEY uk_ops_signature_key(tenant_id,idempotency_key),
  CONSTRAINT fk_ops_signature_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),
  CONSTRAINT ck_ops_signature_hash CHECK(snapshot_sha256 REGEXP '^[a-f0-9]{64}$' AND manifest_sha256 REGEXP '^[a-f0-9]{64}$' AND signature_sha256 REGEXP '^[a-f0-9]{64}$' AND request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结软件签署事实';

CREATE TABLE ops_daily_close_command_result (
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  operation VARCHAR(32) NOT NULL COMMENT 'PREFLIGHT/APPROVE/SIGN_CLOSE/DETECT_LATE_FACTS',idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等键',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',result_state VARCHAR(32) NOT NULL COMMENT '稳定结果状态',result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果摘要',created_at DATETIME(6) NOT NULL COMMENT 'UTC完成时间',
  PRIMARY KEY(command_id),UNIQUE KEY uk_ops_command_tenant_id(tenant_id,command_id),UNIQUE KEY uk_ops_command_key(tenant_id,operation,idempotency_key),
  CONSTRAINT fk_ops_command_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),CONSTRAINT ck_ops_command_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$' AND result_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结幂等命令结果';

CREATE TABLE ops_daily_close_state_event (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态事件ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  from_state VARCHAR(32) NULL COMMENT '原状态',to_state VARCHAR(32) NOT NULL COMMENT '目标状态',request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作人',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC发生时间',PRIMARY KEY(event_id),UNIQUE KEY uk_ops_state_tenant_id(tenant_id,event_id),KEY idx_ops_state_close(tenant_id,close_id,occurred_at),
  CONSTRAINT fk_ops_state_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),CONSTRAINT ck_ops_state_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结状态事件';

CREATE TABLE ops_daily_close_audit (
  audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审计ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  action_code VARCHAR(64) NOT NULL COMMENT '具名动作',result VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILURE/DENIED',request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',
  actor_user_id BIGINT NOT NULL COMMENT '可信操作人',masked_summary VARCHAR(256) NOT NULL COMMENT '不含Secret/PII摘要',occurred_at DATETIME(6) NOT NULL COMMENT 'UTC发生时间',PRIMARY KEY(audit_id),UNIQUE KEY uk_ops_audit_tenant_id(tenant_id,audit_id),KEY idx_ops_audit_close(tenant_id,close_id,occurred_at),
  CONSTRAINT fk_ops_audit_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),CONSTRAINT ck_ops_audit_result CHECK(result IN('SUCCESS','FAILURE','DENIED')),CONSTRAINT ck_ops_audit_hash CHECK(request_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加门店日结领域审计';

CREATE TABLE ops_daily_close_outbox (
  outbox_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Outbox ULID',tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',close_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '日结ULID',
  event_type VARCHAR(96) NOT NULL COMMENT '版本化事件',schema_version INT NOT NULL COMMENT '事件Schema版本',payload_json JSON NOT NULL COMMENT '不含tenant/Secret/PII载荷',payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '载荷摘要',
  correlation_id VARCHAR(64) NOT NULL COMMENT '关联标识',delivery_state VARCHAR(16) NOT NULL COMMENT 'PENDING/DELIVERED/DEAD',attempts INT NOT NULL COMMENT '投递尝试数',created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时间',delivered_at DATETIME(6) NULL COMMENT 'UTC送达时间',
  PRIMARY KEY(outbox_id),UNIQUE KEY uk_ops_outbox_tenant_id(tenant_id,outbox_id),KEY idx_ops_outbox_delivery(tenant_id,delivery_state,created_at),
  CONSTRAINT fk_ops_outbox_close FOREIGN KEY(tenant_id,close_id) REFERENCES ops_daily_close(tenant_id,close_id),CONSTRAINT ck_ops_outbox_state CHECK(delivery_state IN('PENDING','DELIVERED','DEAD')),CONSTRAINT ck_ops_outbox_attempts CHECK(attempts>=0),CONSTRAINT ck_ops_outbox_hash CHECK(payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控更新门店日结Outbox';

DELIMITER $$
CREATE TRIGGER trg_ops_close_guard BEFORE UPDATE ON ops_daily_close FOR EACH ROW BEGIN
  IF OLD.state='CLOSED' OR NOT(NEW.tenant_id<=>OLD.tenant_id) OR NOT(NEW.store_id<=>OLD.store_id)
    OR NOT(NEW.business_date<=>OLD.business_date) OR NOT(NEW.zone_id<=>OLD.zone_id)
    OR NOT(NEW.business_day_start<=>OLD.business_day_start) OR NOT(NEW.close_version<=>OLD.close_version)
    OR NOT(NEW.correction_of_close_id<=>OLD.correction_of_close_id) OR NOT(NEW.correction_reason_sha256<=>OLD.correction_reason_sha256)
    OR NOT(NEW.idempotency_key<=>OLD.idempotency_key) OR NOT(NEW.request_sha256<=>OLD.request_sha256)
    OR NOT(NEW.creator_user_id<=>OLD.creator_user_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close immutable identity or closed fact changed'; END IF;
END$$
CREATE TRIGGER trg_ops_close_no_delete BEFORE DELETE ON ops_daily_close FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close cannot be deleted'; END$$
CREATE TRIGGER trg_ops_snapshot_no_update BEFORE UPDATE ON ops_daily_close_snapshot FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_snapshot append-only'; END$$
CREATE TRIGGER trg_ops_snapshot_no_delete BEFORE DELETE ON ops_daily_close_snapshot FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_snapshot append-only'; END$$
CREATE TRIGGER trg_ops_checkpoint_no_update BEFORE UPDATE ON ops_daily_close_checkpoint FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_checkpoint append-only'; END$$
CREATE TRIGGER trg_ops_checkpoint_no_delete BEFORE DELETE ON ops_daily_close_checkpoint FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_checkpoint append-only'; END$$
CREATE TRIGGER trg_ops_preflight_no_update BEFORE UPDATE ON ops_daily_close_preflight FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_preflight append-only'; END$$
CREATE TRIGGER trg_ops_preflight_no_delete BEFORE DELETE ON ops_daily_close_preflight FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_preflight append-only'; END$$
CREATE TRIGGER trg_ops_difference_no_update BEFORE UPDATE ON ops_daily_close_difference FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_difference append-only'; END$$
CREATE TRIGGER trg_ops_difference_no_delete BEFORE DELETE ON ops_daily_close_difference FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_difference append-only'; END$$
CREATE TRIGGER trg_ops_approval_no_update BEFORE UPDATE ON ops_daily_close_approval FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_approval append-only'; END$$
CREATE TRIGGER trg_ops_approval_no_delete BEFORE DELETE ON ops_daily_close_approval FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_approval append-only'; END$$
CREATE TRIGGER trg_ops_signature_no_update BEFORE UPDATE ON ops_daily_close_signature FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_signature append-only'; END$$
CREATE TRIGGER trg_ops_signature_no_delete BEFORE DELETE ON ops_daily_close_signature FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_signature append-only'; END$$
CREATE TRIGGER trg_ops_command_no_update BEFORE UPDATE ON ops_daily_close_command_result FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_command_result append-only'; END$$
CREATE TRIGGER trg_ops_command_no_delete BEFORE DELETE ON ops_daily_close_command_result FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_command_result append-only'; END$$
CREATE TRIGGER trg_ops_state_no_update BEFORE UPDATE ON ops_daily_close_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_state_event append-only'; END$$
CREATE TRIGGER trg_ops_state_no_delete BEFORE DELETE ON ops_daily_close_state_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_state_event append-only'; END$$
CREATE TRIGGER trg_ops_audit_no_update BEFORE UPDATE ON ops_daily_close_audit FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_audit append-only'; END$$
CREATE TRIGGER trg_ops_audit_no_delete BEFORE DELETE ON ops_daily_close_audit FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops_daily_close_audit append-only'; END$$
DELIMITER ;
