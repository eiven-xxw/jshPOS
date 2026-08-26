# 候选 SQL、索引、影响与回退分析

## SQL 候选

候选 Mapper 仍只查询 `rpt_inventory_cost_daily`，强制可信 `tenant_id`、`projection_version`、
日期范围和授权门店集合，可选仓库/SKU；使用冻结复合键的字典序 after 条件、相同 `ORDER BY`
和强制 `LIMIT`。本阶段只保存结构草案，不修改正式 XML。

## 索引候选

| 候选 | 列顺序 | 优势 | 风险 |
|---|---|---|---|
| A 顺序对齐 | tenant, version, date, store, warehouse, sku, currency | 对多门店 keyset 排序友好 | 单门店强选择场景可能不如 B |
| B 门店优先 | tenant, version, store, date, warehouse, sku, currency | 对单门店等值+日期范围友好 | 多门店全局排序可能仍 filesort |

现有唯一索引包含 `org_id`，现有查询索引列顺序与候选 keyset 不完全一致。是否新增索引不能靠静态
推断：后续运行时批次必须在相同 10k/100k 分布上对比当前索引、A、B 的 EXPLAIN JSON/TREE、
实际行、排序、查询数、写放大和磁盘增量。

## 决策与停止线

- 当前 `indexChangeAuthorized=false`、`migrationChangeAuthorized=false`；
- 若无候选可同时满足计划、查询预算和语义金标，运行时批次保持 `NO-GO`；
- 如需索引，先提交独立索引 CR，指定唯一 V89+ 前向迁移；未经确认不得实施；
- 不删除既有索引，不修改 V1—V88，不改变 API、事件、库存/成本/租户/同步语义；
- 计划改善但数量、成本、权限、重复或缺失任一失败，仍为 `NO-GO`。
