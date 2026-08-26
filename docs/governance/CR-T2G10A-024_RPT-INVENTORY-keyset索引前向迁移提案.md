# CR-T2G10A-024：RPT-INVENTORY keyset 索引前向迁移提案

## 1. 当前状态

- 状态：`INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`
- Finding：`G10A-SQL-P2-001 = OPEN`
- 资源 Finding：`G10A-RES-P2-001 = PREPARED`
- 依据：GitHub Actions [Run 32990329996](https://github.com/eiven-xxw/jshPOS/actions/runs/32990329996)
  的 MySQL 8.4.11 固定 10k/100k 执行计划。
- 证据边界：`INTERNAL_SYNTHETIC_MYSQL84_ONLY`
- 本 CR 只申请一个二级索引及唯一前向迁移，不改变查询结果、API v1、事件、库存、成本、
  租户或同步语义。
- 项目发起人确认：2026-08-27 `CONDITIONAL GO`；只授权从
  `30edba224d9acf35f64d81c042b5153e08e2eb66` 建立
  `t2/gate10a-r2-r2-r2-r2-rpt-inventory-index`，先红后绿实施唯一 V89。
- 验证候选：`0488897009f249ab1887da24cd036186a8320f40`；完整 CI
  [Run 32993457583](https://github.com/eiven-xxw/jshPOS/actions/runs/32993457583) 十项 Job 全绿。

## 2. 可复现实测结论

| 数据档 | 授权租户行数 | 交互查询数 | 流式导出查询数 | 重复/缺失/越权 | 十二项守恒 | 执行计划 |
|---|---:|---:|---:|---|---|---|
| 10k | 8,000 | 1 | 1 | 0/0/0 | PASS | `Table scan + filesort` |
| 100k | 80,000 | 1 | 9 | 0/0/0 | PASS | `Table scan + filesort` |

100k 的 `EXPLAIN ANALYZE` 实际扫描 100,000 行、筛选 80,000 行后排序并返回 501 行。
当前唯一键在 `business_date` 与 `store_id` 之间包含不参与本查询排序的 `org_id`；现有
`idx_rpt_inventory_query` 的列序为
`tenant_id,store_id,business_date,warehouse_id,sku_id`，两者均不能满足已冻结的
`tenant_id + projection_version + business_date,store_id,warehouse_id,sku_id,currency`
稳定 keyset 读取。

## 3. 申请的唯一前向变更

- 候选迁移版本：`V202608260089`
- 候选文件：
  `jshpos-reporting/src/main/resources/db/migration/V202608260089__reporting_inventory_keyset_index.sql`
- 候选索引名：`idx_rpt_inventory_keyset`
- 候选列顺序：
  `(tenant_id, projection_version, business_date, store_id, warehouse_id, sku_id, currency)`
- 迁移策略：只新增二级索引；不得删除或改写
  `uk_rpt_inventory_dimension`、`idx_rpt_inventory_query` 或其他既有索引。
- 回退策略：应用继续兼容 v1 及没有候选索引时的 v2 正确性；若发布前索引门禁失败则停止发布。
  已成功建立的索引不通过下行迁移自动删除，删除或替换必须另行 CR。

## 4. 获批后的强制验收

1. 先增加“V89 不存在时计划不达标”的失败回归，再新增唯一 V89 前向迁移；
2. 完成空库至 V89、V88 至 V89、重复执行、Flyway validate 和 MySQL 8.4.11 验证；
3. 10k/100k 重新采集 `SHOW INDEX`、`EXPLAIN JSON/TREE` 与 `EXPLAIN ANALYZE`；
4. `fullScanObserved=false`、`filesortObserved=false`，交互查询数保持 1，导出查询数不超过 1/9；
5. 0 重复、0 缺失、0 跨租户，十二项数量/成本字段逐项守恒；游标、权限和原身份恢复不退化；
6. 核对 DDL 时间、锁等待、索引大小和写放大，只形成内部合成容量结论，不形成生产 SLA；
7. 从新提交完整运行 CI，不重跑失败 Job、不降低门禁。

## 5. Go/No-Go

- 当前结论：索引子批 `CONDITIONAL PASS / INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。
- 建议：确认 CR-T2G10A-024 授权的单一索引与 V89 已完成内部验证；不得据此关闭整个
  SQL Finding。
- 未经确认，不得进入 RPT-PAY-REC、RES、R3、完整 Alpha 或生产发布。
- 即使索引获批并验证通过，`G10A-SQL-P2-001` 仍保持 `OPEN`，等待后续获批查询全部收口。
