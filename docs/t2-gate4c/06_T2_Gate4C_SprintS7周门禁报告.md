# T2 Gate 4C / Sprint S7 周门禁报告

> 文档编号：JSH-POS-T2-G4C-006
> 日期：2026-08-17
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 4C 分支起点：`02e1854a9b2a63a46323f27457bd61a708f740a9`
> 设计准入提交：`555efaa`
> 技术候选提交：`6017739a2b9685de9c59f4486f5a1c4a1745e398`
> 技术候选 CI：[T2 Gate 4C Quality Gates #31965458348](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 4C 获准的不可变成本流水、仓级移动加权平均成本、出库成本快照、销售退货、采购退货、冲正、负库存暂估与差异结算、余额重建能力，已经完成设计、正式实现和内部验证。GitHub 在 Ubuntu、Windows 与 MySQL 8.4.6 干净执行器运行十个 Job，全部一次通过；没有降低覆盖率或安全阈值、跳过失败测试、自动重跑掩盖 Flaky 或创建绿色占位。

建议将 `T2-CST-001` 保持为 `VERIFIED` 并提交 `CONDITIONAL PASS`，等待项目发起人确认后方可更新为 `ACCEPTED`。本轮最高证据等级为 `STATIC + UNIT + MYSQL_INTEGRATION + FLUTTER_REGRESSION + SYNTHETIC_VECTOR`，不是财务审计、真实门店、长稳、实机、支付沙箱或商业验收，不能宣称 Alpha、可试点或可商用。

`T2-TRF-001` 只补充设计、契约和固定合成验收向量，继续 `DRAFT`，没有调拨表、服务、Controller、任务或库存移动运行时；`T2-PAY-002` 继续 `BLOCKED`，Provider 网络调用为 0。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-CST-001` | `VERIFIED` | 仓级 CNY 移动加权、不可变成本流水、出库快照、原成本退货、负库存暂估/差异、冲正、全量重建、租户隔离 | 多币种、批次/FIFO、生产成本、财务总账、真实门店财务验收 |
| `T2-TRF-001` | `DRAFT` | 申请、审批、发出、在途、收货、差异、取消、幂等和成本继承设计 | 没有正式调拨运行时、在途账或跨仓 E2E |
| `T2-PAY-002` | `BLOCKED` | Gate 3B 真实资料核验清单 | 缺首接 Provider 授权沙箱、测试终端、正式接口和联系人 |

既有 Gate 0—4B 状态保持不变；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。Fake 与合成数据没有解除任何外部阻断。

## 3. 架构与数据主权结果

- 新增模块化单体 `jshpos-costing`，独占 `inv_cost_ledger`、`inv_cost_balance`、成本重放进度、成本审计与成本 Outbox；领域规则未写入 Controller、通用工具类或 RuoYi 系统模块。
- `jshpos-inventory` 继续独占数量流水和库存余额。库存 Owner 在同一数据库事务内写入数量事实并调用 `AuthoritativeCostPostingPort`；成本失败时数量事实、余额、审计和 Outbox 整体回滚，成本模块不得反写数量账。
- 采购模块只提供已确认收货/退货的权威只读事实；草稿、未审批或被取消单据不能成为成本输入。采购价不是成本余额，成本结果只能由成本 Owner 产生。
- 成本流水只追加；余额只是可重建投影。晚到、更正、退货和负库存差异均使用新事实或冲正表达，禁止修改历史成本、库存或采购事实。
- 租户、组织、门店、仓库、币种、商品、来源流水、幂等键、内容摘要和单调序号构成明确边界。`tenant_id` 只来自可信服务端上下文，不接受请求体、路径或查询参数覆盖。
- Mapper 简单持久化复用 MyBatis/MyBatis-Plus 能力；锁定、聚合、重放与复杂约束 SQL 保留在 XML。核心类、实体、状态与不变量具备中文注释。

## 4. 精度、状态、不变量与回退

- 数量统一使用 `DECIMAL(19,6)`/`BigDecimal`；成本金额使用最小货币单位的 `DECIMAL(25,6)`/`BigDecimal`，内部计算采用 `HALF_EVEN`。禁止 `float`/`double` 参与数量和成本计算。
- 成本维度冻结为 `tenant + warehouse + product + unit + currency`；本 Gate 仅允许 `WAREHOUSE/CNY`。同一维度按库存 Owner 单调序号消费，重复同内容返回原结果，同键异内容、序号空洞、非法晚到和跨租户事实均拒绝或隔离。
- 入库按“旧库存价值 + 本次入库价值”重算平均成本；正常出库冻结当时单位成本和出库成本；销售退货优先恢复原销售快照；采购退货使用原收货成本。
- 负库存采用版本化策略：出库先以当时可用成本暂估，后续权威入库以独立差异流水结算，不回写原出库。零库存时数量和总价值归零，历史快照继续保留。
- Flyway V16 建立成本流水、余额、重放和不可变约束，V17 建立 `9200700` 段权限。SHA-256 分别为 `688e1ecbe51314f9b0289ed84edb07161285637b311af3e59f3c3f9c6734aafa`、`93523fcd928ea0f4b2b5be12f8f12e733403d58f8a2a220c7481c175950a8bb6`；封印后只能新增前向迁移。
- 应用回退只能关闭新入口并回退应用版本；不得删除已成功迁移或覆盖成本事实。迁移失败、投影异常和重建中断必须采用安全前向修复，重建以流水摘要和期末对账作为完成条件。

## 5. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | CI 聚合 228 tests，0 failure/error/skipped；本地 clean verify 同样通过 |
| Costing 核心覆盖率 | PASS | line 126/128 = 98.44%；branch 91/106 = 85.85%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | V1—V17 完整 Flyway、重复 migrate/validate、复合租户约束、不可变保护和前向修复 |
| 固定合成向量 | PASS | 24/24；覆盖首笔/连续入库、部分销售、两类退货、零/负库存、晚到、冲突、并发、舍入、重建与迁移 |
| 租户与权限攻击 | PASS | 两个虚构租户、15 个攻击面；越权成功路径 0，外部网络入口 0 |
| 契约与 RTM | PASS | 61 个 Schema/OpenAPI 契约；106 个 Requirement ID；状态和证据规则通过 |
| Flutter Linux | PASS | 既有 38 tests、同步覆盖率、Kotlin 与 debug APK 回归通过 |
| Flutter Windows | PASS | 独立 Windows 执行器完成 analyze、SQLite 与 HTTP 回归 |
| Web 回归 | PASS | audit/build/lint/typecheck/8 tests/许可证门禁通过 |
| 安全与供应链 | PASS | 服务端/Flutter SBOM、HIGH/CRITICAL 漏洞、Secret、IaC 和许可证门禁通过 |

## 6. GitHub Actions

技术候选运行 `#31965458348` 为 `run_attempt=1`、总结果 `success`、总时长 11m54s，共产生 10 个制品。

| Job | Job ID | 时长 | 结果 | 主要证据 |
|---|---:|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838115) | 95209838115 | 9s | PASS | 基线祖先、RTM、ADR、契约、迁移封印和范围差异 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838127) | 95209838127 | 10m05s | PASS | 228 测试、Costing 覆盖率、Admin JAR、聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838121) | 95209838121 | 4m29s | PASS | MySQL 8.4.6、V1—V17、复合租户约束与不可变保护 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838060) | 95209838060 | 4m54s | PASS | 可信上下文、Mapper/XML 与 15 面租户攻击 |
| [fixed-vectors](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838030) | 95209838030 | 5s | PASS | 24 个成本/精度/并发/恢复固定向量 |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838015) | 95209838015 | 7m27s | PASS | Flutter、Kotlin、debug APK、SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838078) | 95209838078 | 7m58s | PASS | Windows Flutter 与 SQLite/HTTP 回归 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95209838079) | 95209838079 | 1m30s | PASS | audit/build/lint/typecheck/测试/许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95211060826) | 95211060826 | 42s | PASS | Trivy、双 SBOM、漏洞/Secret/IaC/许可证 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31965458348/job/95211146927) | 95211146927 | 55s | PASS | 九类上游证据、197 文件与 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、失败测试跳过、阈值降低或绿色占位。GitHub 产生 14 条非阻断告警：部分固定 SHA 的 Action 仍以 Node 20 为目标并被平台强制在 Node 24 运行，以及 `setup-java@v4` 弃用提示；本轮结果不受影响，登记为 P2 供应链维护项，后续须经依赖评审后以固定 SHA 升级。

## 7. 主要制品

| Artifact | ID | 大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate4c-costing-evidence-bundle` | 9268506642 | 436 MB | `sha256:980ad855a492d194141ee6cc311906fcfb4274fb3f6b5995480d899c6bf5979a` |
| `t2-gate4c-security` | 9268494876 | 218 MB | `sha256:f733e649b772f93c7a1da1bfbc96961f289ce8b72504e7794c42f33d11d9956a` |
| `t2-gate4c-server` | 9268485104 | 146 MB | `sha256:15d32a301cb4c78d8cd799ac6a5a440379f599851c19fe43234153e60e8756e9` |
| `t2-gate4c-pos-linux` | 9268450098 | 71.4 MB | `sha256:c5037407ac0d943886586f2ab30fbdba31219ec925552d0ce42d83936f8d767d` |
| `t2-gate4c-pos-windows` | 9268405088 | 2.99 KB | `sha256:ec4f53d5abe536628ff922ca5e594a81ba660e4f3dca7d5be8a037caed9c7cd1` |
| `t2-gate4c-tenant` | 9268418520 | 26.9 KB | `sha256:f80c3b11a4c2cbd4d22cdf50a4c610b3ab78988a71a6aac296d0583f4c61c02f` |
| `t2-gate4c-mysql` | 9268412692 | 5.89 KB | `sha256:d8ab46b393e0c16b9a25ba0ba5a608d3e448fa417bd8bd5f0a3e43c2b19bfb0a` |
| `t2-gate4c-web` | 9268374510 | 77.9 KB | `sha256:7ca91849a7139e701be285a7973b0042c7b830cef9a1a09953917a433e490903` |
| `t2-gate4c-vectors` | 9268356139 | 1.92 KB | `sha256:197a1500cdc3c6539c43e17ae1aa0c50afb159d90b6be0af50d03d57ec6ed4cc` |
| `t2-gate4c-governance` | 9268356788 | 1 KB | `sha256:cccc379b0b46305e2663a54b47fb3905f96813151a0850a01ff22ce8671c6dcf` |

证据聚合器核验九类上游制品并生成 SHA-256 索引，最终摘要：

```text
T2-GATE4C EVIDENCE OK: stage=closure files=197 serverTests=228 vectors=24 paymentNetwork=0
```

## 8. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 缺真实支付授权沙箱、终端、正式接口和技术联系人，继续 `BLOCKED`；本轮支付网络调用为 0。
- P0：主认证 Android 实机、打印、扫码、电子秤、钱箱、客显和物理断电证据缺失，`REAL_DEVICE=0`。
- P1：`T2-TRF-001` 仍无运行时，跨仓在途数量、成本继承和差异闭环尚未形成。
- P1：成本仅为仓级 CNY 移动加权业务成本，不是会计总账、税务、批次/FIFO 或生产成本；真实财务对账和期间结账尚未验证。
- P1：证据来自虚构租户、合成数据与 CI，不是容量长稳、真实历史迁移、实机或商业验收。
- P2：GitHub Action Node 20 与 `setup-java@v4` 弃用告警待后续固定 SHA 升级，不影响本轮门禁结论。
- 外部证据继续为 `sandbox=0`、`realDevice=0`、`pilot=0`；不得用 Fake 或合成数据解除阻断。

## 9. 退出建议

建议项目发起人接受 Gate 4C `CONDITIONAL PASS`，并在明确确认后将 `T2-CST-001` 从 `VERIFIED` 更新为 `ACCEPTED`。如接受，下一内部阶段建议仅正式准入 `T2-TRF-001` 基础调拨，在途、来源/目的库存 Owner 流水及成本继承形成闭环；Gate 5 只允许设计准备，不得正式实现。Gate 3B 继续独立等待真实支付资料。

项目发起人确认本报告前，不得把 `T2-CST-001` 改为 `ACCEPTED`，不得启动调拨、促销、会员、报表、Provider 网络或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
