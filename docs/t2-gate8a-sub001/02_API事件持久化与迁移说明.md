# T2-SUB-001 API、事件、持久化与迁移说明

## 1. 正式边界

- `jshpos-subscription` 独占订阅头、期限版本、状态事件、提醒意图、调度检查点、命令结果、审计与 Outbox。
- `jshpos-saas` 独占套餐权益和技术租户访问投影；Subscription 仅调用 `SaasSubscriptionControlPort`，不得写 SaaS 或 RuoYi 表。
- 平台写 API 要求平台管理员；租户自查 API 仅使用 `TrustedTenantContext`，客户端 `tenant_id` 不构成授权依据。
- Controller 只做协议转换、权限入口和响应封装，状态机、时间判断、幂等与事务均位于 Application/Domain。

## 2. API 与幂等

OpenAPI 当前权威位于 `contracts/t2/gate8a-sub001/openapi-subscription-v1.yaml`。创建、激活、续期、暂停、恢复、
申请终止和逻辑终止均要求稳定 `Idempotency-Key` 与关联标识；同键同摘要返回原结果，同键异摘要以
`SUB-IDEMP-002` 失败关闭。平台可显式触发具名到期扫描，运行时未注册隐式定时器。

订阅期限、合同引用、外部订单引用、业务时区、套餐与权益版本均写入服务端权威快照。
客户端时间只显示，不参与到期、宽限或恢复判断。

## 3. 状态和事件

状态为 `DRAFT、PENDING_ACTIVATION、ACTIVE、GRACE_PERIOD、SUSPENDED、EXPIRED、
TERMINATION_PENDING、TERMINATED、RESTORED`。迁移通过乐观版本控制，状态事实和期限版本只追加。
事件 Schema 位于 `subscription-events.schema.json`；每条 Outbox 绑定租户、订阅、关联标识、
内容 SHA-256 和创建时间，重复消费由稳定事实身份收敛。

到期提醒只追加 `PLANNED` 通知意图并发布 `subscription.reminder-planned.v1` Outbox；
通知意图自身不可更新，未来真实渠道的发送、失败和重试必须由独立消费者事实留痕，本阶段不伪造 `SENT`。

访问模式固定为 `NORMAL、GRACE、RECOVERY_ONLY、TERMINATED_RECOVERY`，策略版本固定为 `RECOVERY-V1`。受限状态只保留退款、
支付退款查询、对账、审计、备份恢复、法定导出、数据迁移和删除请求；菜单隐藏不能替代服务端校验。

## 4. MySQL 与前向修复

- V83 新增 8 张 `sub_*` Owner 表与 2 张 SaaS 访问投影/历史表，共 10 张表；历史表安装禁止更新和删除触发器。
- V84 只追加订阅运营菜单和权限，不修改既有迁移。
- 订阅头是当前可重建投影；期限、状态、访问、审计和 Outbox 历史不可覆盖。
- 迁移失败只能新增更高版本前向修复；禁止修改 V83/V84、物理删除历史或用业务报表覆盖权威事实。
