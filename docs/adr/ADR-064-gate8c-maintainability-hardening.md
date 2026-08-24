# ADR-064：Gate 8C 可维护性边界与行为保持重构

- 状态：Accepted
- 日期：2026-08-24
- 关联：T2-MTN-001、G8C-MTN-P1-001..005、CR-T2G8C-008

## 背景

Gate 8C-Prep 已登记五项 P1：Service Owner 直接依赖 SaaS 应用服务、准备期与运行时 OpenAPI 重复、Flyway 审计器误判合法回调、Foundation 锁定查询使用注解 `SELECT *`，以及 Flutter 结算服务与主页面职责过大。项目发起人已接受前置 `T2-SEC-002`，本轮只允许关闭这些既定差距。

本 ADR 不授权业务行为、数据库 Schema、资金库存事实、租户授权或外部执行变化。任何无法证明行为等价的调整必须停止并另行提交 CR。

## 决策

1. Service 内层定义最小只读权益端口；Service 应用服务只依赖该端口。SaaS 能力经基础设施适配器映射为 Service 自有的只读判定，不向 Service 泄漏 SaaS 应用服务或持久化类型。
2. 当前运行时 OpenAPI 必须保证 `(HTTP method, path)` 与 `operationId` 全库唯一。准备期契约保留为历史证据，但必须以机器可读元数据标记 `HISTORICAL_DRAFT_NON_RUNTIME`、给出替代契约且不参与当前权威契约集合。
3. Flyway 审计器显式区分版本迁移、可重复迁移和合法 callback。兼容 callback 不得放宽非法命名、重复版本、历史迁移摘要或已发布迁移不可修改门禁。
4. Foundation 锁定读取迁入 Mapper XML，使用显式列、完整 `resultMap`、可信 `tenant_id` 条件、确定性排序和 `FOR UPDATE`；Mapper 接口不保留复杂注解 SQL。
5. Flutter 结算代码使用同一 Dart library 的职责分部和内聚组件拆分。对外类、方法签名、状态、错误码、SQLite 表、事务边界、幂等键、恢复路径、金额数量计算及 MethodChannel 边界保持不变。
6. 五项按 `P1-001 → P1-002 → P1-003 → P1-004 → P1-005` 串行关闭；每项先有失败回归或静态断言，再实施最小整改并运行定向测试。

## 不变量

- 不新增或改写 MySQL/SQLite 迁移，不创建业务表、Owner、Controller 或页面旅程。
- 不改变订单、支付、退款、库存、成本、促销、会员、租户和订阅事实；不新增跨 Owner Mapper。
- Flutter 本地成交仍在同一 SQLite 事务中冻结并写入订单、收款、班次效果与 Outbox；UNKNOWN 处理和原幂等键恢复语义不变。
- 客户端 tenant_id 不成为授权依据；所有查询、缓存、任务、导出和对象路径继续使用可信租户上下文。
- 外部支付、硬件、外设、伙伴、完整 Alpha 与生产执行保持为零。

## 兼容、回退与证据

- 本轮只做内部结构与质量工具变更，不改变已发布 API 的请求响应、数据库结构或业务事件 Schema；可按单项提交回退。
- 历史 OpenAPI 仍保留原文件和内容血缘，只增加非运行时标记与替代引用。
- 通过条件为五项发现全部关闭、固定行为向量不变、完整 CI 全绿且 P0/P1 缺陷账为零。证据上限为 `INTERNAL_MAINTAINABILITY_HARDENING_CANDIDATE`，不代表外部或商业验收。
