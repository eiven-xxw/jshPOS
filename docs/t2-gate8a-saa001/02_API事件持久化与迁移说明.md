# API、事件、持久化与迁移说明

## 1. 正式协议

权威契约为：

- `contracts/t2/gate8a-saa001/openapi-saas-v1.yaml`
- `contracts/t2/gate8a-saa001/saas-events-v1.schema.json`
- `contracts/t2/gate8a-saa001/error-codes.json`

API 覆盖申请创建/查询/预检/审批/技术开户/初始化/激活、套餐创建、权益版本创建与
推进、商业生命周期和可信租户权益/配额决策。命令统一携带 `Idempotency-Key` 和
`X-Correlation-ID`；申请 DTO 不接受 `tenant_id`。生命周期路径中的 tenantId 只是平台
管理员选定的操作目标，服务端仍以平台权限和 Owner 记录交叉校验。

一次性密码接口关闭请求与响应日志。SaaS 仅持久化密码内容的 SHA-256 参与幂等摘要，
调用 Foundation 后清零字符与字节缓冲；明文不得进入数据库、审计、Outbox、日志和制品。

## 2. 事件与事务

同一 MySQL 事务提交 Owner 状态投影、只追加状态事实、审计、幂等结果和 Outbox。
Outbox 事件包括申请、权益和生命周期状态变更，负载不含 Secret/PII，并保存 Schema
版本、摘要和关联标识。下游失败不能回滚已确认 Owner 事实，只能按原事件身份重试。

## 3. MyBatis 边界

- `saas_plan` 的普通单表主数据使用 MyBatis-Plus。
- 状态条件更新、只追加事实、复杂查询、幂等和原子配额使用 XML SQL。
- 平台范围 Mapper 显式关闭通用租户/数据权限插件；这是为了支持 tenant_id 尚不存在的
  申请和全局套餐。每个入口先强制平台管理员，租户级授权和配额 SQL 再显式携带可信 tenant_id。

具体登记见 `contracts/t2/gate8a-saa001/persistence-registry.csv`。

## 4. 前向迁移

- V81 创建 12 张 SaaS Owner 表、约束、索引和只追加历史触发器。
- V82 创建 13 个平台菜单/权限项；菜单隐藏不替代服务端权限。
- 已发布迁移不得修改；失败通过安全前向迁移修复。
- 干净 MySQL 必须从统一 V1 前向迁移至 V82、二次执行为零且 Flyway validate 通过。
- 表与字段具备中文 COMMENT；配额由数据库条件更新保证 `0 ≤ used_count ≤ quota_limit`。
