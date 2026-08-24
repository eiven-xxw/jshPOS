# T2-SVC-001 API、事件、持久化与附件安全说明

## 1. 正式边界

- `jshpos-service` 独占服务目录、实施项目与检查项、工单、状态历史、附件元数据、命令结果、审计和 Outbox。
- Service 仅通过正式端口校验已接受的 SaaS 权益和 Subscription 访问状态，不得激活租户、切换套餐、修改订阅或写其他 Owner 私有表。
- Controller 只负责认证、权限、参数和响应转换；状态机、租约、职责分离、幂等、事务和附件安全均位于 Application/Domain。
- `tenant_id` 只取自 `TrustedTenantContext`；门店范围由服务端授权再次校验，客户端字段、缓存键和对象键不得作为授权依据。

## 2. API、幂等与事件

正式 OpenAPI 当前权威位于 `contracts/t2/gate8a-svc001/openapi-service-v1.yaml`，覆盖目录创建/发布、实施项目查询与命令、
检查项完成、工单查询与命令、附件上传、短期下载和清理。写命令要求稳定 `Idempotency-Key` 和
`X-Correlation-ID`；同键同摘要返回原结果，同键异摘要以 `SVC-IDEM-001` 失败关闭。

服务事件由 `service-events.schema.json` 约束。Outbox 保存事件 ID、事件类型、可信租户、聚合身份、
聚合版本、门店范围、内容 SHA-256、关联标识和发生时间。目录、项目、工单和附件状态均使用版本化事件；
消费者必须按事件 ID 幂等，同身份异摘要不得静默覆盖。

## 3. 持久化与前向迁移

- V85 新增 10 张 `svc_*` 表；V86 只追加根菜单和 10 项服务运营权限，不修改任何已发布迁移。
- MyBatis-Plus 只用于工单简单插入；锁定查询、状态条件更新、列表、历史、审计和 Outbox 使用 XML SQL。
- 目录项、工单/项目状态历史、命令结果、审计和 Outbox 只追加；当前头表是带乐观版本的受控投影。
- 每张正式表包含可信 `tenant_id`、必要联合索引、唯一约束和中文 `COMMENT`；迁移失败只能新增更高版本安全前向修复。

## 4. 附件安全

附件正文只经正式对象存储端口写入 `service/{tenant}/tickets/{ticket}/attachments/{attachment}`。
数据库、日志、Git、CI 和普通制品只允许出现不透明对象键、SHA-256、媒体类型、大小、原安全文件名、
保留期限及 `STORED/CLEANED` 状态。单附件上限 10 MiB，路径字符、控制字符、公式前缀和不安全媒体类型失败关闭。

下载不持久化永久 URL。每次签发前重新校验 SaaS/Subscription 权益、可信租户、门店范围、附件状态和
`service:attachment:download` 权限，签名 URL 最长 300 秒。清理只追加审计和状态，不删除摘要历史；
对象存储不可用时失败关闭，不回落到数据库、本地磁盘或日志。
