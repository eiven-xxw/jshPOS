# 模型分型、持久化访问与数据库中文注释开发规范

> 文档编号：JSH-POS-DEV-STD-002
>
> 状态：Accepted
>
> 生效日期：2026-08-17
>
> 修订日期：2026-09-05（CR-T2LOC-001）
>
> 决策依据：CR-DEV-003、CR-DEV-004、ADR-027、ADR-033
>
> 适用范围：全部新增或实质修改的服务端模型、业务表、Repository、Mapper 和 Flyway 迁移

## 1. 目的与结论

本规范解决四类已确认问题：将 `record` 与领域/持久化实体混为一谈、简单 CRUD 缺少 MyBatis-Plus 实体路径、应用层直接依赖 Mapper、Java 与数据库 Schema 缺少可维护的中文语义。

结论不是“一张表必须对应一个领域实体”，也不是“所有 SQL 必须改为 MyBatis-Plus”。项目分别按数据变更风险确定访问策略、按每条 SQL 的复杂度选择实现方式，再以逐表 `sql_mode` 汇总当前实现集合：普通可变实体默认使用 MyBatis-Plus；不可变事实和受控状态迁移只开放最小具名能力；简单 SQL 使用类型安全 Lambda，复杂 SQL 使用 Mapper XML。同一个普通实体 Mapper 可以同时继承 `BaseMapper<T>`、使用 Lambda Wrapper，并声明由 XML 实现的复杂方法。

## 2. 模型分型

### 2.1 领域聚合与实体

- 位于 `domain`，表达业务身份、生命周期、状态迁移和不变量；可以是封装受控变化的普通类，也可以是每次迁移返回新状态的不可变模型。
- 不得依赖 MyBatis、Spring Controller、RuoYi `BaseEntity`、Persistence Entity 或接口 DTO。
- 支付、退款、订单、库存、采购、成本和调拨等有明确生命周期的核心聚合，必须由显式领域模型或经批准的等价状态机对象集中表达关键合法/非法迁移；不得只散落在 Controller、Mapper、应用服务或通用工具中。
- 领域聚合不与数据库表一一对应。聚合可以由多张表持久化，一张账本/历史表也不必拥有独立领域实体。

### 2.2 持久化实体

- 位于 `infrastructure.persistence.entity`，只负责单表映射，不承载跨聚合业务规则。
- 默认使用普通 Java 类；按需声明 `@TableName`、`@TableId`、`@Version`、逻辑删除和显式字段映射。核心领域对象不得继承该类型。
- 必须有中文类级 Javadoc，说明表、Owner、租户主权和更新/删除边界；所有映射业务字段必须有中文说明。
- 金额字段说明币种和最小货币单位/精度，数量说明计量和精度，时间说明 UTC/门店时区/业务日，ID 说明生成方和唯一范围，状态说明状态机，版本/哈希/幂等字段说明比较与不可变语义。

### 2.3 Record 的允许用途

`record` 优先用于：

- REST/消息/连接器 DTO；
- 应用 `Command`、`Query` 和结果；
- 领域值对象；
- Mapper 具名写入参数；
- XML `resultMap` 的只读 Projection/View。

`record` 隐式不可变、不能继承普通基类，适合数据载体但不自动等于 DTO。它不作为 MyBatis-Plus 持久化实体或有生命周期聚合的默认实现；确有例外时必须说明构造映射、框架兼容、更新语义和不变量保护方式。

每个 `record` 必须有中文类型 Javadoc，并以 `@param` 逐一说明组件。组件较多时不得只给外层容器写概括性注释；若组件无法形成内聚概念，应拆分模型而不是制造大 Record。

## 3. 访问策略与 SQL 模式登记

每张新增业务表在开发准入时必须分别登记“数据访问策略”和“SQL 模式”。前者回答允许怎样修改数据，后者汇总该表当前使用哪些 SQL 实现技术，不得再用 SQL 的复杂度反推整张表是否允许通用 CRUD。

| 字段 | 必填内容 |
|---|---|
| table_name | 物理表名 |
| owner_module | 唯一写入 Owner |
| access_strategy | `CRUD_ENTITY`、`CONTROLLED_WRITE`、`APPEND_ONLY` 或 `READ_PROJECTION` |
| sql_mode | `MP`、`XML` 或 `HYBRID` |
| model | 持久化实体、写入参数、领域聚合和读取投影 |
| repository_mapper | Repository/查询端口及 Mapper 路径 |
| tenant_source | 可信租户上下文来源和 fail-closed 行为 |
| mutation_boundary | 允许的插入、条件更新、只追加和禁止删除规则 |
| reason | 默认策略或例外理由 |

没有登记或登记与实现不一致时不得进入编码。访问策略变更属于架构影响变更，必须更新设计、测试和 CR/ADR 评估；新增或迁移某个方法导致表的实现集合在 `MP/XML/HYBRID` 之间变化时，必须同步登记并通过代码评审和对应回归，但不自动改变数据访问策略。

ADR-033 生效前已经完成合法准入登记的 `MP_ENTITY/XML_ONLY/READ_PROJECTION`（包括进行中 Gate 与已封存/验收记录）作为兼容历史证据保留，不因本 CR 单独批量改写：`MP_ENTITY` 兼容理解为 `CRUD_ENTITY + MP`；`XML_ONLY` 的 SQL 模式兼容为 `XML`，访问策略结合原模型与 `mutation_boundary` 兼容解释为普通可变实体 `CRUD_ENTITY`、具名条件迁移 `CONTROLLED_WRITE` 或严格不可变事实 `APPEND_ONLY`；旧 `READ_PROJECTION` 保留原访问语义。ADR-033 生效后的新登记必须使用双维度；既有表下一次改变访问边界或 SQL 组合时也必须转换，实际同时存在 MyBatis-Plus 与自定义 XML 的表登记为 `HYBRID`。

## 4. 数据访问策略

### 4.1 CRUD_ENTITY

适用于分类、品牌、单位、普通配置、字典映射等同时满足以下条件的表：

- 单表 CRUD，字段映射直接；
- 不属于只追加事实、状态历史、账本、审计、Outbox/Inbox 或幂等结果；
- 更新不需要多状态条件、跨记录不变量或专用原子 SQL。

实现要求：普通持久化实体、`BaseMapper<T>`、类型安全 Lambda Wrapper、稳定分页排序、可信租户和数据范围校验。表上存在多表关联、聚合、窗口函数或执行计划敏感 SQL，不会自动取消 `CRUD_ENTITY`；这些复杂方法可以在同一个 Mapper 中声明并由 XML 实现，也可以放入专用查询 Mapper。应用服务不得直接注入 Mapper，应通过领域 Repository 或应用查询端口访问。

### 4.2 CONTROLLED_WRITE

适用于允许变化，但每次变化必须满足原状态、版本、权限、租户、额度、摘要、锁或跨记录不变量的表，例如支付/退款流程状态、审批任务、发布指针和显式状态机。

实现要求：Repository/端口只暴露 `publish`、`transition`、`acknowledge` 等具名能力；普通 `BaseMapper<T>` 会同时暴露 `updateById`、`deleteById` 等超出授权边界的方法，因此不得直接继承。SQL 通常使用 XML 明确状态条件、版本、租户、锁和受影响行数；读取若需复用 MP，必须通过不暴露通用写入/删除的受限查询抽象，不得以 Repository 封装为由保留危险公共方法。

### 4.3 APPEND_ONLY

适用于订单成交快照、支付/退款观察事实、库存/成本/积分/在途账本、状态历史、审计、不可变 Outbox/Inbox 载荷和幂等结果等只追加事实。

实现要求：仅开放幂等插入以及按可信租户和业务键读取；禁止任何覆盖更新、物理删除和 Bean 拷贝覆盖。Outbox/Inbox 若在同一行迁移发送、ACK、重试或死信等投递状态，该行必须登记为 `CONTROLLED_WRITE`；若要保持 `APPEND_ONLY`，必须把不可变载荷与受控投递状态拆成分别登记的对象。Mapper 使用专用接口和 XML，依靠唯一约束、摘要比较、触发器或数据库权限等纵深措施保护不可变语义。

### 4.4 READ_PROJECTION

适用于跨表、聚合或可重建的读取模型。对业务用例只提供查询端口，Projection/View 不能成为业务回写实体；投影构建器可以通过专用 Projector/Rebuild 端口受控 `insert/upsert/rebuild`，但不得暴露通用更新或删除。投影不得携带或推导新的租户授权事实。

## 5. SQL 模式与组合规则

### 5.1 MP

适用于单表、条件清晰、映射直接、无需特殊锁和特殊执行计划的 SQL。使用普通持久化实体、`BaseMapper<T>` 和类型安全 Lambda Wrapper；分页必须稳定排序，租户与数据范围必须显式验证。

### 5.2 XML

适用于多表关联、聚合/分组、子查询、CTE、窗口函数、UNION、复杂动态条件、显式锁、状态条件更新、批处理、执行计划敏感查询和专用结果映射。必须显式字段、稳定别名、`resultMap`、租户条件、索引预期和锁语义。

### 5.3 HYBRID

`HYBRID` 是普通实体表的常见实现：同一个 Mapper 可以继承 `BaseMapper<T>` 处理简单 CRUD，同时声明由同 namespace XML 实现的复杂方法；也可以把复杂读取拆到专用查询 Mapper。MyBatis-Plus 与 XML 可以共存，不得因为存在一条复杂 SQL 就把整张普通表改成受限访问策略。

`HYBRID` 不得突破访问策略：`CONTROLLED_WRITE`、`APPEND_ONLY` 和 `READ_PROJECTION` 不能因为需要简单查询就直接继承会暴露通用写入/删除的 `BaseMapper<T>`。如确需复用 MP 查询能力，必须使用经评审的只读/受限接口或自定义 SQL 注入，并由静态门禁证明危险方法不可调用；否则使用专用 Mapper XML。

逐表组合规则如下：`CRUD_ENTITY` 可采用 `MP`、`XML` 或 `HYBRID`；后三类默认采用 `XML`，只有在受限接口经过架构批准且静态门禁证明没有扩大写入/删除能力时，才可登记 `MP` 或 `HYBRID`。SQL 模式描述实现集合，不代表数据权限。

## 6. SQL 与分层边界

- 正式业务模块原则上禁止新增 `@Select`、`@Insert`、`@Update`、`@Delete` 业务 SQL。简单 SQL 使用 Lambda，复杂 SQL 使用 XML；框架规定或生成代码例外必须登记。
- Repository 接口位于领域层；纯查询端口可位于应用层；实现、Mapper、Wrapper 和 Persistence Entity 位于基础设施层。
- `interfaces -> application -> domain`，基础设施实现依赖内层端口。应用和领域层禁止导入基础设施 Mapper、Wrapper 或持久化实体。
- `tenant_id` 只来自可信上下文。实体、Record、Mapper 参数、XML、Lambda、任务、导出和缓存都不能把客户端租户值变成授权依据。
- 选择 `XML` 不能成为使用原始长参数列表的理由；达到参数阈值时使用内聚的具名写入参数。

## 7. 服务端 MySQL 约束与引用完整性

### 7.1 无物理外键

- 最终商业 MySQL Schema 不使用物理外键。V90 以后新表、新列和前向修复均不得声明
  `FOREIGN KEY`/`REFERENCES`，静态门禁与 MySQL `information_schema` 双重校验为 0。
- 禁止删除主键、唯一键、CHECK、NOT NULL、只追加保护触发器或支撑索引来实现“无外键”。
- 每个引用字段必须在表登记中说明目标 Owner、引用身份、可信租户来源、写入前校验端口、
  允许的生命周期状态、索引、孤儿检测和修复方式。
- 同 Owner 内由应用服务在事务中校验；跨 Owner 经正式只读/命令端口和版本化事实验证，
  不通过数据库跨模块约束制造隐式写入顺序。
- 父事实停用或逻辑注销后，历史子事实继续保留；禁止级联物理删除。

### 7.2 SQLite 例外

POS SQLite 是单设备离线事务域，既有本地外键继续用于同一事务内的订单、行、收款、
Outbox 和快照保护。本次 MySQL 决策不得被扩张为批量移除 SQLite 约束；确需调整时必须
先证明崩溃恢复、事务回滚和旧库升级行为不退化，并提交独立 CR。

## 8. 数据库中文注释

### 8.1 新建表

- 每张业务表必须有准确中文表 `COMMENT`，说明业务用途和数据主权。
- 每个字段必须有准确中文 `COMMENT`；禁止只写“ID”“状态”“时间”“金额”等无法判断语义的注释。
- 金额写明币种或币种字段、最小货币单位/小数精度与舍入；数量写明单位与精度；时间写明时区/业务日；ID 写明生成方和唯一范围；状态写明状态机；tenant 写明可信来源；哈希、版本、游标和幂等键写明稳定性与比较规则。
- 注释不得包含密钥、真实支付报文、未脱敏个人信息或环境凭据。

### 8.2 已发布表

- 禁止修改已经发布或封存的 Flyway 文件和校验和。
- 补充表/字段注释必须创建独立前向迁移；MySQL 修改列注释时必须完整保留类型、长度、符号、字符集、排序规则、空值、默认值、自动更新、生成表达式和其他列属性。
- 迁移前评估表容量、元数据锁、执行时间、灰度和失败恢复；迁移后通过 `information_schema.tables` 与 `information_schema.columns` 验证注释和列定义。
- 注释迁移失败只能停止发布或追加前向修复，不得回写历史迁移或删除业务事实。

## 9. 自动门禁要求

后续 CI 实现必须覆盖：

- 新增表同时具有访问策略和 SQL 模式登记，且 Owner、模型和代码路径存在；
- `CRUD_ENTITY + MP/HYBRID` 具有普通持久化实体、`BaseMapper` 和 Lambda 使用证据；`CRUD_ENTITY + XML` 具有不使用通用 MP 方法的明确理由；`HYBRID` 的复杂方法允许由同一 Mapper namespace 的 XML 实现；
- `CONTROLLED_WRITE` 只暴露具名条件更新，`APPEND_ONLY` 不暴露通用更新/删除，`READ_PROJECTION` 只允许查询端口和受控投影构建；
- `CONTROLLED_WRITE/APPEND_ONLY/READ_PROJECTION` 若登记 `HYBRID`，必须证明 MP 复用没有引入通用写入或删除能力；
- 正式业务模块不新增未经批准的 SQL 注解；
- 应用/领域层不依赖基础设施 Mapper、Wrapper 和 Persistence Entity；
- 持久化实体具有中文类和映射字段注释，`record` 具有中文类型及逐组件 `@param`；
- MySQL 实迁移后所有新增表和字段的中文注释非空；
- MySQL V90 后迁移不含新增外键语法，完整迁移后物理外键数为 0，主键/唯一键/CHECK/索引未退化；
- 租户逃逸、权限、状态条件、幂等、重复和回滚/前向修复测试与风险匹配。

静态门禁只能验证覆盖率和依赖方向，注释质量、模型内聚和策略合理性仍须人工评审。

## 10. 存量治理

截至本规范生效前的存量审计基线为：19 个正式 Flyway 文件、90 张业务表未写表/字段注释；MyBatis-Plus 持久化实体和 `BaseMapper` 仅集中于 foundation；catalog/order/sync/foundation 存在业务 SQL 注解；多个应用服务直接依赖基础设施 Mapper；大量 `record` 缺少逐组件说明。

该基线用于建立整改范围，不代表相关业务验收被推翻；它既未授权当时的 Gate 4D 跨模块重构，也不授权当前或后续 Gate 自动整改。存量整改必须另立 requirement_id，按模块评估数据主权、状态机、API、迁移、容量、元数据锁、回退和回归测试，并遵循以下顺序：

1. 建立全表访问策略、SQL 模式与 Owner 清单；
2. 补齐现有持久化实体和 Record 注释；
3. 普通表渐进登记为 `CRUD_ENTITY + MP/HYBRID`，复杂注解 SQL 迁移到 XML；
4. 建立 Repository/查询端口，消除应用层对 Mapper 的直接依赖；
5. 以独立前向迁移分模块补齐数据库中文注释；
6. 对核心聚合补齐显式领域模型或状态机对象，并以全量不变量、租户和幂等回归证明行为不变。

禁止一次性机械生成 90 张可变实体或全仓替换 SQL；禁止为“规范达标”给账本、历史、审计、Outbox/Inbox 和幂等事实开放通用更新/删除。

## 11. 评审核对表

- [ ] 模型职责已区分领域聚合、持久化实体、DTO/Command/Query、Record 参数和只读投影；
- [ ] 每张新增表已分别登记访问策略与 SQL 模式，并记录 Owner、可信租户来源和更新/删除边界；
- [ ] 普通实体的简单 CRUD 使用 BaseMapper + Lambda，复杂 SQL 可与其在同一 Mapper 中通过 XML 共存；例外有批准理由；
- [ ] 受控写入、只追加事实和只读投影没有因复用 MyBatis-Plus 暴露超出授权边界的通用 CRUD；
- [ ] 应用/领域层没有依赖 Mapper、Wrapper 或 Persistence Entity；
- [ ] 持久化实体类和所有映射业务字段有有效中文注释；
- [ ] Record 类型和每个组件有有效中文 Javadoc/`@param`；
- [ ] 新表和每个字段有有效中文数据库 `COMMENT`；
- [ ] MySQL 新表没有物理外键；每个引用字段已登记应用校验端口、同租户规则、索引和孤儿检测；
- [ ] 存量数据库注释只通过前向迁移补充，迁移属性与元数据锁风险已验证；
- [ ] 租户、权限、状态机、幂等、审计和失败恢复测试证据完整。
