# API、事件、错误码与迁移设计

## 1. 契约状态

本阶段输出均为不可执行草案：

- OpenAPI：`contracts/t2/gate8a-prep/openapi-commercial-operations-draft.yaml`
- 事件：`contracts/t2/gate8a-prep/commercial-operations-events-draft.schema.json`
- 错误码：`contracts/t2/gate8a-prep/error-codes-draft.json`
- 持久化登记：`contracts/t2/gate8a-prep/persistence-design-registry.csv`

所有变更命令要求 `Idempotency-Key`，版本敏感命令要求 `If-Match-Version`，响应返回关联标识、
事实身份、状态、版本和稳定结果摘要。认证后的接口禁止接受 tenant_id 作为授权字段；开户申请
使用 `PRE_TENANT_APPLICATION` 权限域，租户建立后使用 `TRUSTED_TENANT` 权限域。

## 2. API 边界

- SaaS：商户申请、预检、审批、租户编排、套餐权益版本发布和租户生命周期命令。
- Subscription：订阅建立和激活、续期、宽限、暂停、终止、恢复命令。
- Service：实施项目、检查项、工单、认领、转派、解决、关闭、重开和附件登记。
- Controller 未来只承担协议、认证、参数和响应转换；状态机与权限决定必须位于应用/领域层。

错误响应采用统一错误码、可重试性、用户可见摘要和 correlation_id；不得泄露商户资料、
附件对象键、Secret、内部 SQL 或跨租户存在性。

## 3. 事件与事务边界

事件信封绑定 event_id、owner、event_type、schema_version、aggregate_id/version、可信 tenant
上下文、发生时间、correlation/causation、payload_sha256。预租户申请事件不得伪装为租户事件。

每个 Owner 的事实、状态历史、审计、幂等结果和 Outbox 在自己的本地数据库事务内提交。
跨 Owner 使用至少一次传输和 Inbox/Outbox；同 event_id 同摘要幂等，同 ID 异摘要隔离。
UNKNOWN 结果只能查询或观察原命令，不生成替代商业命令。

## 4. MySQL 迁移设计

当前只登记计划表，没有创建 Flyway 文件。正式运行时从当前最高版本之后分批添加前向迁移，
禁止修改既有迁移。所有租户事实表包含 `tenant_id VARCHAR(20)`、版本/状态、创建修改审计、
业务唯一约束和中文 COMMENT；申请域表在 tenant 建立前使用 application_id 和显式权限域。

简单租户 CRUD 可使用 MyBatis-Plus；复杂状态、检查点、配额原子条件和审计查询使用 XML，
并显式包含可信租户和数据范围条件。发布前必须验证空库迁移、重复执行、升级中断、旧应用/
新 Schema 兼容、索引容量和安全前向修复。

## 5. 容量与归档假设

首版按租户 10 门店、100 终端、500 员工、每年 10 万订阅/生命周期事件、10 万工单/处理记录
进行合成设计校验；该数值仅为内部假设，不构成生产 SLA。申请原件、服务附件和导出对象采用
分级保留、到期清理与删除审计；关键状态事实和审计只追加并按法规/合同另行确定期限。
