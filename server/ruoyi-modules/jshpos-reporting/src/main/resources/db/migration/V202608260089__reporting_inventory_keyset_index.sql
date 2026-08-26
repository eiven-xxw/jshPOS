-- CR-T2G10A-024：为版本化库存成本日报 keyset 分页增加确定性复合索引。
-- 仅新增二级索引；既有查询、字段、约束和索引均保持不变。
ALTER TABLE rpt_inventory_cost_daily
  ADD INDEX idx_rpt_inventory_keyset (
    tenant_id,
    projection_version,
    business_date,
    store_id,
    warehouse_id,
    sku_id,
    currency
  ),
  ALGORITHM=INPLACE,
  LOCK=NONE;
