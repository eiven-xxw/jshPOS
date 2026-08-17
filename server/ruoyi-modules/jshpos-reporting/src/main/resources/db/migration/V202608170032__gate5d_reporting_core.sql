CREATE TABLE rpt_source_event_inbox (
  source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源事件ULID；租户内幂等键',
  tenant_id VARCHAR(20) NOT NULL COMMENT '由可信认证或受控任务上下文注入的租户标识',
  source_owner VARCHAR(24) NOT NULL COMMENT '权威事实Owner：ORDER SHIFT PROMOTION INVENTORY COSTING',
  source_aggregate_id VARCHAR(64) NOT NULL COMMENT '来源聚合标识；只作追踪不参与租户授权',
  source_sequence BIGINT UNSIGNED NOT NULL COMMENT '来源分区内从1开始的单调序号',
  partition_key VARCHAR(96) NOT NULL COMMENT '来源Owner定义的稳定分区键',
  schema_version VARCHAR(16) NOT NULL COMMENT '来源事件Schema版本',
  projection_version VARCHAR(32) NOT NULL COMMENT '消费时兼容的投影引擎版本',
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范事件内容SHA-256；同键异摘要拒绝',
  occurred_at DATETIME(3) NOT NULL COMMENT '来源事实发生时间UTC',
  business_date DATE NOT NULL COMMENT '来源Owner按门店时区和日切冻结的业务日',
  org_id BIGINT NOT NULL COMMENT '经服务端核验的组织标识',
  store_id BIGINT NOT NULL COMMENT '经服务端数据范围核验的门店标识',
  terminal_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '销售收银终端标识；非销售为空串',
  cashier_id BIGINT NOT NULL DEFAULT 0 COMMENT '销售收银员标识；非销售为0',
  warehouse_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '' COMMENT '库存成本仓库ULID；非库存为空串',
  sku_id BIGINT NOT NULL DEFAULT 0 COMMENT '库存成本SKU标识；非库存为0',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '货币ISO代码；商业V1为CNY',
  metric_family VARCHAR(24) NOT NULL COMMENT '指标族：SALES或INVENTORY_COST',
  order_count BIGINT NOT NULL DEFAULT 0 COMMENT '成交订单数增量',
  cancelled_order_count BIGINT NOT NULL DEFAULT 0 COMMENT '取消订单数增量',
  return_count BIGINT NOT NULL DEFAULT 0 COMMENT '退货次数增量',
  gross_minor BIGINT NOT NULL DEFAULT 0 COMMENT '原价金额增量；最小货币单位整数',
  discount_minor BIGINT NOT NULL DEFAULT 0 COMMENT '优惠金额增量；最小货币单位整数',
  surcharge_minor BIGINT NOT NULL DEFAULT 0 COMMENT '附加金额增量；最小货币单位整数',
  receivable_minor BIGINT NOT NULL DEFAULT 0 COMMENT '应收金额增量；最小货币单位整数',
  refund_minor BIGINT NOT NULL DEFAULT 0 COMMENT '退款金额增量；最小货币单位整数',
  cash_received_minor BIGINT NOT NULL DEFAULT 0 COMMENT '现金实收增量；最小货币单位整数',
  cash_refunded_minor BIGINT NOT NULL DEFAULT 0 COMMENT '现金退回增量；最小货币单位整数',
  shift_difference_minor BIGINT NOT NULL DEFAULT 0 COMMENT '班次差异增量；最小货币单位整数',
  promotion_snapshot_count BIGINT NOT NULL DEFAULT 0 COMMENT '成交促销快照计数增量',
  on_hand_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '在手数量精确增量；基础单位',
  available_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '可用数量精确增量；基础单位',
  reserved_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '预占数量精确增量；基础单位',
  ledger_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '库存不可变流水数量增量；基础单位',
  purchase_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '采购数量影响；基础单位',
  stocktake_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '盘点数量影响；基础单位',
  transfer_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '调拨数量影响；基础单位',
  inventory_value_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '库存价值变化；最小货币单位的六位小数',
  cogs_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '销售成本变化；最小货币单位的六位小数',
  purchase_cost_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '采购成本影响；最小货币单位的六位小数',
  stocktake_cost_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '盘点成本影响；最小货币单位的六位小数',
  transfer_cost_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '调拨成本影响；最小货币单位的六位小数',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '跨Owner关联ULID',
  status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED' COMMENT '处理状态：RECEIVED或APPLIED',
  applied_at DATETIME(3) NULL COMMENT '投影事务成功时间UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Inbox写入时间UTC',
  PRIMARY KEY (tenant_id,source_event_id),
  UNIQUE KEY uk_rpt_source_sequence (tenant_id,source_owner,partition_key,source_sequence),
  KEY idx_rpt_source_replay (tenant_id,business_date,source_owner,partition_key,source_sequence,source_event_id),
  KEY idx_rpt_source_status (tenant_id,status,created_at),
  CONSTRAINT ck_rpt_source_sequence CHECK (source_sequence>0),
  CONSTRAINT ck_rpt_source_family CHECK (metric_family IN ('SALES','INVENTORY_COST')),
  CONSTRAINT ck_rpt_source_status CHECK (status IN ('RECEIVED','APPLIED')),
  CONSTRAINT ck_rpt_source_sales_money CHECK (gross_minor-discount_minor+surcharge_minor=receivable_minor)
) ENGINE=InnoDB COMMENT='Gate 5D来源事实幂等Inbox；XML_ONLY；内容不可改且可重放';

CREATE TABLE rpt_projection_checkpoint (
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  source_owner VARCHAR(24) NOT NULL COMMENT '来源Owner',
  partition_key VARCHAR(96) NOT NULL COMMENT '来源分区键',
  contiguous_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '从1开始最大连续已应用序号',
  maximum_seen_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最大已见序号',
  projection_status VARCHAR(16) NOT NULL COMMENT 'CURRENT或INCOMPLETE',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '检查点更新时间UTC',
  PRIMARY KEY (tenant_id,source_owner,partition_key),
  CONSTRAINT ck_rpt_checkpoint_sequence CHECK (maximum_seen_sequence>=contiguous_sequence),
  CONSTRAINT ck_rpt_checkpoint_status CHECK (projection_status IN ('CURRENT','INCOMPLETE'))
) ENGINE=InnoDB COMMENT='Gate 5D来源消费检查点与缺口状态；XML_ONLY';

CREATE TABLE rpt_projection_registry (
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  metric_family VARCHAR(24) NOT NULL COMMENT 'SALES或INVENTORY_COST',
  active_projection_version VARCHAR(32) NOT NULL COMMENT '当前对查询可见的投影版本',
  version INT NOT NULL DEFAULT 0 COMMENT '原子切换乐观锁版本',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '切换时间UTC',
  PRIMARY KEY (tenant_id,metric_family),
  CONSTRAINT ck_rpt_registry_family CHECK (metric_family IN ('SALES','INVENTORY_COST'))
) ENGINE=InnoDB COMMENT='Gate 5D活动投影版本指针；XML_ONLY';

CREATE TABLE rpt_projection_lineage (
  lineage_id BIGINT NOT NULL AUTO_INCREMENT COMMENT='投影血缘行主键',
  tenant_id VARCHAR(20) NOT NULL COMMENT='可信租户标识',
  source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT='来源事件ULID',
  source_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT='来源内容摘要',
  source_schema_version VARCHAR(16) NOT NULL COMMENT='来源Schema版本',
  source_owner VARCHAR(24) NOT NULL COMMENT='来源Owner',
  source_partition_key VARCHAR(96) NOT NULL COMMENT='来源分区键',
  source_sequence BIGINT UNSIGNED NOT NULL COMMENT='来源序号',
  projection_version VARCHAR(32) NOT NULL COMMENT='目标投影版本',
  metric_family VARCHAR(24) NOT NULL COMMENT='SALES或INVENTORY_COST',
  dimension_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT='目标维度规范SHA-256',
  org_id BIGINT NOT NULL COMMENT='组织标识',
  store_id BIGINT NOT NULL COMMENT='门店标识',
  business_date DATE NOT NULL COMMENT='来源Owner冻结的业务日',
  checkpoint_contiguous BIGINT UNSIGNED NOT NULL COMMENT='处理时最大连续序号',
  checkpoint_maximum_seen BIGINT UNSIGNED NOT NULL COMMENT='处理时最大已见序号',
  projection_status VARCHAR(16) NOT NULL COMMENT='CURRENT或INCOMPLETE',
  processed_at DATETIME(3) NOT NULL COMMENT='血缘写入时间UTC',
  PRIMARY KEY (lineage_id),
  UNIQUE KEY uk_rpt_lineage_event_version (tenant_id,source_event_id,projection_version),
  KEY idx_rpt_lineage_dimension (tenant_id,projection_version,metric_family,business_date,store_id,dimension_sha256),
  KEY idx_rpt_lineage_checkpoint (tenant_id,source_owner,source_partition_key,source_sequence),
  CONSTRAINT ck_rpt_lineage_sequence CHECK (source_sequence>0),
  CONSTRAINT ck_rpt_lineage_checkpoint CHECK (checkpoint_maximum_seen>=checkpoint_contiguous),
  CONSTRAINT ck_rpt_lineage_status CHECK (projection_status IN ('CURRENT','INCOMPLETE')),
  CONSTRAINT ck_rpt_lineage_family CHECK (metric_family IN ('SALES','INVENTORY_COST')),
  CONSTRAINT fk_rpt_lineage_source FOREIGN KEY (tenant_id,source_event_id)
    REFERENCES rpt_source_event_inbox(tenant_id,source_event_id)
) ENGINE=InnoDB COMMENT='Gate 5D可丢弃投影的逐事件血缘与处理检查点；READ_PROJECTION';

CREATE TABLE rpt_sales_daily (
  projection_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RuoYi平台风格投影行主键',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  projection_version VARCHAR(32) NOT NULL COMMENT '可重建投影版本',
  business_date DATE NOT NULL COMMENT '来源Owner冻结业务日',
  org_id BIGINT NOT NULL COMMENT '组织标识',
  store_id BIGINT NOT NULL COMMENT '门店标识',
  terminal_id VARCHAR(64) NOT NULL COMMENT '终端标识',
  cashier_id BIGINT NOT NULL COMMENT '收银员标识',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种；商业V1为CNY',
  order_count BIGINT NOT NULL DEFAULT 0 COMMENT '成交订单数累计',
  cancelled_order_count BIGINT NOT NULL DEFAULT 0 COMMENT '取消订单数累计',
  return_count BIGINT NOT NULL DEFAULT 0 COMMENT '退货次数累计',
  gross_minor BIGINT NOT NULL DEFAULT 0 COMMENT '原价金额累计；最小货币单位整数',
  discount_minor BIGINT NOT NULL DEFAULT 0 COMMENT '优惠金额累计；最小货币单位整数',
  surcharge_minor BIGINT NOT NULL DEFAULT 0 COMMENT '附加金额累计；最小货币单位整数',
  receivable_minor BIGINT NOT NULL DEFAULT 0 COMMENT '应收金额累计；最小货币单位整数',
  refund_minor BIGINT NOT NULL DEFAULT 0 COMMENT '退款金额累计；最小货币单位整数',
  cash_received_minor BIGINT NOT NULL DEFAULT 0 COMMENT '现金实收累计；最小货币单位整数',
  cash_refunded_minor BIGINT NOT NULL DEFAULT 0 COMMENT '现金退回累计；最小货币单位整数',
  shift_difference_minor BIGINT NOT NULL DEFAULT 0 COMMENT '班次差异累计；最小货币单位整数',
  promotion_snapshot_count BIGINT NOT NULL DEFAULT 0 COMMENT '成交促销快照数累计',
  projection_status VARCHAR(16) NOT NULL DEFAULT 'CURRENT' COMMENT 'CURRENT或INCOMPLETE',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '投影更新时间UTC',
  PRIMARY KEY (projection_id),
  UNIQUE KEY uk_rpt_sales_dimension (tenant_id,projection_version,business_date,org_id,store_id,terminal_id,cashier_id,currency),
  KEY idx_rpt_sales_query (tenant_id,store_id,business_date,terminal_id,cashier_id),
  CONSTRAINT ck_rpt_sales_money CHECK (gross_minor-discount_minor+surcharge_minor=receivable_minor),
  CONSTRAINT ck_rpt_sales_status CHECK (projection_status IN ('CURRENT','INCOMPLETE'))
) ENGINE=InnoDB COMMENT='Gate 5D销售收银可丢弃日投影；READ_PROJECTION';

CREATE TABLE rpt_inventory_cost_daily (
  projection_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RuoYi平台风格投影行主键',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  projection_version VARCHAR(32) NOT NULL COMMENT '可重建投影版本',
  business_date DATE NOT NULL COMMENT '来源Owner冻结业务日',
  org_id BIGINT NOT NULL COMMENT '组织标识',
  store_id BIGINT NOT NULL COMMENT '门店标识',
  warehouse_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仓库ULID',
  sku_id BIGINT NOT NULL COMMENT 'SKU标识',
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '币种；商业V1为CNY',
  on_hand_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '在手数量累计变化；基础单位',
  available_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '可用数量累计变化；基础单位',
  reserved_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '预占数量累计变化；基础单位',
  ledger_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '库存流水数量累计；基础单位',
  purchase_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '采购数量累计影响；基础单位',
  stocktake_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '盘点数量累计影响；基础单位',
  transfer_quantity_delta DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '调拨数量累计影响；基础单位',
  inventory_value_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '库存价值累计变化；最小货币单位六位小数',
  cogs_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '销售成本累计；最小货币单位六位小数',
  purchase_cost_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '采购成本累计影响；最小货币单位六位小数',
  stocktake_cost_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '盘点成本累计影响；最小货币单位六位小数',
  transfer_cost_delta_minor DECIMAL(25,6) NOT NULL DEFAULT 0 COMMENT '调拨成本累计影响；最小货币单位六位小数',
  projection_status VARCHAR(16) NOT NULL DEFAULT 'CURRENT' COMMENT 'CURRENT或INCOMPLETE',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '投影更新时间UTC',
  PRIMARY KEY (projection_id),
  UNIQUE KEY uk_rpt_inventory_dimension (tenant_id,projection_version,business_date,org_id,store_id,warehouse_id,sku_id,currency),
  KEY idx_rpt_inventory_query (tenant_id,store_id,business_date,warehouse_id,sku_id),
  CONSTRAINT ck_rpt_inventory_status CHECK (projection_status IN ('CURRENT','INCOMPLETE'))
) ENGINE=InnoDB COMMENT='Gate 5D库存成本可丢弃日投影；READ_PROJECTION';

CREATE TABLE rpt_difference (
  difference_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '差异ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  difference_type VARCHAR(96) NOT NULL COMMENT 'CONTENT_CONFLICT SEQUENCE_GAP REBUILD_MISMATCH等结构化类型',
  source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '可选来源事件ULID',
  state VARCHAR(24) NOT NULL COMMENT 'OPEN ACKNOWLEDGED RESOLVED IGNORED',
  detail_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去敏差异详情SHA-256',
  assigned_to BIGINT NULL COMMENT '可信处理人用户标识',
  resolution_reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '去敏处理原因SHA-256',
  detected_at DATETIME(3) NOT NULL COMMENT '差异检出时间UTC',
  changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '状态变更时间UTC',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (tenant_id,difference_id),
  KEY idx_rpt_difference_state (tenant_id,state,detected_at),
  KEY idx_rpt_difference_source (tenant_id,source_event_id),
  CONSTRAINT ck_rpt_difference_state CHECK (state IN ('OPEN','ACKNOWLEDGED','RESOLVED','IGNORED'))
) ENGINE=InnoDB COMMENT='Gate 5D只记录不回写业务事实的差异与修复状态；XML_ONLY';

CREATE TABLE rpt_projection_rebuild (
  rebuild_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '重建ULID与幂等键',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  projection_version VARCHAR(32) NOT NULL COMMENT '影子投影版本',
  from_date DATE NOT NULL COMMENT '重建起始业务日',
  to_date DATE NOT NULL COMMENT '重建结束业务日',
  state VARCHAR(16) NOT NULL COMMENT 'RUNNING COMPLETED FAILED',
  requested_by BIGINT NOT NULL COMMENT '可信重建申请人',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  event_count BIGINT NOT NULL DEFAULT 0 COMMENT '实际重放来源事件数',
  projection_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '重建投影规范SHA-256',
  created_at DATETIME(3) NOT NULL COMMENT '重建开始时间UTC',
  completed_at DATETIME(3) NULL COMMENT '重建完成或失败时间UTC',
  PRIMARY KEY (tenant_id,rebuild_id),
  KEY idx_rpt_rebuild_state (tenant_id,state,created_at),
  CONSTRAINT ck_rpt_rebuild_range CHECK (to_date>=from_date),
  CONSTRAINT ck_rpt_rebuild_state CHECK (state IN ('RUNNING','COMPLETED','FAILED'))
) ENGINE=InnoDB COMMENT='Gate 5D影子版本重建状态；XML_ONLY';

CREATE TABLE rpt_export_request (
  export_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '导出ULID与幂等键',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范导出申请SHA-256',
  report_type VARCHAR(32) NOT NULL COMMENT 'SALES_DAILY或INVENTORY_COST_DAILY',
  from_date DATE NOT NULL COMMENT '导出起始业务日',
  to_date DATE NOT NULL COMMENT '导出结束业务日；最多31天',
  store_ids_csv VARCHAR(1024) NOT NULL COMMENT '服务端校验且排序后的门店ID集合',
  fields_csv VARCHAR(2048) NOT NULL COMMENT '服务端白名单校验且排序后的字段集合',
  state VARCHAR(24) NOT NULL COMMENT 'REQUESTED APPROVED REJECTED GENERATING READY FAILED EXPIRED',
  approval_required TINYINT(1) NOT NULL COMMENT '是否必须独立审批',
  requested_by BIGINT NOT NULL COMMENT '可信申请人用户标识',
  approved_by BIGINT NULL COMMENT '独立审批人用户标识',
  approval_reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审批原因SHA-256',
  estimated_rows INT NOT NULL COMMENT '生成前预计投影行数；上限100000',
  correlation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联ULID',
  artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'READY制品SHA-256',
  expires_at DATETIME(3) NULL COMMENT '制品过期时间UTC',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME(3) NOT NULL COMMENT '申请时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '状态更新时间UTC',
  PRIMARY KEY (tenant_id,export_id),
  KEY idx_rpt_export_state (tenant_id,state,expires_at),
  CONSTRAINT ck_rpt_export_range CHECK (to_date>=from_date),
  CONSTRAINT ck_rpt_export_rows CHECK (estimated_rows BETWEEN 0 AND 100000),
  CONSTRAINT ck_rpt_export_state CHECK (state IN ('REQUESTED','APPROVED','REJECTED','GENERATING','READY','FAILED','EXPIRED')),
  CONSTRAINT ck_rpt_export_approval CHECK (approved_by IS NULL OR approved_by<>requested_by)
) ENGINE=InnoDB COMMENT='Gate 5D受权安全导出状态机；XML_ONLY';

CREATE TABLE rpt_export_artifact (
  export_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '导出ULID',
  tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
  object_key VARCHAR(256) NOT NULL COMMENT '服务端生成的reporting/tenant/export/hash.csv租户对象键',
  artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '制品内容SHA-256',
  size_bytes BIGINT UNSIGNED NOT NULL COMMENT '制品字节数',
  content_type VARCHAR(64) NOT NULL COMMENT '安全媒体类型',
  created_at DATETIME(3) NOT NULL COMMENT '制品生成时间UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '制品过期时间UTC',
  download_token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前单次下载令牌HMAC摘要',
  token_user_id BIGINT NULL COMMENT '下载令牌绑定的可信用户',
  token_expires_at DATETIME(3) NULL COMMENT '下载令牌过期时间UTC',
  downloaded_at DATETIME(3) NULL COMMENT '令牌首次成功消费时间UTC',
  PRIMARY KEY (tenant_id,export_id),
  UNIQUE KEY uk_rpt_artifact_object (tenant_id,object_key),
  KEY idx_rpt_artifact_expiry (tenant_id,expires_at),
  CONSTRAINT fk_rpt_artifact_export FOREIGN KEY (tenant_id,export_id) REFERENCES rpt_export_request(tenant_id,export_id)
) ENGINE=InnoDB COMMENT='Gate 5D导出制品与单次令牌元数据；XML_ONLY';

DELIMITER $$
CREATE TRIGGER trg_rpt_inbox_content_guard BEFORE UPDATE ON rpt_source_event_inbox FOR EACH ROW
BEGIN
  IF NOT (OLD.source_owner<=>NEW.source_owner) OR NOT (OLD.source_aggregate_id<=>NEW.source_aggregate_id)
    OR NOT (OLD.source_sequence<=>NEW.source_sequence) OR NOT (OLD.partition_key<=>NEW.partition_key)
    OR NOT (OLD.schema_version<=>NEW.schema_version) OR NOT (OLD.projection_version<=>NEW.projection_version)
    OR NOT (OLD.content_sha256<=>NEW.content_sha256) OR NOT (OLD.occurred_at<=>NEW.occurred_at)
    OR NOT (OLD.business_date<=>NEW.business_date) OR NOT (OLD.org_id<=>NEW.org_id)
    OR NOT (OLD.store_id<=>NEW.store_id) OR NOT (OLD.terminal_id<=>NEW.terminal_id)
    OR NOT (OLD.cashier_id<=>NEW.cashier_id) OR NOT (OLD.warehouse_id<=>NEW.warehouse_id)
    OR NOT (OLD.sku_id<=>NEW.sku_id) OR NOT (OLD.currency<=>NEW.currency)
    OR NOT (OLD.metric_family<=>NEW.metric_family) OR NOT (OLD.order_count<=>NEW.order_count)
    OR NOT (OLD.cancelled_order_count<=>NEW.cancelled_order_count) OR NOT (OLD.return_count<=>NEW.return_count)
    OR NOT (OLD.gross_minor<=>NEW.gross_minor) OR NOT (OLD.discount_minor<=>NEW.discount_minor)
    OR NOT (OLD.surcharge_minor<=>NEW.surcharge_minor) OR NOT (OLD.receivable_minor<=>NEW.receivable_minor)
    OR NOT (OLD.refund_minor<=>NEW.refund_minor) OR NOT (OLD.cash_received_minor<=>NEW.cash_received_minor)
    OR NOT (OLD.cash_refunded_minor<=>NEW.cash_refunded_minor) OR NOT (OLD.shift_difference_minor<=>NEW.shift_difference_minor)
    OR NOT (OLD.promotion_snapshot_count<=>NEW.promotion_snapshot_count) OR NOT (OLD.on_hand_delta<=>NEW.on_hand_delta)
    OR NOT (OLD.available_delta<=>NEW.available_delta) OR NOT (OLD.reserved_delta<=>NEW.reserved_delta)
    OR NOT (OLD.ledger_quantity_delta<=>NEW.ledger_quantity_delta) OR NOT (OLD.purchase_quantity_delta<=>NEW.purchase_quantity_delta)
    OR NOT (OLD.stocktake_quantity_delta<=>NEW.stocktake_quantity_delta) OR NOT (OLD.transfer_quantity_delta<=>NEW.transfer_quantity_delta)
    OR NOT (OLD.inventory_value_delta_minor<=>NEW.inventory_value_delta_minor) OR NOT (OLD.cogs_delta_minor<=>NEW.cogs_delta_minor)
    OR NOT (OLD.purchase_cost_delta_minor<=>NEW.purchase_cost_delta_minor) OR NOT (OLD.stocktake_cost_delta_minor<=>NEW.stocktake_cost_delta_minor)
    OR NOT (OLD.transfer_cost_delta_minor<=>NEW.transfer_cost_delta_minor) OR NOT (OLD.correlation_id<=>NEW.correlation_id)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpt source event content is immutable'; END IF;
END$$
CREATE TRIGGER trg_rpt_inbox_no_delete BEFORE DELETE ON rpt_source_event_inbox FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rpt source event cannot be deleted'$$
DELIMITER ;
