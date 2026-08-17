# CR-DEV-003 模型分型、持久化策略与数据库中文注释

> 状态：APPROVED
>
> 提出日期：2026-08-17
>
> 生效日期：2026-08-17
>
> 变更类型：工程治理与架构边界增强
>
> 影响范围：新增或实质修改的服务端模型、业务表、Repository、Mapper 和 Flyway

## 1. 变更请求

项目发起人指出：正式业务模块大量使用 Java `record`，普通持久化实体和 MyBatis-Plus Lambda CRUD 使用范围有限，数据表字段、持久化实体及 Record 组件缺少中文注释；要求先分析并给出方案，确认后新增开发规范。

项目发起人于 2026-08-17 明确确认分析方案，授权仅登记规范、CR 与 ADR；本次不得修改实体、Mapper、SQL、Flyway、RTM 或 Gate 4D 业务实现。

## 2. 审计事实

- 服务端正式模块扫描到大量 `record`，主要承担 Command、View、只读投影和 Mapper 参数；它们不是自动生成的持久化实体。
- MyBatis-Plus `@TableName` 实体与 `BaseMapper` 仅集中于 foundation，证明 Record 不是 Lambda CRUD 的技术阻断，差异来自持久化策略选择。
- catalog/order/sync/foundation 存在业务 SQL 注解；支付及 Gate 4A—4D 核心模块主要使用 Mapper XML，以支持锁、聚合、状态条件和不可变事实。
- 19 个正式 Flyway 文件创建 90 张业务表，未发现表或字段中文数据库注释。
- 现有 7 个持久化实体缺少中文类级和字段注释；Record 普遍缺少逐组件 `@param`。
- 未发现正式 Repository 声明，多个应用服务直接依赖基础设施 Mapper；领域层主要由 Rules/States/Hash 类型组成，缺少统一可执行的聚合/状态模型准入规则。

这些事实同时包含合理选择和治理缺口：核心账本/资金事实使用 XML 是合理的，简单表也完全手写 SQL、模型职责混淆、缺少 Schema 注释和层间 Mapper 直连则需要规范收紧。

## 3. 批准决策

1. 新增 `JSH-POS-DEV-STD-002`，建立领域聚合、持久化实体、Record 数据载体和只读投影分型。
2. 新增 `ADR-027`，将每张新增表的策略限定为 `MP_ENTITY`、`XML_ONLY` 或 `READ_PROJECTION`。
3. 简单单表 CRUD 默认使用普通持久化实体、`BaseMapper` 和 Lambda Wrapper；不可变事实、状态条件、显式锁与复杂 SQL 使用 Repository 和 XML。
4. 正式业务模块原则上禁止新增 SQL 注解；应用/领域层禁止依赖基础设施 Mapper。
5. 持久化实体类及所有映射业务字段、Record 类型及逐组件 `@param` 必须具有有效中文说明。
6. 新业务表与每个字段必须有有效中文数据库 `COMMENT`；历史 Flyway 不得修改，存量注释通过前向迁移补充。
7. 后续 CI 增加策略、分层、SQL、Java 注释和 Schema 注释门禁；存量整改另立 requirement_id，不由本 CR 自动准入。

## 4. 影响分析

| 维度 | 结论 |
|---|---|
| 商业范围 | 不新增业务能力，不改变 Gate 4D 范围 |
| 资金/库存/状态机 | 不修改既有实现或事实；后续整改必须以不变量回归证明行为不变 |
| 租户与权限 | 强化 Repository、Mapper 和模型的可信租户边界 |
| API/事件 | 本次不修改；模型整改不得未经版本评审改变契约 |
| 数据库/迁移 | 本次不修改；存量中文注释必须使用独立前向迁移 |
| 依赖/许可证 | 不新增依赖 |
| CI | 只登记后续门禁要求，本次不修改流水线 |
| RTM | 工程治理 CR，不改变任何业务 requirement 状态 |
| 回滚 | 文档通过后续 CR/ADR 修订；不得回写已发布迁移或业务事实 |

## 5. 非目标

- 不一次性为 90 张表生成普通实体；
- 不把账本、历史、审计、Outbox/Inbox 或幂等事实改成通用 CRUD；
- 不在 Gate 4D 分支跨模块重构 Repository、Mapper 或领域模型；
- 不修改已发布 Flyway 校验和；
- 不以注释或规范登记宣称存量问题已整改完成。

## 6. 验收与签字

- [x] 根级 `AGENTS.md` 已同步强制底线；
- [x] 权威技术规范已新增模型分型与数据库注释条款；
- [x] `JSH-POS-DEV-STD-001` 评审清单已同步；
- [x] 已新增 `JSH-POS-DEV-STD-002`；
- [x] 已新增并接受 `ADR-027`；
- [x] `change-log.md` 已追加 CR-DEV-003，未修改历史记录；
- [x] 未修改业务代码、SQL、Flyway、API、事件、CI 或 RTM 状态。

- 批准角色：项目发起人（2026-08-17 明确确认）
- 分析与登记：研发治理执行人
- 后续复核：架构、数据库、QA及资金/库存/租户相关 Owner
