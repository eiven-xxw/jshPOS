CREATE TABLE prm_transaction_snapshot (
  snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成交优惠快照ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单Owner只读引用',
  quote_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '促销报价ULID',
  store_id BIGINT NOT NULL COMMENT '可信门店',
  terminal_id VARCHAR(64) NOT NULL COMMENT '报价终端',
  business_date DATE NOT NULL COMMENT '门店业务日',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ISO币种',
  quote_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最终报价摘要',
  snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化成交快照摘要',
  gross_amount_minor BIGINT NOT NULL COMMENT '原金额分',
  discount_amount_minor BIGINT NOT NULL COMMENT '成交优惠分',
  payable_amount_minor BIGINT NOT NULL COMMENT '成交应收分',
  actor_user_id BIGINT NOT NULL COMMENT '冻结操作人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '冻结时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,snapshot_id),
  UNIQUE KEY uk_prm_snapshot_order (tenant_id,order_id),
  UNIQUE KEY uk_prm_snapshot_quote (tenant_id,quote_id),
  KEY idx_prm_snapshot_store_day (tenant_id,store_id,business_date,snapshot_id),
  CONSTRAINT fk_prm_snapshot_quote FOREIGN KEY (tenant_id,quote_id) REFERENCES prm_quote(tenant_id,quote_id),
  CONSTRAINT fk_prm_snapshot_store FOREIGN KEY (tenant_id,store_id) REFERENCES jsh_store(tenant_id,store_id),
  CONSTRAINT ck_prm_snapshot_amount CHECK (gross_amount_minor>=0 AND discount_amount_minor>=0
    AND payable_amount_minor>=0 AND gross_amount_minor=discount_amount_minor+payable_amount_minor),
  CONSTRAINT ck_prm_snapshot_currency CHECK (currency='CNY')
) ENGINE=InnoDB COMMENT='PRM-003不可变成交优惠快照';

CREATE TABLE prm_transaction_allocation (
  allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成交分摊ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成交优惠快照',
  line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交行ULID',
  line_no INT NOT NULL COMMENT '稳定行号',
  sku_id BIGINT NOT NULL COMMENT '成交SKU',
  quantity DECIMAL(19,6) NOT NULL COMMENT '成交基础单位数量',
  gross_amount_minor BIGINT NOT NULL COMMENT '行原金额分',
  discount_amount_minor BIGINT NOT NULL COMMENT '行成交优惠分',
  payable_amount_minor BIGINT NOT NULL COMMENT '行成交应收分',
  source_allocations_json JSON NOT NULL COMMENT '规则及人工授权到该行的来源分摊',
  source_allocations_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源分摊规范摘要',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,allocation_id),
  UNIQUE KEY uk_prm_allocation_line (tenant_id,snapshot_id,line_id),
  UNIQUE KEY uk_prm_allocation_no (tenant_id,snapshot_id,line_no),
  CONSTRAINT fk_prm_allocation_snapshot FOREIGN KEY (tenant_id,snapshot_id)
    REFERENCES prm_transaction_snapshot(tenant_id,snapshot_id),
  CONSTRAINT ck_prm_allocation_quantity CHECK (quantity>0),
  CONSTRAINT ck_prm_allocation_amount CHECK (gross_amount_minor>=0 AND discount_amount_minor>=0
    AND payable_amount_minor>=0 AND gross_amount_minor=discount_amount_minor+payable_amount_minor)
) ENGINE=InnoDB COMMENT='PRM-003不可变成交逐行优惠分摊';

CREATE TABLE prm_refund_allocation_ledger (
  refund_allocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款分摊流水ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交快照',
  refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款Owner只读引用',
  line_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原成交行',
  command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等命令ULID',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令规范化摘要',
  quantity DECIMAL(19,6) NOT NULL COMMENT '本次退回数量',
  gross_amount_minor BIGINT NOT NULL COMMENT '本次原金额恢复分',
  discount_amount_minor BIGINT NOT NULL COMMENT '本次优惠恢复分',
  payable_amount_minor BIGINT NOT NULL COMMENT '本次应退分',
  cumulative_quantity DECIMAL(19,6) NOT NULL COMMENT '执行后累计退回数量',
  cumulative_gross_amount_minor BIGINT NOT NULL COMMENT '执行后累计原金额',
  cumulative_discount_amount_minor BIGINT NOT NULL COMMENT '执行后累计优惠恢复',
  cumulative_payable_amount_minor BIGINT NOT NULL COMMENT '执行后累计应退',
  actor_user_id BIGINT NOT NULL COMMENT '当前认证操作人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (tenant_id,refund_allocation_id),
  UNIQUE KEY uk_prm_refund_line (tenant_id,refund_id,line_id),
  KEY idx_prm_refund_snapshot_line (tenant_id,snapshot_id,line_id,occurred_at,refund_id),
  CONSTRAINT fk_prm_refund_allocation FOREIGN KEY (tenant_id,snapshot_id,line_id)
    REFERENCES prm_transaction_allocation(tenant_id,snapshot_id,line_id),
  CONSTRAINT ck_prm_refund_quantity CHECK (quantity>0 AND cumulative_quantity>=quantity),
  CONSTRAINT ck_prm_refund_amount CHECK (gross_amount_minor>=0 AND discount_amount_minor>=0
    AND payable_amount_minor>=0 AND gross_amount_minor=discount_amount_minor+payable_amount_minor
    AND cumulative_gross_amount_minor>=gross_amount_minor
    AND cumulative_discount_amount_minor>=discount_amount_minor
    AND cumulative_payable_amount_minor>=payable_amount_minor
    AND cumulative_gross_amount_minor=cumulative_discount_amount_minor+cumulative_payable_amount_minor)
) ENGINE=InnoDB COMMENT='PRM-003只追加原快照退款优惠恢复流水';

DELIMITER $$
CREATE TRIGGER trg_prm_snapshot_no_update BEFORE UPDATE ON prm_transaction_snapshot FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_transaction_snapshot is immutable'; END$$
CREATE TRIGGER trg_prm_snapshot_no_delete BEFORE DELETE ON prm_transaction_snapshot FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_transaction_snapshot is immutable'; END$$
CREATE TRIGGER trg_prm_allocation_no_update BEFORE UPDATE ON prm_transaction_allocation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_transaction_allocation is immutable'; END$$
CREATE TRIGGER trg_prm_allocation_no_delete BEFORE DELETE ON prm_transaction_allocation FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_transaction_allocation is immutable'; END$$
CREATE TRIGGER trg_prm_refund_alloc_no_update BEFORE UPDATE ON prm_refund_allocation_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_refund_allocation_ledger is immutable'; END$$
CREATE TRIGGER trg_prm_refund_alloc_no_delete BEFORE DELETE ON prm_refund_allocation_ledger FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prm_refund_allocation_ledger is immutable'; END$$
DELIMITER ;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200912,'成交优惠冻结',9200900,12,'#','',NULL,'',1,0,'F','0','0','promotion:snapshot:freeze','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'冻结订单成交优惠快照与逐行来源分摊'),
(9200913,'退款优惠恢复',9200900,13,'#','',NULL,'',1,0,'F','0','0','promotion:refund:allocate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按原成交快照计算并追加退款优惠恢复'),
(9200914,'成交优惠查询',9200900,14,'#','',NULL,'',1,0,'F','0','0','promotion:snapshot:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'读取门店数据范围内成交优惠与退款恢复事实');
