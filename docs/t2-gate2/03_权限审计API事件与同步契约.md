# Gate 2 权限、审计、API、事件与同步契约

## 1. 权限矩阵

| 权限码 | 行为 | 数据范围 | 高风险控制 |
|---|---|---|---|
| `pos:shift:open` | 开班 | 本人、授权门店与终端 | 同终端重复开班拒绝 |
| `pos:basket:operate` | 购物篮编辑 | 本人活动班次 | 非 DRAFT/ACTIVE 拒绝 |
| `pos:order:suspend` | 挂单/取单 | 本门店；默认本人 | 跨员工取单需主管权限 |
| `pos:cash:collect` | 现金成交 | 本人 OPEN 班次 | 金额篡改与超额边界审计 |
| `pos:shift:close` | 交班 | 本人活动班次 | 差异超阈值需主管审批 |
| `pos:shift:approve-difference` | 审批长短款 | 授权门店 | 审批人与收银员分离 |
| `order:read` | 查订单 | 门店/组织范围 | 终端不得越权导出 |

Flutter 路由和按钮只控制展示；本地应用服务和服务端应用层都必须执行最终授权。测试 fixture 使用 `TENANT_A/TENANT_B`、多个组织/门店/员工/终端。

## 2. 审计字段

所有关键命令至少记录：`tenant_id`、`store_id`、`terminal_id`、`shift_id`、`business_date`、`actor_type/id/name_snapshot`、批准人、`command_id`、幂等键、原因码、前后状态、聚合版本、金额、币种、客户端时间、trace/correlation ID 和请求摘要。

不得记录顾客敏感信息、生产 Secret、银行卡数据或现金抽屉厂商原始敏感报文。订单快照中的商品名属于业务事实，不作为 PII。

## 3. 正式应用 API

`contracts/t2/gate2/openapi-pos-order-v1.yaml` 是服务端正式契约。API 不暴露 `tenant_id`：

- `POST /shifts`：开班；
- `POST /shifts/{shiftId}/close`：交班；
- `POST /cash-orders`：以冻结快照提交现金订单并原子写订单、现金、班次流水、审计和 Outbox；
- `GET /orders/{orderId}`：按可信租户和门店数据范围读取。

S2 Flutter POS 不调用同步端点；在线调用适配器也不在本 Sprint 接线。服务端 API 只验证模块边界、可信上下文和正式订单命令，不构成 Sync Gateway。

## 4. 事件

| 事件 | 生产事务 | 最小内容 |
|---|---|---|
| `shift.opened.v1` | 开班 | shift/store/terminal/cashier/businessDate/openingCash |
| `order.submitted.v1` | 订单冻结 | order/shift/version/receivable/snapshotHash |
| `cash.received.v1` | 现金成功 | payment/order/shift/tendered/change/net/currency |
| `order.completed.v1` | 本地成交闭环 | order/version/receivable/payment/snapshotHash |
| `order.suspended.v1` | 挂单 | order/version/store/terminal |
| `order.resumed.v1` | 取单 | order/version/store/terminal |
| `shift.closed.v1` | 交班 | theoretical/actual/difference/approval |

事件与业务事实同事务写入 Outbox，负载使用规范 JSON 和 `sha256:` 摘要；本 Sprint 不发送。

## 5. 稳定错误码

`TENANT_CONTEXT_REQUIRED`、`RESOURCE_NOT_VISIBLE`、`PERMISSION_DENIED`、`SHIFT_ALREADY_OPEN`、`SHIFT_NOT_OPEN`、`SHIFT_STATE_CONFLICT`、`SHIFT_DIFFERENCE_APPROVAL_REQUIRED`、`ORDER_STATE_CONFLICT`、`ORDER_VERSION_CONFLICT`、`ORDER_AMOUNT_CHANGED`、`CASH_TENDER_INSUFFICIENT`、`IDEMPOTENCY_KEY_REUSED`、`LOCAL_STORAGE_UNAVAILABLE`、`LOCAL_STORAGE_FULL`、`LOCAL_DB_INTEGRITY_FAILED`。

错误响应/本地结果必须带 `trace_id`、`command_id` 与稳定错误码，不暴露内部堆栈。

## 6. T2-SYN-001 冻结契约

`contracts/t2/gate2/sync-design-only-v1.yaml` 只冻结以下语义，不生成运行时客户端：

- Outbox 至少一次发送，业务效果幂等；
- 相同 event ID/相同 hash 为 `DUPLICATE`，相同 ID/不同 hash 为 `EVENT_HASH_MISMATCH` 并阻断设备；
- ACK 丢失复用原 event ID、payload 和 hash；HTTP request ID 可更新；
- 设备序列只诊断缺口，不代替 event ID；
- 服务端确认顺序不得改变聚合版本规则；不允许通用最后写入者获胜；
- `PENDING/SENDING/RETRY/ACKED/FINAL_REJECTED` 状态和超时恢复矩阵固定；
- 本 Sprint 网络调用计数必须为 0。

