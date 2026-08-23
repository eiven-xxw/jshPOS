CREATE TABLE prc_member_price_version (
  version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员价版本ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  book_code VARCHAR(64) NOT NULL COMMENT '会员价簿编码',
  version_no INT NOT NULL COMMENT '版本号',
  store_id BIGINT NULL COMMENT '门店范围；空为租户范围',
  state VARCHAR(16) NOT NULL COMMENT '具名状态',
  currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种',
  effective_at DATETIME(3) NULL COMMENT '生效时间UTC',
  expires_at DATETIME(3) NULL COMMENT '失效时间UTC',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变版本摘要',
  created_by BIGINT NOT NULL COMMENT '创建人',
  approved_by BIGINT NULL COMMENT '独立批准人',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (tenant_id,version_id),
  UNIQUE KEY uk_prc_member_price_book_version (tenant_id,book_code,version_no),
  KEY idx_prc_member_price_scope (tenant_id,store_id,state,effective_at,expires_at),
  CONSTRAINT fk_prc_member_price_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_prc_member_price_state CHECK (state IN ('DRAFT','VALIDATED','APPROVED','SCHEDULED','ACTIVE','RETIRED')),
  CONSTRAINT ck_prc_member_price_currency CHECK (currency='CNY'),
  CONSTRAINT ck_prc_member_price_window CHECK (expires_at IS NULL OR (effective_at IS NOT NULL AND expires_at>effective_at)),
  CONSTRAINT ck_prc_member_price_actors CHECK (created_by>0 AND (approved_by IS NULL OR (approved_by>0 AND approved_by<>created_by)))
) ENGINE=InnoDB COMMENT='T2-MEM-003 Pricing Owner会员价版本；XML_ONLY';

CREATE TABLE prc_member_price_item (
  item_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员价明细ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员价版本ULID',
  level_code VARCHAR(32) NOT NULL COMMENT '适用会员等级',
  sku_id BIGINT NOT NULL COMMENT 'SKU标识',
  unit_id BIGINT NOT NULL COMMENT '销售单位标识',
  amount_minor BIGINT NOT NULL COMMENT 'CNY最小货币单位整数',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '明细内容摘要',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,item_id),
  UNIQUE KEY uk_prc_member_price_item (tenant_id,version_id,level_code,sku_id,unit_id),
  KEY idx_prc_member_price_lookup (tenant_id,level_code,sku_id,unit_id,version_id),
  CONSTRAINT fk_prc_member_price_item_version FOREIGN KEY (tenant_id,version_id) REFERENCES prc_member_price_version(tenant_id,version_id),
  CONSTRAINT fk_prc_member_price_item_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES cat_sku(tenant_id,sku_id),
  CONSTRAINT fk_prc_member_price_item_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES cat_unit(tenant_id,unit_id),
  CONSTRAINT ck_prc_member_price_amount CHECK (amount_minor>=0)
) ENGINE=InnoDB COMMENT='T2-MEM-003只追加会员价明细；XML_ONLY';

CREATE TABLE prc_member_price_command (
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令记录ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  command_type VARCHAR(64) NOT NULL COMMENT '命令类型',
  idempotency_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等键ULID',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求摘要',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员价版本ULID',
  result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果摘要',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
  PRIMARY KEY (tenant_id,command_id),
  UNIQUE KEY uk_prc_member_price_command (tenant_id,command_type,idempotency_key)
) ENGINE=InnoDB COMMENT='T2-MEM-003会员价命令幂等；XML_ONLY';

CREATE TABLE prc_member_price_outbox (
  event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Outbox事件ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  event_type VARCHAR(96) NOT NULL COMMENT '版本化事件类型',
  aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员价版本ULID',
  aggregate_version INT NOT NULL COMMENT '聚合版本',
  payload_json JSON NOT NULL COMMENT '无PII事件载荷',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '载荷摘要',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态',
  occurred_at DATETIME(3) NOT NULL COMMENT '业务发生时间UTC',
  PRIMARY KEY (tenant_id,event_id),
  KEY idx_prc_member_price_outbox (tenant_id,status,occurred_at,event_id),
  CONSTRAINT ck_prc_member_price_outbox CHECK (status IN ('PENDING','SENDING','SENT','DEAD'))
) ENGINE=InnoDB COMMENT='T2-MEM-003会员价事件Outbox；XML_ONLY';

DELIMITER $$
CREATE TRIGGER trg_prc_member_price_item_no_update BEFORE UPDATE ON prc_member_price_item FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member price item is immutable'; END$$
CREATE TRIGGER trg_prc_member_price_item_no_delete BEFORE DELETE ON prc_member_price_item FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='member price item cannot be deleted'; END$$
DELIMITER ;

DELIMITER $$
CREATE PROCEDURE jsh_assert_mem003_price_menu_ids()
BEGIN
  IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201149 AND 9201150) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='T2-MEM-003 price sys_menu reserved ID collision';
  END IF;
END$$
DELIMITER ;
CALL jsh_assert_mem003_price_menu_ids();
DROP PROCEDURE jsh_assert_mem003_price_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201149,'会员价发布',9201140,9,'#','',NULL,'',1,0,'F','0','0','pricing:member-price:publish','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'T2-MEM-003会员价版本创建验证批准与发布'),
(9201150,'会员价查询',9201140,10,'#','',NULL,'',1,0,'F','0','0','pricing:member-price:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'T2-MEM-003会员价候选查询');
