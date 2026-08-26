# G10A-SQL-P2-001 RPT-SALES 索引独立周门禁报告

## 1. 结论

- 子批结论：`CONDITIONAL PASS / INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`
- 验证候选：`6b0552d1edcde3da89ca1c59b5191be20e919aad`
- 完整 CI：[Run 32983607774](https://github.com/eiven-xxw/jshPOS/actions/runs/32983607774)，10/10 Job 成功
- `G10A-SQL-P2-001`：继续 `OPEN`
- `G10A-RES-P2-001`：继续 `PREPARED`
- 外部执行：`0`

本结论只确认 CR-T2G10A-018 授权的 V88 单一索引子批已完成内部验证，不关闭整个 SQL
Finding，不代表生产容量、完整 Alpha、生产发布或商业 SLA。

## 2. 实施范围

唯一生产数据库变更为：

`V202608260088__reporting_sales_keyset_index.sql`

在 `rpt_sales_daily` 新增：

`idx_rpt_sales_keyset(tenant_id, projection_version, business_date, store_id, terminal_id, cashier_id, currency)`

V1—V87、正式 SQL/Mapper、API、事件、资金/库存/租户/同步语义及既有索引均未修改。

## 3. 先红后绿与失败历史

1. 缺少 V88 时，迁移策略测试按预期失败，证明红基线有效；
2. 首轮候选 `d4d59d2c11d9a29818597ff8b00415aff9f6b371` 的
   [Run 32982737623](https://github.com/eiven-xxw/jshPOS/actions/runs/32982737623) 保留失败；
3. 首轮 MySQL 实际已经命中新索引且无全扫，失败来自测试把 JSON 中
   `using_filesort:false` 的字段存在误判为 filesort；
4. 精确修复只递归识别布尔值 `true`，没有重跑旧失败 Job；新提交完整复跑并全绿。

## 4. MySQL 8.4.11 迁移验收

| 项目 | 结果 |
|---|---|
| 空库迁移到 V88 | PASS |
| V87 升级到 V88 | PASS，恰好 1 项迁移 |
| 重复执行 | PASS，后续迁移数 0 |
| Flyway validate | PASS |
| 索引列顺序 | PASS，与 CR 精确一致 |
| 负版本/旧迁移保护 | PASS |
| MySQL 版本 | 8.4.11 |

V87 红计划继续保留：`fullScanObserved=true`、`filesortObserved=true`、
`approvedIndexObserved=false`。

## 5. 10k/100k 计划与正确性

| 数据档 | 授权行 | 交互查询 | 导出查询/预算 | 命中新索引 | 全扫 | filesort | 重复/缺失/越权 | 金额守恒 |
|---|---:|---:|---:|---|---|---|---|---|
| 10k | 8,000 | 1 | 1/1 | 是 | 否 | 否 | 0/0/0 | PASS |
| 100k | 80,000 | 1 | 9/9 | 是 | 否 | 否 | 0/0/0 | PASS |

100k 金额为 gross `80,000,000`、discount `8,000,000`、surcharge `0`、
receivable `72,000,000`，满足金额守恒。以上均为固定虚构数据内部基线，不形成生产 SLA。

## 6. 完整 CI

以下 10 项全部成功：治理 Ubuntu、治理 Windows、Server、Web、Flutter Ubuntu、
Flutter Windows、Android/Kotlin、MySQL 8.4.11、Security、Evidence。

Server 制品、Android APK、Web、Flutter 双平台、SBOM、许可证、安全扫描、测试报告、
MySQL 计划和证据索引均由同一提交、同一 Run 产生。

## 7. Go/No-Go

- 对本索引子批：建议 `CONDITIONAL PASS`；
- 对 `G10A-SQL-P2-001`：仍为 `OPEN`，不得关闭；
- 对下一批：等待项目发起人再次确认后，才可进入 RPT-INVENTORY；
- RPT-PAY-REC、RES、R3、完整 Alpha、生产发布与所有外部执行继续禁止。
