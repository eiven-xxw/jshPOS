# MySQL 8.4.11 执行计划与索引停止线

专用 `SalesKeysetRemediationMySqlIT` 从 22 个正式迁移目录建立 V87 空库，只向临时数据库写入
10k/100k 销售合成投影。每档保存正式 XML 摘要、展开后的 JDBC SQL 摘要、参数、`EXPLAIN JSON`、
`EXPLAIN ANALYZE TREE`、`SHOW INDEX`、查询次数、租户攻击和金额守恒。

功能通过条件：交互单页一次查询且最多 501 条（含 hasMore 探针），流式导出无重复无缺口，跨租户
返回 0，`gross-discount+surcharge=receivable`。计划判据不使用墙钟抖动。

如 100k 计划仍出现事实全扫、filesort 或扫描放大，结论必须为
`STOP_AND_REQUEST_INDEPENDENT_INDEX_CR`。本批不得直接创建索引；索引 CR 必须给出候选列顺序、
写放大/磁盘影响、在线 DDL、唯一前向迁移版本、回退和 10k/100k 前后计划对比。

## 实际结果

专项 Run `32979209873` 在 MySQL 8.4.11 完成实测：

- 10k：授权行 8,000；交互 1 次；导出 1 次；重复/缺失/越权均为 0；金额守恒；
- 100k：授权行 80,000；交互 1 次；导出 9 次；重复/缺失/越权均为 0；金额守恒；
- 两档均观察到 `Table scan + filesort`；100k 实际扫描 100,000 行、筛选 80,000 行后取 501 行；
- 已提交 `CR-T2G10A-018`，结论为 `STOP_AND_REQUEST_INDEPENDENT_INDEX_CR`；
- 未经确认，`V202608260088` 与 `idx_rpt_sales_keyset` 仅为提案，仓库迁移和索引变化保持 0。
