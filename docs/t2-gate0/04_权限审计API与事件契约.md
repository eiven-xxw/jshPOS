# Gate 0 权限、审计、API 与事件契约

## 1. 权限矩阵

| 动作 | 权限键 | 对象范围 | 领域审计 |
|---|---|---|---|
| 查询组织/门店 | `foundation:org:query` / `foundation:store:query` | TENANT/ORG_SUBTREE/STORE | 仅批量导出另审计 |
| 新增/修改/停用组织 | `foundation:org:manage` | TENANT 或父组织范围 | 必须 |
| 新增/修改/停用门店 | `foundation:store:manage` | TENANT 或所属组织范围 | 必须 |
| 授予/撤销员工范围 | `foundation:scope:grant` | TENANT 管理员 | 必须，职责分离 |
| 查询模板 | `foundation:config:query` | 当前租户 | 普通查询不写 |
| 创建/发布/激活/回退模板 | `foundation:config:manage/publish/activate` | 当前租户 | 必须 |
| 查询领域审计 | `foundation:audit:query` | 当前租户且不可跨租户导出 | 查询本身进入平台操作日志 |

所有 Controller 只做协议转换、Bean Validation、权限注解和调用应用服务。租户、范围、状态机、摘要、业务日和审计均在 application/domain 层执行。

## 2. HTTP 约定

- 前缀 `/api/v1/foundation`；JSON UTF-8；时间点 ISO-8601 UTC，业务日 `YYYY-MM-DD`，时区 IANA 名称。
- `X-Correlation-Id` 只接受 16—64 位 `[A-Za-z0-9._-]`；非法/缺失值由服务端生成。响应始终回传该 Header。
- API 不定义 `tenantId` 请求字段；若通用代理追加同名 Header，也不得读取为授权输入。
- 更新使用请求体 `version` 做乐观锁；冲突返回明确业务错误，不静默覆盖。
- 错误码前缀：`FND-IAM-*`、`FND-ORG-*`、`FND-RBAC-*`、`FND-CFG-*`、`FND-MIG-*`；外部响应不泄露 SQL、类名、Secret 或其他租户存在性。

版本化 OpenAPI 权威文件为 `contracts/t2/gate0/openapi-foundation-v1.yaml`。实现与契约通过路径、方法、权限键和 DTO schema 静态/集成校验防漂移。

## 3. 领域事件

Gate 0 不引入消息中间件。跨 Gate 只定义稳定事件 Schema，实际发布将在消费者准入后通过同事务 Outbox 实现：

- `foundation.store.changed.v1`
- `foundation.staff-scope.changed.v1`
- `foundation.config-binding.changed.v1`

Schema 位于 `contracts/t2/gate0/events/`。事件 envelope 必含 `eventId/eventType/schemaVersion/occurredAt/tenantId/correlationId/aggregateType/aggregateId/revision/data`；`tenantId` 由可信上下文产生。当前实现不得“先发内存事件后提交数据库”冒充可靠集成。

## 4. 审计与可观测性

- 平台 `@Log` 记录 HTTP 运维行为；领域审计记录关键状态变化，两者用途不同。
- 指标：请求总量/失败、租户上下文拒绝、权限拒绝、乐观锁冲突、配置发布/回退、审计写失败、迁移状态。tag 仅允许 endpoint class、result、action 等低基数枚举。
- 日志 MDC：`correlationId`；认证成功后可加入 `tenantId` 和 `storeId`，不得加入手机号、用户名、Token、请求正文或配置 JSON。
- 健康检查只报告迁移是否有效和模块是否加载，不暴露数据库地址/凭据/租户统计。
