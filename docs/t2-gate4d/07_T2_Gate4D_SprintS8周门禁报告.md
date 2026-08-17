# T2 Gate 4D / Sprint S8 周门禁报告

> 文档编号：JSH-POS-T2-G4D-007
> 日期：2026-08-17
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 4D 分支起点：`b84555ee7af02c650a47146426f6c9e4029827b5`
> 设计准入提交：`2596a1e0fe5c2203ab89972bcd95a4d61c19c06d`
> 首轮技术候选：`f6ff3ea0c0540078e801ffc98a1b6215c38ef684`
> 修复后候选：`94f6c48e56ab767c52302ffcc462786afae2d393`
> 首轮失败 CI：[T2 Gate 4D Quality Gates #31987101249](https://github.com/eiven-xxw/jshPOS/actions/runs/31987101249)
> 修复后候选 CI：[T2 Gate 4D Quality Gates #31987472677](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 4D 获准的同租户、仓级基础调拨已经完成设计、正式实现和内部验证，覆盖申请、提交、审批、发出、在途、分次收货、差异处理、关闭、发出前取消，以及来源/目的库存和成本的幂等可对账闭环。修复后候选在 GitHub Ubuntu、Windows 与 MySQL 8.4.6 干净执行器运行十个 Job，全部通过；没有降低覆盖率或安全阈值、跳过失败测试、自动重跑掩盖 Flaky 或创建绿色占位。

建议将 `T2-TRF-001` 保持为 `VERIFIED` 并提交 `CONDITIONAL PASS`，等待项目发起人确认后方可更新为 `ACCEPTED`。最高证据等级为 `STATIC + UNIT + MYSQL_INTEGRATION + FLUTTER_REGRESSION + SYNTHETIC_VECTOR`，不包含真实设备、物理断电、真实历史迁移、容量长稳、支付沙箱、设计伙伴或商业验收，不能宣称 Alpha、可试点或可商用。

`T2-PRM-001`、`T2-PRM-002`、`T2-PRM-003` 仅完成 Gate 5 设计、契约和固定合成向量准备，继续 `DRAFT`，没有促销表、服务、Controller、任务或运行时。`T2-PAY-002` 继续 `BLOCKED`，本轮 Provider 网络调用为 0。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-TRF-001` | `VERIFIED` | 申请、审批、发出、在途、部分/最终收货、差异、取消、库存双事实、成本继承、幂等、对账、租户隔离 | 跨租户/跨公司调拨、配送路由、波次/装箱/车次、批次/序列号、真实仓库长稳 |
| `T2-PRM-001` | `DRAFT` | 规则版本、作用域、优先级、互斥/叠加与解释设计 | 无正式规则运行时和迁移 |
| `T2-PRM-002` | `DRAFT` | 手工改价、整单折扣、抹零权限与审计设计 | 无正式优惠计算或 POS 集成 |
| `T2-PRM-003` | `DRAFT` | 优惠分摊、稳定余数顺序、退款恢复设计 | 无正式分摊或退款联动 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B 真实资料核验清单 | 缺首接 Provider 授权沙箱、测试终端、正式接口和联系人 |

既有 Gate 0—4C 状态保持不变；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。Fake 与合成证据没有解除任何外部阻断。

## 3. 架构与数据主权结果

- 新增模块化单体 `jshpos-transfer`，独占调拨单、调拨行、不可变在途账、处理记录、调拨 Outbox 与调拨审计；调拨领域逻辑未写入 Controller、通用工具类或 RuoYi 系统模块。
- 调拨 Owner 只编排业务事实。所有数量效果通过已接受的库存 Owner 生成不可变 `TRANSFER_OUT`/`TRANSFER_IN` 流水；所有成本效果通过已接受的成本 Owner 生成只追加成本流水，禁止调拨模块直接更新库存/成本余额或历史流水。
- 发出与收货是两个独立、稳定幂等事实。发出时冻结来源仓单位成本和总成本快照；目的仓收货按本次实际收货数量继承该快照并参与目的仓移动加权，不使用目的仓当前成本伪造调拨成本。
- 在途账只追加，发出增加、收货/差异结算减少；调拨行的汇总值是可重建投影。对账同时比较单据、在途、库存与成本事实，不能以修改历史流水消除差异。
- `tenant_id` 只来自可信服务端上下文；来源仓和目的仓均执行数据范围校验。请求体、路径、查询参数、Mapper、XML、任务、缓存、导出和对象存储均不得覆盖可信租户。
- 简单持久化复用 MyBatis/MyBatis-Plus；锁定、汇总、幂等竞争恢复和复杂约束 SQL 保留在 Mapper XML。核心类、实体、状态与不变量具备中文注释。

## 4. 状态、精度、不变量与回退

- 主状态机为 `DRAFT → SUBMITTED → APPROVED → IN_TRANSIT → PARTIALLY_RECEIVED/CLOSED`；最终短收进入 `DIFFERENCE_PENDING`，审批差异后关闭。只有发出前允许取消；发出后更正必须使用独立反向事实或后续受控流程，禁止回写。
- 发出采用全量一次模式，收货允许分次；累计收货不得超过发出量。最终短少必须提供 `SHORTAGE`、`DAMAGED`、`REJECTED` 或 `TRANSIT_LOSS` 原因并留下审计，数据库和服务层均强制执行。
- 数量使用 `DECIMAL(19,6)`/`BigDecimal`；单位成本和成本金额使用 `DECIMAL(25,6)`/`BigDecimal`，`HALF_EVEN` 舍入。禁止 `float`/`double` 参与数量和成本计算。
- 商品、单位、换算率、申请数量、发出数量、来源成本、业务日、来源/目的仓、操作人、关联标识、幂等键和内容摘要均在相应冻结点保存。相同键/相同内容返回原结果；相同键/不同内容拒绝。
- Flyway V18 建立调拨、在途、处理记录、Outbox、审计和不可变约束；V19 建立 `9200800` 段权限。SHA-256 分别为 `ea56264ac1b9780e44b415693425e24b3ef02183f5c5e4697d69f435d7340908`、`c98a9fb1d3e2e07d01d44c74a3bcc2afcb7c3eb3f2f8202d8bb6566a6a5b073a`；封印后只能新增前向迁移。
- 应用回退只能关闭新入口并回退应用版本，不能删除成功迁移或覆盖事实。迁移失败、投影异常与游标/对账异常采用安全前向修复；积压恢复必须保留原幂等键和内容摘要。

## 5. 首轮失败、根因与修复

首轮运行 `#31987101249` 没有被计为通过。MySQL 8.4.6 实迁移测试发现，V18 的差异原因 CHECK 在 `reason_code = NULL` 时求值为 SQL `UNKNOWN`；MySQL 将 `TRUE` 或 `UNKNOWN` 都视为满足 CHECK，导致 `DIFFERENCE_PENDING` 行可能缺少原因。

修复在既有迁移尚未封存、尚未发布的 Gate 4D 候选阶段完成：将约束收紧为差异待处理时显式要求 `reason_code IS NOT NULL` 并校验合法枚举，同时补充 MySQL 回归。修复没有放宽业务规则、覆盖率、安全门槛或重试掩盖失败；随后对修复提交完整重跑十个 Job 并全部通过。

## 6. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | 41 模块；CI 聚合 255 tests，0 failure/error/skipped |
| Transfer 核心覆盖率 | PASS | line 44/48 = 91.67%；branch 30/32 = 93.75%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | 20 个 Flyway 迁移完成 validate/migrate；V18/V19、租户复合约束、差异原因、不变性与前向修复通过 |
| 固定合成向量 | PASS | 28/28；覆盖状态、幂等、部分/最终收货、差异、成本继承、并发、迁移与回退 |
| 租户与权限攻击 | PASS | 两个虚构租户、18 个攻击面；越权成功路径 0，外部网络入口 0 |
| 契约与 RTM | PASS | 63 个 Schema/OpenAPI 契约；唯一 Requirement ID、状态与证据规则通过 |
| Flutter Linux | PASS | analyze、测试、覆盖率、Kotlin 编译与 debug APK 通过 |
| Flutter Windows | PASS | 独立 Windows 执行器完成 analyze 和回归 |
| Web 回归 | PASS | audit、build、lint、typecheck、8 tests 与许可证门禁通过 |
| 安全与供应链 | PASS | 服务端/Flutter SBOM、HIGH/CRITICAL 漏洞、Secret、IaC 和许可证门禁通过 |

## 7. GitHub Actions

修复后候选运行 `#31987472677` 为 `run_attempt=1`、总结果 `success`、总时长 9m20s，共产生 10 个制品。

| Job | Job ID | 时长 | 结果 | 主要证据 |
|---|---:|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882406) | 95264882406 | 7s | PASS | 基线祖先、RTM、ADR、契约、范围和未封存迁移规则 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882267) | 95264882267 | 7m32s | PASS | 255 测试、Transfer 覆盖率、Admin JAR、聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882245) | 95264882245 | 1m21s | PASS | MySQL 8.4.6、V18/V19、差异原因与不可变保护 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882329) | 95264882329 | 1m01s | PASS | 可信上下文、双仓权限与 18 面租户攻击 |
| [fixed-vectors](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882338) | 95264882338 | 9s | PASS | 28 个状态/数量/成本/故障固定向量 |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882296) | 95264882296 | 7m05s | PASS | Flutter、Kotlin、debug APK、SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882402) | 95264882402 | 2m33s | PASS | Windows Flutter 干净执行器回归 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95264882387) | 95264882387 | 1m28s | PASS | audit/build/lint/typecheck/测试/许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95265845179) | 95265845179 | 39s | PASS | Trivy、双 SBOM、漏洞/Secret/IaC/许可证 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31987472677/job/95265941668) | 95265941668 | 43s | PASS | 九类上游证据、219 文件与 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、失败测试跳过、阈值降低或绿色占位。GitHub 产生 14 条非阻断告警：部分固定 SHA 的 Action 以 Node 20 为目标并被平台强制在 Node 24 运行，以及 `setup-java@v4` 弃用提示；登记为 P2 供应链维护项，后续只可通过固定 SHA 依赖评审升级。

## 8. 主要制品

| Artifact | ID | 大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate4d-transfer-evidence-bundle` | 9274332397 | 436 MB | `sha256:bfaecd98ddd761355ddcd8287aec8dff03a13e2298282fcbbd1dd815ca0d2860` |
| `t2-gate4d-security` | 9274320984 | 218 MB | `sha256:683d7dee6f7defe0529c099c7966aca3ff21bd132d774047d5289430315d1a4a` |
| `t2-gate4d-server` | 9274310568 | 147 MB | `sha256:63ba65b49d60a02c9d33564192fcd350c1a6f2a6e83243c7fd7e172b48f06a94` |
| `t2-gate4d-pos-linux` | 9274303406 | 71.4 MB | `sha256:af5126dc4c8a97664c27598a18d4ee67559ee0dbd4bb038ebed0bdf1e09bb177` |
| `t2-gate4d-pos-windows` | 9274240993 | 3 KB | `sha256:0040d3e05302804fdda2a931c966ecd290dd8cf5df5977f96e1ae15292365d1c` |
| `t2-gate4d-tenant` | 9274219836 | 47.4 KB | `sha256:e85477c468f6ff9fd5901a012fb3db9b21ec60ebbaa08f2878272351afd91f60` |
| `t2-gate4d-mysql` | 9274224471 | 5.91 KB | `sha256:ab879eae1a725fb5289e4fee239bf5bd914dce22780c0b696a4d26961b7e45e2` |
| `t2-gate4d-web` | 9274226206 | 77.9 KB | `sha256:b4e551c8ad3e21b0a7d1f35f13ca774a909725e884134b254e08622faf4d4fd8` |
| `t2-gate4d-vectors` | 9274207653 | 1.98 KB | `sha256:abf7618a7d8baeaa1daff1d2162372552cd86e3a80e09b2c2da2d425c06ba1c1` |
| `t2-gate4d-governance` | 9274207224 | 1.01 KB | `sha256:15a784e85db17abebe4a5b34336e2317801281c5bcc253807fa5f7a8f68d985d` |

证据聚合器核验九类上游制品并生成 SHA-256 索引，摘要为：

```text
T2-GATE4D EVIDENCE OK: stage=admitted files=219 serverTests=255 vectors=28 paymentNetwork=0
```

## 9. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 缺真实支付授权沙箱、终端、正式接口和技术联系人，继续 `BLOCKED`；本轮支付网络调用为 0。
- P0：主认证 Android 实机、打印、扫码、电子秤、钱箱、客显和物理断电证据缺失，`REAL_DEVICE=0`。
- P1：当前只支持同租户仓级基础调拨，不包含跨公司所有权转移、复杂配送 WMS、波次、装箱、车次、批次/序列号、应付或总账。
- P1：来源成本继承、在途和差异闭环来自合成数据及 CI，不是容量长稳、真实仓库、财务审计或商业验收。
- P1：促销三项仍只有设计证据，不能执行真实优惠、手工改价、优惠分摊或退款恢复。
- P2：GitHub Action Node 20 与 `setup-java@v4` 弃用告警待固定 SHA 升级，不影响本轮结论，但不能长期忽略。
- 外部证据仍为 `sandbox=0`、`realDevice=0`、`pilot=0`；不得用 Fake 或合成数据解除阻断。

## 10. 退出建议

建议项目发起人接受 Gate 4D `CONDITIONAL PASS`，并在明确确认后将 `T2-TRF-001` 从 `VERIFIED` 更新为 `ACCEPTED`。如接受，下一内部阶段建议仅正式准入 Gate 5A 的 `T2-PRM-001`、`T2-PRM-002`、`T2-PRM-003`，按“规则版本与解释 → 授权手工优惠 → 成交快照与稳定分摊/退款恢复”的依赖顺序推进；会员、报表和后续 Gate 继续 `DRAFT`。Gate 3B 继续独立等待真实支付资料。

项目发起人确认本报告前，不得把 `T2-TRF-001` 改为 `ACCEPTED`，不得启动促销、会员、报表、Provider 网络或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
