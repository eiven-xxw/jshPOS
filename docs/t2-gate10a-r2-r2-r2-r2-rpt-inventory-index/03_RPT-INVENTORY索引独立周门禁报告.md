# RPT-INVENTORY 索引独立周门禁报告

## 1. 结论

- 子批结论：`CONDITIONAL PASS / INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`
- 验证候选：`0488897009f249ab1887da24cd036186a8320f40`
- 完整 CI：[Run 32993457583](https://github.com/eiven-xxw/jshPOS/actions/runs/32993457583)，10/10 Job 成功
- `G10A-SQL-P2-001`：继续 `OPEN`
- `G10A-RES-P2-001`：继续 `PREPARED`
- 外部执行：`0`

本结论只确认 CR-T2G10A-024 授权的 V89 单一索引子批完成内部验证，不关闭整个 SQL
Finding，不代表生产容量、完整 Alpha、生产、商业验收或商业 SLA。

## 2. 实施范围

唯一生产数据库变化为新增：

`V202608260089__reporting_inventory_keyset_index.sql`

它只在 `rpt_inventory_cost_daily` 增加：

`idx_rpt_inventory_keyset(tenant_id, projection_version, business_date, store_id, warehouse_id, sku_id, currency)`

V1—V88、生产 Java、正式 SQL/Mapper、API、事件、既有索引以及库存、成本、租户和同步
语义均未修改。

## 3. 先红后绿

1. V89 尚不存在时，静态迁移策略测试按预期失败，失败断言精确指向缺少获批索引；
2. 红基线独立提交为 `c6e30f6`，未通过跳过测试或放宽断言转绿；
3. V88 MySQL 实际红计划为 `fullScanObserved=true`、`filesortObserved=true`、
   `approvedIndexObserved=false`；
4. 新增 V89 后，同一策略测试与 MySQL 计划门禁转绿；未重跑失败 Job。

## 4. MySQL 8.4.11 迁移验收

| 项目 | 结果 |
|---|---|
| 空库迁移到 V89 | PASS |
| V88 升级到 V89 | PASS，恰好执行 1 项迁移 |
| 重复执行 | PASS，后续迁移数 0 |
| Flyway validate | PASS |
| 索引列顺序 | PASS，与 CR 七列精确一致 |
| 既有索引 | 保留，未删除或调整 |
| MySQL 版本 | 8.4.11 |

## 5. 10k/100k 执行计划与正确性

| 数据档 | 授权行 | 交互查询 | 导出查询/预算 | 命中新索引 | 全扫 | filesort | 重复/缺失/越权 | 十二项守恒 |
|---|---:|---:|---:|---|---|---|---|---|
| 10k | 8,000 | 1 | 1/1 | 是 | 否 | 否 | 0/0/0 | PASS |
| 100k | 80,000 | 1 | 9/9 | 是 | 否 | 否 | 0/0/0 | PASS |

十二项覆盖在手、可用、预占、数量流水、采购/盘点/调拨数量、库存价值、销售成本以及
采购/盘点/调拨成本。以上均为固定虚构数据内部基线，不形成生产容量或商业 SLA。

## 6. 完整 CI 与制品

以下 10 项全部成功：治理 Ubuntu、治理 Windows、Server、Web、Flutter Ubuntu、
Flutter Windows、Android/Kotlin、MySQL 8.4.11、Security、Evidence。

- Run 起止：2026-08-26 17:19:37Z—17:28:19Z；
- MySQL Artifact：`9615830998`，SHA-256
  `7830c033fa40d6b0fd3bbad026352212be52e90692ab17f3a04a4d24e205b90b`；
- Evidence Artifact：`9615842261`，SHA-256
  `e52e5fab463c076d29b366117f23b49e2c08288f3fbbdfbd7cc2c433911dd9c6`；
- 证据索引覆盖 9 个生产者、325 个文件，决策为
  `INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。

## 7. Go/No-Go

- 对本索引子批：建议 `CONDITIONAL PASS`；
- 对 `G10A-SQL-P2-001`：仍为 `OPEN`，不得关闭；
- 对 `G10A-RES-P2-001`：仍为 `PREPARED`，不得启动；
- 等待项目发起人确认后，才可考虑 RPT-PAY-REC 的独立准备/整改；
- RES、R3、完整 Alpha、生产发布与所有外部执行继续禁止。

## 8. 建议的下一步操作指令

确认本报告后，只建议启动 `RPT-PAY-REC` 精确整改准备：冻结 Provider 无关支付/退款/
内部账单对账报表的 v1 兼容、Owner 批量读取、keyset 分页、受控导出、查询数、MySQL
10k/100k 计划、租户权限、差异守恒和故障 seed。准备阶段不得修改运行时 SQL、Mapper、
索引、数据库对象或迁移；需要索引时必须再次独立 CR。
