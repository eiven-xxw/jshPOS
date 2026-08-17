# ADR-033：持久化访问策略与 SQL 模式解耦

- 状态：Accepted
- 日期：2026-08-18
- 决策范围：CR-DEV-004、全部新增或实质修改的服务端业务表、Repository 与 Mapper
- 修订关系：修订 ADR-027 的逐表三分类；ADR-027 的模型分型、中文注释、分层和核心事实保护继续有效

## 背景

ADR-027 原以 `MP_ENTITY/XML_ONLY/READ_PROJECTION` 为逐表登记值，正确建立了普通 CRUD 与核心不可变事实之间的安全边界，但把两个不同问题合并到了一个字段：表允许哪些变更能力，以及某条 SQL 使用 MyBatis-Plus 还是 XML 实现。

MyBatis-Plus 与 Mapper XML 在技术上可以共存。同一个普通实体 Mapper 可以继承 `BaseMapper<T>`、使用类型安全 Lambda Wrapper，并声明由同 namespace XML 实现的复杂方法。若仅因存在一条复杂查询就把整张普通表登记为 `XML_ONLY`，会丢失简单 SQL 的类型安全和复用价值；反之，账本或不可变事实若只为复用简单查询而继承普通 `BaseMapper<T>`，又会暴露 `updateById`、`deleteById` 等超出授权边界的方法。

## 决策

1. 每张新增或实质修改的业务表分别登记两个维度：
   - `access_strategy`：`CRUD_ENTITY`、`CONTROLLED_WRITE`、`APPEND_ONLY` 或 `READ_PROJECTION`；
   - `sql_mode`：`MP`、`XML` 或 `HYBRID`。
2. `access_strategy` 决定 Mapper/Repository 可以暴露的最大能力：
   - `CRUD_ENTITY`：普通可变实体，允许经过 Repository 封装的通用单表 CRUD；
   - `CONTROLLED_WRITE`：只允许带状态、版本、权限、租户、锁或业务不变量的具名条件更新；
   - `APPEND_ONLY`：只允许幂等追加和读取，禁止覆盖更新和物理删除；
   - `READ_PROJECTION`：业务侧只读，只允许查询端口和 Projector/Rebuild 端口受控构建。
3. `sql_mode` 只描述该表当前持久化实现所采用的 SQL 技术集合；具体方法再按复杂度选择实现位置：
   - `MP`：单表、条件清晰、映射直接的 SQL 使用 `BaseMapper` 与 Lambda Wrapper；
   - `XML`：关联、聚合、复杂动态条件、显式锁、状态条件、批处理和执行计划敏感 SQL 使用 Mapper XML；
   - `HYBRID`：同一表既有 MyBatis-Plus 方法又有自定义 XML 方法；可以由同一 Mapper namespace 实现，也可以将复杂读取拆入专用 XML 查询 Mapper。
4. `CRUD_ENTITY + HYBRID` 是允许且推荐的组合。XML 方法存在不改变表的普通实体属性，SQL 复杂度不得被当作取消 MyBatis-Plus 简单 CRUD 的理由。
5. SQL 模式不得扩大访问策略授权。普通 `BaseMapper<T>` 暴露完整 CRUD 方法集，因此 `CONTROLLED_WRITE`、`APPEND_ONLY` 和 `READ_PROJECTION` 默认不得直接继承它；如确需复用 MyBatis-Plus 查询能力，必须采用经评审的只读/受限接口或自定义 SQL 注入，并以静态门禁证明危险方法不可调用，否则使用专用 Mapper XML。
6. 应用和领域层继续只依赖 Repository/查询端口，不直接依赖 Mapper、Wrapper 或持久化实体；Controller 不得访问 Mapper。租户、权限、状态、幂等和事务边界不因 SQL 模式变化而放宽。
7. 严格 `APPEND_ONLY` 行不允许任何状态或投递元数据更新。Outbox/Inbox 等对象若把不可变业务载荷和可变投递状态保存在同一行，必须登记为 `CONTROLLED_WRITE`；如需登记为 `APPEND_ONLY`，必须把不可变载荷与受控投递状态拆表，并分别登记。历史登记仍按其原不变量和 `mutation_boundary` 审计，不据此回写。
8. ADR-033 生效前已经完成合法准入登记的旧值（包括进行中 Gate 与已封存/验收记录）不因本 CR 单独批量改写：
   - `MP_ENTITY` 兼容为 `CRUD_ENTITY + MP`；实际同时使用自定义 XML 的，在下一次改变访问边界或 SQL 组合时登记为 `CRUD_ENTITY + HYBRID`；
   - `XML_ONLY` 的 SQL 模式兼容为 `XML`，访问策略必须结合原模型和 `mutation_boundary` 解释：普通可变实体为 `CRUD_ENTITY`，具名条件状态迁移为 `CONTROLLED_WRITE`，严格不可变事实为 `APPEND_ONLY`；下一次改变访问边界或 SQL 组合时显式转换；
   - 旧 `READ_PROJECTION` 保留访问策略语义，并在下一次改变访问边界或 SQL 组合时补充实际 SQL 模式。

## 组合判定

| access_strategy | 允许的 sql_mode | 是否允许普通 BaseMapper | 典型对象 |
|---|---|---|---|
| `CRUD_ENTITY` | `MP` / `XML` / `HYBRID` | 是；纯 XML 实现可不继承 | 分类、品牌、单位、普通配置、普通主数据 |
| `CONTROLLED_WRITE` | 默认 `XML`；受限 `MP/HYBRID` 需批准 | 否；受限接口例外需静态证明 | 发布指针、审批任务、显式状态机、条件 ACK、同表投递状态 |
| `APPEND_ONLY` | 默认 `XML`；受限 `MP/HYBRID` 需批准 | 否；受限接口例外需静态证明 | 订单/支付事实、账本、历史、审计、不可变载荷、幂等结果 |
| `READ_PROJECTION` | 默认 `XML`；受限 `MP/HYBRID` 需批准 | 否；受限只读接口例外需静态证明 | 报表日投影、跨表查询模型、可重建投影 |

## 备选方案

- 保持原三分类不变：拒绝。它把数据权限和 SQL 技术混合，不能准确表达 `BaseMapper + XML` 的合法组合。
- 所有表统一继承 `BaseMapper`，只靠 Repository 约束调用：拒绝。Mapper 仍是可注入 Bean，未来代码可以绕过约定调用通用更新或删除，不符合最小权限和编译期约束。
- 取消逐表登记，仅在代码评审判断：拒绝。资金、库存、租户、幂等和审计边界缺少机器可校验输入，容易随人员和版本漂移。

## 后果与回退

- 优点：普通 CRUD 充分复用 MyBatis-Plus，复杂 SQL 保持可读和可控，核心事实继续通过最小权限接口保护；登记语义与实现事实一致。
- 代价：登记增加一个字段，CI 需要同时校验访问策略、SQL 模式和兼容组合；旧登记在被实质修改时需要显式转换。
- 回退：后续只能通过新 CR/ADR 调整文档规则；不得借回退修改已发布迁移、删除业务事实或放宽资金、库存、租户、审计和幂等边界。
