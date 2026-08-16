# ADR-021：S3 正式 POS 同步边界与持久 Inbox

状态：Accepted  
日期：2026-08-16  
需求：`T2-SYN-001`

## 背景

Gate 2 已建立正式本地订单、现金和 Outbox，但网络发送被明确禁止。S3 获准实现端到云 Outbox、云端持久 Inbox、逐事件 ACK、结果查询、下行 Inbox/游标和故障恢复。支付 Provider、退款、库存与促销仍未准入。

## 决策

1. 新建模块化单体边界 `jshpos-sync`，负责设备范围校验、协议、Inbox、ACK、游标、冲突、重试和可观测性；领域事实仍由其唯一 Owner 通过应用端口写入，Sync 不直接改写其他领域表。
2. 客户端请求不携带可授权 tenant_id。服务端先取得可信租户主体，再以 `device_id + store_id + terminal_id` 查询服务端设备注册表；任一不匹配均失败关闭。
3. Push 使用至少一次传输。云端在返回 `ACCEPTED/ACCEPTED_PENDING` 前必须先持久化 Inbox；`event_id + payload_sha256` 相同为重复，不同为 P0 完整性冲突并阻断设备。
4. POS ACK 丢失或超时只允许重发原事件。Outbox 状态为 `PENDING → SENDING → ACKED`，可恢复分支为 `SENDING → RETRY`，永久分支为 `FINAL_REJECTED`；事件身份字段不可变。
5. 下行每个流使用服务端生成的不透明单调游标。客户端只有在 Inbox 事件和业务投影原子应用完成后才能推进游标；游标损坏、回退或过期不得猜测自增。
6. 同聚合版本严格检查；不同聚合不承诺全局有序。禁止 Last-Write-Wins，缺口进入 `PENDING_GAP`，由原事件重试、缺失事件或权威快照恢复。
7. SQLite 只追加 V2 前向迁移，MySQL 只追加 V7/V8；不改写 Gate 2 已封存迁移。应用回退必须兼容 Schema N/N-1，失败迁移使用前向修复。
8. Gate 3 支付材料仅描述 Provider 无关状态机和端口，`runtimeAllowed=false`、`providerNetworkCallsAllowed=0`。

## 事务边界

- POS 业务事务：业务事实与 Outbox 原子提交，已由 Gate 2 保证。
- POS 出队事务：原子取得租约并转 `SENDING`；HTTP 不在 SQLite 事务内；逐事件响应在新事务内更新 ACK/RETRY/FINAL_REJECTED。
- 云端接收事务：设备校验、Inbox 插入/防重、哈希冲突和接收审计在一个事务内。
- 云端领域事务：Inbox 消费与领域幂等效果在领域应用事务内完成；结果单向推进。
- POS 下行事务：本地 Inbox 插入、事件应用和游标推进在一个事务内。

## 后果与限制

- 服务端可在领域处理较慢时返回 `ACCEPTED_PENDING`，POS 必须查询原 event_id，不能重建命令。
- S3 的设备注册使用服务端治理数据和可信认证适配器；没有主认证设备，因此证据最高为 `NETWORK_INTEGRATION`，不构成 `REAL_DEVICE`。
- 本 ADR 不批准电子支付、退款或库存领域写入。
