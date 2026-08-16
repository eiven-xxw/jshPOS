# T2 Gate 0 / Sprint S0 周门禁报告

> 文档编号：JSH-POS-T2-G0-S0-007
> 日期：2026-08-16
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> 工作分支起点：`7fe4391069d8ee6c641d1b3e509d9f90050be5ef`
> 实现候选提交：`d13bb0ad4c0d1d47185559a854723010207e4e50`
> GitHub Actions：[T2 Gate 0 Sprint S0 Quality Gates #31932706063](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063)
> 提交结论：`GATE0 CONDITIONAL PASS / AWAITING CONFIRMATION`

## 1. 管理结论

T2 Gate 0 / Sprint S0 获准的八项平台基座需求已经完成实现与内部验证，RTM 从 `IN_PROGRESS` 更新为 `VERIFIED`。本次实现覆盖可信租户上下文、组织门店与业务日、员工数据范围、版本化行业配置、追加式审计、Gate 0 安全与可观测性、Flyway 迁移治理，并在 Vue 管理端提供对应平台基础工作台。

GitHub 干净执行器上的七个 Job 全部通过；MySQL 8.4.6 执行了真实 Flyway 迁移、重复 migrate/validate、租户复合外键与追加式审计约束验证。证据聚合器重新解析了测试 XML、JaCoCo、七面攻击矩阵与 RTM 状态，并生成 65 个文件的 SHA-256 索引。

本结论只代表 Gate 0 平台切片达到内部门禁。它不表示系统完成 Alpha、具备试点或商用能力，不解除硬件、支付沙箱、设计伙伴和许可证事项，也不授权 Gate 1 正式编码。八项需求在项目发起人确认本报告前不得更新为 `ACCEPTED`。

## 2. 范围与需求状态

| Requirement ID | 状态 | 本轮结果 | 证据边界 |
|---|---|---|---|
| `T2-IAM-001` | `VERIFIED` | 可信主体注入 tenant_id，客户端覆盖被拒绝，七类租户攻击面通过 | 两个虚构租户；未使用真实商户数据 |
| `T2-ORG-001` | `VERIFIED` | 组织、门店、时区与营业日起点规则，跨租户复合外键 | Gate 0 合成数据量；非生产容量验收 |
| `T2-RBAC-001` | `VERIFIED` | Sa-Token 功能权限加组织/门店数据范围，服务端最终授权 | 未包含后续交易域权限 |
| `T2-CFG-001` | `VERIFIED` | 三业态模板版本、发布、激活、回退及发布后不可变 | 仅平台配置，不含商品价格正式实现 |
| `T2-AUD-001` | `VERIFIED` | 关键平台动作追加式审计、摘要与敏感字段清洗 | 订单支付等交易审计尚未准入 |
| `T2-SEC-001` | `VERIFIED` | Gate 0 漏洞、Secret、IaC、许可证和供应链门禁通过 | 不替代等保、渗透或商业安全验收 |
| `T2-OBS-001` | `VERIFIED` | correlation ID、tenant/store MDC 与低基数指标 | 业务 order/payment/device 标识尚未实现 |
| `T2-MIG-001` | `VERIFIED` | 两个 Gate 0 Flyway 脚本、摘要锁定、MySQL 重复验证与前向修复边界 | SQLite、大表与真实历史数据仍未验收 |

Gate 1 的 `T2-PRD-001`—`T2-PRD-004`、`T2-PRC-001`—`T2-PRC-002`、`T2-DPK-001` 继续保持 `DRAFT`。本轮只产生领域说明、DRAFT OpenAPI、JSON Schema、事件、迁移设计和合成向量，没有商品、价格或数据包运行时实现。

## 3. 实现交付

### 3.1 服务端模块化单体

- 正式代码位于独立 `jshpos-foundation` 模块的 `application/domain/infrastructure/interfaces` 分层内；Controller 只做协议与权限入口，没有承载领域决策；
- 复用 RuoYi-Vue-Plus 的 Sa-Token、MyBatis-Plus、租户会话和系统权限能力，未修改框架系统模块的认证、租户与权限核心逻辑；
- `tenant_id` 只由可信会话上下文产生；Mapper AOP 缺失可信上下文时 fail-closed；原生 SQL 显式包含可信租户条件；
- 任务、缓存、导出和对象存储使用租户命名空间或执行前授权；
- 组织移动、门店时区/业务日、员工范围、配置状态与审计不变量均在应用服务内执行；
- 关联标识过滤器负责生成/校验 correlation ID，并清理 MDC，指标只使用低基数标签。

### 3.2 数据与迁移

Flyway 新增两份只增不改的版本脚本，摘要由 `contracts/t2/gate0/migration-checksums.json` 锁定：

- `V202608160001__gate0_foundation.sql`：7 张平台基础表、租户复合外键、状态与形状约束、发布配置不可变触发器、审计 UPDATE/DELETE 阻断触发器；
- `V202608160002__gate0_foundation_permissions.sql`：11 个 Gate 0 权限点及高位保留 ID 冲突阻断；不修改 RuoYi 既有表结构或记录。

MySQL Job 验证首次迁移执行 2 个版本，第二次迁移执行 0 个版本，`validate` 通过；跨租户门店引用以及审计 UPDATE/DELETE 均由数据库拒绝。

### 3.3 API、事件与管理端

- Gate 0 OpenAPI 明确响应封套、权限、错误、业务日和不接受客户端 tenant_id 的约束；
- 三份事件 Schema 覆盖门店、员工范围和配置绑定变更；
- Vue 管理端实现组织、门店、配置和审计平台工作台，写接口统一阻断客户端租户覆盖；
- Web 契约测试直接调用实际 API 包装函数，不使用绿色占位测试。

## 4. 自动化与量化结果

证据聚合最终输出：

```text
T2-GATE0 EVIDENCE OK: files=65 serverTests=57 mysqlTests=1 tenantTests=12 webTests=3 line=0.9685 branch=0.9101
```

| 门禁 | 结果 | 量化结果 |
|---|---|---|
| 服务端 Gate 0 单测 | PASS | 57 tests，0 failures，0 errors，0 skipped |
| MySQL 8.4 Flyway 集成 | PASS | 1 test，2 migrations，repeat=0，7 表，11 权限点 |
| 租户/权限专项 | PASS | 12 tests，0 skipped；7 个攻击面全部 PASS |
| Web 契约 | PASS | 3 tests，真实 JUnit，0 skipped |
| JaCoCo | PASS | line 96.85%（阈值 90%）；branch 91.01%（阈值 85%） |
| Web 工程 | PASS | npm high audit、lint、typecheck、production build |
| 供应链与安全 | PASS | Trivy 0.72.0 HIGH/CRITICAL vulnerability、Secret、IaC、license 门禁；14 个受限许可证组件精确复核 |
| 治理与契约 | PASS | 106 条 RTM；30 份 JSON Schema 及 T0/T2 OpenAPI；Gate 1 状态全部 DRAFT；禁止运行时领域 0 |

七个租户攻击面为 API 输入、Mapper、原生 SQL、后台任务、缓存、导出和对象存储。结果仅证明 Gate 0 的合成双租户边界，不替代后续每个业务模块的重复攻击回归。

## 5. GitHub Actions 结果

运行时间：2026-08-16 14:58:16—15:06:02（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 核验内容 |
|---|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95129867429) | 95129867429 | PASS | 基线祖先、RTM、准入、契约、迁移摘要和禁止范围 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95129867417) | 95129867417 | PASS | 完整 RuoYi Admin reactor、测试、覆盖率、JAR 与 CycloneDX SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95129867454) | 95129867454 | PASS | 固定 MySQL 8.4.6 镜像上的 Flyway 与数据库不变量 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95129867408) | 95129867408 | PASS | 可信上下文、权限、命名空间和双租户七面攻击 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95129867391) | 95129867391 | PASS | 锁定依赖、audit、构建声明、lint、typecheck、JUnit、许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95130493208) | 95130493208 | PASS | SBOM 输入、高危严重漏洞、Secret、IaC 和许可证策略 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31932706063/job/95130547761) | 95130547761 | PASS | 逐份解析测试与覆盖率、RTM 状态、证据分级和 SHA-256 索引 |

Workflow 不包含 `continue-on-error`，没有降低阈值、跳过测试或自动 retry。

## 6. 失败记录与修复

首个候选运行 [#31932373221](https://github.com/eiven-xxw/jshPOS/actions/runs/31932373221) 的 Web Job 失败。根因是 GitHub 干净执行器在 Vite 生成 `auto-imports.d.ts` 前执行 `vue-tsc`，本地残留的忽略文件掩盖了顺序依赖。

修复提交 `d13bb0a` 将顺序调整为先执行生产构建生成声明，再执行 lint、typecheck 和真实 JUnit；没有删除或跳过任何门禁。首轮失败结果未被计为通过，第二轮在新提交上从干净环境完整执行且一次通过，故不属于用重跑掩盖 Flaky。

## 7. 制品与证据

| Artifact | ID | 大小（B） | GitHub archive SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t2-gate0-sprint-s0-evidence-bundle` | 9259836538 | 305,730,997 | `3da4e9d65cd41319b9ad46ee367c6fa330d42deffb8ac8010640c6e965112fcc` | 2026-09-15 07:05:44 |
| `t2-gate0-security` | 9259829458 | 152,899,103 | `9523c6a62557f30ebbaca2f34c64399b7313a5f188d3dfa17b5c1ca5b857a790` | 2026-09-15 07:05:11 |
| `t2-gate0-server` | 9259823143 | 152,714,858 | `6315d4661bc60f779954f257b249999520d0882359a9199fecebc883f0b52984` | 2026-09-15 07:04:36 |
| `t2-gate0-web` | 9259769679 | 79,628 | `c5d13a58693aafd4dd80fb0020c231f2bc4a81511dab510e110d906705a69dad` | 2026-09-15 07:00:00 |
| `t2-gate0-mysql` | 9259767485 | 5,613 | `7b50697484f6c6cdb8a2d9e13cc70ac96c7c8a2a695ee844e6de82bd968bd5f4` | 2026-09-15 06:59:47 |
| `t2-gate0-tenant` | 9259761265 | 26,166 | `4737bdeacdf637a657f4f392dd410edba626b8f46d68fef2dd513ff6adfbc8f6` | 2026-09-15 06:59:13 |
| `t2-gate0-governance` | 9259755156 | 1,293 | `546eb97bf7182c42360114f2ef68ae86472f74007f34dc75af4e849dc29e910f` | 2026-09-15 06:58:39 |

最终证据等级为 `STATIC+UNIT+INTEGRATION`；证据索引明确记录 `sandbox=0`、`realDevice=0`、`pilot=0`。Fake/合成数据没有被提升为外部或商业验收证据。

## 8. 风险、阻断与不可宣称事项

### 8.1 本 Gate 风险

- Gate 0 范围内未发现未关闭 P0/P1 缺陷；
- P2：Trivy 对第三方生成 SBOM 给出覆盖准确性提示，且个别无显式版本的 POM 子依赖无法继续展开。控制为保留 Maven 聚合 CycloneDX、npm audit、许可证精确清单和后续依赖差异门禁；安全 Owner 在 Gate 1 继续核对扫描覆盖；
- P2：Web 生产构建存在既有大 chunk 提示。它不影响本次功能与类型门禁，但 Gate 1 商品工作台扩展前需要建立拆包和性能预算；
- MySQL 结果基于空白合成库，不能推导生产大表锁时长、真实迁移窗口或真实历史数据质量。

### 8.2 状态保持不变

- `T2-HWD-001`、`T2-PAY-002`、`T2-PAR-001` 继续 `BLOCKED`；
- `T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`；
- 实机、外设、支付沙箱、真实断电、设计伙伴和商业许可证没有绿色占位；
- 未开发订单、支付、退款、库存、采购、成本、促销或 Gate 1 商品价格运行时代码；
- 不得宣称系统完成 Alpha、可试点或可商用。

## 9. Gate 0 退出建议

建议项目发起人确认本报告并接受 `GATE0 CONDITIONAL PASS`。确认后可把八项 Gate 0 需求由 `VERIFIED` 更新为 `ACCEPTED`，并单独授权 Gate 1 / Sprint S1；在收到明确指令前停止业务编码。

Gate 1 只应准入 `T2-PRD-001`—`T2-PRD-004`、`T2-PRC-001`—`T2-PRC-002`、`T2-DPK-001`，继续采用逐项设计准入、两个虚构租户、MySQL 实迁移、OpenAPI/事件一致性、10k/100k 合成导入与数据包、金额整数不变量、租户攻击和原子发布回退门禁。订单、支付、库存、促销及所有外部实证继续禁止。
