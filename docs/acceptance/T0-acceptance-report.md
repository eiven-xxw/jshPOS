# 鲸熵汇收银系统 T0 技术基线最终封板报告

报告日期：2026-08-16

基线阶段：T0 需求治理、工程骨架与质量门禁

验收结论：**T0 技术门禁通过；基线 tag 待项目发起人确认**

## 1. 封板对象与边界

- 正式仓库：`https://github.com/eiven-xxw/jshPOS`；Codeup 仅保留可选镜像配置。
- 代码候选 SHA：`9fd73f04e1b1304071ad5bfd4259fb84679d926c`。
- 全量 CI：GitHub Actions `T0 Quality Gates` #34，run ID `31898880851`，结论 `success`。
- Repository-local Dependency Review：PR #16，`https://github.com/eiven-xxw/jshPOS/pull/16`；原生 GitHub Advanced Security 能力受当前私有仓库套餐限制。
- 待确认 tag：`t0-baseline-2026-08-16`；本报告提交与合并均不得创建该 tag。

本次仅验收技术基线，不验收收银业务能力，不代表可试营业或可商用上线。已交付需求治理、ADR/RTM、Monorepo、固定工具链、RuoYi-Vue-Plus 服务端、Vue 管理后台、Flutter Android POS、Kotlin 设备适配层、契约骨架、MySQL/Redis Compose、CI 与供应链门禁。明确未开发订单、支付、退款、库存、促销、会员、离线同步或连接器正式业务，T1 未启动。

## 2. 最终门禁判定

| 门禁 | 结论 | 封板证据 |
|---|---:|---|
| G0 需求治理与追踪 | PASS | 29 个结构必需项、24 条 RTM/12 条 T0、契约校验通过 |
| G1 架构与范围 | PASS | ADR-001—015、模块边界、变更记录和禁止 T1 业务范围生效 |
| G2 服务端 | PASS | JDK 21 + Maven 3.9.9；37 个 reactor 模块 `clean verify`；兼容性测试通过 |
| G3 管理后台 | PASS | frozen install、high audit、生产构建、lint、typecheck、测试入口通过 |
| G4 Flutter/Kotlin/Android | PASS | 两个 analyze、设备适配器 3 测试、POS 1 测试、Gradle 单测、APK 构建通过 |
| G5 基础设施 | PASS | MySQL 8.4/Redis 7.4 Docker Compose 解析通过；三个 Java 镜像已改为非 root |
| G6 供应链安全 | PASS | 410 组件 SBOM；HIGH/CRITICAL 漏洞、未批准许可证、其余依赖、密钥、IaC 均为 0 |
| G7 制品 | PASS | APK、SBOM、前端许可证清单均已下载并核对大小与 SHA-256 |
| G8 PR 依赖评审 | PASS（合并前置） | PR #16；repository-local `dependency-review` 成功后方可合并本报告 |
| G9 范围控制 | PASS | 未进入 T1，未实现订单/支付/库存/促销等正式业务 |

详细 Job、制品 ID、摘要和修复过程见 `docs/evidence/T0-seal-2026-08-16.md`。

## 3. 制品与测试摘要

| 项目 | 结果 |
|---|---|
| APK | `app-debug.apk`，154,809,692 B，SHA-256 `06a25c608d32b70eaf6b34c07e6215acb98abd92ec11f47af8696538d2278dd4` |
| 服务端 SBOM | CycloneDX 1.6，410 components / 411 dependencies；漏洞 HIGH/CRITICAL = 0 |
| 前端许可证 | 471 条记录、12 类许可证；GPL-3.0/AGPL-3.0 = 0；high audit 无已知漏洞 |
| Android/Kotlin | `testDebugUnitTest` 成功，debug APK 成功生成并上传 |
| Compose | `docker compose --env-file .env config --quiet` 成功 |
| 安全 | 精确核对 14 个受限/多许可证组件；未批准 GPL/AGPL、密钥、HIGH/CRITICAL IaC = 0 |

## 4. 验收决定

1. 将 RTM 中 12 条 T0 需求更新为 `ACCEPTED`；V1/T2—T4 需求继续保持 `DRAFT`。
2. 允许准备但不创建 `t0-baseline-2026-08-16` tag；项目发起人确认前不得打 tag。
3. 不启动 T1，不开发订单、支付、退款、库存、促销等正式业务。
4. 本结论是 T0 技术基线验收，不是商业发布、支付合规、许可证法律意见或试营业验收。

## 5. 风险与强制后续项

| 风险/事项 | 当前控制 | 商用前关闭条件 |
|---|---|---|
| GitHub 私有仓库当前套餐无 Branch Protection/原生 Dependency Review | repository-local PR 依赖评审 + 六项 Actions + RTM/ADR 流程补偿，禁止团队成员直推 main | 升级套餐并启用 Advanced Security、required checks/PR review；或迁至具备同等保护的私有仓库 |
| Aviator、simple-http 许可证义务 | 精确坐标/版本门禁，技术准入不等同法务批准 | 替换、取得适用许可，或书面法务意见及履约方案 |
| MySQL Connector/J 商业分发 | 不假设 Universal FOSS Exception 覆盖闭源分发 | Oracle 商业许可、法务批准的替代驱动或产品许可模式调整 |
| `crypto-js` 停止维护及 AES-ECB | 不进入 T0 业务扩展 | 独立 ADR、双端迁移、兼容回归和安全测试通过 |
| 真实硬件/支付/连接器/灾备未验收 | 明确排除 T0 商用结论 | 按 T1—T4 和商业 V1 计划取得证据与联合签署 |

## 6. Tag 前最终确认

建议 tag：`t0-baseline-2026-08-16`。创建 tag 后应指向最终 evidence PR 合并后的 `main` 提交，而不是仅指向代码候选 SHA；tag 应使用 annotated tag，说明 T0 范围、CI run 和本报告路径。

当前停止点：**T0 已具备封板条件，等待项目发起人确认创建 tag。** 在该确认到达前，不创建 tag、不启动 T1。

## 7. 签署记录

| 角色/证据 | 结论 | 日期 | 备注 |
|---|---|---|---|
| 自动化质量门禁 | 通过 | 2026-08-16 | GitHub Actions #34 六个 Job 全绿 |
| 安全/供应链门禁 | 通过 | 2026-08-16 | 漏洞、许可证、密钥、IaC 门禁通过；制品已复核 |
| 项目发起人 | 待最终确认 tag |  | 确认只授权创建 T0 tag，不自动授权启动 T1 |
