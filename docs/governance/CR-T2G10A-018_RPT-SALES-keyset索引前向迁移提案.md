# CR-T2G10A-018：RPT-SALES keyset 索引前向迁移提案

## 1. 当前状态

- 状态：`PROPOSED_AWAITING_SPONSOR_CONFIRMATION`
- Finding：`G10A-SQL-P2-001 = OPEN`
- 依据：Gate 10A-R2-R2-R2-R1 的 MySQL 8.4.11 固定 10k/100k 可执行计划
- 证据边界：`INTERNAL_SYNTHETIC_MYSQL84_ONLY`
- 本 CR 只申请索引及唯一前向迁移，不改变查询结果、API、事件、资金、库存、租户或同步语义。

## 2. 可复现实测结论

| 数据档 | 授权租户行数 | 交互查询数 | 流式导出查询数 | 重复/缺失/越权 | 金额守恒 | 执行计划 |
|---|---:|---:|---:|---|---|---|
| 10k | 8,000 | 1 | 1 | 0/0/0 | PASS | `Table scan + filesort` |
| 100k | 80,000 | 1 | 9 | 0/0/0 | PASS | `Table scan + filesort` |

100k 的 `EXPLAIN ANALYZE` 实际读取 100,000 行、筛选 80,000 行后排序并返回 501 行。现有
`uk_rpt_sales_dimension` 在 `business_date` 与 `store_id` 之间包含 `org_id`，现有
`idx_rpt_sales_query` 的顺序为 `tenant_id, store_id, business_date, terminal_id, cashier_id`，
均不能直接满足已冻结的多门店稳定顺序。

## 3. 申请的唯一前向变更

- 候选迁移版本：`V202608260088`
- 候选文件：
  `jshpos-reporting/src/main/resources/db/migration/V202608260088__reporting_sales_keyset_index.sql`
- 候选索引名：`idx_rpt_sales_keyset`
- 候选列顺序：
  `(tenant_id, projection_version, business_date, store_id, terminal_id, cashier_id, currency)`
- 迁移策略：只新增二级索引；优先使用 MySQL 8.4 支持的在线 DDL 能力并在测试中核验
  `ALGORITHM/LOCK` 实际行为；不得删除或改写既有索引。
- 回退策略：应用继续兼容旧 v1 和无新索引的 v2 正确性；若上线前索引门禁失败则停止发布。
  已成功建立的索引不通过下行迁移自动删除，删除必须另行 CR，避免恢复窗口再次产生全表扫描。

## 4. 获批后的强制验收

1. 先增加“缺少候选索引时计划不达标”的失败回归，再新增唯一 V88 前向迁移；
2. 空库到 V88、V87 到 V88、重复执行、Flyway validate 和 MySQL 8.4.11 全部通过；
3. 10k/100k 重新采集 `SHOW INDEX`、`EXPLAIN JSON/TREE` 与 `EXPLAIN ANALYZE`；
4. `fullScanObserved=false`、`filesortObserved=false`，交互查询数维持 1，导出查询数不超过预算；
5. 0 重复、0 缺失、0 跨租户，金额逐字段守恒；负向游标、同筛选异摘要和权限攻击不退化；
6. 比较写放大、索引大小、DDL 时间和锁等待，形成容量影响，不形成生产 SLA；
7. 完整 CI 从新提交运行，不重跑失败 Job，不降低门禁。

## 5. Go/No-Go

- 建议：`CONDITIONAL GO`，仅准入上述单一索引与 V88。
- 未经项目发起人明确确认：`NO-GO`，不得创建迁移、索引或修改 SQL/Mapper。
- 即使本 CR 获批，`G10A-SQL-P2-001` 仍保持 `OPEN`；只有索引计划复验及后续获批查询全部完成，
  才能另行申请关闭 Finding。

