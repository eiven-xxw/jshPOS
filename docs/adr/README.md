# 架构决策记录（ADR）

ADR 一经接受不得删除或静默改写。需要改变决策时，新建 ADR 并把旧记录标记为 `Superseded by ADR-xxx`。

状态：`Proposed`、`Accepted`、`Deprecated`、`Superseded`。

每项都应有上下文、决策、后果和验证方式。ADR-027 的模型分型、分层与中文注释决策继续有效，其第 3—4 项逐表单列登记由 ADR-033 的“访问策略 + SQL 模式”双维度决策部分取代。

## 决策目录

| 编号 | 决策 | 状态 |
|---|---|---|
| ADR-001 | [采用 RuoYi-Vue-Plus 模块化单体](ADR-001-modular-monolith.md) | Accepted |
| ADR-002 | [工具链与语言基线](ADR-002-toolchain-baseline.md) | Accepted |
| ADR-003 | [Android 优先的终端策略](ADR-003-terminal-strategy.md) | Accepted |
| ADR-004 | [Flutter 应用栈](ADR-004-flutter-application-stack.md) | Accepted |
| ADR-005 | [标识、时间与金额](ADR-005-identifiers-time-money.md) | Accepted |
| ADR-006 | [租户、组织、门店与设备隔离](ADR-006-tenant-organization-isolation.md) | Accepted |
| ADR-007 | [SQLite、Outbox/Inbox 与同步](ADR-007-offline-sync.md) | Accepted |
| ADR-008 | [Android 设备适配与插件治理](ADR-008-device-adapter.md) | Accepted |
| ADR-009 | [支付适配、未知状态与对账](ADR-009-payment-unknown.md) | Accepted |
| ADR-010 | [迁移、兼容、灰度与回滚](ADR-010-migration-compatibility-release.md) | Accepted |
| ADR-011 | [消息队列引入门槛](ADR-011-outbox-to-message-broker.md) | Accepted |
| ADR-012 | [日志、隐私、诊断与远程支持边界](ADR-012-observability-privacy-support.md) | Accepted |
| ADR-013 | [GitHub 作为正式代码仓库与 T0 CI 执行平台](ADR-013-github-primary-repository-ci.md) | Accepted |
| ADR-014 | [T0 供应链安全版本覆盖与 fastjson2 兼容迁移](ADR-014-t0-supply-chain-security-overrides.md) | Accepted |
| ADR-015 | [受限许可证的精确组件准入策略](ADR-015-restricted-license-policy.md) | Accepted |
| ADR-016 | [私有仓库的 PR 依赖评审门禁](ADR-016-private-repository-dependency-review.md) | Accepted |
| ADR-017 | [T1 风险 PoC 范围与外部集成深度](ADR-017-t1-risk-poc-scope-and-integration-depth.md) | Accepted |
| ADR-018 | [T1 退出与 T2 准入建议](ADR-018-t1-exit-and-t2-entry-recommendation.md) | Accepted |
| ADR-019 | [T2 Alpha 模块门禁与行业模板策略](ADR-019-t2-alpha-module-gates-and-industry-templates.md) | Accepted |
| ADR-020 | [Gate 2 本地订单、现金与 Outbox 原子性](ADR-020-gate2-local-order-cash-atomicity.md) | Accepted |
| ADR-021 | [S3 正式 POS 同步边界与持久 Inbox](ADR-021-sprint3-formal-pos-sync.md) | Accepted |
| ADR-022 | [Gate 3A Provider 无关支付、退款与对账核心](ADR-022-gate3a-provider-neutral-payment-core.md) | Accepted |
| ADR-023 | [Gate 4A 不可变库存账本与版本化库存策略](ADR-023-gate4a-immutable-inventory-ledger.md) | Accepted |
| ADR-024 | [Gate 4B 动态盘点与采购库存边界](ADR-024-gate4b-stocktake-procurement-boundaries.md) | Accepted |
| ADR-025 | [Gate 4C 仓级移动加权成本账本](ADR-025-gate4c-moving-average-cost-ledger.md) | Accepted |
| ADR-026 | [Gate 4D 调拨在途账与成本继承](ADR-026-gate4d-transfer-transit-and-inherited-cost.md) | Accepted |
| ADR-027 | [模型分型、持久化策略与 Schema 中文注释](ADR-027-model-persistence-and-schema-comments.md) | Accepted；第 3—4 项部分被 ADR-033 取代 |
| ADR-028 | [Gate 5A 确定性促销、跨端规则包与优惠分摊](ADR-028-gate5a-deterministic-promotion-allocation.md) | Accepted |
| ADR-029 | [Gate 5B 促销成交、订单消费与退货退款 Owner 编排](ADR-029-gate5b-sale-refund-owner-orchestration.md) | Accepted |
| ADR-030 | [Gate 5C 会员隐私与权益账本边界](ADR-030-gate5c-member-privacy-points.md) | Accepted |
| ADR-031 | [Gate 5D 可重建报表投影与安全导出边界](ADR-031-gate5d-rebuildable-reporting-projections.md) | Accepted |
| ADR-032 | [Gate 6A 终端唯一事实源与恢复性边界](ADR-032-gate6a-terminal-and-recovery.md) | Accepted |
| ADR-033 | [持久化访问策略与 SQL 模式解耦](ADR-033-persistence-access-strategy-and-sql-mode.md) | Accepted |
| ADR-034 | [Gate 6B Provider 无关发布治理与真实设备边界](ADR-034-gate6b-provider-neutral-release-governance.md) | Accepted |
| ADR-035 | [Gate 6C 外部 P0 证据治理与 Alpha 准入](ADR-035-gate6c-external-p0-evidence-and-alpha-admission.md) | Accepted |
| ADR-036 | [Gate 6D 内部产品化 UI 编排与合成闭环边界](ADR-036-gate6d-internal-productization-ui-orchestration.md) | Accepted |
| ADR-037 | [Gate 6E 后台运营、原单退货退款与内部 Alpha 候选边界](ADR-037-gate6e-operations-return-internal-alpha.md) | Accepted |
| ADR-038 | [Gate 6F 外部执行准入、完整 Alpha UAT 与发布准备边界](ADR-038-gate6f-external-admission-uat-release-prep.md) | Accepted |
| ADR-039 | [Gate 6G 商业 V1 内部核心代码收口](ADR-039-gate6g-core-productization-closure.md) | Accepted |
| ADR-040 | [Gate 6H 体验、性能、运维与内部发布候选](ADR-040-gate6h-experience-performance-operations-rc.md) | Accepted |
| ADR-041 | [Gate 6I 外部执行准入快照与完整 Alpha 冻结](ADR-041-gate6i-external-admission-alpha-freeze.md) | Accepted |
| ADR-042 | [Gate 7A 商业 V1 内部业务差距审计与串行准入](ADR-042-gate7a-v1-business-gap-audit.md) | Accepted |
| ADR-043 | [Gate 7B POS 交易运营第一批串行扩展](ADR-043-gate7b-pos-operations-first-batch.md) | Accepted |
| ADR-044 | [Gate 7B 第二批换货编排与组合支付准入准备](ADR-044-gate7b-second-batch-exchange-tender-prep.md) | Accepted |
| ADR-045 | [Gate 7C 秤码、金额码与成交计量快照](ADR-045-gate7c-weighted-barcode.md) | Accepted |
| ADR-046 | [Gate 7C 版本化货架价签与真实打印边界](ADR-046-gate7c-shelf-label.md) | Accepted |
| ADR-047 | [Gate 7C 确定性补货建议与采购草稿边界](ADR-047-gate7c-deterministic-replenishment.md) | Accepted |
| ADR-048 | [Gate 7C 可恢复开业资料迁移与 Owner 写入边界](ADR-048-gate7c-recoverable-business-migration.md) | Accepted |
| ADR-049 | [Gate 7C 门店开通编排、模板白名单与外部失败关闭](ADR-049-gate7c-store-onboarding.md) | Accepted |
| ADR-050 | [Gate 7C 社区超市基础批次、效期与 FEFO](ADR-050-gate7c-community-lot-expiry.md) | Accepted |
| ADR-051 | [Gate 7D 门店业务日日结、差异和只追加签署](ADR-051-gate7d-store-business-day-close.md) | Accepted |
| ADR-052 | [Gate 7D Operations Owner 统一异常案件与修复编排](ADR-052-gate7d-unified-exception-center.md) | Accepted |
| ADR-053 | [Gate 7D 会员权益、会员价与成交快照联动边界](ADR-053-gate7d-member-benefit-price-prep.md) | Accepted |
| ADR-054 | [Gate 7E 商业 V1 内部汇总验收边界](ADR-054-gate7e-internal-v1-business-complete.md) | Accepted |
| ADR-055 | [Gate 7F 外部 P0、许可证与完整 Alpha 执行准入准备](ADR-055-gate7f-external-p0-license-alpha-admission-prep.md) | Accepted |
| ADR-056 | [Gate 8A 商业 SaaS 运营 Owner 与串行准入边界](ADR-056-gate8a-commercial-saas-operations-prep.md) | Accepted |
| ADR-057 | [Gate 8A SaaS 开户与权益运行时边界](ADR-057-gate8a-saa-runtime.md) | Accepted |
| ADR-058 | [Gate 8A 订阅生命周期、受控降级与时间收敛](ADR-058-gate8a-subscription-runtime.md) | Accepted |
| ADR-059 | [Gate 8A 服务目录、实施项目、工单与附件安全边界](ADR-059-gate8a-service-operations-runtime.md) | Accepted |
| ADR-060 | [Gate 8B 商业 SaaS 运营内部汇总验收与证据边界](ADR-060-gate8b-commercial-operations-aggregate-prep.md) | Accepted |
| ADR-061 | [Gate 8B 正式运行时 API 汇总验收与平台职责分离](ADR-061-gate8b-runtime-api-acceptance.md) | Accepted |
| ADR-062 | [Gate 8C 质量、性能、可维护性与发布差距复核](ADR-062-gate8c-quality-performance-release-gap-audit.md) | Accepted |
| ADR-063 | [Gate 8C 生产安全配置、管理端点与附件资源边界](ADR-063-gate8c-production-security-hardening.md) | Accepted |
| ADR-064 | [Gate 8C 可维护性边界与行为保持重构](ADR-064-gate8c-maintainability-hardening.md) | Accepted |
| ADR-065 | [Gate 8C 正式运行栈性能重基线与退化门禁](ADR-065-gate8c-formal-runtime-performance-rebaseline.md) | Accepted |
| ADR-066 | [Gate 8C 内部发布准备、供应链证据与强制 NO-GO](ADR-066-gate8c-internal-release-readiness.md) | Accepted |
| ADR-067 | [Gate 8D 外部 P0、许可证、完整 Alpha 与发布准入复核](ADR-067-gate8d-external-p0-license-alpha-release-admission.md) | Accepted |
| ADR-068 | [Gate 9A 商业 V1 内部产品完整性审计](ADR-068-gate9a-internal-product-completeness-audit.md) | Accepted |
| ADR-069 | [Gate 9B 正式 API 与当前 OpenAPI 双向一致性](ADR-069-gate9b-formal-api-openapi-alignment.md) | Accepted |
| ADR-070 | [G9A-R2 商业默认装配与非 V1 平台能力隔离](ADR-070-g9a-r2-commercial-assembly-isolation.md) | Accepted |
| ADR-071 | [G9A-R3 页面状态、权限与可恢复交互边界](ADR-071-g9a-r3-page-state-permission-recovery.md) | Accepted |
| ADR-072 | [G9A-R4 同窗正式运行栈跨 Owner E2E](ADR-072-g9a-r4-formal-runtime-cross-owner-e2e.md) | Accepted |
| ADR-073 | [Gate 10A 内部质量加固的串行批次与语义停止线](ADR-073-gate10a-internal-quality-hardening-sequence.md) | Accepted |
| ADR-074 | [Gate 10A-R2 Server、数据库与资源整改边界](ADR-074-gate10a-r2-server-database-resource-remediation.md) | Accepted |
| ADR-075 | [本地可运行基线与 MySQL 业务表无物理外键](ADR-075-local-runnable-and-no-mysql-foreign-keys.md) | Accepted |
