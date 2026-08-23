# SAA、SUB、SVC 汇总矩阵

| 需求 | Owner / 模块 | 主权事实 | 正式协议 | 前向迁移 | 当前结论 |
|---|---|---|---|---|---|
| T2-SAA-001 | SaaS / `jshpos-saas` | 商户申请、套餐权益、商业租户生命周期、配额 | `/api/v1/saas/**`；SaaS 事件 Schema | V81、V82 | ACCEPTED / INTERNAL_SYNTHETIC_SOFTWARE |
| T2-SUB-001 | Subscription / `jshpos-subscription` | 订阅期限、状态历史、访问模式、提醒意图 | `/api/v1/subscriptions/**`；Subscription 事件 Schema | V83、V84 | ACCEPTED / INTERNAL_SYNTHETIC_SOFTWARE |
| T2-SVC-001 | Service / `jshpos-service` | 服务目录、实施项目、工单、附件元数据、责任历史 | `/api/v1/service/**`；Service 事件 Schema | V85、V86 | ACCEPTED / INTERNAL_SYNTHETIC_SOFTWARE |

## Owner 边界

- Foundation 独占技术租户和可信上下文；SaaS 只能经正式端口编排技术租户。
- Subscription 只引用已发布套餐/权益与可信租户，不修改 SaaS 或 Foundation 私有事实。
- Service 只读取 SaaS/Subscription 的正式快照，不激活租户、不切换套餐、不修改订阅。
- 三者都不能直接写其他 Owner 表；跨 Owner 通过正式端口、事件、Inbox/Outbox 协作。

完整机器矩阵见 `contracts/t2/gate8b-prep/owner-api-event-migration-matrix.csv`。既有单项证据保持不可变，本阶段只新增聚合索引。
