# 候选 SQL、索引、影响与回退分析

## SQL 候选

候选 Mapper 仍只读 `rpt_payment_reconciliation`，强制可信 `tenant_id`、日期范围和授权门店集合，
可选差异/处理状态；使用 `(business_date,reconciliation_id)` 的字典序 after 条件、相同 `ORDER BY`
和强制 `LIMIT`。Payment 事实候选批量读取必须在 Payment Owner Mapper 内使用有界引用集合，禁止
Reporting 直接联查 `pay_*`。本阶段只保存结构草案，不修改正式 XML。

## 当前计划与候选索引

当前 `idx_rpt_recon_query(tenant_id,store_id,business_date,difference_type,handling_state)` 服务于单门店
v1 筛选，但不完整覆盖多门店全局 keyset 顺序。固定基线已经观察到 filesort。

| 候选 | 列顺序 | 目标 | 风险/待验证 |
|---|---|---|---|
| A 顺序对齐 | tenant, date, reconciliation_id | 多门店全局 keyset | 门店/状态筛选可能扩大扫描 |
| B 状态优先 | tenant, difference, handling, date, reconciliation_id | 差异工作队列 | 无状态筛选时利用率下降 |
| C 现有族 | tenant, store, date, difference, handling | v1 单门店 | 多门店排序可能继续 filesort |

后续运行时若获准，必须在相同 10k/100k 数据、相同参数和统计信息上比较 EXPLAIN JSON/TREE、
实际扫描行、filesort、查询数、写放大及磁盘增量。不能用墙钟波动或静态推断决定索引。

## 快照一致性影响

对账投影的 `handling_state/version/updated_at` 可变。候选游标必须绑定来源检查点和范围摘要；每页/
每批前后验证快照，漂移则失败关闭。若运行时证明无法依靠现有检查点可靠实现，必须停止并为只追加
读世代/快照对象提交独立 CR 与前向迁移，不得用弱游标伪造“0 缺失”。

## 停止线与回退

- 当前 `indexChangeAuthorized=false`、`migrationChangeAuthorized=false`；V90 不得创建；
- 如需索引，只提交独立索引 CR、在线 DDL 影响和唯一前向迁移；未经确认不得实施；
- 不修改 V1—V89，不删除/调整既有索引，不改变 API v1、事件、金额、状态、差异或租户语义；
- 计划改善但十二项守恒、权限、重复/缺失或 UNKNOWN 语义任一失败，仍为 `NO-GO`；
- 回退只关闭 v2/批量装配，保留 v1 和全部历史，不通过逆向迁移覆盖数据库历史。
