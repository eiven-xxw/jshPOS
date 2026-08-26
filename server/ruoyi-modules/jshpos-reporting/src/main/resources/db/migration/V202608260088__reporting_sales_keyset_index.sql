-- CR-T2G10A-018：为版本化销售日报 keyset 分页增加确定性复合索引。
-- 仅新增二级索引；既有查询、字段、约束和索引均保持不变。
ALTER TABLE rpt_sales_daily
  ADD INDEX idx_rpt_sales_keyset (
    tenant_id,
    projection_version,
    business_date,
    store_id,
    terminal_id,
    cashier_id,
    currency
  ),
  ALGORITHM=INPLACE,
  LOCK=NONE;
