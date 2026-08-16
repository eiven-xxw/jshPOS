# T2 Gate 1 / Sprint S1 周门禁报告

> 文档编号：JSH-POS-T2-G1-S1-006
> 日期：2026-08-16
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 0 封板起点：`cf2ef29bd74c5d0f8fa5845a689305ebb56c7ef2`
> 实现提交：`af627f7`
> 最终实现候选：`2b00aba29893f5dfbf54fafc4d5f6edb0c4a6705`
> GitHub Actions：[T2 Gate 1 Sprint S1 Quality Gates #31936276008](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008)
> 当前结论：`GATE1 CONDITIONAL PASS / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 1 获准的七项商品、价格和正式服务端数据包切片已完成实现及分级验证，RTM 从 `IN_PROGRESS` 更新为 `VERIFIED`。GitHub 干净执行器的七个 Job 全部通过；MySQL 8.4.6 实际执行四个 Flyway 版本、重复 migrate/validate 和租户数据库约束，安全 Job 以原阈值通过 HIGH/CRITICAL 漏洞、Secret、IaC 与许可证门禁，证据聚合器复核 90 个文件并生成 SHA-256 索引。

本轮建立独立 `jshpos-catalog` 模块，未把领域逻辑放入 Controller、通用工具类或 RuoYi 系统模块；所有持久化入口显式接收可信上下文产生的 tenant_id，Mapper AOP 在可信主体缺失时 fail-closed。商品导入使用 staging 与活动批次指针；价格采用 CNY 最小货币单位整数、版本化发布与门店覆盖；数据包采用 canonical SHA-256、Ed25519 外部签名端口和租户对象命名空间。

当前状态不是 `ACCEPTED`，也不表示 Alpha、试点或商用。外部证据仍为 `sandbox=0`、`realDevice=0`、`pilot=0`。

## 2. 需求状态与边界

| Requirement ID | 状态 | 本轮验证 | 明确保留边界 |
|---|---|---|---|
| `T2-PRD-001` | `VERIFIED` | SPU/SKU、名称、状态、销售属性、乐观锁、审计与 Outbox | 历史订单快照尚未准入 |
| `T2-PRD-002` | `VERIFIED` | 前导零条码、租户唯一、唯一主单位、整数分数换算 | 秤码规则与实机扫码尚未准入 |
| `T2-PRD-003` | `VERIFIED` | 预检、错误明细、幂等键、staging、活动指针、回退及 10k/100k 合成算法 | 流式上传、真实样本和商业吞吐未验收 |
| `T2-PRD-004` | `VERIFIED` | 分类、品牌、状态与版本化扩展属性治理 | 组合/加工/服务类商品未准入 |
| `T2-PRC-001` | `VERIFIED` | 基础价、门店价、门店数据范围、半开时间窗和稳定解析 | 订单成交价冻结留待订单 Gate |
| `T2-PRC-002` | `VERIFIED` | 发布摘要、未来生效、版本裁决、发布后不可变和退役回落 | 本 Gate 仅服务端价格发布 |
| `T2-DPK-001` | `VERIFIED` | 正式服务端全量包、连续版本、N/N-1、摘要、Ed25519 验签与损坏拒收 | 增量包、断点下载、POS 双槽切换为冻结非目标 |

`T2-DPK-001` 的 VERIFIED 只对应 `01_范围决策与逐项准入.md` 已冻结的服务端包切片，不借用 T1 Fake 结果，不把 POS 断点下载或双槽切换标绿。

## 3. 主要交付

- 服务端：商品定义、SPU/SKU、条码多单位、导入批次、价格簿、价格解析、正式数据包、审计与同事务 Outbox；
- 数据库：`V202608160003` 领域表/复合租户外键/检查约束/不可变触发器和 `V202608160004` 十个权限点，SHA-256 账本锁定；
- 契约：正式 OpenAPI、商品/导入/价格/Manifest Schema 与三份事件 Schema；
- Web：商品价格工作台、可信 payload、64 位 ID 边界、金额安全整数与单位精确整数校验；
- CI：governance、server、mysql-migration、tenant-security-capacity、admin-web、security-sbom-license、evidence 七个 Job；
- 合成数据：两个虚构租户、多组织多门店以及便利店、零食折扣店、社区超市边界向量。

## 4. 本地门禁结果

| 门禁 | 当前结果 | 量化结果 |
|---|---|---|
| 完整 RuoYi Admin reactor | PASS | 34 modules `BUILD SUCCESS` |
| Foundation 回归 | PASS | 57 tests；0 failure/error/skipped |
| Catalog 回归 | PASS | 21 tests；0 failure/error/skipped |
| Catalog JaCoCo | PASS | line 97.0%；branch 91.8%；阈值 90%/85% |
| Web | PASS | 8 tests；lint、typecheck、production build 通过 |
| 租户攻击 | PASS | 2 个虚构租户；9 个攻击面；6 个现存面通过且 3 个未准入面 fail-closed |
| 合成容量 | PASS | seed 20260816；10k=11 ms；100k=103 ms |
| 契约/RTM/范围 | PASS | 30 JSON Schema 及 T0/T2 OpenAPI；106 条 RTM；禁入运行时代码 0 |
| MySQL 8.4 / 安全 / SBOM / 许可证 / 证据包 | PASS | GitHub 七个 Job 全绿；90 文件证据索引 |

容量时间只描述本次 Windows 开发机上的 canonical 预检算法，不包含 HTTP 解析、100k 行数据库落库、真实数据分布或 SLA，不能用作采购和商业承诺。

远端证据聚合最终输出：

```text
T2-GATE1 EVIDENCE OK: files=90 serverTests=78 mysqlTests=1 tenantTests=9 webTests=8 line=0.9700 branch=0.9177 capacity=10k+100k
```

## 5. 安全、隔离与回退

- API 不接受 tenant_id；Mapper、原生 SQL、导入、数据包和对象键显式绑定可信 tenant；任务、缓存、导出未准入并保持 fail-closed；
- 金额为 BIGINT 最小货币单位，数量为 BigDecimal/DECIMAL(19,6)，单位换算为约分后的正整数分子/分母；
- 扩展属性自动补齐 schemaVersion，并限制键数、键名、16 KiB 大小及敏感凭据键；
- 导入发布只切换活动批次指针，失败不改变当前版本；价格退役后解析器回落至上一仍有效发布版本；
- 数据包版本严格连续并引用数据库当前前版；损坏、错摘要、错签名、错身份和未知 Schema 拒收；
- KMS/HSM 和对象存储正式端口缺失时返回 503，仓库不保存生产私钥。

## 6. GitHub Actions 与制品

运行时间：2026-08-16 16:22:03—16:30:30（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 核验内容 |
|---|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95138626067) | 95138626067 | PASS | 基线祖先、RTM、准入、契约、迁移摘要与禁入边界 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95138626070) | 95138626070 | PASS | 34 模块 Admin Reactor、78 测试、覆盖率、JAR 与 CycloneDX SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95138626102) | 95138626102 | PASS | 固定 MySQL 8.4.6 镜像、四版 Flyway、repeat=0、validate 与数据库不变量 |
| [tenant-security-capacity](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95138626066) | 95138626066 | PASS | 双租户九面攻击、可信上下文、10k/100k 合成预检 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95138626035) | 95138626035 | PASS | frozen lock、audit、build、lint、typecheck、8 项 JUnit 与许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95139361279) | 95139361279 | PASS | Trivy 0.72.0、HIGH/CRITICAL 漏洞、Secret、IaC、14 个精确复核组件 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31936276008/job/95139421936) | 95139421936 | PASS | 测试/覆盖率/容量/RTM 分级复核和 90 文件 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、阈值降低、跳过失败测试或绿色占位。

| Artifact | ID | 大小（B） | GitHub archive SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t2-gate1-sprint-s1-evidence-bundle` | 9260832091 | 305985377 | `8b6a198fa7f4b1574096a5d8e89735d08637f67613ce130ea709d95e7e206159` | 2026-09-15 08:30:16 |
| `t2-gate1-security` | 9260826070 | 153026066 | `8b1298df16dc2f472d3decd583bb3f2c55da44eda400aec8d3b98ba6ad46aef4` | 2026-09-15 08:29:50 |
| `t2-gate1-server` | 9260819371 | 152840456 | `6cda947828eb11cba7b9f2b87e5fea95aa791b715cb39f5303046604d09d7428` | 2026-09-15 08:29:17 |
| `t2-gate1-web` | 9260750974 | 79793 | `325a15aad90f104e5e4b454f308e4681e213b2b519f0fc577bd637003d2d6220` | 2026-09-15 08:23:27 |
| `t2-gate1-mysql` | 9260751613 | 5644 | `47f02fcf57bdfb03219ac3042a96a94381f852dee5c03eeefa88fa3009c6c26b` | 2026-09-15 08:23:31 |
| `t2-gate1-tenant` | 9260746158 | 26316 | `832c0d74d549e81da21a8ca7a9126604686d966de9fcebfc6a8a97e53107c4c0` | 2026-09-15 08:23:02 |
| `t2-gate1-governance` | 9260739344 | 1427 | `d4d5de39dcac09390a25873f8d7325e40f4e30efe7b6b6484dcb92eea1b8b0fe` | 2026-09-15 08:22:27 |

## 7. 失败记录与修复

首轮候选运行 [#31936005932](https://github.com/eiven-xxw/jshPOS/actions/runs/31936005932) 的 MySQL Job 在 `V202608160003` 创建导入明细表时失败。根因是 `row_number` 在 MySQL 8.4 中与 `ROW_NUMBER` 保留关键字冲突。修复提交 `2b00aba` 将两张导入表的列统一改为 `source_row_no`，同步 Mapper 和迁移 SHA-256 账本；没有跳过迁移、放宽 SQL 模式或降低门禁。首轮失败不计为通过，第二轮在新提交和新 MySQL 容器上完整执行且一次通过。

## 8. 风险与阻断

- P1：100k 当前使用请求内列表和逐行 staging，尚未实现流式上传/分批落库；进入真实试点前必须完成性能加固与数据库基准；
- P1：生产 KMS/HSM、对象存储适配和密钥轮换方案尚未配置，当前只能验证端口契约和临时合成签名；
- P2：Web 生产构建沿用既有大 chunk 警告；不影响本 Gate 正确性，但需在试点性能门禁前拆包；
- `T2-HWD-001`、`T2-PAY-002`、`T2-PAR-001` 继续 `BLOCKED`；`T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`。

## 9. 不可宣称与退出建议

继续禁止订单、支付、退款、库存、采购、成本、促销及后续 Gate 编码；禁止真实支付、生产密钥和未脱敏数据；禁止用 Fake 解除 SANDBOX、REAL_DEVICE 或 PILOT 阻断。

GitHub 七个 Job 与证据聚合均已达到本 Gate 内部门槛，建议项目发起人接受 `GATE1 CONDITIONAL PASS`。项目发起人确认前七项需求不得更新为 `ACCEPTED`，不得进入下一 Gate；确认时仍应保留本报告列出的性能加固、生产 KMS/对象存储和所有外部阻断。
