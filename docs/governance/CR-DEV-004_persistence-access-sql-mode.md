# CR-DEV-004：持久化访问策略与 SQL 模式双维度登记

- 状态：APPROVED
- 日期：2026-08-18
- 决策人：项目发起人
- 影响范围：AGENTS.md、README/治理索引、技术架构与开发规范、JSH-POS-DEV-STD-001/002、ADR-027、ADR-033、change-log
- 非目标：不修改业务代码、Mapper、实体、Flyway、RTM、既有 Gate 验收状态或历史证据

## 1. 变更原因

项目发起人指出，MyBatis-Plus `BaseMapper + Lambda` 与 Mapper XML 可以在同一 Mapper 中共用：简单 SQL 使用 MyBatis-Plus，复杂 SQL 写入 XML。原 `MP_ENTITY/XML_ONLY/READ_PROJECTION` 单列分类容易把“数据允许怎样变化”和“SQL 如何实现”混为一谈，造成普通表只因存在复杂查询就被迫放弃 MyBatis-Plus，或让人误以为 `BaseMapper` 与 XML 不能共存。

分析同时确认，不能因此让支付、退款、订单快照、库存/成本/积分账本、历史、审计、Outbox/Inbox 和幂等事实统一继承普通 `BaseMapper`。它会暴露通用更新与删除方法，削弱最小权限、不可变事实和状态条件保护。

## 2. 批准决策

1. 保留新增表登记制度，将单列策略拆为：
   - 数据访问策略：`CRUD_ENTITY/CONTROLLED_WRITE/APPEND_ONLY/READ_PROJECTION`；
   - SQL 模式：`MP/XML/HYBRID`。
2. 明确 `CRUD_ENTITY + HYBRID`：同一个 Mapper 可以继承 `BaseMapper<T>`、使用 Lambda Wrapper，并声明由同 namespace XML 实现的复杂方法。
3. SQL 复杂度只决定方法实现位置，不自动改变整张表的数据访问策略。
4. `CONTROLLED_WRITE` 只开放具名条件更新，`APPEND_ONLY` 禁止任何覆盖更新和物理删除，`READ_PROJECTION` 只允许查询和受控投影构建；SQL 模式不得扩大上述能力。Outbox/Inbox 若在同一行保存并迁移投递状态，登记为 `CONTROLLED_WRITE`；只有不可变载荷与投递状态拆分后，不可变载荷表才登记为 `APPEND_ONLY`。
5. `sql_mode` 是逐表登记的当前实现集合：只使用 MyBatis-Plus 为 `MP`，只使用 XML 为 `XML`，两者并存为 `HYBRID`。`CRUD_ENTITY` 可以采用三种模式，不能把“使用 XML”误判成受限访问策略。
6. ADR-033 生效前已完成合法准入的旧 `MP_ENTITY/XML_ONLY/READ_PROJECTION` 登记，包括进行中 Gate 与已封存/验收记录，均作为兼容历史证据保留，不因本 CR 单独批量改写。旧 `XML_ONLY` 结合原模型与 `mutation_boundary` 兼容解释为 `CRUD_ENTITY`、`CONTROLLED_WRITE` 或 `APPEND_ONLY`，SQL 模式为 `XML`；新登记立即使用双维度，既有表下一次改变访问边界或 SQL 组合时按 ADR-033 转换。
7. 本 CR 只修改治理文档；业务实现和存量登记整改必须遵守当前 Gate 准入并另行取得授权。

## 3. 验收结果

- AGENTS 与技术架构规范明确双维度登记和 `BaseMapper + XML` 共存规则；
- JSH-POS-DEV-STD-001/002 明确四类访问策略、三类 SQL 模式、合法组合和 CI 检查；
- ADR-027 保留模型与注释决策并引用 ADR-033 修订；
- ADR-033 记录架构原因、决策、兼容映射和回退；
- 根目录与治理/ADR 索引能够定位当前阶段、权威规范和决策替代关系；
- change-log 新增 CR-DEV-004，不修改 CR-DEV-003 等历史记录。

批准依据：项目发起人于 2026-08-18 确认工作区开发规范修订属于正式变更，并授权完成一致性修复、独立治理提交、推送和完整 CI；本批准不改变 Gate 6A 的 `VERIFIED` 状态，也不准入 Gate 6B。
