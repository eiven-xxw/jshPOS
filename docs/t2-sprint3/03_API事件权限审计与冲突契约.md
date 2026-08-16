# S3 API、事件、权限、审计与冲突契约

## 1. 正式接口

权威契约：`contracts/t2/sprint3/openapi-pos-sync-v1.yaml`。

- `POST /api/pos/v1/sync/bootstrap`：协议能力、设备状态、服务器时间与流游标；
- `POST /api/pos/v1/sync/push`：最多 100 个原始 Outbox 事件，逐事件返回结果；
- `GET /api/pos/v1/sync/results/{eventId}`：查询 `ACCEPTED_PENDING` 原事件结果；
- `GET /api/pos/v1/sync/pull`：按流和不透明游标拉取下行事件；
- `POST /api/pos/v1/sync/ack`：确认显式消费事件，ACK 本身幂等。

请求不得出现 tenant_id。`X-Device-Id`、`X-Store-Id`、`X-Terminal-Id` 只是待校验标识，只有与可信主体和服务端设备注册表一致后才形成 `TrustedSyncContext`。

## 2. 权限

| 权限码 | 行为 | 额外守卫 |
|---|---|---|
| `pos:sync:bootstrap` | 建立同步能力 | ACTIVE 设备和授权门店 |
| `pos:sync:push` | 上传本机 Outbox | event/device/store/terminal 全部一致 |
| `pos:sync:pull` | 拉取授权流 | 流白名单和单调 cursor |
| `pos:sync:ack` | 确认下行消费 | event 必须属于本设备可见范围 |
| `pos:sync:result` | 查询原事件结果 | event 必须属于本设备 |
| `sync:ops:repair` | 人工恢复/隔离 | 运维职责分离与证据引用 |

## 3. 审计

记录 tenant、device、store、terminal、request/batch/event ID、device sequence、stream、event type、aggregate/version、payload hash、Inbox 前后状态、结果码、attempt、cursor 前后值、可信操作者、trace、服务端接收时间和脱敏错误。禁止记录令牌、私钥、支付码、银行卡或未脱敏个人信息。

## 4. 冲突矩阵

| 输入 | 结果 | 自动动作 |
|---|---|---|
| 同 event ID + 同 hash | `DUPLICATE` | 返回原 server reference |
| 同 event ID + 异 hash | `DEVICE_BLOCKED` | P0 审计并停止新同步/交易 |
| device sequence 缺口 | `ACCEPTED_PENDING` | 保存事件并标记 GAP，不伪造补号 |
| 聚合旧版本且效果相同 | `DUPLICATE` | 不重复业务效果 |
| 聚合未来版本 | `ACCEPTED`（仅 Sync 不可变事实） | Sync 不推导领域当前版本；后续由领域 Owner 应用端口判定 GAP，S3 不越权投影 |
| 跨租户/门店/终端 | `RESOURCE_NOT_VISIBLE` | 统一不可见并安全审计 |
| Schema N-1 | 按兼容规则处理 | 忽略未知可选字段 |
| Schema 不支持必理解字段 | `REJECTED_FINAL` | 要求升级，不推进游标 |
| 领域事实冲突 | `CONFLICT` | 人工处置，禁止 LWW |

## 5. 稳定错误码

`SYNC_CONTEXT_REQUIRED`、`DEVICE_NOT_ACTIVE`、`DEVICE_SCOPE_MISMATCH`、`PROTOCOL_UNSUPPORTED`、`SCHEMA_UNSUPPORTED`、`EVENT_HASH_MISMATCH`、`EVENT_DUPLICATE`、`EVENT_VERSION_GAP`、`CURSOR_INVALID`、`CURSOR_EXPIRED`、`CURSOR_ROLLBACK`、`RETRY_LATER`、`FINAL_REJECTED`、`RESOURCE_NOT_VISIBLE`、`SYNC_STORAGE_UNAVAILABLE`。
