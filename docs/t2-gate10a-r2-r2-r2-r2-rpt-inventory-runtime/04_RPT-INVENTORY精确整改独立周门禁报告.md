# RPT-INVENTORY 精确整改独立周门禁报告

## 1. 结论

- 门禁结论：`CONDITIONAL_NO_GO_PENDING_INDEX_CR`
- 运行时功能：`IMPLEMENTED_AND_INTERNALLY_VERIFIED`
- 索引/迁移：`NOT_IMPLEMENTED / AWAITING_SPONSOR_CONFIRMATION`
- Finding：`G10A-SQL-P2-001 = OPEN`
- 资源 Finding：`G10A-RES-P2-001 = PREPARED`
- 外部执行：`0`

运行时已完成 Owner 批量读取、v2 keyset 分页、HMAC 签名游标、受控流式导出和原身份恢复；
但 MySQL 8.4.11 在 10k/100k 均观察到全表扫描与 filesort，已按授权停止并提交
`CR-T2G10A-024`。本报告不是本批完成或 Finding 关闭声明。

## 2. 范围与兼容性

- 起点：`f36df63b21bd3bb98ea0d5022f8fe5fac5def72f`
- CI 验证提交：`c11bad82b183d88bccadb5b679b13a75dc0864cc`
- 当前治理候选：以本报告提交为准。
- 复用：`T2-RPT-001`、`T2-API-001`、`CR-T2G10A-014`、`ADR-074`。
- v1 Controller、OpenAPI、Mapper SQL 与响应语义保持冻结兼容。
- 相对准备起点，Flyway/SQLite 迁移变化为 0；没有创建或调整索引。

## 3. 功能与正确性证据

| 项目 | 结果 |
|---|---|
| Owner 批量端口 | `ReportingBatchReadPort.readInventoryCost` 已装配 |
| 交互读取 | v2、单页 1..500、limit+1、稳定 keyset |
| 游标 | HMAC-SHA256，绑定可信租户、投影版本和筛选摘要 |
| 导出 | 固定批量流式输出，保存原请求身份及恢复检查点 |
| 10k | 8,000 授权行；交互 1 次；导出 1 次；0 重复/缺失/越权 |
| 100k | 80,000 授权行；交互 1 次；导出 9 次；0 重复/缺失/越权 |
| 数据守恒 | 7 个数量字段与 5 个成本字段在两档数据均逐项等于权威汇总 |
| 权限/租户 | 可信 tenant_id 与门店范围失败关闭 |
| 本地 Server | 50 个 Maven reactor 模块全绿；Reporting 78 项测试通过 |

## 4. MySQL 停止线证据

- GitHub Actions：[Run 32990329996](https://github.com/eiven-xxw/jshPOS/actions/runs/32990329996)
- 结果：governance Ubuntu/Windows、Server、Web、Flutter Ubuntu/Windows、MySQL 8.4、
  Android、Security、Evidence 共 10 个 Job 全绿。
- MySQL Artifact：`9614637298`
- MySQL Artifact SHA-256：
  `6b4ff8527a9433e73e51de7caa3527a87584b7bf5ecc98d29af85d5cbda1a2ce`
- Evidence Artifact：`9614645522`
- Evidence Artifact SHA-256：
  `b3d6d6f4990ba1356d9fc0952432a8005221808164ea1920f99b26adb7e11bba`
- SQL SHA-256：`b49d8e5f4d8a4b56984cb017a81a0336f506e18e0ad90e61abb13daae5dd3712`
- 100k 逻辑计划 SHA-256：`6424d7cb4096a0116e5e8f315126a234243c8e5ec74522707f5f43aa379864de`
- 100k 实际计划 SHA-256：`4533c9e07bd8a3a5492054f1908345faf8ac27d241f9aef1589da879db543df2`
- 观察：扫描 100,000 行、筛选 80,000 行、排序后返回 501 行；
  `fullScanObserved=true`、`filesortObserved=true`、
  `recommendation=STOP_AND_REQUEST_INDEPENDENT_INDEX_CR`。

## 5. 停止与未解决项

1. 已提交 `CR-T2G10A-024`，候选为唯一前向迁移 `V202608260089` 新增
   `idx_rpt_inventory_keyset(tenant_id,projection_version,business_date,store_id,warehouse_id,sku_id,currency)`；
2. 当前仓库未创建 V89、未执行 DDL、未修改既有索引；
3. 未经项目发起人确认，不得继续索引子批或把本报告更新为 `CONDITIONAL PASS`；
4. `G10A-SQL-P2-001` 继续 `OPEN`，`G10A-RES-P2-001` 继续 `PREPARED`；
5. 外部 BLOCKED、UAT/REL DRAFT、LIC/JSH DEFERRED 与全部零执行边界保持不变。

## 6. 建议决策

建议对 `CR-T2G10A-024` 作限定 `CONDITIONAL GO`：只授权 V89 新增单一复合索引，
先红后绿完成迁移与 MySQL 8.4.11 计划复验。索引子批通过后再回到 RPT-INVENTORY
独立周门禁，不自动进入 RPT-PAY-REC、RES 或后续 Gate。
