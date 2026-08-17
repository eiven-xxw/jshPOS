# 模型分型、持久化策略与数据库中文注释开发规范

> 文档编号：JSH-POS-DEV-STD-002
>
> 状态：Accepted
>
> 生效日期：2026-08-17
>
> 决策依据：CR-DEV-003、ADR-027
>
> 适用范围：全部新增或实质修改的服务端模型、业务表、Repository、Mapper 和 Flyway 迁移

## 1. 目的与结论

本规范解决四类已确认问题：将 `record` 与领域/持久化实体混为一谈、简单 CRUD 缺少 MyBatis-Plus 实体路径、应用层直接依赖 Mapper、Java 与数据库 Schema 缺少可维护的中文语义。

结论不是“一张表必须对应一个领域实体”，也不是“所有 SQL 必须改为 MyBatis-Plus”。项目按模型职责和表的风险选择实现：简单单表 CRUD 默认使用普通持久化实体与 MyBatis-Plus Lambda；不可变事实、状态条件更新、显式锁和复杂 SQL 使用 Repository 与 Mapper XML；组合读取使用只读投影。

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

## 3. 持久化策略登记

每张新增业务表在开发准入时必须登记以下内容：

| 字段 | 必填内容 |
|---|---|
| table_name | 物理表名 |
| owner_module | 唯一写入 Owner |
| strategy | `MP_ENTITY`、`XML_ONLY` 或 `READ_PROJECTION` |
| model | 持久化实体、写入参数、领域聚合和读取投影 |
| repository_mapper | Repository/查询端口及 Mapper 路径 |
| tenant_source | 可信租户上下文来源和 fail-closed 行为 |
| mutation_boundary | 允许的插入、条件更新、只追加和禁止删除规则 |
| reason | 默认策略或例外理由 |

没有登记或登记与实现不一致时不得进入编码。策略变更属于架构影响变更，必须更新设计、测试和 CR/ADR 评估。

## 4. 三类实现策略

### 4.1 MP_ENTITY

适用于分类、品牌、单位、普通配置、字典映射等同时满足以下条件的表：

- 单表 CRUD，字段映射直接；
- 不涉及显式锁、聚合、窗口函数或执行计划敏感 SQL；
- 不属于只追加事实、状态历史、账本、审计、Outbox/Inbox 或幂等结果；
- 更新不需要多状态条件、跨记录不变量或专用原子 SQL。

实现要求：普通持久化实体、`BaseMapper<T>`、类型安全 Lambda Wrapper、稳定分页排序、可信租户和数据范围校验。应用服务不得直接注入 Mapper，应通过领域 Repository 或应用查询端口访问。

### 4.2 XML_ONLY

出现任一条件时使用：多表关联、聚合/分组、子查询、窗口函数、UNION、显式锁、复杂动态条件、状态/版本条件更新、批处理、执行计划敏感查询、专用结果映射，或表属于资金、库存、成本、在途、历史、审计、Outbox/Inbox、幂等事实。

实现要求：Repository/端口、基础设施 Mapper XML、显式字段、稳定别名、`resultMap`、租户条件、锁与索引说明。写入可使用不可变具名参数 `record`，但不得通过通用 `updateById`、`deleteById` 或 Bean 拷贝覆盖历史事实。

### 4.3 READ_PROJECTION

适用于跨表或聚合的只读查询。使用 Mapper XML 与只读 Projection/View，不能成为回写实体；投影不得携带或推导新的租户授权事实。

## 5. SQL 与分层边界

- 正式业务模块原则上禁止新增 `@Select`、`@Insert`、`@Update`、`@Delete` 业务 SQL。简单 SQL 使用 Lambda，复杂 SQL 使用 XML；框架规定或生成代码例外必须登记。
- Repository 接口位于领域层；纯查询端口可位于应用层；实现、Mapper、Wrapper 和 Persistence Entity 位于基础设施层。
- `interfaces -> application -> domain`，基础设施实现依赖内层端口。应用和领域层禁止导入基础设施 Mapper、Wrapper 或持久化实体。
- `tenant_id` 只来自可信上下文。实体、Record、Mapper 参数、XML、Lambda、任务、导出和缓存都不能把客户端租户值变成授权依据。
- 选择 `XML_ONLY` 不能成为使用原始长参数列表的理由；达到参数阈值时使用内聚的具名写入参数。

## 6. 数据库中文注释

### 6.1 新建表

- 每张业务表必须有准确中文表 `COMMENT`，说明业务用途和数据主权。
- 每个字段必须有准确中文 `COMMENT`；禁止只写“ID”“状态”“时间”“金额”等无法判断语义的注释。
- 金额写明币种或币种字段、最小货币单位/小数精度与舍入；数量写明单位与精度；时间写明时区/业务日；ID 写明生成方和唯一范围；状态写明状态机；tenant 写明可信来源；哈希、版本、游标和幂等键写明稳定性与比较规则。
- 注释不得包含密钥、真实支付报文、未脱敏个人信息或环境凭据。

### 6.2 已发布表

- 禁止修改已经发布或封存的 Flyway 文件和校验和。
- 补充表/字段注释必须创建独立前向迁移；MySQL 修改列注释时必须完整保留类型、长度、符号、字符集、排序规则、空值、默认值、自动更新、生成表达式和其他列属性。
- 迁移前评估表容量、元数据锁、执行时间、灰度和失败恢复；迁移后通过 `information_schema.tables` 与 `information_schema.columns` 验证注释和列定义。
- 注释迁移失败只能停止发布或追加前向修复，不得回写历史迁移或删除业务事实。

## 7. 自动门禁要求

后续 CI 实现必须覆盖：

- 新增表具有策略登记，且登记的 Owner、策略和代码路径存在；
- `MP_ENTITY` 具有普通持久化实体、`BaseMapper` 和 Lambda 使用证据；
- `XML_ONLY` 不暴露通用删除或无条件更新，复杂 SQL 位于 XML；
- 正式业务模块不新增未经批准的 SQL 注解；
- 应用/领域层不依赖基础设施 Mapper、Wrapper 和 Persistence Entity；
- 持久化实体具有中文类和映射字段注释，`record` 具有中文类型及逐组件 `@param`；
- MySQL 实迁移后所有新增表和字段的中文注释非空；
- 租户逃逸、权限、状态条件、幂等、重复和回滚/前向修复测试与风险匹配。

静态门禁只能验证覆盖率和依赖方向，注释质量、模型内聚和策略合理性仍须人工评审。

## 8. 存量治理

截至本规范生效前的存量审计基线为：19 个正式 Flyway 文件、90 张业务表未写表/字段注释；MyBatis-Plus 持久化实体和 `BaseMapper` 仅集中于 foundation；catalog/order/sync/foundation 存在业务 SQL 注解；多个应用服务直接依赖基础设施 Mapper；大量 `record` 缺少逐组件说明。

该基线用于建立整改范围，不代表相关业务验收被推翻，也不授权当前 Gate 4D 跨模块重构。存量整改必须另立 requirement_id，按模块评估数据主权、状态机、API、迁移、容量、元数据锁、回退和回归测试，并遵循以下顺序：

1. 建立全表持久化策略与 Owner 清单；
2. 补齐现有持久化实体和 Record 注释；
3. 简单表渐进迁移为 `MP_ENTITY`，复杂注解 SQL 迁移到 XML；
4. 建立 Repository/查询端口，消除应用层对 Mapper 的直接依赖；
5. 以独立前向迁移分模块补齐数据库中文注释；
6. 对核心聚合补齐显式领域模型或状态机对象，并以全量不变量、租户和幂等回归证明行为不变。

禁止一次性机械生成 90 张可变实体或全仓替换 SQL；禁止为“规范达标”给账本、历史、审计、Outbox/Inbox 和幂等事实开放通用更新/删除。

## 9. 评审核对表

- [ ] 模型职责已区分领域聚合、持久化实体、DTO/Command/Query、Record 参数和只读投影；
- [ ] 每张新增表已登记持久化策略、Owner、可信租户来源和更新/删除边界；
- [ ] 简单 CRUD 使用 MP Entity + BaseMapper + Lambda，例外有批准理由；
- [ ] 核心事实、锁和复杂 SQL 使用 Repository + Mapper XML，未暴露通用 CRUD；
- [ ] 应用/领域层没有依赖 Mapper、Wrapper 或 Persistence Entity；
- [ ] 持久化实体类和所有映射业务字段有有效中文注释；
- [ ] Record 类型和每个组件有有效中文 Javadoc/`@param`；
- [ ] 新表和每个字段有有效中文数据库 `COMMENT`；
- [ ] 存量数据库注释只通过前向迁移补充，迁移属性与元数据锁风险已验证；
- [ ] 租户、权限、状态机、幂等、审计和失败恢复测试证据完整。
