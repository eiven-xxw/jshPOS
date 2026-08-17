CREATE TABLE prm_manual_price_audit (
  manual_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '人工优惠事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  authorization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '人工授权ULID',
  event_sequence INT NOT NULL COMMENT '授权内只增事件序号',
  state VARCHAR(24) NOT NULL COMMENT '授权状态',
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等命令ULID',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令规范化摘要',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始报价ULID',
  store_id BIGINT NOT NULL COMMENT '门店',
  terminal_id VARCHAR(64) NOT NULL COMMENT '终端标识',
  action_type VARCHAR(24) NOT NULL COMMENT '人工优惠动作',
  source_line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '行改价目标购物行ULID',
  amount_or_rate VARCHAR(32) NOT NULL COMMENT '分金额折扣率或抹零倍数原文',
  payment_method VARCHAR(16) NOT NULL COMMENT '支付方式约束',
  before_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '应用前报价指纹',
  preview_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预检或已应用结果指纹',
  incremental_discount_minor BIGINT NOT NULL COMMENT '本动作新增优惠分金额',
  policy_version_id BIGINT NOT NULL COMMENT 'Gate0阈值配置版本',
  policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '阈值配置摘要',
  without_approval_minor BIGINT NOT NULL COMMENT '免复核增量优惠上限分',
  with_approval_minor BIGINT NOT NULL COMMENT '复核后增量优惠硬上限分',
  minimum_line_payable_minor BIGINT NOT NULL COMMENT '行改价最低应收分',
  maximum_rounding_minor BIGINT NOT NULL COMMENT '现金抹零绝对上限分',
  rounding_multiples_json JSON NOT NULL COMMENT '允许抹零倍数的冻结JSON',
  reason_code VARCHAR(32) NOT NULL COMMENT '原因码',
  reason_text VARCHAR(256) NOT NULL COMMENT '原因说明或复核说明',
  operator_user_id BIGINT NOT NULL COMMENT '操作人',
  approver_user_id BIGINT NULL COMMENT '独立复核人',
  business_date DATE NOT NULL COMMENT '门店业务日',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  result_json JSON NOT NULL COMMENT '预检或已应用确定性报价JSON',
  result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果规范化摘要',
  occurred_at DATETIME(3) NOT NULL COMMENT '事件发生时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,manual_event_id),
  UNIQUE KEY uk_prm_manual_command (tenant_id,command_id),
  UNIQUE KEY uk_prm_manual_sequence (tenant_id,authorization_id,event_sequence),
  KEY idx_prm_manual_quote (tenant_id,quote_id,occurred_at,authorization_id),
  CONSTRAINT fk_prm_manual_quote FOREIGN KEY (tenant_id,quote_id) REFERENCES prm_quote(tenant_id,quote_id),
  CONSTRAINT fk_prm_manual_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_prm_manual_state CHECK (state IN ('PENDING_APPROVAL','APPLIED','REJECTED')),
  CONSTRAINT ck_prm_manual_action CHECK (action_type IN ('LINE_FIXED_PRICE','ORDER_AMOUNT_OFF','ORDER_PERCENT_OFF','ROUNDING')),
  CONSTRAINT ck_prm_manual_payment CHECK (payment_method IN ('CASH','NON_CASH')),
  CONSTRAINT ck_prm_manual_amount CHECK (event_sequence>0 AND incremental_discount_minor>0
    AND without_approval_minor>=0 AND with_approval_minor>=without_approval_minor
    AND minimum_line_payable_minor>=0 AND maximum_rounding_minor>=0),
  CONSTRAINT ck_prm_manual_sod CHECK ((state='PENDING_APPROVAL' AND approver_user_id IS NULL)
    OR (state='APPLIED' AND (approver_user_id IS NULL OR approver_user_id<>operator_user_id))
    OR state='REJECTED')
) ENGINE=InnoDB COMMENT='Gate 5A人工优惠授权只追加审计事件';

DELIMITER $$
CREATE TRIGGER trg_prm_manual_no_update BEFORE UPDATE ON prm_manual_price_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_manual_price_audit is immutable'; END$$
CREATE TRIGGER trg_prm_manual_no_delete BEFORE DELETE ON prm_manual_price_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_manual_price_audit is immutable'; END$$
DELIMITER ;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200909,'人工优惠授权',9200900,9,'#','',NULL,'',1,0,'F','0','0','promotion:manual:authorize','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'执行阈值内人工优惠或创建待复核申请'),
(9200910,'人工优惠复核',9200900,10,'#','',NULL,'',1,0,'F','0','0','promotion:manual:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'由不同已认证主体复核超阈值人工优惠'),
(9200911,'人工优惠审计',9200900,11,'#','',NULL,'',1,0,'F','0','0','promotion:manual:audit:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'读取门店数据范围内人工优惠只追加审计');
